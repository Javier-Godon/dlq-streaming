package es.bluesolution.dlq_streaming.functional_framework;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;
import org.springframework.web.servlet.NoHandlerFoundException;

import org.jspecify.annotations.Nullable;

import java.io.EOFException;
import java.io.FileNotFoundException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeoutException;

/**
 * Maps Java exceptions to Railway framework error codes.
 * This follows Railway-Oriented Programming principles by converting exceptions
 * caught from external libraries into proper FailureResultDescription error codes.
 *
 * In ROP, we don't throw exceptions but we still need to handle them when they
 * come from external systems (databases, HTTP clients, etc.).
 */
public final class ExceptionToErrorCodeMapper {

    private static final Map<Class<? extends Exception>, FailureResultDescription.ErrorCode> EXCEPTION_MAPPINGS;

    static {
        EXCEPTION_MAPPINGS = new HashMap<>();

        // ---- JDK: Validation and client errors ----
        EXCEPTION_MAPPINGS.put(IllegalArgumentException.class, FailureResultDescription.ErrorCode.VALIDATION_ERROR);
        EXCEPTION_MAPPINGS.put(NumberFormatException.class, FailureResultDescription.ErrorCode.VALIDATION_ERROR);
        EXCEPTION_MAPPINGS.put(IndexOutOfBoundsException.class, FailureResultDescription.ErrorCode.VALIDATION_ERROR);
        EXCEPTION_MAPPINGS.put(UnsupportedOperationException.class, FailureResultDescription.ErrorCode.VALIDATION_ERROR);
        EXCEPTION_MAPPINGS.put(IllegalStateException.class, FailureResultDescription.ErrorCode.BUSINESS_RULE_ERROR);

        // ---- JDK: Not found cases ----
        EXCEPTION_MAPPINGS.put(NoSuchElementException.class, FailureResultDescription.ErrorCode.NOT_FOUND);
        EXCEPTION_MAPPINGS.put(FileNotFoundException.class, FailureResultDescription.ErrorCode.NOT_FOUND);

        // ---- JDK: Network and I/O ----
        EXCEPTION_MAPPINGS.put(ConnectException.class, FailureResultDescription.ErrorCode.EXTERNAL_SERVICE_ERROR);
        EXCEPTION_MAPPINGS.put(SocketTimeoutException.class, FailureResultDescription.ErrorCode.EXTERNAL_SERVICE_ERROR);
        EXCEPTION_MAPPINGS.put(TimeoutException.class, FailureResultDescription.ErrorCode.EXTERNAL_SERVICE_ERROR);
        EXCEPTION_MAPPINGS.put(EOFException.class, FailureResultDescription.ErrorCode.EXTERNAL_SERVICE_ERROR);

        // ---- JDK: Security and configuration ----
        EXCEPTION_MAPPINGS.put(SecurityException.class, FailureResultDescription.ErrorCode.AUTHENTICATION_ERROR);
        EXCEPTION_MAPPINGS.put(NullPointerException.class, FailureResultDescription.ErrorCode.CONFIGURATION_ERROR);

        // ---- Spring Web/MVC: Validation errors ----
        EXCEPTION_MAPPINGS.put(MethodArgumentNotValidException.class, FailureResultDescription.ErrorCode.VALIDATION_ERROR);
        EXCEPTION_MAPPINGS.put(HttpMessageNotReadableException.class, FailureResultDescription.ErrorCode.VALIDATION_ERROR);
        EXCEPTION_MAPPINGS.put(MissingServletRequestParameterException.class, FailureResultDescription.ErrorCode.VALIDATION_ERROR);
        EXCEPTION_MAPPINGS.put(MissingRequestHeaderException.class, FailureResultDescription.ErrorCode.VALIDATION_ERROR);
        EXCEPTION_MAPPINGS.put(ServletRequestBindingException.class, FailureResultDescription.ErrorCode.VALIDATION_ERROR);
        EXCEPTION_MAPPINGS.put(HttpMediaTypeNotSupportedException.class, FailureResultDescription.ErrorCode.VALIDATION_ERROR);
        EXCEPTION_MAPPINGS.put(HttpMediaTypeNotAcceptableException.class, FailureResultDescription.ErrorCode.VALIDATION_ERROR);
        EXCEPTION_MAPPINGS.put(HttpRequestMethodNotSupportedException.class, FailureResultDescription.ErrorCode.VALIDATION_ERROR);

        // ---- Spring Web/MVC: Not found ----
        EXCEPTION_MAPPINGS.put(NoHandlerFoundException.class, FailureResultDescription.ErrorCode.NOT_FOUND);

        // ---- Spring Web reactive ----
        EXCEPTION_MAPPINGS.put(ServerWebInputException.class, FailureResultDescription.ErrorCode.VALIDATION_ERROR);

        // ---- Spring Data/JDBC: Database operations ----
        EXCEPTION_MAPPINGS.put(EmptyResultDataAccessException.class, FailureResultDescription.ErrorCode.NOT_FOUND);
        EXCEPTION_MAPPINGS.put(DataAccessException.class, FailureResultDescription.ErrorCode.DATABASE_ERROR);
        EXCEPTION_MAPPINGS.put(DataIntegrityViolationException.class, FailureResultDescription.ErrorCode.BUSINESS_RULE_ERROR);
        EXCEPTION_MAPPINGS.put(DuplicateKeyException.class, FailureResultDescription.ErrorCode.BUSINESS_RULE_ERROR);

        // ---- Spring HTTP Clients: External service calls ----
        EXCEPTION_MAPPINGS.put(HttpClientErrorException.class, FailureResultDescription.ErrorCode.EXTERNAL_SERVICE_ERROR);
        EXCEPTION_MAPPINGS.put(HttpServerErrorException.class, FailureResultDescription.ErrorCode.EXTERNAL_SERVICE_ERROR);
        EXCEPTION_MAPPINGS.put(ResourceAccessException.class, FailureResultDescription.ErrorCode.EXTERNAL_SERVICE_ERROR);

        // ---- Top-level fallbacks (should be last due to inheritance hierarchy) ----
        EXCEPTION_MAPPINGS.put(RuntimeException.class, FailureResultDescription.ErrorCode.UNKNOWN_ERROR);
        EXCEPTION_MAPPINGS.put(Exception.class, FailureResultDescription.ErrorCode.UNKNOWN_ERROR);
    }

    private ExceptionToErrorCodeMapper() {
        // Utility class - prevent instantiation
    }

    /**
     * Maps an exception to the appropriate error code.
     * Uses both exact type matching and inheritance hierarchy traversal.
     * This ensures we never have an unmapped exception.
     *
     * @param exception The exception to map
     * @return The corresponding ErrorCode, never null
     */
    public static FailureResultDescription.ErrorCode mapException(@Nullable Exception exception) {
        if (exception == null) {
            return FailureResultDescription.ErrorCode.UNKNOWN_ERROR;
        }

        // Special case: Spring's ResponseStatusException contains its own status
        if (exception instanceof ResponseStatusException rse) {
            return switch (rse.getStatusCode().value()) {
                case 400, 422 -> FailureResultDescription.ErrorCode.VALIDATION_ERROR;
                case 401 -> FailureResultDescription.ErrorCode.AUTHENTICATION_ERROR;
                case 403 -> FailureResultDescription.ErrorCode.AUTHORIZATION_ERROR;
                case 404 -> FailureResultDescription.ErrorCode.NOT_FOUND;
                case 409 -> FailureResultDescription.ErrorCode.BUSINESS_RULE_ERROR;
                case 502, 503, 504 -> FailureResultDescription.ErrorCode.EXTERNAL_SERVICE_ERROR;
                default -> FailureResultDescription.ErrorCode.UNKNOWN_ERROR;
            };
        }

        // Walk up the class hierarchy to find the most specific mapping
        Class<?> cls = exception.getClass();
        while (cls != null && cls != Object.class) {
            FailureResultDescription.ErrorCode errorCode = EXCEPTION_MAPPINGS.get(cls);
            if (errorCode != null) {
                return errorCode;
            }
            cls = cls.getSuperclass();
        }

        return FailureResultDescription.ErrorCode.UNKNOWN_ERROR;
    }

    /**
     * Creates a FailureResultDescription from an exception, automatically mapping
     * the exception type to the appropriate error code.
     *
     * @param message The human-readable error message
     * @param exception The underlying exception
     * @return A properly categorized FailureResultDescription
     */
    public static FailureResultDescription mapToFailure(String message, Exception exception) {
        FailureResultDescription.ErrorCode errorCode = mapException(exception);
        return new FailureResultDescription(errorCode, message, exception);
    }

    /**
     * Creates a FailureResultDescription from an exception using the exception's message.
     *
     * @param exception The exception to map
     * @return A FailureResultDescription with the exception's message and mapped error code
     */
    public static FailureResultDescription mapToFailure(Exception exception) {
        return mapToFailure(exception.getMessage(), exception);
    }
}
