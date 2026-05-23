package es.bluesolution.dlq_streaming.functional_framework.execution;

import es.bluesolution.dlq_streaming.functional_framework.Result;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;

import java.util.function.Supplier;

/**
 * Composable execution context that adds behavior to another ExecutionContext.
 *
 * This enables building complex execution strategies by layering:
 * - Transactions (TransactionExecutionContext)
 * - Logging (LoggingExecutionContext)
 * - Caching (CachingExecutionContext - to be added)
 * - Metrics (MetricsExecutionContext - to be added)
 *
 * Example - nested transactions with logging:
 *
 * var baseContext = TransactionExecutionContext.of(txManager);
 * var loggedContext = new LoggingExecutionContext(baseContext);
 *
 * Result<Order> result = pipeline.within(loggedContext);
 * // Executes with: logging -> transaction -> computation
 *
 * @see ExecutionContext for the interface definition
 * @see TransactionExecutionContext for transactional execution
 * @see LoggingExecutionContext for logging execution
 */
@NullMarked
@RequiredArgsConstructor
public abstract class ComposableExecutionContext implements ExecutionContext {

    /**
     * The next execution context in the chain.
     * Implementations should call next.execute(computation) to delegate.
     */
    protected final ExecutionContext next;

    /**
     * Template method that subclasses override to add behavior.
     * Subclasses should call next.execute() to continue the chain.
     */
    protected abstract <T> Result<T> executeWithBehavior(Supplier<Result<T>> computation);

    /**
     * Final execute method that calls the template method.
     */
    @Override
    public final <T> Result<T> execute(Supplier<Result<T>> computation) {
        return executeWithBehavior(computation);
    }
}
