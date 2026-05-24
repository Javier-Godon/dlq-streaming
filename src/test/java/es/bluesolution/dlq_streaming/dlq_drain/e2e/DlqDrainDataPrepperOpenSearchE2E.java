package es.bluesolution.dlq_streaming.dlq_drain.e2e;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import es.bluesolution.dlq_streaming.dlq_drain.shared.infrastructure.DlqDrainProperties;
import es.bluesolution.dlq_streaming.dlq_drain.shared.infrastructure.persistence.JdbcDeadLetterRepository;
import es.bluesolution.dlq_streaming.dlq_drain.shared.infrastructure.receiver.DataPrepperDeadLetterReceiver;
import es.bluesolution.dlq_streaming.dlq_drain.usecases.drain_dead_letters.application.DrainDeadLettersCommand;
import es.bluesolution.dlq_streaming.dlq_drain.usecases.drain_dead_letters.application.DrainDeadLettersHandler;
import es.bluesolution.dlq_streaming.functional_framework.execution.TransactionExecutionContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in E2E suite for the real topology:
 * PostgreSQL dead-letter table -> DLQ drain -> Data Prepper -> OpenSearch.
 *
 * This test is intentionally not part of the default unit/integration suite.
 * Run it with:
 * ./mvnw verify -Pe2e-tests \
 *   -DDLQ_E2E_POSTGRES_JDBC_URL=jdbc:postgresql://localhost:5432/dlq \
 *   -DDLQ_E2E_POSTGRES_USERNAME=postgres \
 *   -DDLQ_E2E_POSTGRES_PASSWORD=postgres \
 *   -DDLQ_E2E_DATAPREPPER_URL=http://localhost:2021/log/ingest \
 *   -DDLQ_E2E_OPENSEARCH_URL=http://localhost:9200 \
 *   -Ddlq.e2e.row-count=1000000
 */
class DlqDrainDataPrepperOpenSearchE2E {
    private static final int DEFAULT_ROW_COUNT = 1_000_000;
    private static final int DEFAULT_BATCH_SIZE = 500;
    private static final Duration OPENSEARCH_WAIT_TIMEOUT = Duration.ofMinutes(10);

    @Test
    void drainsPostgresRowsThroughDataPrepperIntoOpenSearch() {
        assumeE2eEnabledAndConfigured();

        var rowCount = Integer.getInteger("dlq.e2e.row-count", DEFAULT_ROW_COUNT);
        var batchSize = Integer.getInteger("dlq.e2e.batch-size", DEFAULT_BATCH_SIZE);
        var indexName = e2eProperty("DLQ_E2E_OPENSEARCH_INDEX", "dlq-drain-e2e");

        try (var dataSource = dataSource()) {
            migrate(dataSource);
            truncateDlq(dataSource);
            bulkInsertDlqRows(dataSource, rowCount);

            var handler = handler(dataSource, dataPrepperUrl());
            var drained = 0;
            while (drained < rowCount) {
                var result = handler.handle(new DrainDeadLettersCommand(batchSize, "e2e-worker", 300, true));
                assertThat(result.isSuccess()).as(result.isFailure() ? result.failure().message() : "drain result").isTrue();
                assertThat(result.value().stoppedBecauseReceiverFailed()).isFalse();

                if (result.value().claimedCount() == 0) {
                    break;
                }
                drained += result.value().deletedCount();
            }

            assertThat(countRemainingDlqRows(dataSource)).isZero();
            awaitOpenSearchCount(indexName, rowCount);
        }
    }

    @Test
    void stopsAndKeepsRowsWhenDataPrepperCommunicationFails() {
        assumeE2eEnabledAndConfigured();
        var brokenDataPrepperUrl = e2eProperty("DLQ_E2E_BROKEN_DATAPREPPER_URL", null);
        Assumptions.assumeTrue(brokenDataPrepperUrl != null && !brokenDataPrepperUrl.isBlank(),
                "Set DLQ_E2E_BROKEN_DATAPREPPER_URL to run the communication-failure E2E scenario");

        try (var dataSource = dataSource()) {
            migrate(dataSource);
            truncateDlq(dataSource);
            bulkInsertDlqRows(dataSource, 10);

            var handler = handler(dataSource, brokenDataPrepperUrl);
            var result = handler.handle(new DrainDeadLettersCommand(10, "e2e-worker", 60, true));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.value().stoppedBecauseReceiverFailed()).isTrue();
            assertThat(result.value().deletedCount()).isZero();
            assertThat(countRemainingDlqRows(dataSource)).isEqualTo(10);
        }
    }

    private static DrainDeadLettersHandler handler(HikariDataSource dataSource, String dataPrepperUrl) {
        var repository = new JdbcDeadLetterRepository(JdbcClient.create(dataSource));
        var properties = new DlqDrainProperties();
        properties.getReceiver().setType("dataprepper");
        properties.getReceiver().getDataPrepper().setUrl(dataPrepperUrl);
        var restClient = RestClient.builder().build();
        var receiver = new DataPrepperDeadLetterReceiver(restClient, properties);
        var txContext = TransactionExecutionContext.of(new DataSourceTransactionManager(dataSource));
        return new DrainDeadLettersHandler(repository, receiver, txContext, Clock.systemUTC());
    }

    private static HikariDataSource dataSource() {
        var config = new HikariConfig();
        config.setJdbcUrl(requiredE2eProperty("DLQ_E2E_POSTGRES_JDBC_URL"));
        config.setUsername(requiredE2eProperty("DLQ_E2E_POSTGRES_USERNAME"));
        config.setPassword(requiredE2eProperty("DLQ_E2E_POSTGRES_PASSWORD"));
        config.setMaximumPoolSize(4);
        return new HikariDataSource(config);
    }

    private static void migrate(HikariDataSource dataSource) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private static void truncateDlq(HikariDataSource dataSource) {
        JdbcClient.create(dataSource)
                .sql("TRUNCATE TABLE dlq.dead_letter_record RESTART IDENTITY")
                .update();
    }

    private static void bulkInsertDlqRows(HikariDataSource dataSource, int rowCount) {
        var jdbcTemplate = new JdbcTemplate(dataSource);
        var sql = """
                INSERT INTO dlq.dead_letter_record(process_id, occurred_at, payload, status)
                VALUES (?, ?, CAST(? AS jsonb), 'PENDING')
                """;
        var chunkSize = 5_000;
        var rows = new ArrayList<Object[]>(chunkSize);
        var runId = UUID.randomUUID();
        var occurredAt = Instant.now();

        for (var i = 1; i <= rowCount; i++) {
            rows.add(new Object[]{
                    "product-" + i + "_" + runId,
                    java.sql.Timestamp.from(occurredAt),
                    "{\"processId\":\"product-" + i + "_" + runId + "\",\"message\":\"hello\",\"sequence\":" + i + "}"
            });
            if (rows.size() == chunkSize) {
                jdbcTemplate.batchUpdate(sql, rows);
                rows.clear();
            }
        }
        if (!rows.isEmpty()) {
            jdbcTemplate.batchUpdate(sql, rows);
        }
    }

    private static int countRemainingDlqRows(HikariDataSource dataSource) {
        return JdbcClient.create(dataSource)
                .sql("SELECT COUNT(*) FROM dlq.dead_letter_record")
                .query(Integer.class)
                .single();
    }

    @SuppressWarnings("unchecked")
    private static void awaitOpenSearchCount(String indexName, int expectedCount) {
        var restClient = RestClient.create(requiredE2eProperty("DLQ_E2E_OPENSEARCH_URL"));
        var deadline = Instant.now().plus(OPENSEARCH_WAIT_TIMEOUT);
        AssertionError lastFailure = null;

        while (Instant.now().isBefore(deadline)) {
            try {
                var response = restClient.get()
                        .uri("/{index}/_count", indexName)
                        .retrieve()
                        .body(Map.class);
                var count = ((Number) response.get("count")).longValue();
                if (count >= expectedCount) {
                    return;
                }
                lastFailure = new AssertionError("OpenSearch count " + count + " is lower than expected " + expectedCount);
            } catch (RuntimeException e) {
                lastFailure = new AssertionError("OpenSearch count query failed", e);
            }

            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for OpenSearch", e);
            }
        }

        throw lastFailure == null
                ? new AssertionError("OpenSearch did not reach expected count " + expectedCount)
                : lastFailure;
    }

    private static String dataPrepperUrl() {
        return requiredE2eProperty("DLQ_E2E_DATAPREPPER_URL");
    }

    private static void assumeE2eEnabledAndConfigured() {
        Assumptions.assumeTrue(Boolean.getBoolean("dlq.e2e.enabled"), "E2E suite is opt-in via -Pe2e-tests");
        requiredE2eProperty("DLQ_E2E_POSTGRES_JDBC_URL");
        requiredE2eProperty("DLQ_E2E_POSTGRES_USERNAME");
        requiredE2eProperty("DLQ_E2E_POSTGRES_PASSWORD");
        requiredE2eProperty("DLQ_E2E_DATAPREPPER_URL");
        requiredE2eProperty("DLQ_E2E_OPENSEARCH_URL");
    }

    private static String requiredE2eProperty(String name) {
        var value = e2eProperty(name, null);
        Assumptions.assumeTrue(value != null && !value.isBlank(), "Missing required E2E property/env: " + name);
        return value;
    }

    private static String e2eProperty(String name, String defaultValue) {
        var systemValue = System.getProperty(name);
        if (systemValue != null && !systemValue.isBlank()) {
            return systemValue;
        }
        var envValue = System.getenv(name);
        if (envValue != null && !envValue.isBlank()) {
            return envValue;
        }
        return defaultValue;
    }
}

