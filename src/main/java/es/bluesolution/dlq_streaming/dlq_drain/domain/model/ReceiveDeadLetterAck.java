package es.bluesolution.dlq_streaming.dlq_drain.domain.model;

import es.bluesolution.dlq_streaming.functional_framework.Result;
import org.jspecify.annotations.Nullable;

import static es.bluesolution.dlq_streaming.functional_framework.FailureResultDescription.ErrorCode.VALIDATION_ERROR;

public record ReceiveDeadLetterAck(
        ProcessId processId,
        String receiverReference
) {
    public static Result<ReceiveDeadLetterAck> create(
            @Nullable ProcessId processId,
            @Nullable String receiverReference) {
        if (processId == null) {
            return Result.failure(VALIDATION_ERROR, "ProcessId is required", null);
        }
        if (receiverReference == null || receiverReference.isBlank()) {
            return Result.failure(VALIDATION_ERROR, "ReceiverReference is required", null);
        }
        return Result.success(new ReceiveDeadLetterAck(processId, receiverReference.trim()));
    }
}

