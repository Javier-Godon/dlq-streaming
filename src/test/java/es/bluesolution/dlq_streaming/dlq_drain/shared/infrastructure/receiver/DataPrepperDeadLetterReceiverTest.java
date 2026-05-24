package es.bluesolution.dlq_streaming.dlq_drain.shared.infrastructure.receiver;

import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DeadLetterPayload;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.ProcessId;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.ReceiveDeadLetterCommand;
import es.bluesolution.dlq_streaming.dlq_drain.shared.infrastructure.DlqDrainProperties;
import es.bluesolution.dlq_streaming.functional_framework.FailureResultDescription;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DataPrepperDeadLetterReceiverTest {

    @Test
    void postsJsonPayloadToDataPrepperWithIdempotencyHeaders() {
        var restClientBuilder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(restClientBuilder).build();
        var properties = properties("http://dataprepper:2021/log/ingest");
        // Build RestClient after binding MockRestServiceServer
        var receiver = new DataPrepperDeadLetterReceiver(restClientBuilder.build(), properties);
        var command = command("product-1_2026-05-23T10:15:30Z");

        server.expect(once(), requestTo("http://dataprepper:2021/log/ingest"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Process-Id", "product-1_2026-05-23T10:15:30Z"))
                .andExpect(header("Idempotency-Key", "product-1_2026-05-23T10:15:30Z"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"message\":\"hello\"}"))
                .andRespond(withSuccess());

        var result = receiver.receive(command);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value().receiverReference()).isEqualTo("dataprepper:product-1_2026-05-23T10:15:30Z");
        server.verify();
    }

    @Test
    void returnsFailureImmediatelyForNonRetryableServerError() {
        var restClientBuilder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(restClientBuilder).build();
        // maxRetryAttempts=1 with 1 attempt, so no retry
        var properties = properties("http://dataprepper:2021/log/ingest");
        properties.getReceiver().getDataPrepper().setMaxRetryAttempts(1);
        var receiver = new DataPrepperDeadLetterReceiver(restClientBuilder.build(), properties);

        // 500 is NOT a retryable status — receiver fails immediately (no retry)
        server.expect(once(), requestTo("http://dataprepper:2021/log/ingest"))
                .andRespond(withServerError());

        var result = receiver.receive(command("product-1_2026-05-23T10:15:30Z"));

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().code()).isEqualTo(FailureResultDescription.ErrorCode.EXTERNAL_SERVICE_ERROR);
        server.verify();
    }

    private static DlqDrainProperties properties(String dataPrepperUrl) {
        var properties = new DlqDrainProperties();
        properties.getReceiver().setType("dataprepper");
        properties.getReceiver().getDataPrepper().setUrl(dataPrepperUrl);
        // Short retry delay so tests don't sleep
        properties.getReceiver().getDataPrepper().setRetryInitialDelayMillis(0);
        return properties;
    }

    private static ReceiveDeadLetterCommand command(String processId) {
        return ReceiveDeadLetterCommand.create(
                ProcessId.create(processId).value(),
                DeadLetterPayload.create("{\"message\":\"hello\"}").value()).value();
    }
}
