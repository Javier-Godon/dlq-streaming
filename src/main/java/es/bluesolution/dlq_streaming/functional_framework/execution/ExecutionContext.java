package es.bluesolution.dlq_streaming.functional_framework.execution;

import es.bluesolution.dlq_streaming.functional_framework.Result;
import org.jspecify.annotations.NullMarked;

import java.util.function.Supplier;

/**
 * Execution context that describes HOW a Result pipeline is executed.
 *
 * This interface is the bridge between pure ROP pipelines and side effects (I/O, transactions).
 *
 * Core principle (from FP languages):
 * - Pure functions describe WHAT should happen (return Result<T>)
 * - ExecutionContext describes HOW it happens (with transactions, logging, etc.)
 * - Never mix them by annotating individual functions as @Transactional
 *
 * Examples of ExecutionContext:
 * - NoOpExecutionContext: runs without any wrapper (for testing)
 * - TransactionExecutionContext: wraps with Spring's TransactionTemplate
 * - CachedExecutionContext: wraps with caching layer
 * - LoggingExecutionContext: adds structured logging
 * - You can compose them: LoggingExecutionContext(TransactionExecutionContext(...))
 *
 * This mirrors:
 * - Haskell: runIO, runST, other effect runners
 * - F#: computation expressions (result { ... }, async { ... })
 * - Elixir: Repo.transaction, Task.async, other effect executors
 *
 * @see TransactionExecutionContext for Spring TransactionTemplate implementation
 * @see NoOpExecutionContext for testing without effects
 */
@NullMarked
public interface ExecutionContext {

    /**
     * Execute a Result-returning computation within this execution context.
     *
     * The execution context is responsible for:
     * - Applying transactional boundaries (if applicable)
     * - Setting up resources (connections, transactions, etc.)
     * - Handling cleanup and rollback
     * - Preserving functional purity: the computation itself remains pure
     *
     * @param computation a pure function that returns a Result
     * @param <T> the success value type
     * @return the Result of executing the computation within this context
     *
     * @example
     * // Pure pipeline (unchanged, no @Transactional)
     * Result<Order> pipeline(Order order) {
     *     return Stage.validate(order)
     *         .flatMap(Stage::enrich)
     *         .flatMap(Stage::persist);
     * }
     *
     * // Execute within transaction at the boundary
     * Result<Order> result = txContext.execute(() -> pipeline(order));
     *
     * This is equivalent to Haskell's:
     * do {
     *   order <- runDB (validate order >>= enrich >>= persist)
     * }
     *
     * Or F#'s:
     * let! order = transaction (result {
     *   let! o1 = validate order
     *   let! o2 = enrich o1
     *   let! o3 = persist o2
     *   return o3
     * })
     */
    <T> Result<T> execute(Supplier<Result<T>> computation);
}
