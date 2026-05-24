package es.bluesolution.dlq_streaming.functional_framework;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static es.bluesolution.dlq_streaming.functional_framework.FailureResultDescription.ErrorCode.DATABASE_ERROR;
import static es.bluesolution.dlq_streaming.functional_framework.FailureResultDescription.ErrorCode.NOT_FOUND;
import static es.bluesolution.dlq_streaming.functional_framework.FailureResultDescription.ErrorCode.VALIDATION_ERROR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link Result} monad.
 *
 * <p>Covers all public API methods: factories, introspection, transformations,
 * side effects, recovery, Java 25 pattern matching, and utility combinators.</p>
 */
class ResultTest {

    // ── Static factories ──────────────────────────────────────────────────────

    @Test
    void successCreatesSuccessResult() {
        var result = Result.success("hello");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isFailure()).isFalse();
        assertThat(result.value()).isEqualTo("hello");
    }

    @Test
    void failureWithCodeMessageExceptionCreatesFailureResult() {
        var ex = new RuntimeException("boom");
        Result<String> result = Result.failure(VALIDATION_ERROR, "bad input", ex);

        assertThat(result.isFailure()).isTrue();
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.failure().code()).isEqualTo(VALIDATION_ERROR);
        assertThat(result.failure().message()).isEqualTo("bad input");
        assertThat(result.failure().exception()).isEqualTo(ex);
    }

    @Test
    void failureWithDescriptionCreatesFailureResult() {
        var desc = new FailureResultDescription(DATABASE_ERROR, "db down");
        Result<Integer> result = Result.failure(desc);

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure()).isSameAs(desc);
    }

    // ── Introspection: value() and failure() ──────────────────────────────────

    @Test
    void valueThrowsWhenCalledOnFailure() {
        Result<String> result = Result.failure(VALIDATION_ERROR, "bad", null);

        assertThatThrownBy(result::value)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bad");
    }

    @Test
    void failureThrowsWhenCalledOnSuccess() {
        var result = Result.success(42);

        assertThatThrownBy(result::failure)
                .isInstanceOf(IllegalStateException.class);
    }

    // ── map ───────────────────────────────────────────────────────────────────

    @Test
    void mapTransformsSuccessValue() {
        var result = Result.success(5).map(v -> v * 2);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value()).isEqualTo(10);
    }

    @Test
    void mapPreservesFailure() {
        Result<Integer> result = Result.<Integer>failure(VALIDATION_ERROR, "bad", null).map(v -> v * 2);

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().message()).isEqualTo("bad");
    }

    // ── mapFailure ────────────────────────────────────────────────────────────

    @Test
    void mapFailureTransformsFailureDescription() {
        Result<String> result = Result.<String>failure(VALIDATION_ERROR, "original", null)
                .mapFailure(f -> new FailureResultDescription(DATABASE_ERROR, "transformed: " + f.message()));

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().code()).isEqualTo(DATABASE_ERROR);
        assertThat(result.failure().message()).isEqualTo("transformed: original");
    }

    @Test
    void mapFailurePreservesSuccess() {
        var result = Result.success("ok").mapFailure(f -> new FailureResultDescription(DATABASE_ERROR, "ignored"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value()).isEqualTo("ok");
    }

    // ── flatMap ───────────────────────────────────────────────────────────────

    @Test
    void flatMapChainsSuccessResults() {
        var result = Result.success("hello")
                .flatMap(s -> Result.success(s.toUpperCase()));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value()).isEqualTo("HELLO");
    }

    @Test
    void flatMapShortCircuitsOnFailure() {
        AtomicBoolean called = new AtomicBoolean(false);
        Result<String> result = Result.<String>failure(VALIDATION_ERROR, "bad", null)
                .flatMap(s -> {
                    called.set(true);
                    return Result.success(s.toUpperCase());
                });

        assertThat(result.isFailure()).isTrue();
        assertThat(called.get()).isFalse();
    }

    @Test
    void flatMapCanReturnFailure() {
        var result = Result.success("bad-value")
                .flatMap(s -> Result.<String>failure(VALIDATION_ERROR, "rejected: " + s, null));

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().message()).isEqualTo("rejected: bad-value");
    }

    // ── peek and peekFailure ──────────────────────────────────────────────────

    @Test
    void peekInvokesActionOnSuccess() {
        AtomicReference<String> seen = new AtomicReference<>();
        var result = Result.success("value").peek(seen::set);

        assertThat(result.isSuccess()).isTrue();
        assertThat(seen.get()).isEqualTo("value");
    }

    @Test
    void peekDoesNotInvokeOnFailure() {
        AtomicBoolean called = new AtomicBoolean(false);
        Result<String> failure = Result.failure(VALIDATION_ERROR, "bad", null);
        failure.peek(v -> called.set(true));

        assertThat(called.get()).isFalse();
    }

    @Test
    void peekFailureInvokesActionOnFailure() {
        AtomicReference<String> seen = new AtomicReference<>();
        Result<String> result = Result.<String>failure(DATABASE_ERROR, "db-error", null)
                .peekFailure(f -> seen.set(f.message()));

        assertThat(result.isFailure()).isTrue();
        assertThat(seen.get()).isEqualTo("db-error");
    }

    @Test
    void peekFailureDoesNotInvokeOnSuccess() {
        AtomicBoolean called = new AtomicBoolean(false);
        Result.success("ok").peekFailure(f -> called.set(true));

        assertThat(called.get()).isFalse();
    }

    // ── either ────────────────────────────────────────────────────────────────

    @Test
    void eitherAppliesSuccessBranchOnSuccess() {
        var label = Result.success(42).either(v -> "value:" + v, f -> "fail:" + f.message());

        assertThat(label).isEqualTo("value:42");
    }

    @Test
    void eitherAppliesFailureBranchOnFailure() {
        var label = Result.<Integer>failure(VALIDATION_ERROR, "oops", null)
                .either(v -> "value:" + v, f -> "fail:" + f.message());

        assertThat(label).isEqualTo("fail:oops");
    }

    // ── recover, getOrElse, getOrElseGet ──────────────────────────────────────

    @Test
    void recoverConvertsFailureToSuccess() {
        Result<String> result = Result.<String>failure(VALIDATION_ERROR, "bad", null)
                .recover(f -> "recovered");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value()).isEqualTo("recovered");
    }

    @Test
    void recoverPreservesExistingSuccess() {
        var result = Result.success("original").recover(f -> "recovered");

        assertThat(result.value()).isEqualTo("original");
    }

    @Test
    void getOrElseReturnsValueOnSuccess() {
        assertThat(Result.success("hello").getOrElse("default")).isEqualTo("hello");
    }

    @Test
    void getOrElseReturnsDefaultOnFailure() {
        String val = Result.<String>failure(VALIDATION_ERROR, "bad", null).getOrElse("default");
        assertThat(val).isEqualTo("default");
    }

    @Test
    void getOrElseGetAppliesFallbackOnFailure() {
        String val = Result.<String>failure(DATABASE_ERROR, "db-error", null)
                .getOrElseGet(f -> "fallback-" + f.code().name());
        assertThat(val).isEqualTo("fallback-DATABASE_ERROR");
    }

    @Test
    void getOrElseGetReturnsValueOnSuccess() {
        String val = Result.success("ok").getOrElseGet(f -> "fallback");
        assertThat(val).isEqualTo("ok");
    }

    // ── ensure (instance) ────────────────────────────────────────────────────

    @Test
    void ensurePassesWhenConditionIsTrue() {
        var result = Result.success(10)
                .ensure(v -> v > 0, new FailureResultDescription(VALIDATION_ERROR, "must be positive"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value()).isEqualTo(10);
    }

    @Test
    void ensureFailsWhenConditionIsFalse() {
        var result = Result.success(-1)
                .ensure(v -> v > 0, new FailureResultDescription(VALIDATION_ERROR, "must be positive"));

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().message()).isEqualTo("must be positive");
    }

    @Test
    void ensureShortCircuitsOnExistingFailure() {
        AtomicBoolean predicateCalled = new AtomicBoolean(false);
        Result<Integer> result = Result.<Integer>failure(VALIDATION_ERROR, "already failed", null)
                .ensure(v -> { predicateCalled.set(true); return true; },
                        new FailureResultDescription(DATABASE_ERROR, "ignored"));

        assertThat(result.failure().message()).isEqualTo("already failed");
        assertThat(predicateCalled.get()).isFalse();
    }

    // ── ensure (static) ──────────────────────────────────────────────────────

    @Test
    void staticEnsurePassesWhenConditionIsTrue() {
        var result = Result.ensure(42, v -> v > 0,
                new FailureResultDescription(VALIDATION_ERROR, "must be positive"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value()).isEqualTo(42);
    }

    @Test
    void staticEnsureFailsWhenConditionIsFalse() {
        var result = Result.ensure(-1, v -> v > 0,
                new FailureResultDescription(VALIDATION_ERROR, "must be positive"));

        assertThat(result.isFailure()).isTrue();
    }

    // ── fromNullable ─────────────────────────────────────────────────────────

    @Test
    void fromNullableReturnsSuccessForNonNullValue() {
        var result = Result.fromNullable("hello", "required");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value()).isEqualTo("hello");
    }

    @Test
    void fromNullableReturnsFailureForNullValue() {
        Result<String> result = Result.fromNullable(null, "value is required");

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().code()).isEqualTo(VALIDATION_ERROR);
        assertThat(result.failure().message()).isEqualTo("value is required");
    }

    @Test
    void fromNullableWithErrorCodeReturnsFailureWithCustomCode() {
        Result<String> result = Result.fromNullable(null, NOT_FOUND, "entity not found");

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().code()).isEqualTo(NOT_FOUND);
        assertThat(result.failure().message()).isEqualTo("entity not found");
    }

    // ── fromComputation ──────────────────────────────────────────────────────

    @Test
    void fromComputationWrapsSuccessfulResult() {
        var result = Result.fromComputation(() -> "computed", VALIDATION_ERROR, "failed");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value()).isEqualTo("computed");
    }

    @Test
    void fromComputationCatchesExceptionAndReturnsFailure() {
        var result = Result.fromComputation(
                () -> { throw new RuntimeException("boom"); },
                DATABASE_ERROR, "computation failed");

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().code()).isEqualTo(DATABASE_ERROR);
        assertThat(result.failure().message()).isEqualTo("computation failed");
    }

    // ── combine ──────────────────────────────────────────────────────────────

    @Test
    void combineTwoSuccessResultsIntoOne() {
        var ra = Result.success("hello");
        var rb = Result.success(42);

        var combined = Result.combine(ra, rb, (s, n) -> s + "-" + n);

        assertThat(combined.isSuccess()).isTrue();
        assertThat(combined.value()).isEqualTo("hello-42");
    }

    @Test
    void combineShortCircuitsOnFirstFailure() {
        var ra = Result.<String>failure(VALIDATION_ERROR, "first failed", null);
        var rb = Result.success(42);

        var combined = Result.combine(ra, rb, (s, n) -> s + "-" + n);

        assertThat(combined.isFailure()).isTrue();
        assertThat(combined.failure().message()).isEqualTo("first failed");
    }

    @Test
    void combineShortCircuitsOnSecondFailure() {
        var ra = Result.success("ok");
        var rb = Result.<Integer>failure(DATABASE_ERROR, "second failed", null);

        var combined = Result.combine(ra, rb, (s, n) -> s + "-" + n);

        assertThat(combined.isFailure()).isTrue();
        assertThat(combined.failure().message()).isEqualTo("second failed");
    }

    @Test
    void combineThreeSuccessResultsIntoOne() {
        var ra = Result.success("a");
        var rb = Result.success("b");
        var rc = Result.success("c");

        var combined = Result.combine(ra, rb, rc, (a, b, c) -> a + b + c);

        assertThat(combined.isSuccess()).isTrue();
        assertThat(combined.value()).isEqualTo("abc");
    }

    // ── allOf ────────────────────────────────────────────────────────────────

    @Test
    void allOfCollectsAllSuccessValues() {
        var results = List.of(Result.success(1), Result.success(2), Result.success(3));

        var combined = Result.allOf(results);

        assertThat(combined.isSuccess()).isTrue();
        assertThat(combined.value()).containsExactly(1, 2, 3);
    }

    @Test
    void allOfReturnsFirstFailure() {
        var results = List.<Result<Integer>>of(
                Result.success(1),
                Result.failure(VALIDATION_ERROR, "item 2 invalid", null),
                Result.success(3));

        var combined = Result.allOf(results);

        assertThat(combined.isFailure()).isTrue();
        assertThat(combined.failure().message()).isEqualTo("item 2 invalid");
    }

    // ── match (Java 25 pattern matching) ─────────────────────────────────────

    @Test
    void matchInvokesSuccessBranch() {
        var label = Result.success(7).match(v -> "value:" + v, f -> "fail:" + f.message());

        assertThat(label).isEqualTo("value:7");
    }

    @Test
    void matchInvokesFailureBranch() {
        var label = Result.<Integer>failure(VALIDATION_ERROR, "oops", null)
                .match(v -> "value:" + v, f -> "fail:" + f.message());

        assertThat(label).isEqualTo("fail:oops");
    }

    @Test
    void matchWithGuardsRoutesByErrorCode() {
        Result<Integer> validationFail = Result.failure(VALIDATION_ERROR, "bad format", null);
        Result<Integer> notFoundFail = Result.failure(NOT_FOUND, "not found", null);
        Result<Integer> otherFail = Result.failure(DATABASE_ERROR, "db error", null);
        Result<Integer> success = Result.success(99);

        String successLabel = success.matchWithGuards(v -> "ok", f -> "validation", f -> "notfound", f -> "other");
        String validationLabel = validationFail.matchWithGuards(v -> "ok", f -> "validation", f -> "notfound", f -> "other");
        String notFoundLabel = notFoundFail.matchWithGuards(v -> "ok", f -> "validation", f -> "notfound", f -> "other");
        String otherLabel = otherFail.matchWithGuards(v -> "ok", f -> "validation", f -> "notfound", f -> "other");

        assertThat(successLabel).isEqualTo("ok");
        assertThat(validationLabel).isEqualTo("validation");
        assertThat(notFoundLabel).isEqualTo("notfound");
        assertThat(otherLabel).isEqualTo("other");
    }

    // ── pipeline + within (NoOpExecutionContext) ──────────────────────────────

    @Test
    void pipelineWithNoOpContextRunsAllStages() {
        var context = new es.bluesolution.dlq_streaming.functional_framework.execution.NoOpExecutionContext();

        var result = Result.pipeline("start")
                .flatMap(s -> Result.success(s + "-step1"))
                .flatMap(s -> Result.success(s + "-step2"))
                .within(context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value()).isEqualTo("start-step1-step2");
    }

    @Test
    void pipelineShortCircuitsOnFailure() {
        AtomicBoolean step2Called = new AtomicBoolean(false);
        var context = new es.bluesolution.dlq_streaming.functional_framework.execution.NoOpExecutionContext();

        Result<String> result = Result.pipeline("start")
                .flatMap(s -> Result.<String>failure(VALIDATION_ERROR, "step1 failed", null))
                .flatMap(s -> { step2Called.set(true); return Result.success(s); })
                .within(context);

        assertThat(result.isFailure()).isTrue();
        assertThat(step2Called.get()).isFalse();
    }
}


