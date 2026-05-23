package es.bluesolution.dlq_streaming.dlq_drain.usecases.drain_dead_letters.application;

public record DrainDeadLettersCommand(
        int batchSize,
        String workerId,
        long leaseSeconds,
        boolean releaseExpiredLeases
) {
}

