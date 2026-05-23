package es.bluesolution.dlq_streaming.dlq_drain.usecases.drain_dead_letters.application;

import es.bluesolution.dlq_streaming.dlq_drain.domain.repository.DeadLetterRepository;
import es.bluesolution.dlq_streaming.dlq_drain.domain.repository.DeadLetterReceiver;

import java.time.Clock;

public record DrainDeadLettersPorts(
        DeadLetterRepository deadLetterRepository,
        DeadLetterReceiver deadLetterReceiver,
        Clock clock
) {
    public static DrainDeadLettersPorts of(
            DeadLetterRepository deadLetterRepository,
            DeadLetterReceiver deadLetterReceiver,
            Clock clock) {
        return new DrainDeadLettersPorts(deadLetterRepository, deadLetterReceiver, clock);
    }
}

