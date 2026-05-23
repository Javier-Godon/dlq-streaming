package es.bluesolution.dlq_streaming.functional_framework.execution;

import es.bluesolution.dlq_streaming.functional_framework.Result;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;

import java.util.function.Supplier;

/**
 * Adds structured logging to any ExecutionContext.
 *
 * Logs:
 * - Computation start (with context type)
 * - Success results
 * - Failures with error codes and messages
 * - Execution time
 *
 * Example:
 *
 * var txContext = TransactionExecutionContext.of(txManager);
 * var loggedContext = new LoggingExecutionContext(txContext);
 *
 * Result<Order> result = Result.pipeline(data)
 *     .flatMap(stages::persist)
 *     .within(loggedContext);
 *
 * Output:
 * [DEBUG] Starting execution within LoggingExecutionContext
 * [DEBUG] Starting execution within TransactionExecutionContext
 * [INFO] Execution completed successfully in 45ms
 *
 * @see ExecutionContext for the interface definition
 * @see ComposableExecutionContext for composition pattern
 */
@NullMarked
@Slf4j
public final class LoggingExecutionContext extends ComposableExecutionContext {

    public LoggingExecutionContext(ExecutionContext next) {
        super(next);
    }

    @Override
    protected <T> Result<T> executeWithBehavior(Supplier<Result<T>> computation) {
        long startTime = System.nanoTime();
        log.debug("Starting execution within {}", getClass().getSimpleName());

        Result<T> result = next.execute(computation);

        long durationMs = (System.nanoTime() - startTime) / 1_000_000;

        if (result.isSuccess()) {
            log.info("Execution completed successfully in {}ms", durationMs);
        } else {
            var error = result.failure();
            log.warn("Execution failed with {} ({}ms): {}",
                error.code(), durationMs, error.message());
        }

        return result;
    }
}
