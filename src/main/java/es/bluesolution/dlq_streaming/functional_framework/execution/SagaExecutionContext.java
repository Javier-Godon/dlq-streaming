package es.bluesolution.dlq_streaming.functional_framework.execution;

import es.bluesolution.dlq_streaming.functional_framework.Result;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * SagaExecutionContext implements the Saga pattern for distributed transactions.
 *
 * <p>A Saga is a sequence of operations where each operation has a corresponding compensation
 * (rollback) operation. If any operation fails, all previous compensations are executed
 * in reverse order to maintain consistency across multiple systems.
 *
 * <p>This is particularly useful for operations that span multiple services (e.g., database + Keycloak):
 *
 * <p>Flow:
 * <ol>
 *   <li>Execute operation 1 (persist to database) — register compensation: undo database persist</li>
 *   <li>Execute operation 2 (update Keycloak) — register compensation: undo Keycloak update</li>
 *   <li>If operation 2 fails → execute compensations in reverse:
 *     <ul>
 *       <li>Undo Keycloak update</li>
 *       <li>Undo database persist</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p>Result: Strong consistency across distributed systems using functional composition.
 *
 * <p>Integration with ExecutionContext:
 * <ul>
 *   <li>Implements ExecutionContext for compatibility</li>
 *   <li>Can be composed with other contexts (logging, metrics)</li>
 *   <li>Works with existing Result&lt;T&gt; monad pattern</li>
 *   <li>Requires aggregators to track compensations</li>
 * </ul>
 *
 * @see SagaAggregator - Aggregator interface for tracking compensations
 */
@NullMarked
@Slf4j
public class SagaExecutionContext implements ExecutionContext {

    private final ExecutionContext next;
    private final ThreadLocal<List<Supplier<Result<?>>>> compensations = ThreadLocal.withInitial(ArrayList::new);

    /**
     * Create a SagaExecutionContext that delegates to another context.
     *
     * @param next the execution context to delegate to (e.g., TransactionExecutionContext)
     */
    public SagaExecutionContext(ExecutionContext next) {
        this.next = next;
    }

    /**
     * Create a standalone SagaExecutionContext with no-op execution.
     * Useful for testing or when you only need compensation tracking.
     */
    public SagaExecutionContext() {
        this(new NoOpExecutionContext());
    }

    /**
     * Execute a computation with compensation support.
     *
     * <p>The computation may register compensations that will be executed in
     * reverse order if the overall saga fails.
     *
     * @param computation a supplier that may return Result.success() or Result.failure()
     * @param <T> the success value type
     * @return the Result from executing the computation
     */
    @Override
    public <T> Result<T> execute(Supplier<Result<T>> computation) {
        log.debug("Starting saga execution");

        try {
            // Execute the computation within the delegated context
            Result<T> result = next.execute(computation);

            // If successful, clear compensations (saga completed successfully)
            if (result.isSuccess()) {
                log.debug("Saga completed successfully");
                compensations.get().clear();
                return result;
            }

            // If failed, execute compensations in reverse order
            log.warn("Saga failed, executing compensations");
            executeCompensations();

            return result;

        } finally {
            // Always clean up ThreadLocal to prevent memory leaks
            compensations.remove();
        }
    }

    /**
     * Register a compensation operation.
     *
     * <p>This is called by stages during execution to register what should happen
     * if the saga fails. Compensations are executed in reverse order (LIFO - Last In First Out).
     *
     * <p>According to ROP Golden Rule: compensations return meaningful output (IDs, aggregates),
     * never {@code Result<Void>}. This maintains functional composition and traceability.
     *
     * <p>Example usage in a stage:
     * <pre>
     * public static Result&lt;State&gt; persist(State state, SagaExecutionContext context) {
     *     var saved = repository.save(state.order());
     *
     *     // Register: if saga fails, re-create the deleted order
     *     context.registerCompensation(() -&gt; {
     *         return repository.save(state.order());  // Returns Result&lt;OrderId&gt;
     *     });
     *
     *     return Result.success(state.withOrder(saved));
     * }
     * </pre>
     *
     * @param compensation a function that undoes what the stage did, returning meaningful output
     */
    public void registerCompensation(Supplier<Result<?>> compensation) {
        log.debug("Registering compensation operation");
        compensations.get().add(compensation);
    }

    /**
     * Execute all registered compensations in reverse order.
     *
     * <p>Compensations are executed LIFO (Last In First Out) to properly unwind
     * the saga. If a compensation fails, remaining compensations still execute.
     */
    private void executeCompensations() {
        List<Supplier<Result<?>>> toCompensate = new ArrayList<>(compensations.get());

        // Reverse the list to execute in LIFO order
        for (int i = toCompensate.size() - 1; i >= 0; i--) {
            Supplier<Result<?>> compensation = toCompensate.get(i);
            try {
                log.debug("Executing compensation {} of {}", toCompensate.size() - i, toCompensate.size());
                Result<?> result = compensation.get();

                if (result.isFailure()) {
                    log.error("Compensation failed: {}", result.failure().message());
                    // Continue with remaining compensations even if one fails
                }
            } catch (Exception e) {
                log.error("Compensation threw exception", e);
                // Continue with remaining compensations even if one throws
            }
        }

        log.debug("Completed executing {} compensations", toCompensate.size());
    }
}
