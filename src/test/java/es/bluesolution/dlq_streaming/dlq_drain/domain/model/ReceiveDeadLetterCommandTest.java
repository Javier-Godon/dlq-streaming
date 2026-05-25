package es.bluesolution.dlq_streaming.dlq_drain.domain.model;

import org.junit.jupiter.api.Test;


import static org.assertj.core.api.Assertions.assertThat;

class ReceiveDeadLetterCommandTest {

    private static final ProcessId VALID_PROCESS_ID = ProcessId.create("product-1_2026-05-23T10:15:30Z").value();
    private static final DeadLetterPayload VALID_PAYLOAD = DeadLetterPayload.create("{\"event\":\"data\"}").value();

    @Test
    void createsValidCommand() {
        var result = ReceiveDeadLetterCommand.create(VALID_PROCESS_ID, VALID_PAYLOAD);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value().processId()).isEqualTo(VALID_PROCESS_ID);
        assertThat(result.value().payload()).isEqualTo(VALID_PAYLOAD);
    }

    @Test
    void rejectsNullProcessId() {
        var result = ReceiveDeadLetterCommand.create(null, VALID_PAYLOAD);

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().message()).isEqualTo("ProcessId is required");
    }

    @Test
    void rejectsNullPayload() {
        var result = ReceiveDeadLetterCommand.create(VALID_PROCESS_ID, null);

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().message()).isEqualTo("DeadLetterPayload is required");
    }
}

