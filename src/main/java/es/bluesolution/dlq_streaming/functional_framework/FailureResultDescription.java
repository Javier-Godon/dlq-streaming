package es.bluesolution.dlq_streaming.functional_framework;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record FailureResultDescription(
        ErrorCode code,
        String message,
        Exception exception,
        Instant timestamp
) {
    public FailureResultDescription {
        Objects.requireNonNull(code);
        Objects.requireNonNull(message);
        timestamp = Optional.ofNullable(timestamp).orElse(Instant.now());
    }

    public FailureResultDescription(ErrorCode code, String message, Exception exception) {
        this(code, message, exception, Instant.now());
    }

    public FailureResultDescription(ErrorCode code, String message) {
        this(code, message, null, Instant.now());
    }

    public String fullStackTrace() {
        if (exception == null) return message;
        StringBuilder sb = new StringBuilder(message).append("\n");
        sb.append(exception).append("\n");
        for (StackTraceElement ste : exception.getStackTrace()) {
            sb.append("\tat ").append(ste).append("\n");
        }
        return sb.toString();
    }

    public enum ErrorCode {
        // Client-side errors (4xx HTTP range)
        VALIDATION_ERROR,        // Invalid input format, missing fields (400)
        AUTHENTICATION_ERROR,    // Invalid credentials, expired tokens (401)
        AUTHORIZATION_ERROR,     // Insufficient permissions (403)
        NOT_FOUND,              // Resource doesn't exist (404)
        BUSINESS_RULE_ERROR,    // Business constraints violated (409)
        RATE_LIMIT_ERROR,       // Request limits exceeded (429)
        
        // Server-side errors (5xx HTTP range)
        TECHNICAL_ERROR,        // Infrastructure issues (500)
        DATABASE_ERROR,         // Database connectivity failures (500)
        CONFIGURATION_ERROR,    // System misconfiguration (500)
        EXTERNAL_SERVICE_ERROR, // External API failures (502)
        SERVICE_UNAVAILABLE_ERROR, // Service maintenance (503)
        TIMEOUT_ERROR,          // Operation timeout (504)
        UNKNOWN_ERROR          // Unexpected failures (500)
    }
}
