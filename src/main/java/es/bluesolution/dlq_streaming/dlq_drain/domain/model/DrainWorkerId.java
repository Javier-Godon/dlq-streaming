package es.bluesolution.dlq_streaming.dlq_drain.domain.model;

import es.bluesolution.dlq_streaming.functional_framework.Result;
import org.jspecify.annotations.Nullable;

import static es.bluesolution.dlq_streaming.functional_framework.FailureResultDescription.ErrorCode.VALIDATION_ERROR;

public record DrainWorkerId(String value) {
    public static Result<DrainWorkerId> create(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return Result.failure(VALIDATION_ERROR, "DrainWorkerId is required", null);
        }

        var normalized = value.trim();
        if (normalized.length() > 100) {
            return Result.failure(VALIDATION_ERROR, "DrainWorkerId must not exceed 100 characters", null);
        }

        return Result.success(new DrainWorkerId(normalized));
    }
}

