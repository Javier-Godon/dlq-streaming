package es.bluesolution.dlq_streaming.dlq_drain.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReceiveDeadLetterAckTest {

    private static final ProcessId VALID_PROCESS_ID = ProcessId.create("product-1_2026-05-23T10:15:30Z").value();

    @Test
    void createsValidAck() {
        var result = ReceiveDeadLetterAck.create(VALID_PROCESS_ID, "dataprepper:product-1_2026-05-23T10:15:30Z");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value().processId()).isEqualTo(VALID_PROCESS_ID);
        assertThat(result.value().receiverReference()).isEqualTo("dataprepper:product-1_2026-05-23T10:15:30Z");
    }

    @Test
    void trimsWhitespaceFromReceiverReference() {
        var result = ReceiveDeadLetterAck.create(VALID_PROCESS_ID, "  dataprepper:ref  ");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value().receiverReference()).isEqualTo("dataprepper:ref");
    }

    @Test
    void rejectsNullProcessId() {
        var result = ReceiveDeadLetterAck.create(null, "dataprepper:ref");

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().message()).isEqualTo("ProcessId is required");
    }

    @Test
    void rejectsNullReceiverReference() {
        var result = ReceiveDeadLetterAck.create(VALID_PROCESS_ID, null);

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().message()).isEqualTo("ReceiverReference is required");
    }

    @Test
    void rejectsBlankReceiverReference() {
        var result = ReceiveDeadLetterAck.create(VALID_PROCESS_ID, "   ");

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().message()).isEqualTo("ReceiverReference is required");
    }
}

