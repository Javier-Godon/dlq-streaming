package es.bluesolution.dlq_streaming.functional_framework;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * Standard error response for Railway-Oriented Programming framework.
 *
 * This record provides consistent error response structure across all
 * REST endpoints with optional stack trace and exception type information.
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        String code,
        String message,
        LocalDateTime timestamp,
        String stackTrace,
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
