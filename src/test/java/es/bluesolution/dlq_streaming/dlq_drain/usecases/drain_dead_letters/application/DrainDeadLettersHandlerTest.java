package es.bluesolution.dlq_streaming.dlq_drain.usecases.drain_dead_letters.application;

import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DeadLetterPayload;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DeadLetterOccurredAt;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DeadLetterRecord;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DrainBatchSize;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DrainLeaseDuration;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DrainWorkerId;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.ProcessId;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.ReceiveDeadLetterAck;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.ReceiveDeadLetterCommand;
import es.bluesolution.dlq_streaming.dlq_drain.domain.repository.DeadLetterRepository;
import es.bluesolution.dlq_streaming.dlq_drain.domain.repository.DeadLetterReceiver;
import es.bluesolution.dlq_streaming.functional_framework.Result;
import es.bluesolution.dlq_streaming.functional_framework.execution.NoOpExecutionContext;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static es.bluesolution.dlq_streaming.functional_framework.FailureResultDescription.ErrorCode.DATABASE_ERROR;
import static es.bluesolution.dlq_streaming.functional_framework.FailureResultDescription.ErrorCode.EXTERNAL_SERVICE_ERROR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class DrainDeadLettersHandlerTest {
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-05-23T10:15:30Z"), ZoneOffset.UTC);

    @Test
    void rejectsNullCommand() {
        var handler = handler(new FakeDeadLetterRepository(List.of()), new FakeDeadLetterReceiver(-1));

        var result = handler.handle(null);

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().message()).isEqualTo("Command is mandatory");
    }

    @Test
    void returnsEmptyResultWhenThereIsNoWork() {
        var repository = new FakeDeadLetterRepository(List.of());
        var writer = new FakeDeadLetterReceiver(-1);
        var handler = handler(repository, writer);

        var result = handler.handle(new DrainDeadLettersCommand(100, "worker-a", 60, true));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value().releasedExpiredLeases()).isEqualTo(2);
        assertThat(result.value().claimedCount()).isZero();
        assertThat(result.value().storedCount()).isZero();
        assertThat(result.value().deletedCount()).isZero();
        assertThat(result.value().stoppedBecauseReceiverFailed()).isFalse();
        assertThat(writer.writtenProcessIds).isEmpty();
        assertThat(repository.deletedProcessIds).isEmpty();
    }

    @Test
    void writesThenDeletesEveryClaimedRecord() {
        var records = List.of(
                buildRecord("product-1_2026-05-23T10:15:30Z"),
                buildRecord("product-2_2026-05-23T10:16:30Z"));
        var repository = new FakeDeadLetterRepository(records);
        var writer = new FakeDeadLetterReceiver(-1);
        var handler = handler(repository, writer);

        var result = handler.handle(new DrainDeadLettersCommand(100, "worker-a", 60, false));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value().claimedCount()).isEqualTo(2);
        assertThat(result.value().storedCount()).isEqualTo(2);
        assertThat(result.value().deletedCount()).isEqualTo(2);
        assertThat(result.value().stoppedBecauseReceiverFailed()).isFalse();
        assertThat(writer.writtenProcessIds).containsExactly(
                "product-1_2026-05-23T10:15:30Z",
                "product-2_2026-05-23T10:16:30Z");
        assertThat(repository.deletedProcessIds).containsExactly(
                "product-1_2026-05-23T10:15:30Z",
                "product-2_2026-05-23T10:16:30Z");
    }

    @Test
    void propagatesDbFailureFromClaimBatch() {
        var repository = org.mockito.Mockito.mock(DeadLetterRepository.class);
        when(repository.releaseExpiredLeases(any())).thenReturn(Result.success(0));
        when(repository.claimNextBatch(any(), any(), any(), any()))
                .thenReturn(Result.failure(DATABASE_ERROR, "Connection pool exhausted", null));
        var handler = handler(repository, new FakeDeadLetterReceiver(-1));

        var result = handler.handle(new DrainDeadLettersCommand(100, "worker-a", 60, true));

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().message()).isEqualTo("Connection pool exhausted");
    }

    @Test
    void propagatesDbFailureFromDeleteClaimed() {
        // Receiver accepts the record, but the DB delete fails.
        // The record remains in PROCESSING state and will be retried after lease expiry.
        var records = List.of(buildRecord("product-1_2026-05-23T10:15:30Z"));
        var repository = org.mockito.Mockito.mock(DeadLetterRepository.class);
        when(repository.releaseExpiredLeases(any())).thenReturn(Result.success(0));
        when(repository.claimNextBatch(any(), any(), any(), any())).thenReturn(Result.success(records));
        when(repository.deleteClaimed(any(), any()))
                .thenReturn(Result.failure(DATABASE_ERROR, "Row lock contention", null));
        var writer = new FakeDeadLetterReceiver(-1);
        var handler = handler(repository, writer);

        var result = handler.handle(new DrainDeadLettersCommand(100, "worker-a", 60, false));

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().message()).isEqualTo("Row lock contention");
        // Although the delete failed, the receiver still received the record.
        // The record will remain PROCESSING until lease expires and be retried.
        assertThat(writer.writtenProcessIds).containsExactly("product-1_2026-05-23T10:15:30Z");
    }

    @Test
    void stopsImmediatelyAndSkipsDeleteWhenPrimaryWriteFails() {
        var records = List.of(
                buildRecord("product-1_2026-05-23T10:15:30Z"),
                buildRecord("product-2_2026-05-23T10:16:30Z"),
                buildRecord("product-3_2026-05-23T10:17:30Z"));
        var repository = new FakeDeadLetterRepository(records);
        var writer = new FakeDeadLetterReceiver(2);
        var handler = handler(repository, writer);

        var result = handler.handle(new DrainDeadLettersCommand(100, "worker-a", 60, false));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value().claimedCount()).isEqualTo(3);
        assertThat(result.value().storedCount()).isEqualTo(1);
        assertThat(result.value().deletedCount()).isEqualTo(1);
        assertThat(result.value().stoppedBecauseReceiverFailed()).isTrue();
        assertThat(result.value().lastProcessedProcessId()).contains("product-2_2026-05-23T10:16:30Z");
        assertThat(result.value().stopReason()).contains("receiver unavailable");
        assertThat(writer.writtenProcessIds).containsExactly(
                "product-1_2026-05-23T10:15:30Z",
                "product-2_2026-05-23T10:16:30Z");
        assertThat(repository.deletedProcessIds).containsExactly("product-1_2026-05-23T10:15:30Z");
    }

    private static DrainDeadLettersHandler handler(DeadLetterRepository repository, DeadLetterReceiver writer) {
        return new DrainDeadLettersHandler(repository, writer, new NoOpExecutionContext(), FIXED_CLOCK);
    }

    private static DeadLetterRecord buildRecord(String processId) {
        return DeadLetterRecord.create(
                ProcessId.create(processId).value(),
                DeadLetterOccurredAt.create(Instant.parse("2026-05-23T10:00:00Z")).value(),
                DeadLetterPayload.create("{\"hello\":\"world\"}").value(),
                0).value();
    }

    private static final class FakeDeadLetterRepository implements DeadLetterRepository {
        private final List<DeadLetterRecord> records;
        private final List<String> deletedProcessIds = new ArrayList<>();

        private FakeDeadLetterRepository(List<DeadLetterRecord> records) {
            this.records = records;
        }

        @Override
        public Result<List<DeadLetterRecord>> claimNextBatch(
                DrainBatchSize batchSize,
                DrainWorkerId workerId,
                DrainLeaseDuration leaseDuration,
                Instant claimedAt) {
            return Result.success(records.stream().limit(batchSize.value()).toList());
        }

        @Override
        public Result<ProcessId> deleteClaimed(ProcessId processId, DrainWorkerId workerId) {
            deletedProcessIds.add(processId.value());
            return Result.success(processId);
        }

        @Override
        public Result<Integer> releaseExpiredLeases(Instant now) {
            return Result.success(2);
        }
    }

    private static final class FakeDeadLetterReceiver implements DeadLetterReceiver {
        private final int failOnWriteNumber;
        private final List<String> writtenProcessIds = new ArrayList<>();

        private FakeDeadLetterReceiver(int failOnWriteNumber) {
            this.failOnWriteNumber = failOnWriteNumber;
        }

        @Override
        public Result<ReceiveDeadLetterAck> receive(ReceiveDeadLetterCommand command) {
            writtenProcessIds.add(command.processId().value());
            if (writtenProcessIds.size() == failOnWriteNumber) {
                return Result.failure(EXTERNAL_SERVICE_ERROR, "receiver unavailable", null);
            }
            return ReceiveDeadLetterAck.create(command.processId(), "receiver:" + command.processId().value());
        }
    }
}

