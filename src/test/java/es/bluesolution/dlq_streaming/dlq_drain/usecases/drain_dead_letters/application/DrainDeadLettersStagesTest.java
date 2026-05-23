package es.bluesolution.dlq_streaming.dlq_drain.usecases.drain_dead_letters.application;

import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DeadLetterPayload;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DeadLetterOccurredAt;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DeadLetterRecord;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.ProcessId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DrainDeadLettersStagesTest {
    @Test
    void parsesValidCommand() {
        var data = DrainDeadLettersData.initialize(new DrainDeadLettersCommand(100, "worker-a", 120, true));

        var result = DrainDeadLettersStages.parseCommand(data);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value().batchSize().value()).isEqualTo(100);
        assertThat(result.value().workerId().value()).isEqualTo("worker-a");
        assertThat(result.value().leaseDuration().value().getSeconds()).isEqualTo(120);
    }

    @Test
    void rejectsInvalidBatchSize() {
        var data = DrainDeadLettersData.initialize(new DrainDeadLettersCommand(0, "worker-a", 120, true));

        var result = DrainDeadLettersStages.parseCommand(data);

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().message()).isEqualTo("DrainBatchSize must be greater than zero");
    }

    @Test
    void buildsResultFromStoppedData() {
        var record = record("product-1_2026-05-23T10:15:30Z");
        var data = DrainDeadLettersData.initialize(new DrainDeadLettersCommand(10, "worker-a", 60, false))
                .withClaimedRecords(List.of(record));

        var stopped = DrainDeadLettersStages.markReceiverFailure(data, record, "receiver unavailable");
        var result = DrainDeadLettersStages.buildResult(stopped);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value().stoppedBecauseReceiverFailed()).isTrue();
        assertThat(result.value().lastProcessedProcessId()).contains("product-1_2026-05-23T10:15:30Z");
        assertThat(result.value().stopReason()).contains("receiver unavailable");
    }

    private static DeadLetterRecord record(String processId) {
        return DeadLetterRecord.create(
                ProcessId.create(processId).value(),
                DeadLetterOccurredAt.create(Instant.parse("2026-05-23T10:00:00Z")).value(),
                DeadLetterPayload.create("{\"hello\":\"world\"}").value(),
                1).value();
    }
}

