package es.bluesolution.dlq_streaming.dlq_drain.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DeadLetterOccurredAtTest {
    @Test
    void createsValidTimestamp() {
        var occurredAt = Instant.parse("2026-05-23T10:15:30Z");

        var result = DeadLetterOccurredAt.create(occurredAt);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value().value()).isEqualTo(occurredAt);
    }

    @Test
    void rejectsNullTimestamp() {
        var result = DeadLetterOccurredAt.create(null);

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().message()).isEqualTo("DeadLetterOccurredAt is required");
    }
}

