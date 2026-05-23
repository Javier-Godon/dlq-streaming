package es.bluesolution.dlq_streaming.dlq_drain.usecases.drain_dead_letters.application;

import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DeadLetterRecord;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DrainBatchSize;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DrainLeaseDuration;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DrainWorkerId;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.ProcessId;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;

public record DrainDeadLettersData(
        DrainDeadLettersCommand command,
        @Nullable DrainBatchSize batchSize,
        @Nullable DrainWorkerId workerId,
        @Nullable DrainLeaseDuration leaseDuration,
        int releasedExpiredLeases,
        List<DeadLetterRecord> claimedRecords,
        int storedCount,
        int deletedCount,
        boolean stoppedBecauseReceiverFailed,
        Optional<ProcessId> lastProcessedProcessId,
        Optional<String> stopReason
) {
    public DrainDeadLettersData {
        claimedRecords = claimedRecords == null ? List.of() : List.copyOf(claimedRecords);
        lastProcessedProcessId = lastProcessedProcessId == null ? Optional.empty() : lastProcessedProcessId;
        stopReason = stopReason == null ? Optional.empty() : stopReason;
    }

    public static DrainDeadLettersData initialize(DrainDeadLettersCommand command) {
        return new DrainDeadLettersData(command, null, null, null, 0, List.of(), 0, 0, false, Optional.empty(), Optional.empty());
    }

    DrainDeadLettersData withParsed(DrainBatchSize batchSize, DrainWorkerId workerId, DrainLeaseDuration leaseDuration) {
        return new DrainDeadLettersData(command, batchSize, workerId, leaseDuration, releasedExpiredLeases, claimedRecords,
                storedCount, deletedCount, stoppedBecauseReceiverFailed, lastProcessedProcessId, stopReason);
    }

    DrainDeadLettersData withReleasedExpiredLeases(int releasedExpiredLeases) {
        return new DrainDeadLettersData(command, batchSize, workerId, leaseDuration, releasedExpiredLeases, claimedRecords,
                storedCount, deletedCount, stoppedBecauseReceiverFailed, lastProcessedProcessId, stopReason);
    }

    DrainDeadLettersData withClaimedRecords(List<DeadLetterRecord> claimedRecords) {
        return new DrainDeadLettersData(command, batchSize, workerId, leaseDuration, releasedExpiredLeases, claimedRecords,
                storedCount, deletedCount, stoppedBecauseReceiverFailed, lastProcessedProcessId, stopReason);
    }

    DrainDeadLettersData markStored(ProcessId processId) {
        return new DrainDeadLettersData(command, batchSize, workerId, leaseDuration, releasedExpiredLeases, claimedRecords,
                storedCount + 1, deletedCount, stoppedBecauseReceiverFailed, Optional.of(processId), stopReason);
    }

    DrainDeadLettersData markDeleted() {
        return new DrainDeadLettersData(command, batchSize, workerId, leaseDuration, releasedExpiredLeases, claimedRecords,
                storedCount, deletedCount + 1, stoppedBecauseReceiverFailed, lastProcessedProcessId, stopReason);
    }

    DrainDeadLettersData markReceiverFailure(ProcessId processId, String reason) {
        return new DrainDeadLettersData(command, batchSize, workerId, leaseDuration, releasedExpiredLeases, claimedRecords,
                storedCount, deletedCount, true, Optional.of(processId), Optional.of(reason));
    }
}

