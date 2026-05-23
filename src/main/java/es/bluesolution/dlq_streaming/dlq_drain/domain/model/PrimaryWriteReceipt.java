package es.bluesolution.dlq_streaming.dlq_drain.domain.model;

import es.bluesolution.dlq_streaming.functional_framework.Result;
import org.jspecify.annotations.Nullable;

import static es.bluesolution.dlq_streaming.functional_framework.FailureResultDescription.ErrorCode.VALIDATION_ERROR;

@Deprecated(since = "2026.05", forRemoval = true)
public record PrimaryWriteReceipt(
        ProcessId processId,
        String primaryReference
) {
    public static Result<PrimaryWriteReceipt> create(
            @Nullable ProcessId processId,
            @Nullable String primaryReference) {
        if (processId == null) {
            return Result.failure(VALIDATION_ERROR, "ProcessId is required", null);
        }
        if (primaryReference == null || primaryReference.isBlank()) {
            return Result.failure(VALIDATION_ERROR, "PrimaryReference is required", null);
        }
        return Result.success(new PrimaryWriteReceipt(processId, primaryReference.trim()));
    }
}

