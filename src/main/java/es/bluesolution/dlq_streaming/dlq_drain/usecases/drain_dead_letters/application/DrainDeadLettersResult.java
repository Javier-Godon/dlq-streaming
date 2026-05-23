package es.bluesolution.dlq_streaming.dlq_drain.usecases.drain_dead_letters.application;

import java.util.Optional;

public record DrainDeadLettersResult(
        int releasedExpiredLeases,
        int claimedCount,
        int storedCount,
        int deletedCount,
        boolean stoppedBecauseReceiverFailed,
        Optional<String> lastProcessedProcessId,
        Optional<String> stopReason
) {
    public DrainDeadLettersResult {
        lastProcessedProcessId = lastProcessedProcessId == null ? Optional.empty() : lastProcessedProcessId;
        stopReason = stopReason == null ? Optional.empty() : stopReason;
    }
}

