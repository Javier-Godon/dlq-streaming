package es.bluesolution.dlq_streaming.dlq_drain.usecases.drain_dead_letters.application;

import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DeadLetterPayload;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DeadLetterOccurredAt;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DeadLetterRecord;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DrainBatchSize;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DrainLeaseDuration;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DrainWorkerId;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.ProcessId;
import es.bluesolution.dlq_streaming.dlq_drain.domain.repository.DeadLetterReceiver;
import es.bluesolution.dlq_streaming.dlq_drain.domain.repository.DeadLetterRepository;
import es.bluesolution.dlq_streaming.functional_framework.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static es.bluesolution.dlq_streaming.functional_framework.FailureResultDescription.ErrorCode.DATABASE_ERROR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DrainDeadLettersStagesTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-05-23T10:00:00Z"), ZoneOffset.UTC);

    @Mock
    DeadLetterRepository deadLetterRepository;

    @Mock
    DeadLetterReceiver deadLetterReceiver;

    // ── parseCommand ──────────────────────────────────────────────────────────

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
    void rejectsInvalidWorkerId() {
        var data = DrainDeadLettersData.initialize(new DrainDeadLettersCommand(100, "", 120, true));

        var result = DrainDeadLettersStages.parseCommand(data);

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().message()).isEqualTo("DrainWorkerId is required");
    }

    @Test
    void rejectsInvalidLeaseDuration() {
        var data = DrainDeadLettersData.initialize(new DrainDeadLettersCommand(100, "worker-a", 0, true));

        var result = DrainDeadLettersStages.parseCommand(data);

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().message()).isEqualTo("DrainLeaseDuration must be greater than zero seconds");
    }

    // ── releaseExpiredLeases ──────────────────────────────────────────────────

    @Test
    void skipsReleaseOfExpiredLeasesWhenFlagIsFalse() {
        var data = parsedData(false);
        var ports = DrainDeadLettersPorts.of(deadLetterRepository, deadLetterReceiver, FIXED_CLOCK);

        var result = DrainDeadLettersStages.releaseExpiredLeases(data, ports);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value().releasedExpiredLeases()).isZero();
        verify(deadLetterRepository, never()).releaseExpiredLeases(any());
    }

    @Test
    void releasesExpiredLeasesWhenFlagIsTrue() {
        when(deadLetterRepository.releaseExpiredLeases(any())).thenReturn(Result.success(3));
        var data = parsedData(true);
        var ports = DrainDeadLettersPorts.of(deadLetterRepository, deadLetterReceiver, FIXED_CLOCK);

        var result = DrainDeadLettersStages.releaseExpiredLeases(data, ports);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value().releasedExpiredLeases()).isEqualTo(3);
    }

    @Test
    void forwardsRepositoryFailureFromReleaseExpiredLeases() {
        when(deadLetterRepository.releaseExpiredLeases(any()))
                .thenReturn(Result.failure(DATABASE_ERROR, "DB unreachable", null));
        var data = parsedData(true);
        var ports = DrainDeadLettersPorts.of(deadLetterRepository, deadLetterReceiver, FIXED_CLOCK);

        var result = DrainDeadLettersStages.releaseExpiredLeases(data, ports);

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().message()).isEqualTo("DB unreachable");
    }

    // ── claimBatch ────────────────────────────────────────────────────────────

    @Test
    void claimsBatchSuccessfully() {
        var records = List.of(buildRecord("product-1_2026-05-23T10:15:30Z"));
        when(deadLetterRepository.claimNextBatch(any(), any(), any(), any()))
                .thenReturn(Result.success(records));
        var data = parsedData(false);
        var ports = DrainDeadLettersPorts.of(deadLetterRepository, deadLetterReceiver, FIXED_CLOCK);

        var result = DrainDeadLettersStages.claimBatch(data, ports);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value().claimedRecords()).hasSize(1);
    }

    @Test
    void failsClaimBatchWhenCommandNotParsed() {
        var data = DrainDeadLettersData.initialize(new DrainDeadLettersCommand(100, "worker-a", 120, false));
        var ports = DrainDeadLettersPorts.of(deadLetterRepository, deadLetterReceiver, FIXED_CLOCK);

        var result = DrainDeadLettersStages.claimBatch(data, ports);

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().message()).isEqualTo("Drain command has not been parsed");
    }

    @Test
    void forwardsRepositoryFailureFromClaimBatch() {
        when(deadLetterRepository.claimNextBatch(any(), any(), any(), any()))
                .thenReturn(Result.failure(DATABASE_ERROR, "Connection pool exhausted", null));
        var data = parsedData(false);
        var ports = DrainDeadLettersPorts.of(deadLetterRepository, deadLetterReceiver, FIXED_CLOCK);

        var result = DrainDeadLettersStages.claimBatch(data, ports);

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().message()).isEqualTo("Connection pool exhausted");
    }

    // ── deleteClaimed ─────────────────────────────────────────────────────────

    @Test
    void deletesClaimedRecordSuccessfully() {
        var rec = buildRecord("product-1_2026-05-23T10:15:30Z");
        when(deadLetterRepository.deleteClaimed(any(), any()))
                .thenReturn(Result.success(rec.processId()));
        var data = parsedData(false).withClaimedRecords(List.of(rec));
        var ports = DrainDeadLettersPorts.of(deadLetterRepository, deadLetterReceiver, FIXED_CLOCK);

        var result = DrainDeadLettersStages.deleteClaimed(data, rec, ports);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value().deletedCount()).isEqualTo(1);
    }

    @Test
    void failsDeleteWhenCommandNotParsed() {
        var rec = buildRecord("product-1_2026-05-23T10:15:30Z");
        var unparsed = DrainDeadLettersData.initialize(new DrainDeadLettersCommand(100, "worker-a", 120, false));
        var ports = DrainDeadLettersPorts.of(deadLetterRepository, deadLetterReceiver, FIXED_CLOCK);

        var result = DrainDeadLettersStages.deleteClaimed(unparsed, rec, ports);

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().message()).isEqualTo("Drain command has not been parsed");
    }

    // ── buildResult ───────────────────────────────────────────────────────────

    @Test
    void buildsResultFromStoppedData() {
        var dlr = buildRecord("product-1_2026-05-23T10:15:30Z");
        var data = DrainDeadLettersData.initialize(new DrainDeadLettersCommand(10, "worker-a", 60, false))
                .withClaimedRecords(List.of(dlr));

        var stopped = DrainDeadLettersStages.markReceiverFailure(data, dlr, "receiver unavailable");
        var result = DrainDeadLettersStages.buildResult(stopped);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value().stoppedBecauseReceiverFailed()).isTrue();
        assertThat(result.value().lastProcessedProcessId()).contains("product-1_2026-05-23T10:15:30Z");
        assertThat(result.value().stopReason()).contains("receiver unavailable");
    }

    @Test
    void buildsResultFromEmptyDrain() {
        var data = DrainDeadLettersData.initialize(new DrainDeadLettersCommand(10, "worker-a", 60, false));

        var result = DrainDeadLettersStages.buildResult(data);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value().claimedCount()).isZero();
        assertThat(result.value().storedCount()).isZero();
        assertThat(result.value().deletedCount()).isZero();
        assertThat(result.value().stoppedBecauseReceiverFailed()).isFalse();
        assertThat(result.value().lastProcessedProcessId()).isEmpty();
        assertThat(result.value().stopReason()).isEmpty();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Returns a Data instance with parsed batchSize/workerId/leaseDuration. */
    private static DrainDeadLettersData parsedData(boolean releaseExpiredLeases) {
        return DrainDeadLettersData.initialize(
                new DrainDeadLettersCommand(10, "worker-a", 60, releaseExpiredLeases))
                .withParsed(
                        DrainBatchSize.create(10).value(),
                        DrainWorkerId.create("worker-a").value(),
                        DrainLeaseDuration.create(Duration.ofSeconds(60)).value());
    }

    private static DeadLetterRecord buildRecord(String processId) {
        return DeadLetterRecord.create(
                ProcessId.create(processId).value(),
                DeadLetterOccurredAt.create(Instant.parse("2026-05-23T10:00:00Z")).value(),
                DeadLetterPayload.create("{\"hello\":\"world\"}").value(),
                1).value();
    }
}

