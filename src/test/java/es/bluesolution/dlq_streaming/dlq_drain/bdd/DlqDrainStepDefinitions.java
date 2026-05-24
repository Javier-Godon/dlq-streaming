package es.bluesolution.dlq_streaming.dlq_drain.bdd;

import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DeadLetterPayload;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DeadLetterOccurredAt;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DeadLetterRecord;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DrainBatchSize;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DrainLeaseDuration;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DrainWorkerId;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.ProcessId;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.ReceiveDeadLetterAck;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.ReceiveDeadLetterCommand;
import es.bluesolution.dlq_streaming.dlq_drain.domain.repository.DeadLetterReceiver;
import es.bluesolution.dlq_streaming.dlq_drain.domain.repository.DeadLetterRepository;
import es.bluesolution.dlq_streaming.dlq_drain.usecases.drain_dead_letters.application.DrainDeadLettersCommand;
import es.bluesolution.dlq_streaming.dlq_drain.usecases.drain_dead_letters.application.DrainDeadLettersHandler;
import es.bluesolution.dlq_streaming.dlq_drain.usecases.drain_dead_letters.application.DrainDeadLettersResult;
import es.bluesolution.dlq_streaming.functional_framework.Result;
import es.bluesolution.dlq_streaming.functional_framework.execution.NoOpExecutionContext;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static es.bluesolution.dlq_streaming.functional_framework.FailureResultDescription.ErrorCode.EXTERNAL_SERVICE_ERROR;
import static org.assertj.core.api.Assertions.assertThat;

public class DlqDrainStepDefinitions {
    private ScenarioDeadLetterRepository repository;
    private ScenarioReceiver receiver;
    private Result<DrainDeadLettersResult> result;

    @Before
    public void reset() {
        repository = new ScenarioDeadLetterRepository();
        receiver = new ScenarioReceiver();
        result = null;
    }

    @Given("the dead-letter table has pending records")
    public void theDeadLetterTableHasPendingRecords(DataTable dataTable) {
        dataTable.asMaps().forEach(row -> repository.records.add(buildRecord(row.get("processId"))));
    }

    @Given("the dead-letter table is empty")
    public void theDeadLetterTableIsEmpty() {
        // repository.records is already empty after @Before reset — nothing to do
    }

    @Given("the receiver accepts every record")
    public void theReceiverAcceptsEveryRecord() {
        receiver.failOnReceiveNumber = -1;
    }

    @Given("the receiver fails on record number {int}")
    public void theReceiverFailsOnRecordNumber(int failOnReceiveNumber) {
        receiver.failOnReceiveNumber = failOnReceiveNumber;
    }

    @When("the drain runs with batch size {int}")
    public void theDrainRunsWithBatchSize(int batchSize) {
        var handler = new DrainDeadLettersHandler(
                repository,
                receiver,
                new NoOpExecutionContext(),
                Clock.fixed(Instant.parse("2026-05-23T10:20:00Z"), ZoneOffset.UTC));

        result = handler.handle(new DrainDeadLettersCommand(batchSize, "bdd-worker", 60, false));
    }

    @Then("{int} records are received")
    public void recordsAreReceived(int expectedReceived) {
        assertThat(result.isSuccess()).isTrue();
        assertThat(receiver.receivedProcessIds).hasSize(expectedReceived);
    }

    @Then("{int} records are deleted from the dead-letter table")
    public void recordsAreDeletedFromTheDeadLetterTable(int expectedDeleted) {
        assertThat(repository.deletedProcessIds).hasSize(expectedDeleted);
    }

    @Then("the drain does not stop because the receiver failed")
    public void theDrainDoesNotStopBecauseTheReceiverFailed() {
        assertThat(result.value().stoppedBecauseReceiverFailed()).isFalse();
    }

    @Then("the drain stops because the receiver failed at {string}")
    public void theDrainStopsBecauseTheReceiverFailedAt(String processId) {
        assertThat(result.value().stoppedBecauseReceiverFailed()).isTrue();
        assertThat(result.value().lastProcessedProcessId()).contains(processId);
    }

    private static DeadLetterRecord buildRecord(String processId) {
        return DeadLetterRecord.create(
                ProcessId.create(processId).value(),
                DeadLetterOccurredAt.create(Instant.parse("2026-05-23T10:00:00Z")).value(),
                DeadLetterPayload.create("{\"message\":\"hello\"}").value(),
                0).value();
    }

    private static final class ScenarioDeadLetterRepository implements DeadLetterRepository {
        private final List<DeadLetterRecord> records = new ArrayList<>();
        private final List<String> deletedProcessIds = new ArrayList<>();

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
            return Result.success(0);
        }
    }

    private static final class ScenarioReceiver implements DeadLetterReceiver {
        private int failOnReceiveNumber = -1;
        private final List<String> receivedProcessIds = new ArrayList<>();

        @Override
        public Result<ReceiveDeadLetterAck> receive(ReceiveDeadLetterCommand command) {
            receivedProcessIds.add(command.processId().value());
            if (receivedProcessIds.size() == failOnReceiveNumber) {
                return Result.failure(EXTERNAL_SERVICE_ERROR, "receiver unavailable", null);
            }
            return ReceiveDeadLetterAck.create(command.processId(), "receiver:" + command.processId().value());
        }
    }
}


