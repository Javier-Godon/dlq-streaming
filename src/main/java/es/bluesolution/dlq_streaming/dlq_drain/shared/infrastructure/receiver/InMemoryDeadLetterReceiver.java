package es.bluesolution.dlq_streaming.dlq_drain.shared.infrastructure.receiver;

import es.bluesolution.dlq_streaming.dlq_drain.domain.model.ReceiveDeadLetterAck;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.ReceiveDeadLetterCommand;
import es.bluesolution.dlq_streaming.dlq_drain.domain.repository.DeadLetterReceiver;
import es.bluesolution.dlq_streaming.functional_framework.Result;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnMissingBean(DeadLetterReceiver.class)
public class InMemoryDeadLetterReceiver implements DeadLetterReceiver {
    @Override
    public Result<ReceiveDeadLetterAck> receive(ReceiveDeadLetterCommand command) {
        return ReceiveDeadLetterAck.create(command.processId(), "in-memory-receiver:" + command.processId().value());
    }
}

