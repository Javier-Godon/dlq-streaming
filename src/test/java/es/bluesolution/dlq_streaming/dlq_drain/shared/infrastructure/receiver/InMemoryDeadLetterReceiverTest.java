package es.bluesolution.dlq_streaming.dlq_drain.shared.infrastructure.receiver;

import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DeadLetterPayload;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.ProcessId;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.ReceiveDeadLetterCommand;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link InMemoryDeadLetterReceiver}.
 *
 * <p>The in-memory receiver is the default when {@code dlq-drain.receiver.type=in-memory}
 * (the default). It stores without persistence and returns an ack with a predictable reference
 * format {@code "in-memory-receiver:<processId>"}. Used in development, smoke tests, and BDD.</p>
 */
class InMemoryDeadLetterReceiverTest {

    private final InMemoryDeadLetterReceiver receiver = new InMemoryDeadLetterReceiver();

    @Test
    void returnsAckWithInMemoryReceiverReference() {
        var command = ReceiveDeadLetterCommand.create(
                ProcessId.create("product-1_2026-05-23T10:15:30Z").value(),
                DeadLetterPayload.create("{\"message\":\"hello\"}").value()).value();

        var result = receiver.receive(command);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value().processId().value()).isEqualTo("product-1_2026-05-23T10:15:30Z");
        assertThat(result.value().receiverReference())
                .isEqualTo("in-memory-receiver:product-1_2026-05-23T10:15:30Z");
    }

    @Test
    void alwaysSucceedsRegardlessOfPayload() {
        var command = ReceiveDeadLetterCommand.create(
                ProcessId.create("product-99_2026-01-01T00:00:00Z").value(),
                DeadLetterPayload.create("{}").value()).value();

        var result = receiver.receive(command);

        assertThat(result.isSuccess()).isTrue();
    }
}

