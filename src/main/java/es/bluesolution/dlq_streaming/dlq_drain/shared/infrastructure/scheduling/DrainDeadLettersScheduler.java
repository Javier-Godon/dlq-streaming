package es.bluesolution.dlq_streaming.dlq_drain.shared.infrastructure.scheduling;

import es.bluesolution.dlq_streaming.dlq_drain.shared.infrastructure.DlqDrainProperties;
import es.bluesolution.dlq_streaming.dlq_drain.usecases.drain_dead_letters.application.DrainDeadLettersCommand;
import es.bluesolution.dlq_streaming.dlq_drain.usecases.drain_dead_letters.application.DrainDeadLettersHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@ConditionalOnBean(DrainDeadLettersHandler.class)
@ConditionalOnProperty(prefix = "dlq-drain.scheduler", name = "enabled", havingValue = "true")
public class DrainDeadLettersScheduler {
    private final DrainDeadLettersHandler handler;
    private final DlqDrainProperties properties;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public DrainDeadLettersScheduler(DrainDeadLettersHandler handler, DlqDrainProperties properties) {
        this.handler = handler;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${dlq-drain.scheduler.fixed-delay-millis:30000}",
            initialDelayString = "${dlq-drain.scheduler.initial-delay-millis:5000}")
    public void drain() {
        if (!running.compareAndSet(false, true)) {
            log.debug("Skipping DLQ drain run because the previous run is still active");
            return;
        }

        try {
            var scheduler = properties.getScheduler();
            var command = new DrainDeadLettersCommand(
                    scheduler.getBatchSize(),
                    scheduler.getWorkerId(),
                    scheduler.getLeaseSeconds(),
                    scheduler.isReleaseExpiredLeases());

            handler.handle(command)
                    .peek(result -> log.info(
                            "DLQ drain completed: claimed={}, stored={}, deleted={}, releasedExpiredLeases={}, stoppedBecauseReceiverFailed={}",
                            result.claimedCount(),
                            result.storedCount(),
                            result.deletedCount(),
                            result.releasedExpiredLeases(),
                            result.stoppedBecauseReceiverFailed()))
                    .peekFailure(failure -> log.warn("DLQ drain failed: {} - {}", failure.code(), failure.message()));
        } finally {
            running.set(false);
        }
    }
}

