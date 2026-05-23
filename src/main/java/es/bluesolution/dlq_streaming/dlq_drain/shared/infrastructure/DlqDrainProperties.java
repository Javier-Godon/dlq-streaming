package es.bluesolution.dlq_streaming.dlq_drain.shared.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dlq-drain")
public class DlqDrainProperties {
    private final Scheduler scheduler = new Scheduler();
    private final Receiver receiver = new Receiver();

    public Scheduler getScheduler() {
        return scheduler;
    }

    public Receiver getReceiver() {
        return receiver;
    }

    public static class Scheduler {
        private boolean enabled = false;
        private int batchSize = 100;
        private String workerId = "dlq-drain-worker";
        private long leaseSeconds = 120;
        private boolean releaseExpiredLeases = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public String getWorkerId() {
            return workerId;
        }

        public void setWorkerId(String workerId) {
            this.workerId = workerId;
        }

        public long getLeaseSeconds() {
            return leaseSeconds;
        }

        public void setLeaseSeconds(long leaseSeconds) {
            this.leaseSeconds = leaseSeconds;
        }

        public boolean isReleaseExpiredLeases() {
            return releaseExpiredLeases;
        }

        public void setReleaseExpiredLeases(boolean releaseExpiredLeases) {
            this.releaseExpiredLeases = releaseExpiredLeases;
        }
    }

    public static class Receiver {
        private String type = "in-memory";
        private final DataPrepper dataPrepper = new DataPrepper();

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public DataPrepper getDataPrepper() {
            return dataPrepper;
        }
    }

    public static class DataPrepper {
        private String url = "http://localhost:2021/log/ingest";

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }
    }
}

