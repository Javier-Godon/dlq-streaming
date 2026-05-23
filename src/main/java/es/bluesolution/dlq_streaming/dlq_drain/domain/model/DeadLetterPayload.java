package es.bluesolution.dlq_streaming.dlq_drain.domain.model;

import es.bluesolution.dlq_streaming.functional_framework.Result;
import org.jspecify.annotations.Nullable;

import static es.bluesolution.dlq_streaming.functional_framework.FailureResultDescription.ErrorCode.VALIDATION_ERROR;

public record DeadLetterPayload(String value) {
    public static Result<DeadLetterPayload> create(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return Result.failure(VALIDATION_ERROR, "DeadLetterPayload is required", null);
        }
        return Result.success(new DeadLetterPayload(value));
    }
}

