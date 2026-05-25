package es.bluesolution.dlq_streaming.functional_framework.execution;

import es.bluesolution.dlq_streaming.functional_framework.FailureResultDescription;
import es.bluesolution.dlq_streaming.functional_framework.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Spring-backed transaction execution context.
 *
 * <p>This is how your framework applies transactional boundaries to pure Result pipelines.
 * It uses Spring's TransactionTemplate, which is the FP-idiomatic way (vs {@code @Transactional} annotations).
 *
 * <p>Design principles:
 * <ol>
 *   <li>The computation (pipeline) stays pure - no {@code @Transactional} needed</li>
 *   <li>Transaction management is injected at the execution boundary</li>
 *   <li>Rollback happens automatically if Result is a Failure</li>
 *   <li>Composition is possible: wrap with logging, caching, etc.</li>
 * </ol>
 *
 * <p>Usage:
 * <pre>
 * {@code @Service @RequiredArgsConstructor @Slf4j}
 * public class CreateOrderHandler {
 *     private final CreateOrderAggregator aggregator;
 *     private final TransactionExecutionContext txContext;
 *
 *     public Result&lt;Order&gt; handle(CreateOrderCommand cmd) {
 *         // Pure pipeline - no @Transactional!
 *         var pipeline = () -> aggregator.start()
 *             .flatMap(CreateOrderStages::validate)
 *             .flatMap(CreateOrderStages::persist);
 *
 *         // Transaction applied at boundary
 *         return txContext.execute(pipeline);
 *     }
 * }
 * </pre>
 *
 * <p>For conditional transactions:
 * <pre>
 * Result&lt;Order&gt; result = isProduction
 *     ? txContext.execute(pipeline)
 *     : noOpContext.execute(pipeline);
 * </pre>
 *
 * @see ExecutionContext for the interface definition
 * @see NoOpExecutionContext for testing without transactions
 */
@NullMarked
@RequiredArgsConstructor
@Slf4j
public class TransactionExecutionContext implements ExecutionContext {
    
    private final TransactionTemplate transactionTemplate;
    
    /**
     * Alternative constructor using PlatformTransactionManager (more explicit).
     * Use this if you have the transaction manager but not TransactionTemplate.
     */
    public static TransactionExecutionContext of(PlatformTransactionManager txManager) {
        var template = new TransactionTemplate(txManager);
        template.setReadOnly(false);  // Default: read-write
        return new TransactionExecutionContext(template);
    }
    
    /**
     * Create a read-only transaction context.
     * Useful for optimized queries.
     */
    public static TransactionExecutionContext readOnly(PlatformTransactionManager txManager) {
        var template = new TransactionTemplate(txManager);
        template.setReadOnly(true);
        return new TransactionExecutionContext(template);
    }
    
    /**
     * Create a transaction context with custom isolation level.
     */
    public static TransactionExecutionContext withIsolation(
            PlatformTransactionManager txManager,
            int isolationLevel) {
        var template = new TransactionTemplate(txManager);
        template.setIsolationLevel(isolationLevel);
        return new TransactionExecutionContext(template);
    }

    /**
     * Execute a Result-returning computation within a Spring transaction.
     *
     * <p>If the Result is a Failure, the transaction is rolled back.
     * This preserves functional semantics: errors propagate as values (Results),
     * not as exceptions.
     *
     * @param computation a pure function that returns a Result
     * @param <T> the success value type
     * @return the Result exactly as returned by computation (success or failure)
     */
    @Override
    public <T> Result<T> execute(Supplier<Result<T>> computation) {
        try {
            return Objects.requireNonNull(transactionTemplate.execute(txStatus -> {
                Result<T> result = computation.get();

                // If the computation returned a Failure, rollback the transaction
                if (result.isFailure()) {
                    txStatus.setRollbackOnly();
                    log.debug("Transaction marked for rollback due to Result.Failure");
                }

                return result;
            }));
        } catch (RuntimeException e) {
            // Handle unexpected exceptions that escape the Result
            log.error("Unexpected exception during transaction execution", e);
            return Result.failure(new FailureResultDescription(
                FailureResultDescription.ErrorCode.UNKNOWN_ERROR,
                "Transaction execution failed: " + e.getMessage(),
                e
            ));
        }
    }
    
    /**
     * Execute with side effects for logging or auditing.
     *
     * <p>This allows you to add behavior at the transaction boundary without
     * polluting the pure pipeline.
     *
     * @param computation the pure computation
     * @param beforeTx callback before transaction starts (for pre-flight checks)
     * @param afterTx callback after transaction completes (for logging/auditing)
     * @param <T> the success value type
     * @return the Result exactly as returned by computation
     */
    public <T> Result<T> executeWithAudit(
            Supplier<Result<T>> computation,
            Runnable beforeTx,
            Consumer<Result<T>> afterTx) {
        
        beforeTx.run();
        Result<T> result = execute(computation);
        afterTx.accept(result);
        
        return result;
    }
}
