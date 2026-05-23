package es.bluesolution.dlq_streaming.functional_framework;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Formats error messages for HTTP responses based on the configured error response mode.
 * Handles security considerations by hiding sensitive information in production.
 */
@Component
public class ErrorMessageFormatter {

    private final ErrorResponseMode mode;

    public ErrorMessageFormatter(
        @Value("${railway.error.response.mode:PRODUCTION}") String errorMode
    ) {
        this.mode = ErrorResponseMode.valueOf(errorMode.toUpperCase());
    }

    /**
     * Formats an error message based on the current error response mode.
     * 
     * @param failure The failure description to format
     * @return A formatted error message appropriate for the current environment
     */
    public String formatErrorMessage(FailureResultDescription failure) {
        return switch (mode) {
            case DEVELOPMENT -> formatDevelopmentMessage(failure);
            case DETAILED -> formatDetailedMessage(failure);
            case PRODUCTION -> formatProductionMessage(failure);
        };
    }

    /**
     * Determines if stack traces should be included in the response.
     */
    public boolean shouldIncludeStackTrace() {
        return mode == ErrorResponseMode.DEVELOPMENT;
    }

    /**
     * Determines if exception details should be included in the response.
     */
    public boolean shouldIncludeExceptionDetails() {
        return mode == ErrorResponseMode.DEVELOPMENT || mode == ErrorResponseMode.DETAILED;
    }

    private String formatDevelopmentMessage(FailureResultDescription failure) {
        StringBuilder message = new StringBuilder(failure.message());
        
        if (failure.exception() != null) {
            message.append(" | Exception: ").append(failure.exception().getClass().getSimpleName());
            message.append(" - ").append(failure.exception().getMessage());
        }
        
        return message.toString();
    }

    private String formatDetailedMessage(FailureResultDescription failure) {
        String message = failure.message();
        
        if (failure.exception() != null && shouldIncludeExceptionDetails()) {
            message += " (" + failure.exception().getClass().getSimpleName() + ")";
        }
        
        return message;
    }

    private String formatProductionMessage(FailureResultDescription failure) {
        // In production, only return the message, hide internal details
        return failure.message();
    }

    /**
     * Creates a sanitized error message that's safe for production use.
     * Removes potentially sensitive information while keeping it meaningful.
     */
    public String sanitizeMessage(String originalMessage) {
        if (mode == ErrorResponseMode.PRODUCTION) {
            // Remove common sensitive patterns
            return originalMessage
                .replaceAll("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b", "[IP_ADDRESS]") // IP addresses
                .replaceAll("password=[^\\s]+", "password=[REDACTED]") // Passwords in URLs
                .replaceAll("secret=[^\\s]+", "secret=[REDACTED]") // Secrets
                .replaceAll("token=[^\\s]+", "token=[REDACTED]"); // Tokens
        }
        
        return originalMessage;
    }
}