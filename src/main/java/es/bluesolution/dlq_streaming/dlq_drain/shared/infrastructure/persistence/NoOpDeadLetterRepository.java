package es.bluesolution.dlq_streaming.dlq_drain.shared.infrastructure.persistence;

import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DeadLetterRecord;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DrainBatchSize;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DrainLeaseDuration;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DrainWorkerId;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.ProcessId;
import es.bluesolution.dlq_streaming.dlq_drain.domain.repository.DeadLetterRepository;
import es.bluesolution.dlq_streaming.functional_framework.Result;

import java.time.Instant;
import java.util.List;

import static es.bluesolution.dlq_streaming.functional_framework.FailureResultDescription.ErrorCode.SERVICE_UNAVAILABLE_ERROR;

/**
 * No-op {@link DeadLetterRepository} used when no JDBC data source is configured.
 *
 * <p>Registered as a {@code @Bean} fallback in
 * {@link es.bluesolution.dlq_streaming.dlq_drain.shared.infrastructure.DlqDrainAutoConfiguration}
 * via {@code @ConditionalOnMissingBean(DeadLetterRepository.class)}.
 * This allows the application context to load without a database (e.g. smoke tests,
 * local dev without Postgres) while returning a clear service-unavailable failure when
 * the drain endpoint is actually called.</p>
 *
 * <p>All methods return {@code Result.failure(SERVICE_UNAVAILABLE_ERROR, ...)}
 * so the drain handler surfaces a meaningful error instead of a NullPointerException.</p>
 */
public class NoOpDeadLetterRepository implements DeadLetterRepository {

    @Override
    public Result<List<DeadLetterRecord>> claimNextBatch(
            DrainBatchSize batchSize,
            DrainWorkerId workerId,
            DrainLeaseDuration leaseDuration,
            Instant claimedAt) {
        return Result.failure(SERVICE_UNAVAILABLE_ERROR, "No database configured — NoOpDeadLetterRepository active", null);
    }

    @Override
    public Result<ProcessId> deleteClaimed(ProcessId processId, DrainWorkerId workerId) {
        return Result.failure(SERVICE_UNAVAILABLE_ERROR, "No database configured — NoOpDeadLetterRepository active", null);
    }

    @Override
    public Result<Integer> releaseExpiredLeases(Instant now) {
        return Result.failure(SERVICE_UNAVAILABLE_ERROR, "No database configured — NoOpDeadLetterRepository active", null);
    }
}

