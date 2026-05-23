package es.bluesolution.dlq_streaming.functional_framework;

/**
 * Configuration for error response formatting in Railway-Oriented Programming.
 * Controls how much detail to include in error responses based on environment
 * and security considerations.
 */
public enum ErrorResponseMode {
    
    /**
     * Development mode - includes full stack traces and detailed error information.
     * Use only in development environments.
     */
    DEVELOPMENT,
    
    /**
     * Production mode - sanitized error messages without sensitive details.
     * Hides stack traces and internal system information.
     */
    PRODUCTION,
    
    /**
     * Detailed mode - includes exception details but not full stack traces.
     * Good for staging or controlled environments.
     */
    DETAILED
}