package es.bluesolution.dlq_streaming.dlq_drain.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DeadLetterRecordTest {

    private static final ProcessId VALID_PROCESS_ID = ProcessId.create("product-1_2026-05-23T10:15:30Z").value();
    private static final DeadLetterOccurredAt VALID_OCCURRED_AT = DeadLetterOccurredAt.create(Instant.parse("2026-05-23T10:15:30Z")).value();
    private static final DeadLetterPayload VALID_PAYLOAD = DeadLetterPayload.create("{\"message\":\"hello\"}").value();

    @Test
    void createsValidRecord() {
        var result = DeadLetterRecord.create(VALID_PROCESS_ID, VALID_OCCURRED_AT, VALID_PAYLOAD, 1);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value().processId()).isEqualTo(VALID_PROCESS_ID);
        assertThat(result.value().occurredAt()).isEqualTo(VALID_OCCURRED_AT);
        assertThat(result.value().payload()).isEqualTo(VALID_PAYLOAD);
        assertThat(result.value().attemptCount()).isEqualTo(1);
    }

    @Test
    void acceptsZeroAttemptCount() {
        var result = DeadLetterRecord.create(VALID_PROCESS_ID, VALID_OCCURRED_AT, VALID_PAYLOAD, 0);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value().attemptCount()).isZero();
    }

    @Test
    void rejectsNullProcessId() {
        var result = DeadLetterRecord.create(null, VALID_OCCURRED_AT, VALID_PAYLOAD, 0);

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().message()).isEqualTo("ProcessId is required");
    }

    @Test
    void rejectsNullOccurredAt() {
        var result = DeadLetterRecord.create(VALID_PROCESS_ID, null, VALID_PAYLOAD, 0);

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().message()).isEqualTo("DeadLetterOccurredAt is required");
    }

    @Test
    void rejectsNullPayload() {
        var result = DeadLetterRecord.create(VALID_PROCESS_ID, VALID_OCCURRED_AT, null, 0);

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().message()).isEqualTo("DeadLetterPayload is required");
    }

    @Test
    void rejectsNegativeAttemptCount() {
        var result = DeadLetterRecord.create(VALID_PROCESS_ID, VALID_OCCURRED_AT, VALID_PAYLOAD, -1);

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().message()).isEqualTo("AttemptCount must not be negative");
    }
}

