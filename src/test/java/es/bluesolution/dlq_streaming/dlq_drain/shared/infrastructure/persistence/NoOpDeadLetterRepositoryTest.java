package es.bluesolution.dlq_streaming.dlq_drain.shared.infrastructure.persistence;

import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DrainBatchSize;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DrainLeaseDuration;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DrainWorkerId;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.ProcessId;
import es.bluesolution.dlq_streaming.functional_framework.FailureResultDescription;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link NoOpDeadLetterRepository}.
 *
 * <p>The {@code NoOpDeadLetterRepository} is registered when no JDBC datasource is configured
 * (e.g. smoke tests, local dev without PostgreSQL). Every method must return a clear
 * {@code SERVICE_UNAVAILABLE_ERROR} rather than throwing a {@code NullPointerException}.</p>
 */
class NoOpDeadLetterRepositoryTest {

    private final NoOpDeadLetterRepository repository = new NoOpDeadLetterRepository();

    @Test
    void claimNextBatchReturnsServiceUnavailableError() {
        var result = repository.claimNextBatch(
                DrainBatchSize.create(10).value(),
                DrainWorkerId.create("worker").value(),
                DrainLeaseDuration.create(Duration.ofMinutes(2)).value(),
                Instant.now());

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().code()).isEqualTo(FailureResultDescription.ErrorCode.SERVICE_UNAVAILABLE_ERROR);
        assertThat(result.failure().message()).contains("NoOpDeadLetterRepository");
    }

    @Test
    void deleteClaimedReturnsServiceUnavailableError() {
        var result = repository.deleteClaimed(
                ProcessId.create("product-1_2026-05-23T10:15:30Z").value(),
                DrainWorkerId.create("worker").value());

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().code()).isEqualTo(FailureResultDescription.ErrorCode.SERVICE_UNAVAILABLE_ERROR);
        assertThat(result.failure().message()).contains("NoOpDeadLetterRepository");
    }

    @Test
    void releaseExpiredLeasesReturnsServiceUnavailableError() {
        var result = repository.releaseExpiredLeases(Instant.now());

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().code()).isEqualTo(FailureResultDescription.ErrorCode.SERVICE_UNAVAILABLE_ERROR);
        assertThat(result.failure().message()).contains("NoOpDeadLetterRepository");
    }
}

