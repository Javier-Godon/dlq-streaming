package es.bluesolution.dlq_streaming.dlq_drain.domain.model;

import es.bluesolution.dlq_streaming.functional_framework.Result;
import org.jspecify.annotations.Nullable;

import static es.bluesolution.dlq_streaming.functional_framework.FailureResultDescription.ErrorCode.VALIDATION_ERROR;

public record DeadLetterRecord(
        ProcessId processId,
        DeadLetterOccurredAt occurredAt,
        DeadLetterPayload payload,
        int attemptCount
) {
    public static Result<DeadLetterRecord> create(
            @Nullable ProcessId processId,
            @Nullable DeadLetterOccurredAt occurredAt,
            @Nullable DeadLetterPayload payload,
            int attemptCount) {
        if (processId == null) {
            return Result.failure(VALIDATION_ERROR, "ProcessId is required", null);
        }
        if (occurredAt == null) {
            return Result.failure(VALIDATION_ERROR, "DeadLetterOccurredAt is required", null);
        }
        if (payload == null) {
            return Result.failure(VALIDATION_ERROR, "DeadLetterPayload is required", null);
        }
        if (attemptCount < 0) {
            return Result.failure(VALIDATION_ERROR, "AttemptCount must not be negative", null);
        }
        return Result.success(new DeadLetterRecord(processId, occurredAt, payload, attemptCount));
    }
}

