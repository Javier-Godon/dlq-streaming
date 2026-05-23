package es.bluesolution.dlq_streaming.functional_framework;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.function.Function;

/**
 * Centralized error response builder for Railway-Oriented Programming controllers.
 * Provides consistent error handling across all API endpoints with enhanced
 * exception mapping and environment-aware error formatting.
 */
public final class RailwayErrorResponseBuilder {

    private static ErrorMessageFormatter errorMessageFormatter;

    private RailwayErrorResponseBuilder() {
        // Utility class - prevent instantiation
    }

    /**
     * Sets the error message formatter (typically injected by Spring).
     * This allows the builder to format messages according to environment settings.
     */
    public static void setErrorMessageFormatter(ErrorMessageFormatter formatter) {
        errorMessageFormatter = formatter;
    }

    /**
     * Creates a function that maps FailureResultDescription to appropriate HTTP error response.
     * This method is designed to be used with Result.either() for consistent error handling.
     * Uses enhanced mapping that considers both error codes and exception types.
     *
     * @param <T> The expected success response type
     * @return A function that converts failures to ResponseEntity with proper HTTP status
     */
    public static <T> Function<FailureResultDescription, ResponseEntity<T>> buildErrorResponse() {
        return failure -> {
            HttpStatus status = HttpStatusMapper.mapToHttpStatus(failure);
            String message = formatErrorMessage(failure);
            
            ErrorResponse errorResponse = ErrorResponse.of(
                    failure.code().name(),
                    message
            );
            
            @SuppressWarnings("unchecked")
            ResponseEntity<T> response = (ResponseEntity<T>) ResponseEntity
                    .status(status)
                    .body(errorResponse);
            
            return response;
        };
    }

    /**
     * Creates a function that maps FailureResultDescription to detailed HTTP error response.
     * Includes additional error details when available and appropriate for the environment.
     * 
     * @param <T> The expected success response type
     * @return A function that converts failures to ResponseEntity with detailed error information
     */
    public static <T> Function<FailureResultDescription, ResponseEntity<T>> buildDetailedErrorResponse() {
        return failure -> {
            HttpStatus status = HttpStatusMapper.mapToHttpStatus(failure);
            String message = formatDetailedErrorMessage(failure);
            
            ErrorResponse errorResponse = shouldIncludeStackTrace(failure) 
                ? ErrorResponse.ofWithStackTrace(
                    failure.code().name(),
                    message,
                    failure.fullStackTrace())
                : ErrorResponse.of(
                    failure.code().name(),
                    message);
            
            @SuppressWarnings("unchecked")
            ResponseEntity<T> response = (ResponseEntity<T>) ResponseEntity
                    .status(status)
                    .body(errorResponse);
            
            return response;
        };
    }

    /**
     * Helper method for formatting error messages using the configured formatter.
     */
    private static String formatErrorMessage(FailureResultDescription failure) {
        if (errorMessageFormatter != null) {
            return errorMessageFormatter.formatErrorMessage(failure);
        }
        return failure.message(); // Fallback
    }

    /**
     * Helper method for formatting detailed error messages.
     */
    private static String formatDetailedErrorMessage(FailureResultDescription failure) {
        if (errorMessageFormatter != null) {
            return errorMessageFormatter.formatErrorMessage(failure);
        }
        
        // Fallback detailed formatting
        String message = failure.message();
        if (failure.exception() != null) {
            message += " - " + failure.exception().getMessage();
        }
        return message;
    }

    /**
     * Determines if stack trace should be included based on configuration.
     */
    private static boolean shouldIncludeStackTrace(FailureResultDescription failure) {
        return errorMessageFormatter != null 
            && errorMessageFormatter.shouldIncludeStackTrace() 
            && failure.exception() != null;
    }
}