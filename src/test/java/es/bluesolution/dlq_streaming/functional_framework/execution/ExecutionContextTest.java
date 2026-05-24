package es.bluesolution.dlq_streaming.functional_framework.execution;

import es.bluesolution.dlq_streaming.functional_framework.FailureResultDescription;
import es.bluesolution.dlq_streaming.functional_framework.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static es.bluesolution.dlq_streaming.functional_framework.FailureResultDescription.ErrorCode.DATABASE_ERROR;
import static es.bluesolution.dlq_streaming.functional_framework.FailureResultDescription.ErrorCode.UNKNOWN_ERROR;
import static es.bluesolution.dlq_streaming.functional_framework.FailureResultDescription.ErrorCode.VALIDATION_ERROR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the execution context hierarchy.
 *
 * <p>Covers {@link NoOpExecutionContext}, {@link TransactionExecutionContext},
 * {@link LoggingExecutionContext}, and {@link ComposableExecutionContext}.</p>
 */
@ExtendWith(MockitoExtension.class)
class ExecutionContextTest {

    // ── NoOpExecutionContext ────────────────────────────────────────────────

    @Test
    void noOpContextRunsComputationImmediately() {
        var ctx = new NoOpExecutionContext();

        var result = ctx.execute(() -> Result.success("computed"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value()).isEqualTo("computed");
    }

    @Test
    void noOpContextPropagatesFailure() {
        var ctx = new NoOpExecutionContext();

        Result<String> result = ctx.execute(() -> Result.failure(VALIDATION_ERROR, "invalid", null));

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().message()).isEqualTo("invalid");
    }

    // ── TransactionExecutionContext ─────────────────────────────────────────

    @Mock
    private PlatformTransactionManager txManager;

    /** Builds a mock TransactionTemplate that runs the callback synchronously. */
    @SuppressWarnings("unchecked")
    private TransactionTemplate mockTemplate(boolean throwOnExecute) {
        var template = mock(TransactionTemplate.class);
        if (throwOnExecute) {
            when(template.execute(any(TransactionCallback.class)))
                    .thenThrow(new RuntimeException("tx failure"));
        } else {
            when(template.execute(any(TransactionCallback.class))).thenAnswer(inv -> {
                TransactionCallback<?> cb = inv.getArgument(0);
                TransactionStatus status = mock(TransactionStatus.class);
                return cb.doInTransaction(status);
            });
        }
        return template;
    }

    @Test
    void transactionContextRunsComputationInsideTransaction() {
        var ctx = new TransactionExecutionContext(mockTemplate(false));

        var result = ctx.execute(() -> Result.success(42));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value()).isEqualTo(42);
    }

    @Test
    void transactionContextPropagatesFailureResult() {
        var ctx = new TransactionExecutionContext(mockTemplate(false));

        Result<Integer> result = ctx.execute(() -> Result.failure(DATABASE_ERROR, "db fail", null));

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().code()).isEqualTo(DATABASE_ERROR);
    }

    @Test
    void transactionContextWrapsUnexpectedExceptionAsUnknownError() {
        var ctx = new TransactionExecutionContext(mockTemplate(true));

        Result<Integer> result = ctx.execute(() -> Result.success(1));

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().code()).isEqualTo(UNKNOWN_ERROR);
        assertThat(result.failure().message()).contains("tx failure");
    }

    @Test
    void transactionContextCreatedFromPlatformTransactionManager() {
        var template = mockTemplate(false);
        // TransactionExecutionContext.of() creates from PlatformTransactionManager
        // We use the template directly here since we can't easily inject a manager that
        // produces a specific template. Test the constructor path.
        var ctx = new TransactionExecutionContext(template);

        var result = ctx.execute(() -> Result.success("ok"));

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void transactionContextStaticFactoryOfCreatesContext() {
        // of() creates a read-write TransactionTemplate
        var ctx = TransactionExecutionContext.of(txManager);
        assertThat(ctx).isNotNull();
    }

    @Test
    void transactionContextStaticFactoryReadOnlyCreatesContext() {
        var ctx = TransactionExecutionContext.readOnly(txManager);
        assertThat(ctx).isNotNull();
    }

    @Test
    void transactionContextStaticFactoryWithIsolationCreatesContext() {
        var ctx = TransactionExecutionContext.withIsolation(
                txManager, java.sql.Connection.TRANSACTION_SERIALIZABLE);
        assertThat(ctx).isNotNull();
    }

    @Test
    void transactionContextExecuteWithAuditCallsBothCallbacks() {
        var ctx = new TransactionExecutionContext(mockTemplate(false));
        AtomicBoolean beforeCalled = new AtomicBoolean(false);
        AtomicReference<Result<String>> afterResult = new AtomicReference<>();

        var result = ctx.executeWithAudit(
                () -> Result.success("audited"),
                () -> beforeCalled.set(true),
                afterResult::set);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value()).isEqualTo("audited");
        assertThat(beforeCalled.get()).isTrue();
        assertThat(afterResult.get()).isNotNull();
        assertThat(afterResult.get().isSuccess()).isTrue();
    }

    // ── LoggingExecutionContext (extends ComposableExecutionContext) ─────────

    @Test
    void loggingContextDelegatesToInnerContextOnSuccess() {
        var inner = new NoOpExecutionContext();
        var logging = new LoggingExecutionContext(inner);

        var result = logging.execute(() -> Result.success("logged"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value()).isEqualTo("logged");
    }

    @Test
    void loggingContextDelegatesToInnerContextOnFailure() {
        var inner = new NoOpExecutionContext();
        var logging = new LoggingExecutionContext(inner);

        Result<String> result = logging.execute(() ->
                Result.failure(VALIDATION_ERROR, "logged failure", null));

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().message()).isEqualTo("logged failure");
    }

    @Test
    void loggingContextCanWrapTransactionContext() {
        var txCtx = new TransactionExecutionContext(mockTemplate(false));
        var logging = new LoggingExecutionContext(txCtx);

        var result = logging.execute(() -> Result.success("logged+tx"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value()).isEqualTo("logged+tx");
    }
}


