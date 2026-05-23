package es.bluesolution.dlq_streaming.functional_framework;

import es.bluesolution.dlq_streaming.functional_framework.execution.ExecutionContext;
import org.jspecify.annotations.NullMarked;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A lazy/deferred Result pipeline that builds computation without executing it.
 *
 * <h2>The Problem</h2>
 * In Java, {@link Result#flatMap(Function)} evaluates <strong>eagerly</strong>.
 * When you chain flatMap calls on a Result, each step executes immediately.
 * This means {@link Result#within(ExecutionContext)} captures the already-computed
 * result, and the transaction wraps nothing useful:
 * <pre>
 * // ❌ BROKEN: stages execute BEFORE the transaction starts
 * Result.success(data)
 *     .flatMap(d -> fetchFromDB(d, ports))   // Runs NOW, outside tx!
 *     .flatMap(Stages::validate)              // Runs NOW, outside tx!
 *     .within(txContext);                     // Transaction wraps... nothing
 * </pre>
 *
 * <h2>The Solution</h2>
 * {@code ResultPipeline} wraps a {@code Supplier<Result<T>>} — a deferred computation.
 * Its {@link #flatMap(Function)} and {@link #map(Function)} compose Suppliers <em>lazily</em>,
 * building a description of what to compute. Only {@link #within(ExecutionContext)} or
 * {@link #run()} triggers evaluation:
 * <pre>
 * // ✅ CORRECT: ALL stages execute inside the transaction
 * Result.pipeline(data)
 *     .flatMap(d -> fetchFromDB(d, ports))   // Deferred — NOT executed yet
 *     .flatMap(Stages::validate)              // Deferred — NOT executed yet
 *     .flatMap(d -> persist(d, ports))        // Deferred — NOT executed yet
 *     .within(txContext)                      // EVERYTHING executes HERE, inside tx!
 *     .flatMap(Stages::buildResult)           // Eager on Result (pure, outside tx)
 *     .peek(r -> log.info("Done: {}", r));
 * </pre>
 *
 * <h2>FP Analogy</h2>
 * This is Java's equivalent of:
 * <ul>
 *   <li><strong>Haskell's IO monad</strong> — describes effects without executing them</li>
 *   <li><strong>Scala's ZIO / Cats IO</strong> — deferred effect types</li>
 *   <li><strong>F#'s computation expressions</strong> — {@code result { let! ... }}</li>
 * </ul>
 *
 * <h2>Key Design Decisions</h2>
 * <ul>
 *   <li>{@code flatMap}/{@code map} take the same function signatures as {@link Result} —
 *       existing stages work unchanged</li>
 *   <li>{@code .within(ctx)} returns {@link Result}, not {@code ResultPipeline} —
 *       the type system marks the execution boundary</li>
 *   <li>Immutable and thread-safe — each {@code flatMap}/{@code map} returns a new instance</li>
 * </ul>
 *
 * @param <T> the success value type
 * @see Result#pipeline(Object) convenient entry point
 * @see ExecutionContext the context that controls execution (transactions, etc.)
 */
@NullMarked
public final class ResultPipeline<T> {

    private final Supplier<Result<T>> computation;

    private ResultPipeline(Supplier<Result<T>> computation) {
        this.computation = Objects.requireNonNull(computation);
    }

    // --- Static factories ---

    /**
     * Start a deferred pipeline from a success value.
     * <p>Equivalent to {@code ResultPipeline.of(() -> Result.success(value))}.</p>
     *
     * @param value the initial value (must not be null)
     * @param <T>   the value type
     * @return a deferred pipeline that will produce {@code Result.success(value)} when executed
     */
    public static <T> ResultPipeline<T> start(T value) {
        Objects.requireNonNull(value);
        return new ResultPipeline<>(() -> Result.success(value));
    }

    /**
     * Create a deferred pipeline from a {@code Supplier<Result<T>>}.
     * <p>The supplier is NOT called until {@link #within(ExecutionContext)} or {@link #run()}.</p>
     *
     * @param computation the deferred computation
     * @param <T>         the value type
     * @return a deferred pipeline wrapping the computation
     */
    public static <T> ResultPipeline<T> of(Supplier<Result<T>> computation) {
        return new ResultPipeline<>(computation);
    }

    // --- Lazy composition ---

    /**
     * Lazily compose a flatMap operation. Does <strong>NOT</strong> execute —
     * builds a new computation that chains the function after this pipeline.
     *
     * <p>Stages return {@code Result<U>} (not {@code ResultPipeline<U>}),
     * so existing stage methods work unchanged:</p>
     * <pre>
     * pipeline.flatMap(d -> Stages.fetch(d, ports))    // Impure stage
     *         .flatMap(Stages::validate)                 // Pure stage
     * </pre>
     *
     * @param fn  the function to apply to the success value
     * @param <U> the new value type
     * @return a new deferred pipeline with the composed operation
     */
    public <U> ResultPipeline<U> flatMap(Function<? super T, Result<U>> fn) {
        return new ResultPipeline<>(() -> computation.get().flatMap(fn));
    }

    /**
     * Lazily compose a map (pure transformation). Does <strong>NOT</strong> execute —
     * builds a new computation that transforms the value after this pipeline.
     *
     * @param fn  the transformation function
     * @param <U> the new value type
     * @return a new deferred pipeline with the composed transformation
     */
    public <U> ResultPipeline<U> map(Function<? super T, ? extends U> fn) {
        return new ResultPipeline<>(() -> computation.get().map(fn));
    }

    // --- Execution triggers ---

    /**
     * Execute this deferred pipeline within the given execution context.
     *
     * <p>This is the <strong>correct</strong> {@code .within()} — the entire computation
     * (all composed flatMap/map operations) is passed as a {@code Supplier} to
     * {@link ExecutionContext#execute(Supplier)}, so ALL stages run inside the context
     * (e.g., inside a database transaction).</p>
     *
     * <p>Returns {@link Result}, not {@code ResultPipeline} — the type system marks
     * the execution boundary. Pure operations after {@code .within()} chain on
     * {@link Result} directly.</p>
     *
     * <pre>
     * Result.pipeline(data)
     *     .flatMap(d -> Stages.fetch(d, ports))       // Inside tx
     *     .flatMap(Stages::validate)                   // Inside tx
     *     .flatMap(d -> Stages.persist(d, ports))      // Inside tx
     *     .within(txContext)                            // ← Execution boundary
     *     .flatMap(Stages::buildResult)                // Outside tx (pure)
     *     .peek(r -> log.info("Created: {}", r));
     * </pre>
     *
     * @param ctx the execution context (e.g., {@code TransactionExecutionContext})
     * @return the evaluated {@link Result}
     */
    public Result<T> within(ExecutionContext ctx) {
        return ctx.execute(computation);
    }

    /**
     * Execute this deferred pipeline without any execution context.
     * <p>Useful for testing or for pipelines that don't need transactional boundaries.</p>
     *
     * @return the evaluated {@link Result}
     */
    public Result<T> run() {
        return computation.get();
    }
}

