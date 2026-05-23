package es.bluesolution.dlq_streaming.dlq_drain.shared.infrastructure.persistence;

import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DeadLetterPayload;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DeadLetterOccurredAt;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DeadLetterRecord;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DrainBatchSize;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DrainLeaseDuration;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DrainWorkerId;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.ProcessId;
import es.bluesolution.dlq_streaming.dlq_drain.domain.repository.DeadLetterRepository;
import es.bluesolution.dlq_streaming.functional_framework.Result;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static es.bluesolution.dlq_streaming.functional_framework.FailureResultDescription.ErrorCode.BUSINESS_RULE_ERROR;
import static es.bluesolution.dlq_streaming.functional_framework.FailureResultDescription.ErrorCode.DATABASE_ERROR;

@Repository
@ConditionalOnBean(JdbcClient.class)
public class JdbcDeadLetterRepository implements DeadLetterRepository {
    private static final String CLAIM_NEXT_BATCH_SQL = """
            WITH next_rows AS (
                SELECT dlq_id
                FROM dlq.dead_letter_record
                WHERE status = 'PENDING'
                   OR (status = 'PROCESSING' AND lease_until < :claimedAt)
                ORDER BY dlq_id
                LIMIT :batchSize
                FOR UPDATE SKIP LOCKED
            )
            UPDATE dlq.dead_letter_record d
            SET status = 'PROCESSING',
                claimed_by = :workerId,
                lease_until = :leaseUntil,
                attempt_count = d.attempt_count + 1,
                updated_at = :claimedAt
            FROM next_rows n
            WHERE d.dlq_id = n.dlq_id
            RETURNING d.process_id, d.occurred_at, d.payload::text AS payload, d.attempt_count
            """;

    private static final String DELETE_CLAIMED_SQL = """
            DELETE FROM dlq.dead_letter_record
            WHERE process_id = :processId
              AND status = 'PROCESSING'
              AND claimed_by = :workerId
            """;

    private static final String RELEASE_EXPIRED_LEASES_SQL = """
            UPDATE dlq.dead_letter_record
            SET status = 'PENDING',
                claimed_by = NULL,
                lease_until = NULL,
                updated_at = :now
            WHERE status = 'PROCESSING'
              AND lease_until < :now
            """;

    private final JdbcClient jdbcClient;

    public JdbcDeadLetterRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Result<List<DeadLetterRecord>> claimNextBatch(
            DrainBatchSize batchSize,
            DrainWorkerId workerId,
            DrainLeaseDuration leaseDuration,
            Instant claimedAt) {
        try {
            var leaseUntil = claimedAt.plus(leaseDuration.value());
            var records = jdbcClient.sql(CLAIM_NEXT_BATCH_SQL)
                    .param("batchSize", batchSize.value())
                    .param("workerId", workerId.value())
                    .param("claimedAt", Timestamp.from(claimedAt))
                    .param("leaseUntil", Timestamp.from(leaseUntil))
                    .query(deadLetterRecordRowMapper())
                    .list();
            return Result.success(records);
        } catch (Exception e) {
            return Result.failure(DATABASE_ERROR, "Failed to claim dead-letter records", e);
        }
    }

    @Override
    public Result<ProcessId> deleteClaimed(ProcessId processId, DrainWorkerId workerId) {
        try {
            var deletedRows = jdbcClient.sql(DELETE_CLAIMED_SQL)
                    .param("processId", processId.value())
                    .param("workerId", workerId.value())
                    .update();

            if (deletedRows != 1) {
                return Result.failure(BUSINESS_RULE_ERROR, "Claimed dead-letter record was not deleted", null);
            }

            return Result.success(processId);
        } catch (Exception e) {
            return Result.failure(DATABASE_ERROR, "Failed to delete claimed dead-letter record", e);
        }
    }

    @Override
    public Result<Integer> releaseExpiredLeases(Instant now) {
        try {
            var releasedRows = jdbcClient.sql(RELEASE_EXPIRED_LEASES_SQL)
                    .param("now", Timestamp.from(now))
                    .update();
            return Result.success(releasedRows);
        } catch (Exception e) {
            return Result.failure(DATABASE_ERROR, "Failed to release expired dead-letter leases", e);
        }
    }

    private RowMapper<DeadLetterRecord> deadLetterRecordRowMapper() {
        return this::mapDeadLetterRecord;
    }

    private DeadLetterRecord mapDeadLetterRecord(ResultSet rs, int rowNum) throws SQLException {
        var processId = ProcessId.create(rs.getString("process_id"));
        if (processId.isFailure()) {
            throw new SQLException(processId.failure().message());
        }

        var payload = DeadLetterPayload.create(rs.getString("payload"));
        if (payload.isFailure()) {
            throw new SQLException(payload.failure().message());
        }

        var occurredAt = DeadLetterOccurredAt.create(rs.getTimestamp("occurred_at").toInstant());
        if (occurredAt.isFailure()) {
            throw new SQLException(occurredAt.failure().message());
        }

        var record = DeadLetterRecord.create(processId.value(), occurredAt.value(), payload.value(), rs.getInt("attempt_count"));
        if (record.isFailure()) {
            throw new SQLException(record.failure().message());
        }

        return record.value();
    }
}


