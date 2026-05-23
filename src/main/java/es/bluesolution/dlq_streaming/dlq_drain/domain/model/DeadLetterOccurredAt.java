package es.bluesolution.dlq_streaming.dlq_drain.domain.model;

import es.bluesolution.dlq_streaming.functional_framework.Result;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

import static es.bluesolution.dlq_streaming.functional_framework.FailureResultDescription.ErrorCode.VALIDATION_ERROR;

public record DeadLetterOccurredAt(Instant value) {
    public static Result<DeadLetterOccurredAt> create(@Nullable Instant value) {
        if (value == null) {
            return Result.failure(VALIDATION_ERROR, "DeadLetterOccurredAt is required", null);
        }
        return Result.success(new DeadLetterOccurredAt(value));
    }
}

