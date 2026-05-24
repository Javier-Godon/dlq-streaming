package es.bluesolution.dlq_streaming.dlq_drain.usecases.drain_dead_letters.infrastructure.rest;

import es.bluesolution.dlq_streaming.dlq_drain.shared.infrastructure.DlqDrainProperties;
import es.bluesolution.dlq_streaming.dlq_drain.usecases.drain_dead_letters.application.DrainDeadLettersCommand;
import es.bluesolution.dlq_streaming.dlq_drain.usecases.drain_dead_letters.application.DrainDeadLettersHandler;
import es.bluesolution.dlq_streaming.dlq_drain.usecases.drain_dead_letters.infrastructure.rest.spec.TriggerDrainSpec;
import es.bluesolution.dlq_streaming.functional_framework.FailureResultDescription;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller that exposes the DLQ drain as a single HTTP trigger endpoint.
 *
 * <p>Designed to be called by a Kubernetes CronJob (see {@link TriggerDrainSpec}).
 * All operational parameters (batch size, worker ID, lease duration) are read from
 * {@link DlqDrainProperties} and must not be supplied by the caller — the caller
 * simply fires the trigger.</p>
 *
 * <h3>Concurrency</h3>
 * The drain handler itself is stateless.  Concurrent requests from multiple pods
 * are safe because the PostgreSQL query uses {@code FOR UPDATE SKIP LOCKED}: each
 * pod claims a disjoint set of rows.  The K8s CronJob should use
 * {@code concurrencyPolicy: Forbid} to avoid two runs on the same pod.
 *
 * <h3>HTTP status mapping for {@code stoppedBecauseReceiverFailed}</h3>
 * When the handler returns success but {@code stoppedBecauseReceiverFailed=true},
 * this controller returns {@code 503 Service Unavailable} so the K8s Job fails and
 * the failure is visible in {@code kubectl get cronjob} history.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class TriggerDrainController implements TriggerDrainSpec {

    private final DrainDeadLettersHandler handler;
    private final DlqDrainProperties properties;


    @Override
    public ResponseEntity<TriggerDrainResponse> trigger() {
        var sched = properties.getScheduler();
        var command = new DrainDeadLettersCommand(
                sched.getBatchSize(),
                sched.getWorkerId(),
                sched.getLeaseSeconds(),
                sched.isReleaseExpiredLeases());

        var result = handler.handle(command);

        if (result.isFailure()) {
            var failure = result.failure();
            log.error("Drain trigger failed: [{}] {}", failure.code(), failure.message());
            return ResponseEntity
                    .status(mapFailureToStatus(failure))
                    .build();
        }

        var drainResult = result.value();
        log.info("Drain trigger completed: claimed={}, stored={}, deleted={}, " +
                 "releasedExpiredLeases={}, stoppedBecauseReceiverFailed={}",
                drainResult.claimedCount(),
                drainResult.storedCount(),
                drainResult.deletedCount(),
                drainResult.releasedExpiredLeases(),
                drainResult.stoppedBecauseReceiverFailed());

        if (drainResult.stoppedBecauseReceiverFailed()) {
            // Return 503 so that the K8s CronJob Job is marked as failed.
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(TriggerDrainResponse.from(drainResult));
        }

        return ResponseEntity.ok(TriggerDrainResponse.from(drainResult));
    }

    private static HttpStatus mapFailureToStatus(FailureResultDescription failure) {
        return switch (failure.code()) {
            case VALIDATION_ERROR -> HttpStatus.BAD_REQUEST;
            case EXTERNAL_SERVICE_ERROR, SERVICE_UNAVAILABLE_ERROR -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}

