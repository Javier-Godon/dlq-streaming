package es.bluesolution.dlq_streaming.dlq_drain.domain.repository;

import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DeadLetterRecord;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DrainBatchSize;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DrainLeaseDuration;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DrainWorkerId;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.ProcessId;
import es.bluesolution.dlq_streaming.functional_framework.Result;

import java.time.Instant;
import java.util.List;

public interface DeadLetterRepository {
    Result<List<DeadLetterRecord>> claimNextBatch(
            DrainBatchSize batchSize,
            DrainWorkerId workerId,
            DrainLeaseDuration leaseDuration,
            Instant claimedAt);

    Result<ProcessId> deleteClaimed(ProcessId processId, DrainWorkerId workerId);

    Result<Integer> releaseExpiredLeases(Instant now);
}

