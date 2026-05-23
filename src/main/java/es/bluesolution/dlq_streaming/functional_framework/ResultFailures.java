package es.bluesolution.dlq_streaming.functional_framework;

/**
 * Utility methods for creating Result failures in Railway-Oriented Programming.
 * Provides convenient methods that automatically map exceptions to appropriate error codes.
 * 
 * Inspired by functional programming patterns from Rust, Go, Haskell, and Elixir.
 * This approach eliminates traditional Java exception throwing in favor of explicit error handling.
 */
public final class ResultFailures {

    private ResultFailures() {
        // Utility class - prevent instantiation
    }

    /**
     * Creates a failure result from an exception, automatically mapping the exception
     * type to the appropriate error code. This is used when interfacing with legacy
     * Java code that still throws exceptions.
     * 
     * @param message The human-readable error message
     * @param exception The underlying exception from external systems
     * @param <T> The expected success type
     * @return A Result.failure with properly mapped error code
     */
    public static <T> Result<T> fromException(String message, Exception exception) {
        FailureResultDescription failure = ExceptionToErrorCodeMapper.mapToFailure(message, exception);
        return Result.failure(failure);
    }

    /**
     * Creates a failure result from an exception using the exception's message.
     * Use when integrating with libraries that throw exceptions.
     * 
     * @param exception The exception to map
     * @param <T> The expected success type
     * @return A Result.failure with the exception's message and mapped error code
     */
    public static <T> Result<T> fromException(Exception exception) {
        FailureResultDescription failure = ExceptionToErrorCodeMapper.mapToFailure(exception);
        return Result.failure(failure);
    }

    /**
     * Creates a validation error result - the functional equivalent of input validation errors.
     * 
     * @param message The validation error message
     * @param <T> The expected success type
     * @return A Result.failure with VALIDATION_ERROR code
     */
    public static <T> Result<T> validationError(String message) {
        return Result.failure(new FailureResultDescription(
            FailureResultDescription.ErrorCode.VALIDATION_ERROR,
            message
        ));
    }

    /**
     * Creates a business rule error result - equivalent to domain invariant violations.
     * 
     * @param message The business rule violation message
     * @param <T> The expected success type
     * @return A Result.failure with BUSINESS_RULE_ERROR code
     */
    public static <T> Result<T> businessRuleError(String message) {
        return Result.failure(new FailureResultDescription(
            FailureResultDescription.ErrorCode.BUSINESS_RULE_ERROR,
            message
        ));
    }

    /**
     * Creates a not found error result - functional equivalent of Option::None or Maybe Nothing.
     * 
     * @param resourceType The type of resource that was not found
     * @param identifier The identifier that was searched for
     * @param <T> The expected success type
     * @return A Result.failure with NOT_FOUND code
     */
    public static <T> Result<T> notFound(String resourceType, String identifier) {
        return Result.failure(new FailureResultDescription(
            FailureResultDescription.ErrorCode.NOT_FOUND,
            String.format("%s with identifier '%s' was not found", resourceType, identifier)
        ));
    }

    /**
     * Generic not found for cases where you don't have specific identifiers.
     */
    public static <T> Result<T> notFound(String message) {
        return Result.failure(new FailureResultDescription(
            FailureResultDescription.ErrorCode.NOT_FOUND,
            message
        ));
    }

    /**
     * Creates an authentication error result - equivalent to authorization failures.
     * 
     * @param message The authentication error message
     * @param <T> The expected success type
     * @return A Result.failure with AUTHENTICATION_ERROR code
     */
    public static <T> Result<T> authenticationError(String message) {
        return Result.failure(new FailureResultDescription(
            FailureResultDescription.ErrorCode.AUTHENTICATION_ERROR,
            message
        ));
    }

    /**
     * Creates a configuration error result - equivalent to environment/setup failures.
     * 
     * @param message The configuration error message
     * @param <T> The expected success type
     * @return A Result.failure with CONFIGURATION_ERROR code
     */
    public static <T> Result<T> configurationError(String message) {
        return Result.failure(new FailureResultDescription(
            FailureResultDescription.ErrorCode.CONFIGURATION_ERROR,
            message
        ));
    }

    /**
     * Creates an external service error - equivalent to network/remote failures.
     * 
     * @param serviceName The name of the external service
     * @param operation The operation that failed
     * @param <T> The expected success type
     * @return A Result.failure with EXTERNAL_SERVICE_ERROR code
     */
    public static <T> Result<T> externalServiceError(String serviceName, String operation) {
        return Result.failure(new FailureResultDescription(
            FailureResultDescription.ErrorCode.EXTERNAL_SERVICE_ERROR,
            String.format("External service '%s' failed during operation: %s", serviceName, operation)
        ));
    }

    /**
     * Creates a database error result - equivalent to persistence failures.
     * 
     * @param operation The database operation that failed
     * @param <T> The expected success type
     * @return A Result.failure with DATABASE_ERROR code
     */
    public static <T> Result<T> databaseError(String operation) {
        return Result.failure(new FailureResultDescription(
            FailureResultDescription.ErrorCode.DATABASE_ERROR,
            String.format("Database operation failed: %s", operation)
        ));
    }
}