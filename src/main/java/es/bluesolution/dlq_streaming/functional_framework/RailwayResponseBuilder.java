package es.bluesolution.dlq_streaming.functional_framework;

import es.bluesolution.dlq_streaming.functional_framework.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Method;

/**
 * Enhanced Railway-Oriented Programming response builder that handles both success and failure cases.
 * Automatically determines appropriate HTTP status codes for success responses using annotations
 * and method introspection, while maintaining comprehensive error handling.
 */
public final class RailwayResponseBuilder {

    private RailwayResponseBuilder() {
        // Utility class - prevent instantiation
    }

    /**
     * Builds appropriate ResponseEntity based on Result and automatically determines success status.
     * Uses @SuccessStatus annotation or HTTP method conventions to determine the success status code.
     * Enhanced with Java 25 pattern matching for cleaner code.
     *
     * @param result The Result to convert
     * @param <T> The success type
     * @return ResponseEntity with appropriate status and body
     */
    public static <T> ResponseEntity<Object> buildResponse(Result<T> result) {
        return result.match(
            success -> {
                HttpStatus successStatus = determineSuccessStatus();
                return ResponseEntity.status(successStatus).body(success);
            },
            RailwayResponseBuilder::buildErrorResponse
        );
    }

    /**
     * Builds appropriate ResponseEntity with explicit success status specification.
     * Enhanced with Java 25 pattern matching.
     *
     * @param result The Result to convert
     * @param successStatus The HTTP status to use for success cases
     * @param <T> The success type
     * @return ResponseEntity with appropriate status and body
     */
    public static <T> ResponseEntity<Object> buildResponse(Result<T> result, HttpStatus successStatus) {
        return result.match(
            success -> ResponseEntity.status(successStatus).body(success),
            RailwayResponseBuilder::buildErrorResponse
        );
    }

    /**
     * Builds error response using the enhanced error handling system.
     */
    private static ResponseEntity<Object> buildErrorResponse(FailureResultDescription failure) {
        HttpStatus status = HttpStatusMapper.mapToHttpStatus(failure);
        
        ErrorResponse errorResponse = ErrorResponse.of(
            failure.code().name(),
            failure.message()
        );
        
        return ResponseEntity.status(status).body(errorResponse);
    }

    /**
     * Advanced response building with pattern matching for different error types.
     * Uses Java 25 guard patterns for fine-grained control.
     *
     * @param result The Result to convert
     * @param <T> The success type
     * @return ResponseEntity with context-aware error handling
     */
    public static <T> ResponseEntity<Object> buildAdvancedResponse(Result<T> result) {
        return result.matchWithGuards(
            // Success case
            success -> {
                HttpStatus successStatus = determineSuccessStatus();
                return ResponseEntity.status(successStatus).body(success);
            },
            // Validation errors -> 400 Bad Request with detailed validation info
            validationError -> {
                ErrorResponse errorResponse = ErrorResponse.of(
                    "VALIDATION_ERROR",
                    validationError.message()
                );
                return ResponseEntity.badRequest().body(errorResponse);
            },
            // Not found errors -> 404 Not Found
            notFoundError -> ResponseEntity.notFound().build(),
            // Other errors -> Use standard mapping
            RailwayResponseBuilder::buildErrorResponse
        );
    }

    /**
     * Determines success HTTP status using @SuccessStatus annotation or method name conventions.
     */
    private static HttpStatus determineSuccessStatus() {
        // Get the calling method via stack trace
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        
        for (int i = 2; i < Math.min(stackTrace.length, 10); i++) { // Start from 2 to skip this method and Thread.getStackTrace
            StackTraceElement element = stackTrace[i];
            try {
                Class<?> callingClass = Class.forName(element.getClassName());
                Method[] methods = callingClass.getDeclaredMethods();
                
                for (Method method : methods) {
                    if (method.getName().equals(element.getMethodName())) {
                        // Check for @SuccessStatus annotation
                        if (method.isAnnotationPresent(SuccessStatus.class)) {
                            return method.getAnnotation(SuccessStatus.class).value();
                        }
                        
                        // Fallback to method name conventions
                        return determineStatusFromMethodName(method.getName());
                    }
                }
            } catch (ClassNotFoundException _) {
                // Continue to next stack element - exception intentionally ignored
            }
        }
        
        return HttpStatus.OK; // Default fallback
    }

    /**
     * Determines HTTP status based on method naming conventions.
     * This follows REST conventions for HTTP verbs.
     */
    private static HttpStatus determineStatusFromMethodName(String methodName) {
        String lowerMethodName = methodName.toLowerCase();
        
        // CREATE operations -> 201 Created
        if (lowerMethodName.contains("create") || 
            lowerMethodName.contains("post") || 
            lowerMethodName.startsWith("add")) {
            return HttpStatus.CREATED;
        }
        
        // DELETE operations -> 204 No Content  
        if (lowerMethodName.contains("delete") || 
            lowerMethodName.contains("remove") || 
            lowerMethodName.startsWith("destroy")) {
            return HttpStatus.NO_CONTENT;
        }
        
        // ASYNC operations -> 202 Accepted
        if (lowerMethodName.contains("async") || 
            lowerMethodName.contains("queue") || 
            lowerMethodName.contains("submit") ||
            lowerMethodName.contains("schedule")) {
            return HttpStatus.ACCEPTED;
        }
        
        // UPDATE operations -> 200 OK (default for PUT/PATCH)
        if (lowerMethodName.contains("update") || 
            lowerMethodName.contains("put") || 
            lowerMethodName.contains("patch") ||
            lowerMethodName.contains("modify")) {
            return HttpStatus.OK;
        }
        
        // GET operations -> 200 OK (default)
        return HttpStatus.OK;
    }
}