package es.bluesolution.dlq_streaming.dlq_drain.shared.infrastructure.receiver;

import es.bluesolution.dlq_streaming.dlq_drain.domain.model.ReceiveDeadLetterAck;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.ReceiveDeadLetterCommand;
import es.bluesolution.dlq_streaming.dlq_drain.domain.repository.DeadLetterReceiver;
import es.bluesolution.dlq_streaming.dlq_drain.shared.infrastructure.DlqDrainProperties;
import es.bluesolution.dlq_streaming.functional_framework.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.Set;

import static es.bluesolution.dlq_streaming.functional_framework.FailureResultDescription.ErrorCode.EXTERNAL_SERVICE_ERROR;

/**
 * Data Prepper HTTP receiver adapter.
 *
 * <h3>Timeout</h3>
 * The injected {@link RestClient} must be pre-configured with connect and read timeouts
 * (see {@link es.bluesolution.dlq_streaming.dlq_drain.shared.infrastructure.DlqDrainInfrastructureConfig}).
 * Without timeouts the receiver will hang indefinitely on a slow or unreachable Data Prepper instance.
 *
 * <h3>Retry policy</h3>
 * Transient failures are retried with exponential back-off:
 * <ul>
 *   <li>HTTP 503 Service Unavailable — Data Prepper restarting or overloaded.</li>
 *   <li>HTTP 502 Bad Gateway — upstream gateway error.</li>
 *   <li>HTTP 429 Too Many Requests — rate limit; respect Retry-After if present.</li>
 *   <li>HTTP 504 Gateway Timeout — gateway did not respond in time.</li>
 *   <li>{@link ResourceAccessException} — transient TCP failure (connection refused / reset).</li>
 * </ul>
 * Non-transient errors (400, 401, 500, etc.) fail immediately without retrying.
 *
 * <h3>Security</h3>
 * {@code processId} is set as an HTTP header value. The domain model already rejects control
 * characters in {@link es.bluesolution.dlq_streaming.dlq_drain.domain.model.ProcessId}, but a
 * second sanitisation layer strips {@code \r} and {@code \n} characters here as defence-in-depth.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "dlq-drain.receiver", name = "type", havingValue = "dataprepper")
public class DataPrepperDeadLetterReceiver implements DeadLetterReceiver {

    private static final Set<Integer> RETRYABLE_STATUS_CODES = Set.of(429, 502, 503, 504);

    private final RestClient restClient;
    private final DlqDrainProperties properties;


    @Override
    public Result<ReceiveDeadLetterAck> receive(ReceiveDeadLetterCommand command) {
        var dp = properties.getReceiver().getDataPrepper();
        var maxAttempts = Math.max(1, dp.getMaxRetryAttempts());
        var delayMs = dp.getRetryInitialDelayMillis();
        var multiplier = dp.getRetryMultiplier();

        // Defence-in-depth: strip control characters from header value even though
        // ProcessId.create() already rejects them at the domain level.
        var safeProcessId = sanitiseHeaderValue(command.processId().value());

        Exception lastException = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                restClient.post()
                        .uri(dp.getUrl())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Process-Id", safeProcessId)
                        .header("Idempotency-Key", safeProcessId)
                        .body(command.payload().value())
                        .retrieve()
                        .toBodilessEntity();

                return ReceiveDeadLetterAck.create(command.processId(),
                        "dataprepper:" + command.processId().value());

            } catch (org.springframework.web.client.HttpStatusCodeException e) {
                int code = e.getStatusCode().value();
                if (isRetryableHttpStatus(code)) {
                    lastException = e;
                    log.warn("Data Prepper returned {} on attempt {}/{} for process-id '{}'; scheduling retry",
                            code, attempt, maxAttempts, safeProcessId);
                    backOff(attempt, delayMs, multiplier);
                } else {
                    log.error("Data Prepper returned non-retryable HTTP {} for process-id '{}'",
                            code, safeProcessId);
                    return Result.failure(EXTERNAL_SERVICE_ERROR,
                            "Data Prepper receiver failed with status " + code, e);
                }
            } catch (ResourceAccessException e) {
                // Transient I/O failure (connection refused, reset by peer, etc.)
                lastException = e;
                log.warn("Data Prepper I/O error on attempt {}/{} for process-id '{}': {}",
                        attempt, maxAttempts, safeProcessId, e.getMessage());
                backOff(attempt, delayMs, multiplier);
            } catch (Exception e) {
                log.error("Unexpected error calling Data Prepper for process-id '{}'", safeProcessId, e);
                return Result.failure(EXTERNAL_SERVICE_ERROR,
                        "Data Prepper receiver failed", e);
            }
        }

        return Result.failure(EXTERNAL_SERVICE_ERROR,
                "Data Prepper unavailable after " + maxAttempts + " attempt(s)", lastException);
    }

    private void backOff(int attempt, long initialDelayMs, double multiplier) {
        if (attempt >= properties.getReceiver().getDataPrepper().getMaxRetryAttempts()) {
            return; // last attempt — do not sleep before giving up
        }
        long delay = (long) (initialDelayMs * Math.pow(multiplier, (double) attempt - 1));
        try {
            Thread.sleep(delay);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isRetryableHttpStatus(int code) {
        return RETRYABLE_STATUS_CODES.contains(code);
    }

    /** Strip CR and LF to prevent HTTP header-injection attacks. */
    private static String sanitiseHeaderValue(String value) {
        return value.replace("\r", "").replace("\n", "");
    }
}
