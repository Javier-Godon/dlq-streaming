package es.bluesolution.dlq_streaming.dlq_drain.domain.repository;

import es.bluesolution.dlq_streaming.dlq_drain.domain.model.ReceiveDeadLetterAck;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.ReceiveDeadLetterCommand;
import es.bluesolution.dlq_streaming.functional_framework.Result;

public interface DeadLetterReceiver {
    Result<ReceiveDeadLetterAck> receive(ReceiveDeadLetterCommand command);
}

