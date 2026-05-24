package es.bluesolution.dlq_streaming.dlq_drain.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeadLetterPayloadTest {

    @Test
    void createsValidPayload() {
        var result = DeadLetterPayload.create("{\"message\":\"hello\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value().value()).isEqualTo("{\"message\":\"hello\"}");
    }

    @Test
    void rejectsNullPayload() {
        var result = DeadLetterPayload.create(null);

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().message()).isEqualTo("DeadLetterPayload is required");
    }

    @Test
    void rejectsBlankPayload() {
        var result = DeadLetterPayload.create("   ");

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().message()).isEqualTo("DeadLetterPayload is required");
    }
}

