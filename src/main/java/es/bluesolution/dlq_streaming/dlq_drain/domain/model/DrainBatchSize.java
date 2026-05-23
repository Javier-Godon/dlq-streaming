package es.bluesolution.dlq_streaming.dlq_drain.domain.model;

import es.bluesolution.dlq_streaming.functional_framework.Result;

import static es.bluesolution.dlq_streaming.functional_framework.FailureResultDescription.ErrorCode.VALIDATION_ERROR;

public record DrainBatchSize(int value) {
    private static final int MAX_BATCH_SIZE = 1_000;

    public static Result<DrainBatchSize> create(int value) {
        if (value <= 0) {
            return Result.failure(VALIDATION_ERROR, "DrainBatchSize must be greater than zero", null);
        }
        if (value > MAX_BATCH_SIZE) {
            return Result.failure(VALIDATION_ERROR, "DrainBatchSize must not exceed " + MAX_BATCH_SIZE, null);
        }
        return Result.success(new DrainBatchSize(value));
    }
}

