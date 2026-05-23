package es.bluesolution.dlq_streaming.functional_framework;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * Standard error response for Railway-Oriented Programming framework.
 *
 * This record provides consistent error response structure across all
 * REST endpoints with optional stack trace and exception type information.
 */
@Schema(
    name = "ErrorResponse",
    description = "Standard error response for all API endpoints"
)
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        @Schema(
            description = "Error code identifying the type of error",
            example = "VALIDATION_ERROR",
            allowableValues = {
                "VALIDATION_ERROR", "BUSINESS_RULE_ERROR", "RESOURCE_NOT_FOUND",
                "AUTHORIZATION_ERROR", "AUTHENTICATION_ERROR", "CONFLICT_ERROR",
                "EXTERNAL_SERVICE_ERROR", "DATABASE_ERROR", "UNEXPECTED_ERROR",
                "BAD_REQUEST_ERROR", "RATE_LIMIT_ERROR", "TIMEOUT_ERROR", "NOT_IMPLEMENTED_ERROR"
            }
        )
        String code,

        @Schema(
            description = "Human-readable error message",
            example = "The provided email address is not valid"
        )
        String message,

        @Schema(
            description = "Timestamp when the error occurred",
            example = "2025-11-30T10:15:30"
        )
        LocalDateTime timestamp,

        @Schema(
            description = "Stack trace (only in DEVELOPMENT mode)",
            accessMode = Schema.AccessMode.READ_ONLY
        )
        String stackTrace,

        @Schema(
            description = "Type of exception that caused the error (only in DETAILED/DEVELOPMENT mode)",
            example = "IllegalArgumentException"
        )
        String exceptionType
) {
    /**
     * Create a basic error response with code and message.
     * Automatically sets timestamp to current time.
     */
    public static ErrorResponse of(String code, String message) {
        return ErrorResponse.builder()
                .code(code)
                .message(message)
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * Create an error response with stack trace information.
     * Useful for development and debugging scenarios.
     */
    public static ErrorResponse ofWithStackTrace(String code, String message, String stackTrace) {
        return ErrorResponse.builder()
                .code(code)
                .message(message)
                .timestamp(LocalDateTime.now())
                .stackTrace(stackTrace)
                .build();
    }

    /**
     * Create an error response with exception type information.
     * Provides additional context about the underlying exception.
     */
    public static ErrorResponse ofWithException(String code, String message, Exception exception) {
        return ErrorResponse.builder()
                .code(code)
                .message(message)
                .timestamp(LocalDateTime.now())
                .exceptionType(exception != null ? exception.getClass().getSimpleName() : null)
                .build();
    }
}
