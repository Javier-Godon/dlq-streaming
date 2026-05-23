package es.bluesolution.dlq_streaming.functional_framework.execution;

import es.bluesolution.dlq_streaming.functional_framework.Result;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;

import java.util.function.Supplier;

/**
 * No-op execution context for testing and non-transactional scenarios.
 *
 * This simply executes the computation as-is, with no transaction wrapping.
 *
 * Use cases:
 * 1. Unit tests: verify logic without Spring context
 * 2. Read-only queries: no transaction needed
 * 3. Dry runs: execute without side effects
 * 4. Local development: test without database setup
 *
 * Example (unit test):
 *
 * @Test
 * void testValidation() {
 *     var ctx = new NoOpExecutionContext();
 *     var result = ctx.execute(() ->
 *         CreateOrderStages.validate(invalidOrder)
 *     );
 *     assertTrue(result.isFailure());
 * }
 *
 * This allows you to test pure stages independently, which is the ROP ideal.
 *
 * @see ExecutionContext for the interface definition
 * @see TransactionExecutionContext for transactional execution
 */
@NullMarked
@Slf4j
public class NoOpExecutionContext implements ExecutionContext {

    /**
     * Execute the computation immediately, with no wrapping.
     *
     * @param computation a pure function that returns a Result
     * @param <T> the success value type
     * @return the Result exactly as returned by computation
     */
    @Override
    public <T> Result<T> execute(Supplier<Result<T>> computation) {
        return computation.get();
    }
}
