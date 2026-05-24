package es.bluesolution.dlq_streaming.dlq_drain.usecases.drain_dead_letters.infrastructure.rest;

import es.bluesolution.dlq_streaming.dlq_drain.usecases.drain_dead_letters.application.DrainDeadLettersResult;

/**
 * JSON response body for {@code POST /drain/trigger}.
 *
 * <p>The Kubernetes CronJob calling this endpoint reads this body to determine
 * whether the drain run completed cleanly or stopped early because the receiver
 * was unavailable.  The HTTP status code encodes the same signal:</p>
 * <ul>
 *   <li>{@code 200 OK} — drain ran and all claimed records were sent + deleted.</li>
 *   <li>{@code 200 OK, stoppedBecauseReceiverFailed=true} — drain stopped early;
 *       the K8s Job is still considered as succeeded but an alert can be raised
 *       on the {@code stoppedBecauseReceiverFailed} field.</li>
 *   <li>{@code 503 Service Unavailable} — the receiver is unreachable after all
 *       retries; the K8s Job will fail and CronJob history will show the failure.</li>
 *   <li>{@code 500 Internal Server Error} — infrastructure failure
 *       (database not reachable, configuration error, etc.).</li>
 * </ul>
 */
public record TriggerDrainResponse(
        int releasedExpiredLeases,
        int claimedCount,
        int storedCount,
        int deletedCount,
        boolean stoppedBecauseReceiverFailed,
        String lastProcessedProcessId,
        String stopReason
) {
    public static TriggerDrainResponse from(DrainDeadLettersResult result) {
        return new TriggerDrainResponse(
                result.releasedExpiredLeases(),
                result.claimedCount(),
                result.storedCount(),
                result.deletedCount(),
                result.stoppedBecauseReceiverFailed(),
                result.lastProcessedProcessId().orElse(null),
                result.stopReason().orElse(null));
    }
}

