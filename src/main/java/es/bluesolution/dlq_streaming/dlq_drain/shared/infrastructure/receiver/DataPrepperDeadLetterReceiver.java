package es.bluesolution.dlq_streaming.dlq_drain.shared.infrastructure.receiver;

import es.bluesolution.dlq_streaming.dlq_drain.domain.model.ReceiveDeadLetterAck;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.ReceiveDeadLetterCommand;
import es.bluesolution.dlq_streaming.dlq_drain.domain.repository.DeadLetterReceiver;
import es.bluesolution.dlq_streaming.dlq_drain.shared.infrastructure.DlqDrainProperties;
import es.bluesolution.dlq_streaming.functional_framework.Result;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import static es.bluesolution.dlq_streaming.functional_framework.FailureResultDescription.ErrorCode.EXTERNAL_SERVICE_ERROR;

@Component
@ConditionalOnProperty(prefix = "dlq-drain.receiver", name = "type", havingValue = "dataprepper")
public class DataPrepperDeadLetterReceiver implements DeadLetterReceiver {
    private final RestClient restClient;
    private final DlqDrainProperties properties;

    public DataPrepperDeadLetterReceiver(RestClient.Builder restClientBuilder, DlqDrainProperties properties) {
        this.restClient = restClientBuilder.build();
        this.properties = properties;
    }

    @Override
    public Result<ReceiveDeadLetterAck> receive(ReceiveDeadLetterCommand command) {
        try {
            restClient.post()
                    .uri(properties.getReceiver().getDataPrepper().getUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Process-Id", command.processId().value())
                    .header("Idempotency-Key", command.processId().value())
                    .body(command.payload().value())
                    .retrieve()
                    .toBodilessEntity();

            return ReceiveDeadLetterAck.create(command.processId(), "dataprepper:" + command.processId().value());
        } catch (Exception e) {
            return Result.failure(EXTERNAL_SERVICE_ERROR, "Data Prepper receiver failed", e);
        }
    }
}

