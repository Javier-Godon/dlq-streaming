package es.bluesolution.dlq_streaming.dlq_drain.usecases.drain_dead_letters.infrastructure.rest.spec;

import es.bluesolution.dlq_streaming.dlq_drain.usecases.drain_dead_letters.infrastructure.rest.TriggerDrainResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * REST API contract for the drain trigger endpoint.
 *
 * <h3>Kubernetes CronJob integration</h3>
 * <pre>
 * apiVersion: batch/v1
 * kind: CronJob
 * metadata:
 *   name: dlq-drain
 * spec:
 *   schedule: "&#42;/5 &#42; &#42; &#42; &#42;"       # every 5 minutes
 *   concurrencyPolicy: Forbid
 *   jobTemplate:
 *     spec:
 *       activeDeadlineSeconds: 300
 *       template:
 *         spec:
 *           restartPolicy: Never
 *           containers:
 *             - name: trigger
 *               image: curlimages/curl:8
 *               command:
 *                 - curl
 *                 - -sf
 *                 - -X POST
 *                 - -H "Authorization: Bearer $(DLQ_DRAIN_API_KEY)"
 *                 - http://dlq-streaming:8080/drain/trigger
 * </pre>
 *
 * <h3>HTTP response contract</h3>
 * <ul>
 *   <li>{@code 200 OK} — drain ran, body is a {@link TriggerDrainResponse}</li>
 *   <li>{@code 503 Service Unavailable} — receiver is down or all retries exhausted;
 *       the K8s Job fails → alert via PagerDuty / Alertmanager</li>
 *   <li>{@code 500 Internal Server Error} — database or configuration failure</li>
 *   <li>{@code 401 Unauthorized} — missing or invalid API key (when auth is enabled)</li>
 * </ul>
 *
 * <h3>Security</h3>
 * Protect this endpoint with one of:
 * <ol>
 *   <li>Bearer API key: set {@code DLQ_DRAIN_API_KEY} env var.</li>
 *   <li>Kubernetes NetworkPolicy: allow only the CronJob namespace/service-account.</li>
 * </ol>
 */
@RequestMapping("/drain")
public interface TriggerDrainSpec {

    /**
     * Trigger a single drain run.
     *
     * <p>Executes one batch of claims → receiver writes → deletes.
     * Returns synchronously once the batch completes.
     * Returns {@code 503} when the receiver is unavailable so the Kubernetes CronJob
     * Job fails and appears in {@code kubectl get cronjob} failure history.</p>
     */
    @PostMapping("/trigger")
    ResponseEntity<TriggerDrainResponse> trigger();
}

