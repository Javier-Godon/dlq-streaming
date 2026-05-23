package es.bluesolution.dlq_streaming.dlq_drain.integration;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DrainBatchSize;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DrainLeaseDuration;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DrainWorkerId;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.ProcessId;
import es.bluesolution.dlq_streaming.dlq_drain.shared.infrastructure.persistence.JdbcDeadLetterRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class DeadLetterRepositoryIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>("postgres:17-alpine");

    static HikariDataSource dataSource;
    static JdbcClient jdbcClient;
    static JdbcDeadLetterRepository repository;

    @BeforeAll
    static void startDatabase() {
        var config = new HikariConfig();
        config.setJdbcUrl(POSTGRESQL.getJdbcUrl());
        config.setUsername(POSTGRESQL.getUsername());
        config.setPassword(POSTGRESQL.getPassword());
        dataSource = new HikariDataSource(config);

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        jdbcClient = JdbcClient.create(dataSource);
        repository = new JdbcDeadLetterRepository(jdbcClient);
    }

    @AfterAll
    static void closeDatabase() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @BeforeEach
    void cleanTable() {
        jdbcClient.sql("TRUNCATE TABLE dlq.dead_letter_record RESTART IDENTITY").update();
    }

    @Test
    void claimsOnlyTheConfiguredLimit() {
        insertPending("product-1_2026-05-23T10:15:30Z");
        insertPending("product-2_2026-05-23T10:16:30Z");
        insertPending("product-3_2026-05-23T10:17:30Z");

        var result = repository.claimNextBatch(
                DrainBatchSize.create(2).value(),
                DrainWorkerId.create("worker-a").value(),
                DrainLeaseDuration.create(Duration.ofMinutes(2)).value(),
                Instant.parse("2026-05-23T10:20:00Z"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value()).hasSize(2);
        assertThat(result.value()).extracting(record -> record.processId().value())
                .containsExactly("product-1_2026-05-23T10:15:30Z", "product-2_2026-05-23T10:16:30Z");
        assertThat(countByStatus("PROCESSING")).isEqualTo(2);
        assertThat(countByStatus("PENDING")).isEqualTo(1);
    }

    @Test
    void differentWorkersDoNotClaimTheSameRows() {
        insertPending("product-1_2026-05-23T10:15:30Z");
        insertPending("product-2_2026-05-23T10:16:30Z");
        insertPending("product-3_2026-05-23T10:17:30Z");
        insertPending("product-4_2026-05-23T10:18:30Z");

        var firstClaim = repository.claimNextBatch(
                DrainBatchSize.create(2).value(),
                DrainWorkerId.create("worker-a").value(),
                DrainLeaseDuration.create(Duration.ofMinutes(2)).value(),
                Instant.parse("2026-05-23T10:20:00Z"));
        var secondClaim = repository.claimNextBatch(
                DrainBatchSize.create(2).value(),
                DrainWorkerId.create("worker-b").value(),
                DrainLeaseDuration.create(Duration.ofMinutes(2)).value(),
                Instant.parse("2026-05-23T10:20:01Z"));

        assertThat(firstClaim.isSuccess()).isTrue();
        assertThat(secondClaim.isSuccess()).isTrue();
        var firstIds = firstClaim.value().stream().map(record -> record.processId().value()).toList();
        var secondIds = secondClaim.value().stream().map(record -> record.processId().value()).toList();
        assertThat(new HashSet<>(firstIds)).doesNotContainAnyElementsOf(secondIds);
        assertThat(firstIds).containsExactly("product-1_2026-05-23T10:15:30Z", "product-2_2026-05-23T10:16:30Z");
        assertThat(secondIds).containsExactly("product-3_2026-05-23T10:17:30Z", "product-4_2026-05-23T10:18:30Z");
    }

    @Test
    void expiredLeasesAreReleasedAndReclaimable() {
        insertProcessing("product-1_2026-05-23T10:15:30Z", "old-worker", Instant.parse("2026-05-23T10:00:00Z"));

        var released = repository.releaseExpiredLeases(Instant.parse("2026-05-23T10:20:00Z"));
        var claimed = repository.claimNextBatch(
                DrainBatchSize.create(1).value(),
                DrainWorkerId.create("worker-a").value(),
                DrainLeaseDuration.create(Duration.ofMinutes(2)).value(),
                Instant.parse("2026-05-23T10:20:01Z"));

        assertThat(released.isSuccess()).isTrue();
        assertThat(released.value()).isEqualTo(1);
        assertThat(claimed.isSuccess()).isTrue();
        assertThat(claimed.value()).hasSize(1);
        assertThat(claimed.value().getFirst().processId().value()).isEqualTo("product-1_2026-05-23T10:15:30Z");
    }

    @Test
    void deleteRemovesOnlyTheClaimedWorkerRow() {
        insertPending("product-1_2026-05-23T10:15:30Z");
        var worker = DrainWorkerId.create("worker-a").value();
        repository.claimNextBatch(
                DrainBatchSize.create(1).value(),
                worker,
                DrainLeaseDuration.create(Duration.ofMinutes(2)).value(),
                Instant.parse("2026-05-23T10:20:00Z"));

        var deleted = repository.deleteClaimed(ProcessId.create("product-1_2026-05-23T10:15:30Z").value(), worker);

        assertThat(deleted.isSuccess()).isTrue();
        assertThat(countAll()).isZero();
    }

    @Test
    void deleteFailsWhenTheWorkerDoesNotOwnTheClaim() {
        insertProcessing("product-1_2026-05-23T10:15:30Z", "worker-a", Instant.parse("2026-05-23T10:30:00Z"));

        var deleted = repository.deleteClaimed(
                ProcessId.create("product-1_2026-05-23T10:15:30Z").value(),
                DrainWorkerId.create("worker-b").value());

        assertThat(deleted.isFailure()).isTrue();
        assertThat(countAll()).isEqualTo(1);
    }

    private void insertPending(String processId) {
        jdbcClient.sql("""
                INSERT INTO dlq.dead_letter_record(process_id, payload, status)
                VALUES (:processId, CAST(:payload AS jsonb), 'PENDING')
                """)
                .param("processId", processId)
                .param("payload", "{\"message\":\"hello\"}")
                .update();
    }

    private void insertProcessing(String processId, String workerId, Instant leaseUntil) {
        jdbcClient.sql("""
                INSERT INTO dlq.dead_letter_record(process_id, payload, status, claimed_by, lease_until)
                VALUES (:processId, CAST(:payload AS jsonb), 'PROCESSING', :workerId, :leaseUntil)
                """)
                .param("processId", processId)
                .param("payload", "{\"message\":\"hello\"}")
                .param("workerId", workerId)
                .param("leaseUntil", Timestamp.from(leaseUntil))
                .update();
    }

    private int countByStatus(String status) {
        return jdbcClient.sql("SELECT COUNT(*) FROM dlq.dead_letter_record WHERE status = :status")
                .param("status", status)
                .query(Integer.class)
                .single();
    }

    private int countAll() {
        return jdbcClient.sql("SELECT COUNT(*) FROM dlq.dead_letter_record")
                .query(Integer.class)
                .single();
    }
}


