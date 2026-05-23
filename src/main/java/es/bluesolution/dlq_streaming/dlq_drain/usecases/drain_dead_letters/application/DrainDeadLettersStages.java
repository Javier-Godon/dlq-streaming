package es.bluesolution.dlq_streaming.dlq_drain.usecases.drain_dead_letters.application;

import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DeadLetterRecord;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DrainBatchSize;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DrainLeaseDuration;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.DrainWorkerId;
import es.bluesolution.dlq_streaming.dlq_drain.domain.model.ReceiveDeadLetterCommand;
import es.bluesolution.dlq_streaming.functional_framework.Result;

import java.time.Instant;

import static es.bluesolution.dlq_streaming.functional_framework.FailureResultDescription.ErrorCode.VALIDATION_ERROR;

final class DrainDeadLettersStages {
    private DrainDeadLettersStages() {
    }

    static Result<DrainDeadLettersData> parseCommand(DrainDeadLettersData data) {
        var batchSize = DrainBatchSize.create(data.command().batchSize());
        if (batchSize.isFailure()) {
            return Result.failure(batchSize.failure());
        }

        var workerId = DrainWorkerId.create(data.command().workerId());
        if (workerId.isFailure()) {
            return Result.failure(workerId.failure());
        }

        var leaseDuration = DrainLeaseDuration.create(data.command().leaseSeconds());
        if (leaseDuration.isFailure()) {
            return Result.failure(leaseDuration.failure());
        }

        return Result.success(data.withParsed(batchSize.value(), workerId.value(), leaseDuration.value()));
    }

    static Result<DrainDeadLettersData> releaseExpiredLeases(DrainDeadLettersData data, DrainDeadLettersPorts ports) {
        if (!data.command().releaseExpiredLeases()) {
            return Result.success(data);
        }

        return ports.deadLetterRepository()
                .releaseExpiredLeases(Instant.now(ports.clock()))
                .map(data::withReleasedExpiredLeases);
    }

    static Result<DrainDeadLettersData> claimBatch(DrainDeadLettersData data, DrainDeadLettersPorts ports) {
        if (data.batchSize() == null || data.workerId() == null || data.leaseDuration() == null) {
            return Result.failure(VALIDATION_ERROR, "Drain command has not been parsed", null);
        }

        return ports.deadLetterRepository()
                .claimNextBatch(data.batchSize(), data.workerId(), data.leaseDuration(), Instant.now(ports.clock()))
                .map(data::withClaimedRecords);
    }

    static Result<ReceiveDeadLetterCommand> buildReceiveCommand(DeadLetterRecord record) {
        return ReceiveDeadLetterCommand.create(record.processId(), record.payload());
    }

    static Result<DrainDeadLettersData> deleteClaimed(
            DrainDeadLettersData data,
            DeadLetterRecord record,
            DrainDeadLettersPorts ports) {
        if (data.workerId() == null) {
            return Result.failure(VALIDATION_ERROR, "Drain command has not been parsed", null);
        }

        return ports.deadLetterRepository()
                .deleteClaimed(record.processId(), data.workerId())
                .map(ignored -> data.markDeleted());
    }

    static DrainDeadLettersData markStored(DrainDeadLettersData data, DeadLetterRecord record) {
        return data.markStored(record.processId());
    }

    static DrainDeadLettersData markReceiverFailure(DrainDeadLettersData data, DeadLetterRecord record, String reason) {
        return data.markReceiverFailure(record.processId(), reason);
    }

    static Result<DrainDeadLettersResult> buildResult(DrainDeadLettersData data) {
        return Result.success(new DrainDeadLettersResult(
                data.releasedExpiredLeases(),
                data.claimedRecords().size(),
                data.storedCount(),
                data.deletedCount(),
                data.stoppedBecauseReceiverFailed(),
                data.lastProcessedProcessId().map(processId -> processId.value()),
                data.stopReason()));
    }
}

