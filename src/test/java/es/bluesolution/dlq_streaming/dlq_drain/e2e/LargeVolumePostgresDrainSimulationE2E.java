package es.bluesolution.dlq_streaming.dlq_drain.e2e;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.ReceiveDeadLetterAck;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.ReceiveDeadLetterCommand;
import es.bluesolution.dlq_streaming.dlq_drain.domain.repository.DeadLetterReceiver;
import es.bluesolution.dlq_streaming.dlq_drain.shared.infrastructure.persistence.JdbcDeadLetterRepository;
import es.bluesolution.dlq_streaming.dlq_drain.usecases.drain_dead_letters.application.DrainDeadLettersCommand;
import es.bluesolution.dlq_streaming.dlq_drain.usecases.drain_dead_letters.application.DrainDeadLettersHandler;
import es.bluesolution.dlq_streaming.functional_framework.Result;
import es.bluesolution.dlq_streaming.functional_framework.execution.TransactionExecutionContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class LargeVolumePostgresDrainSimulationE2E {
    private static final int DEFAULT_ROW_COUNT = 1_000_000;
    private static final int DEFAULT_BATCH_SIZE = 1_000;

    @Test
    void drainsLargePostgresBacklogWithRealClaimDeleteAndInMemoryReceiver() {
        Assumptions.assumeTrue(Boolean.getBoolean("dlq.e2e.large-volume.enabled"),
                "Large-volume simulation is opt-in with -Ddlq.e2e.large-volume.enabled=true");

        var rowCount = Integer.getInteger("dlq.e2e.large-volume.row-count", DEFAULT_ROW_COUNT);
        var batchSize = Integer.getInteger("dlq.e2e.large-volume.batch-size", DEFAULT_BATCH_SIZE);

        try (var postgresql = new PostgreSQLContainer<>("postgres:17-alpine")) {
            postgresql.start();

            try (var dataSource = dataSource(postgresql)) {
                migrate(dataSource);
                truncateDlq(dataSource);
                bulkInsertDlqRows(dataSource, rowCount);

                var receiver = new CountingReceiver();
                var handler = handler(dataSource, receiver);
                var drained = 0;
                var runs = 0;
                var startedAt = System.nanoTime();

                while (drained < rowCount) {
                    var result = handler.handle(new DrainDeadLettersCommand(batchSize, "large-volume-worker", 300, true));
                    assertThat(result.isSuccess()).as(result.isFailure() ? result.failure().message() : "drain result").isTrue();
                    assertThat(result.value().stoppedBecauseReceiverFailed()).isFalse();

                    if (result.value().claimedCount() == 0) {
                        break;
                    }

                    drained += result.value().deletedCount();
                    runs++;
                }

                var elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000.0;
                assertThat(drained).isEqualTo(rowCount);
                assertThat(receiver.received()).isEqualTo(rowCount);
                assertThat(countRemainingDlqRows(dataSource)).isZero();
                assertThat(runs).isGreaterThanOrEqualTo(1);

                System.out.printf("Large-volume DLQ drain simulation: rows=%d, batchSize=%d, runs=%d, seconds=%.3f, rowsPerSecond=%.2f%n",
                        rowCount, batchSize, runs, elapsedSeconds, rowCount / Math.max(elapsedSeconds, 0.001));
            }
        }
    }

    private static DrainDeadLettersHandler handler(HikariDataSource dataSource, DeadLetterReceiver receiver) {
        var repository = new JdbcDeadLetterRepository(JdbcClient.create(dataSource));
        var txContext = TransactionExecutionContext.of(new DataSourceTransactionManager(dataSource));
        return new DrainDeadLettersHandler(repository, receiver, txContext, Clock.systemUTC());
    }

    private static HikariDataSource dataSource(PostgreSQLContainer<?> postgresql) {
        var config = new HikariConfig();
        config.setJdbcUrl(postgresql.getJdbcUrl());
        config.setUsername(postgresql.getUsername());
        config.setPassword(postgresql.getPassword());
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
        var chunkSize = 10_000;
        var rows = new ArrayList<Object[]>(chunkSize);
        var runId = UUID.randomUUID();
        var occurredAt = Timestamp.from(Instant.now());

        for (var i = 1; i <= rowCount; i++) {
            var processId = "product-" + i + "_" + runId;
            rows.add(new Object[]{
                    processId,
                    occurredAt,
                    "{\"processId\":\"" + processId + "\",\"sequence\":" + i + "}"
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

    private static final class CountingReceiver implements DeadLetterReceiver {
        private final AtomicInteger received = new AtomicInteger();

        @Override
        public Result<ReceiveDeadLetterAck> receive(ReceiveDeadLetterCommand command) {
            received.incrementAndGet();
            return ReceiveDeadLetterAck.create(command.processId(), "large-volume-simulation:" + command.processId().value());
        }

        int received() {
            return received.get();
        }
    }
}



