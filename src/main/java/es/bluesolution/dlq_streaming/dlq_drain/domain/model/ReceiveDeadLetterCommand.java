package es.bluesolution.dlq_streaming.dlq_drain.domain.model;

import es.bluesolution.dlq_streaming.functional_framework.Result;
import org.jspecify.annotations.Nullable;

import static es.bluesolution.dlq_streaming.functional_framework.FailureResultDescription.ErrorCode.VALIDATION_ERROR;

public record ReceiveDeadLetterCommand(
        ProcessId processId,
        DeadLetterPayload payload
) {
    public static Result<ReceiveDeadLetterCommand> create(
            @Nullable ProcessId processId,
            @Nullable DeadLetterPayload payload) {
        if (processId == null) {
            return Result.failure(VALIDATION_ERROR, "ProcessId is required", null);
        }
        if (payload == null) {
            return Result.failure(VALIDATION_ERROR, "DeadLetterPayload is required", null);
        }
        return Result.success(new ReceiveDeadLetterCommand(processId, payload));
    }
}

