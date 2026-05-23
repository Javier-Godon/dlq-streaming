package es.bluesolution.dlq_streaming.dlq_drain.domain.model;

import es.bluesolution.dlq_streaming.functional_framework.Result;
import org.jspecify.annotations.Nullable;

import static es.bluesolution.dlq_streaming.functional_framework.FailureResultDescription.ErrorCode.VALIDATION_ERROR;

public record ProcessId(String value) {
    public static Result<ProcessId> create(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return Result.failure(VALIDATION_ERROR, "ProcessId is required", null);
        }

        var normalized = value.trim();
        if (normalized.length() > 300) {
            return Result.failure(VALIDATION_ERROR, "ProcessId must not exceed 300 characters", null);
        }

        var separatorIndex = normalized.lastIndexOf('_');
        if (separatorIndex <= 0 || separatorIndex == normalized.length() - 1) {
            return Result.failure(VALIDATION_ERROR, "ProcessId must follow productReference_timestamp", null);
        }

        return Result.success(new ProcessId(normalized));
    }

    public String productReference() {
        return value.substring(0, value.lastIndexOf('_'));
    }

    public String timestampReference() {
        return value.substring(value.lastIndexOf('_') + 1);
    }
}

