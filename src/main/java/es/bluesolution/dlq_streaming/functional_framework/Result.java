package es.bluesolution.dlq_streaming.functional_framework;

import es.bluesolution.dlq_streaming.functional_framework.execution.ExecutionContext;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.time.Duration;

/**
 * Railway-Oriented Programming Result monad with compile-time null safety.
 * In @NullMarked scope, all parameters and return types are non-null by default.
 *
 * This interface provides comprehensive functional error handling patterns
 * with guaranteed null safety through JSpecify annotations.
 */
public sealed interface Result<S> permits Result.Success, Result.Failure {

    /**
     * Apply either success or failure function based on Result state.
     * Both functions receive guaranteed non-null values in @NullMarked scope.
     */
    <R> R either(Function<? super S, ? extends R> onSuccess,
                 Function<? super FailureResultDescription, ? extends R> onFailure);

    // --- Convenience methods for introspection ---

    /**
     * Check if this Result represents a success.
     * @return true if this is a Success, false if it's a Failure
     */
    default boolean isSuccess() {
        return this instanceof Success<S>;
    }

    /**
     * Check if this Result represents a failure.
     * @return true if this is a Failure, false if it's a Success
     */
    default boolean isFailure() {
        return this instanceof Failure<S>;
    }

    /**
     * Get the success value if this Result is a Success.
     * @return the success value
     * @throws IllegalStateException if this Result is a Failure
     */
    default S value() {
        return either(
            Function.identity(),
            error -> { throw new IllegalStateException("Cannot get value from a Failure: " + error.message()); }
        );
    }

    /**
     * Get the failure description if this Result is a Failure.
     * @return the failure description
     * @throws IllegalStateException if this Result is a Success
     */
    default FailureResultDescription failure() {
        return either(
            value -> { throw new IllegalStateException("Cannot get failure from a Success: " + value); },
            Function.identity()
        );
    }

    // --- NEW: Java 25 Pattern Matching Support ---

    /**
     * Pattern matching support for Java 25 switch expressions.
     * @param onSuccess function to apply on success
     * @param onFailure function to apply on failure
     * @return the result of the applied function
     */
    default <U> U match(
            Function<S, U> onSuccess,
            Function<FailureResultDescription, U> onFailure) {
        return switch (this) {
            case Success<S>(var value) -> onSuccess.apply(value);
            case Failure<S>(var error) -> onFailure.apply(error);
        };
    }

    /**
     * Enhanced pattern matching with guard patterns for specific error types.
     * @param onSuccess function to apply on success
     * @param onValidationError function to apply on validation errors
     * @param onNotFound function to apply on not found errors
     * @param onOtherError function to apply on other errors
     * @return the result of the applied function
     */
    default <U> U matchWithGuards(
            Function<S, U> onSuccess,
            Function<FailureResultDescription, U> onValidationError,
            Function<FailureResultDescription, U> onNotFound,
            Function<FailureResultDescription, U> onOtherError) {
        return switch (this) {
            case Success<S>(var value) -> onSuccess.apply(value);
            case Failure<S>(var error) when error.code() == FailureResultDescription.ErrorCode.VALIDATION_ERROR ->
                onValidationError.apply(error);
            case Failure<S>(var error) when error.code() == FailureResultDescription.ErrorCode.NOT_FOUND ->
                onNotFound.apply(error);
            case Failure<S>(var error) -> onOtherError.apply(error);
        };
    }

    // --- NEW: Virtual Threads Support ---

    /**
     * Execute this Result asynchronously using virtual threads.
     * @return CompletableFuture of this Result
     */
    default CompletableFuture<Result<S>> async() {
        return CompletableFuture.supplyAsync(() -> this, virtualThreadExecutor());
    }

    /**
     * Map this Result asynchronously using virtual threads.
     * @param mapper function to apply to the success value
     * @return CompletableFuture of the mapped Result
     */
    default <U> CompletableFuture<Result<U>> mapAsync(Function<S, U> mapper) {
        return CompletableFuture.supplyAsync(() -> this.map(mapper), virtualThreadExecutor());
    }

    /**
     * FlatMap this Result asynchronously using virtual threads.
     * @param mapper function to apply to the success value
     * @return CompletableFuture of the flatMapped Result
     */
    default <U> CompletableFuture<Result<U>> flatMapAsync(Function<S, Result<U>> mapper) {
        return CompletableFuture.supplyAsync(() -> this.flatMap(mapper), virtualThreadExecutor());
    }

    /**
     * Add timeout support to Result operations.
     * @param timeout the maximum time to wait
     * @return Result with timeout handling
     */
    default Result<S> withTimeout(Duration timeout) {
        try {
            return CompletableFuture.supplyAsync(() -> this, virtualThreadExecutor())
                .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // Re-interrupt the thread
            return Result.failure(new FailureResultDescription(
                FailureResultDescription.ErrorCode.EXTERNAL_SERVICE_ERROR,
                "Operation was interrupted",
                e
            ));
        } catch (Exception e) {
            return Result.failure(new FailureResultDescription(
                FailureResultDescription.ErrorCode.EXTERNAL_SERVICE_ERROR,
                "Operation timed out after " + timeout,
                e
            ));
        }
    }

    // --- Transformations ---
    default <T> Result<T> map(Function<? super S, ? extends T> mapper) {
        return either(
                value -> new Success<>(mapper.apply(value)),
                Failure::new
        );
    }

    default Result<S> mapFailure(Function<? super FailureResultDescription, FailureResultDescription> mapper) {
        return either(
                Success::new,
                error -> new Failure<>(mapper.apply(error))
        );
    }

    default <T> Result<T> flatMap(Function<? super S, Result<T>> mapper) {
        return either(
                mapper,
                Failure::new
        );
    }

    // --- Execution context (Effect boundary) ---

    /**
     * Start a deferred pipeline from a success value.
     *
     * <p>Returns a {@link ResultPipeline} where {@code flatMap}/{@code map} compose lazily.
     * Use {@code .within(txContext)} to execute the entire pipeline inside a transaction,
     * or {@code .run()} to execute without a context.</p>
     *
     * <h3>Why use this instead of {@code Result.success(data).flatMap(...).within(txContext)}?</h3>
     * <p>In Java, {@code Result.flatMap()} evaluates <strong>eagerly</strong>. Chaining flatMap
     * on a Result executes each stage immediately. By the time {@code .within()} is reached,
     * all DB operations have already run outside the transaction.</p>
     *
     * <p>{@code ResultPipeline} solves this by composing Suppliers lazily — nothing executes
     * until {@code .within()} or {@code .run()} is called:</p>
     *
     * <pre>
     * // ✅ CORRECT: All stages execute INSIDE the transaction
     * return Result.pipeline(data)
     *     .flatMap(d -> Stages.fetch(d, ports))       // Deferred (impure)
     *     .flatMap(Stages::validate)                   // Deferred (pure)
     *     .flatMap(d -> Stages.persist(d, ports))      // Deferred (impure)
     *     .within(txContext)                            // EXECUTES HERE inside tx!
     *     .flatMap(Stages::buildResult)                // Eager on Result (pure, outside tx)
     *     .peek(r -> log.info("Created: {}", r.id()))
     *     .peekFailure(e -> log.warn("Failed: {}", e.message()));
     * </pre>
     *
     * <p>This mirrors Haskell's IO monad, Scala's ZIO, and F#'s computation expressions —
     * where computation is described separately from execution.</p>
     *
     * @param value the initial value (must not be null)
     * @param <T>   the value type
     * @return a deferred {@link ResultPipeline}
     * @see ResultPipeline
     * @see ResultPipeline#within(ExecutionContext)
     */
    static <T> ResultPipeline<T> pipeline(T value) {
        return ResultPipeline.start(value);
    }

    /**
     * Execute this Result within an execution context.
     *
     * @deprecated <strong>BROKEN IN JAVA</strong> — In Java (eagerly evaluated), the flatMap chain
     * before {@code .within()} has already executed by the time this method is called.
     * The transaction wraps the already-computed result, not the computation.
     *
     * <p>Use {@link #pipeline(Object)} instead, which defers computation correctly:</p>
     * <pre>
     * // ❌ BROKEN: stages execute BEFORE the transaction
     * return Result.success(data)
     *     .flatMap(d -> fetch(d, ports))   // Runs eagerly, outside tx!
     *     .within(txContext);               // Transaction wraps nothing useful
     *
     * // ✅ CORRECT: stages execute INSIDE the transaction
     * return Result.pipeline(data)
     *     .flatMap(d -> fetch(d, ports))   // Deferred until .within()
     *     .within(txContext);               // Everything runs inside tx!
     *
     * // ✅ ALSO CORRECT: explicit txContext.execute()
     * return txContext.execute(() ->
     *     Result.success(data)
     *         .flatMap(d -> fetch(d, ports))
     * );
     * </pre>
     *
     * @param executionContext the context that describes how to execute
     * @return this Result (already computed), executed within the given context
     * @see #pipeline(Object) the correct deferred alternative
     * @see ResultPipeline#within(ExecutionContext)
     */
    @Deprecated(since = "2026.04", forRemoval = true)
    default Result<S> within(ExecutionContext executionContext) {
        return executionContext.execute(() -> this);
    }

    // --- Side effects ---
    default Result<S> peek(Consumer<? super S> action) {
        either(
                value -> {
                    action.accept(value);
                    return null;
                },
                error -> null
        );
        return this;
    }

    default Result<S> peekFailure(Consumer<? super FailureResultDescription> action) {
        either(
                value -> null,
                error -> {
                    action.accept(error);
                    return null;
                }
        );
        return this;
    }

    // --- Recovery & unwrapping ---
    default Result<S> recover(Function<? super FailureResultDescription, ? extends S> recoverFn) {
        return either(
                Success::new,
                error -> new Success<>(recoverFn.apply(error))
        );
    }

    default S getOrElse(S defaultValue) {
        return either(
                Function.identity(),
                error -> defaultValue
        );
    }

    default S getOrElseGet(Function<? super FailureResultDescription, ? extends S> fallback) {
        return either(
                Function.identity(),
                fallback
        );
    }

    // --- Static factories ---
    static <S> Result<S> success(S value) {
        return new Success<>(value);
    }

    static <S> Result<S> failure(FailureResultDescription.ErrorCode code, String message, Exception exception) {
        return new Failure<>(new FailureResultDescription(code, message, exception));
    }

    static <S> Result<S> failure(FailureResultDescription error) {
        return new Failure<>(error);
    }

    // --- Utility Static Factories ---

    /**
     * Create a Result from a computation that may throw an exception.
     * Wraps exceptions into Result.failure with the specified error code.
     *
     * This eliminates try-catch boilerplate in repository implementations:
     *
     * Before:
     * try {
     *     return Result.success(repo.findById(id));
     * } catch (Exception e) {
     *     return Result.failure(DATABASE_ERROR, "Failed", e);
     * }
     *
     * After:
     * return Result.fromComputation(
     *     () -> repo.findById(id),
     *     DATABASE_ERROR,
     *     "Failed to find entity"
     * );
     *
     * @param computation the operation that may throw
     * @param errorCode the error code to use if an exception occurs
     * @param errorMessage the error message to use if an exception occurs
     * @param <S> the success value type
     * @return Result.success if computation succeeds, Result.failure if it throws
     */
    static <S> Result<S> fromComputation(
            Supplier<S> computation,
            FailureResultDescription.ErrorCode errorCode,
            String errorMessage) {
        try {
            return Result.success(computation.get());
        } catch (Exception e) {
            return Result.failure(new FailureResultDescription(errorCode, errorMessage, e));
        }
    }

    /**
     * Create a Result from a nullable value.
     * Returns Success if value is non-null, Failure with VALIDATION_ERROR if null.
     *
     * Usage:
     * Result<String> name = Result.fromNullable(input, "Name is required");
     *
     * @param value the possibly null value
     * @param errorMessage the error message if value is null
     * @param <S> the success value type
     * @return Result.success if value is non-null, Result.failure if null
     */
    static <S> Result<S> fromNullable(
            @Nullable S value,
            String errorMessage) {
        return value != null
                ? Result.success(value)
                : Result.failure(new FailureResultDescription(
                        FailureResultDescription.ErrorCode.VALIDATION_ERROR,
                        errorMessage));
    }

    /**
     * Create a Result from a nullable value with custom error code.
     *
     * @param value the possibly null value
     * @param errorCode the error code to use if value is null
     * @param errorMessage the error message if value is null
     * @param <S> the success value type
     * @return Result.success if value is non-null, Result.failure if null
     */
    static <S> Result<S> fromNullable(
            @Nullable S value,
            FailureResultDescription.ErrorCode errorCode,
            String errorMessage) {
        return value != null
                ? Result.success(value)
                : Result.failure(new FailureResultDescription(errorCode, errorMessage));
    }

    /**
     * Create a Result based on a condition.
     * Returns Success with the value if condition is true, Failure otherwise.
     *
     * Usage:
     * Result<Order> validated = Result.ensure(
     *     order,
     *     o -> o.total() > 0,
     *     new FailureResultDescription(VALIDATION_ERROR, "Order total must be positive")
     * );
     *
     * @param value the value to wrap
     * @param condition the condition that must be true
     * @param error the failure description if condition is false
     * @param <S> the success value type
     * @return Result.success if condition is true, Result.failure if false
     */
    static <S> Result<S> ensure(
            S value,
            java.util.function.Predicate<S> condition,
            FailureResultDescription error) {
        return condition.test(value)
                ? Result.success(value)
                : Result.failure(error);
    }

    /**
     * Combine two Results into a single Result.
     * Both must succeed for the combination to succeed.
     *
     * Usage:
     * Result<Order> order = Result.combine(
     *     validateCustomer(cmd),
     *     validateProducts(cmd),
     *     (customer, products) -> new Order(customer, products)
     * );
     *
     * @param ra first Result
     * @param rb second Result
     * @param combiner function to combine the success values
     * @param <A> first success type
     * @param <B> second success type
     * @param <R> combined result type
     * @return Result containing combined value, or first failure encountered
     */
    static <A, B, R> Result<R> combine(
            Result<A> ra,
            Result<B> rb,
            java.util.function.BiFunction<A, B, R> combiner) {
        return ra.flatMap(a -> rb.map(b -> combiner.apply(a, b)));
    }

    /**
     * Combine three Results into a single Result.
     * All must succeed for the combination to succeed.
     *
     * @param ra first Result
     * @param rb second Result
     * @param rc third Result
     * @param combiner function to combine the success values
     * @param <A> first success type
     * @param <B> second success type
     * @param <C> third success type
     * @param <R> combined result type
     * @return Result containing combined value, or first failure encountered
     */
    static <A, B, C, R> Result<R> combine(
            Result<A> ra,
            Result<B> rb,
            Result<C> rc,
            TriFunction<A, B, C, R> combiner) {
        return ra.flatMap(a -> rb.flatMap(b -> rc.map(c -> combiner.apply(a, b, c))));
    }

    /**
     * Functional interface for combining three values.
     */
    @FunctionalInterface
    interface TriFunction<A, B, C, R> {
        R apply(A a, B b, C c);
    }

    // --- NEW: Instance method ensure ---

    /**
     * Validate the success value against a condition.
     * Short-circuits on failure (returns this if already a failure).
     *
     * Usage:
     * return Result.success(order)
     *     .ensure(o -> o.total() > 0, new FailureResultDescription(...))
     *     .ensure(o -> !o.items().isEmpty(), new FailureResultDescription(...));
     *
     * @param condition the condition that must be true
     * @param error the failure description if condition is false
     * @return this Result if success and condition true, failure otherwise
     */
    default Result<S> ensure(
            java.util.function.Predicate<S> condition,
            FailureResultDescription error) {
        return flatMap(value -> condition.test(value)
                ? Result.success(value)
                : Result.failure(error));
    }

    // --- NEW: Enhanced Static Factories for Java 25 ---

    /**
     * Create a Result from an async operation using virtual threads.
     * @param supplier the operation to execute
     * @return CompletableFuture of Result
     */
    static <S> CompletableFuture<Result<S>> ofAsync(Supplier<S> supplier) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return Result.success(supplier.get());
            } catch (Exception e) {
                return Result.failure(new FailureResultDescription(
                    FailureResultDescription.ErrorCode.UNKNOWN_ERROR,
                    "Async operation failed",
                    e
                ));
            }
        }, virtualThreadExecutor());
    }

    /**
     * Combine multiple Results into a single Result containing a List.
     * Uses virtual threads for parallel processing.
     * @param results the Results to combine
     * @return Result containing List of all success values, or the first failure
     */
    static <S> Result<List<S>> allOf(List<Result<S>> results) {
        var futures = results.stream()
            .map(Result::async)
            .toList();

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> {
                var values = futures.stream()
                    .map(CompletableFuture::join)
                    .toList();

                var failures = values.stream()
                    .filter(r -> !r.isSuccess())
                    .map(Result::failure)
                    .toList();

                if (!failures.isEmpty()) {
                    return Result.<List<S>>failure(failures.get(0));
                }

                var successValues = values.stream()
                    .map(Result::value)
                    .toList();

                return Result.success(successValues);
            })
            .join();
    }

    /**
     * Execute multiple operations in parallel with virtual threads.
     * @param operations list of operations to execute
     * @return Result containing List of results
     */
    static <S> CompletableFuture<Result<List<S>>> parallelOf(List<Supplier<S>> operations) {
        var futures = operations.stream()
            .map(Result::ofAsync)
            .toList();

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> {
                var results = futures.stream()
                    .map(CompletableFuture::join)
                    .toList();

                return Result.allOf(results);
            });
    }

    /**
     * Get the virtual thread executor for async operations.
     * @return virtual thread executor
     */
    private static Executor virtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    // --- Implementations ---
    record Success<S>(S value) implements Result<S> {
        public Success {
            Objects.requireNonNull(value);
        }

        @Override
        public <R> R either(Function<? super S, ? extends R> onSuccess,
                            Function<? super FailureResultDescription, ? extends R> onFailure) {
            return onSuccess.apply(value);
        }
    }

    record Failure<S>(FailureResultDescription error) implements Result<S> {
        public Failure {
            Objects.requireNonNull(error);
        }

        @Override
        public <R> R either(Function<? super S, ? extends R> onSuccess,
                            Function<? super FailureResultDescription, ? extends R> onFailure) {
            return onFailure.apply(error);
        }
    }
}


