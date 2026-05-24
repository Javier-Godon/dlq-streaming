package es.bluesolution.dlq_streaming.dlq_drain.shared.infrastructure;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@ConfigurationProperties(prefix = "dlq-drain")
public class DlqDrainProperties {
    private final Scheduler scheduler = new Scheduler();
    private final Receiver receiver = new Receiver();
    @Setter
    private String apiKey;

    @Setter
    @Getter
    public static class Scheduler {
        private boolean enabled = false;
        private int batchSize = 100;
        private String workerId = "dlq-drain-worker";
        private long leaseSeconds = 120;
        private boolean releaseExpiredLeases = true;
        private long fixedDelayMillis = 30000;
        private long initialDelayMillis = 5000;

    }

    @Getter
    public static class Receiver {
        @Setter
        private String type = "in-memory";
        private final DataPrepper dataPrepper = new DataPrepper();

    }

    @Setter
    @Getter
    public static class DataPrepper {
        private String url = "http://localhost:2021/log/ingest";

        /** TCP connect timeout in milliseconds. */
        private int connectTimeoutMillis = 5000;

        /** HTTP read / response timeout in milliseconds. */
        private int readTimeoutMillis = 30000;

        /**
         * Maximum number of delivery attempts per record.
         * 1 = no retry.  Retry is applied only for transient HTTP status codes
         * (503, 502, 429, 504) and connection-reset / refused exceptions.
         */
        private int maxRetryAttempts = 3;

        /** Initial back-off delay in milliseconds before the first retry. */
        private long retryInitialDelayMillis = 500;

        /** Multiplier applied to the delay on each retry (exponential back-off). */
        private double retryMultiplier = 2.0;

    }
}
