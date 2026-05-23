package es.bluesolution.dlq_streaming.dlq_drain.domain.model;

import es.bluesolution.dlq_streaming.functional_framework.Result;

import java.time.Duration;

import static es.bluesolution.dlq_streaming.functional_framework.FailureResultDescription.ErrorCode.VALIDATION_ERROR;

public record DrainLeaseDuration(Duration value) {
    private static final Duration MIN_LEASE = Duration.ofSeconds(1);
    private static final Duration MAX_LEASE = Duration.ofHours(1);

    public static Result<DrainLeaseDuration> create(long seconds) {
        if (seconds <= 0) {
            return Result.failure(VALIDATION_ERROR, "DrainLeaseDuration must be greater than zero seconds", null);
        }
        return create(Duration.ofSeconds(seconds));
    }

    public static Result<DrainLeaseDuration> create(Duration value) {
        if (value == null) {
            return Result.failure(VALIDATION_ERROR, "DrainLeaseDuration is required", null);
        }
        if (value.compareTo(MIN_LEASE) < 0) {
            return Result.failure(VALIDATION_ERROR, "DrainLeaseDuration must be at least one second", null);
        }
        if (value.compareTo(MAX_LEASE) > 0) {
            return Result.failure(VALIDATION_ERROR, "DrainLeaseDuration must not exceed one hour", null);
        }
        return Result.success(new DrainLeaseDuration(value));
    }
}

