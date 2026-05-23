package es.bluesolution.dlq_streaming.functional_framework;

import org.springframework.http.HttpStatus;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to specify the HTTP status code for successful Railway-Oriented Programming responses.
 * When not specified, defaults to 200 OK.
 * 
 * Usage examples:
 * 
 * @SuccessStatus(HttpStatus.CREATED)  // 201 for resource creation
 * @SuccessStatus(HttpStatus.ACCEPTED) // 202 for async operations
 * @SuccessStatus(HttpStatus.NO_CONTENT) // 204 for successful deletion
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SuccessStatus {
    
    /**
     * The HTTP status code to return on successful operations.
     * @return the HttpStatus to use for success responses
     */
    HttpStatus value() default HttpStatus.OK;
}