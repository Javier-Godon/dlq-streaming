package es.bluesolution.dlq_streaming.dlq_drain.shared.infrastructure.receiver;

import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DeadLetterPayload;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.ProcessId;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.ReceiveDeadLetterCommand;
import es.bluesolution.dlq_streaming.dlq_drain.shared.infrastructure.DlqDrainProperties;
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
        var receiver = new DataPrepperDeadLetterReceiver(restClientBuilder, properties);
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
    void returnsFailureWhenDataPrepperFails() {
        var restClientBuilder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(restClientBuilder).build();
        var receiver = new DataPrepperDeadLetterReceiver(restClientBuilder, properties("http://dataprepper:2021/log/ingest"));

        server.expect(once(), requestTo("http://dataprepper:2021/log/ingest"))
                .andRespond(withServerError());

        var result = receiver.receive(command("product-1_2026-05-23T10:15:30Z"));

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().message()).isEqualTo("Data Prepper receiver failed");
        server.verify();
    }

    private static DlqDrainProperties properties(String dataPrepperUrl) {
        var properties = new DlqDrainProperties();
        properties.getReceiver().setType("dataprepper");
        properties.getReceiver().getDataPrepper().setUrl(dataPrepperUrl);
        return properties;
    }

    private static ReceiveDeadLetterCommand command(String processId) {
        return ReceiveDeadLetterCommand.create(
                ProcessId.create(processId).value(),
                DeadLetterPayload.create("{\"message\":\"hello\"}").value()).value();
    }
}

