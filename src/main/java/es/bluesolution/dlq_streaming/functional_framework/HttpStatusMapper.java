package es.bluesolution.dlq_streaming.functional_framework;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerErrorException;
import org.springframework.web.server.ServerWebInputException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.io.EOFException;
import java.io.FileNotFoundException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeoutException;

/**
 * Maps exceptions to appropriate HTTP status codes following established conventions.
 * This mapper provides comprehensive coverage of JDK, Spring, and JDBC exceptions.
 *
 * The mapping strategy follows Railway-Oriented Programming principles by converting
 * exceptions from external libraries into appropriate HTTP responses without throwing
 * exceptions within our domain logic.
 */
public final class HttpStatusMapper {

    private static final Map<Class<? extends Throwable>, HttpStatus> EXCEPTION_TO_STATUS_MAP;

    static {
        EXCEPTION_TO_STATUS_MAP = new HashMap<>();

        // ---- JDK: Client errors (400 Bad Request) ----
        EXCEPTION_TO_STATUS_MAP.put(IllegalArgumentException.class, HttpStatus.BAD_REQUEST);
        EXCEPTION_TO_STATUS_MAP.put(NumberFormatException.class, HttpStatus.BAD_REQUEST);
        EXCEPTION_TO_STATUS_MAP.put(IndexOutOfBoundsException.class, HttpStatus.BAD_REQUEST);
        EXCEPTION_TO_STATUS_MAP.put(UnsupportedOperationException.class, HttpStatus.BAD_REQUEST);
        EXCEPTION_TO_STATUS_MAP.put(EOFException.class, HttpStatus.BAD_REQUEST);

        // ---- JDK: Conflict states (409 Conflict) ----
        EXCEPTION_TO_STATUS_MAP.put(IllegalStateException.class, HttpStatus.CONFLICT);

        // ---- JDK: Not found (404 Not Found) ----
        EXCEPTION_TO_STATUS_MAP.put(NoSuchElementException.class, HttpStatus.NOT_FOUND);
        EXCEPTION_TO_STATUS_MAP.put(FileNotFoundException.class, HttpStatus.NOT_FOUND);

        // ---- JDK: Forbidden (403 Forbidden) ----
        EXCEPTION_TO_STATUS_MAP.put(SecurityException.class, HttpStatus.FORBIDDEN);

        // ---- JDK: Service unavailable/timeout ----
        EXCEPTION_TO_STATUS_MAP.put(ConnectException.class, HttpStatus.SERVICE_UNAVAILABLE);
        EXCEPTION_TO_STATUS_MAP.put(SocketTimeoutException.class, HttpStatus.GATEWAY_TIMEOUT);
        EXCEPTION_TO_STATUS_MAP.put(TimeoutException.class, HttpStatus.REQUEST_TIMEOUT);

        // ---- JDK: Internal server error (500) ----
        EXCEPTION_TO_STATUS_MAP.put(NullPointerException.class, HttpStatus.INTERNAL_SERVER_ERROR);

        // ---- Spring Web/MVC: Validation errors (400 Bad Request) ----
        EXCEPTION_TO_STATUS_MAP.put(HttpMessageNotReadableException.class, HttpStatus.BAD_REQUEST);
        EXCEPTION_TO_STATUS_MAP.put(MissingServletRequestParameterException.class, HttpStatus.BAD_REQUEST);
        EXCEPTION_TO_STATUS_MAP.put(MissingRequestHeaderException.class, HttpStatus.BAD_REQUEST);
        EXCEPTION_TO_STATUS_MAP.put(ServletRequestBindingException.class, HttpStatus.BAD_REQUEST);

        // ---- Spring Web/MVC: Validation errors (422 Unprocessable Entity) ----
        EXCEPTION_TO_STATUS_MAP.put(MethodArgumentNotValidException.class, HttpStatus.valueOf(422));

        // ---- Spring Web/MVC: Not found (404) ----
        EXCEPTION_TO_STATUS_MAP.put(NoHandlerFoundException.class, HttpStatus.NOT_FOUND);

        // ---- Spring Web/MVC: Method not allowed (405) ----
        EXCEPTION_TO_STATUS_MAP.put(HttpRequestMethodNotSupportedException.class, HttpStatus.METHOD_NOT_ALLOWED);

        // ---- Spring Web/MVC: Media type issues ----
        EXCEPTION_TO_STATUS_MAP.put(HttpMediaTypeNotAcceptableException.class, HttpStatus.NOT_ACCEPTABLE);
        EXCEPTION_TO_STATUS_MAP.put(HttpMediaTypeNotSupportedException.class, HttpStatus.UNSUPPORTED_MEDIA_TYPE);

        // ---- Spring Web reactive ----
        EXCEPTION_TO_STATUS_MAP.put(ServerWebInputException.class, HttpStatus.BAD_REQUEST);
        EXCEPTION_TO_STATUS_MAP.put(ServerErrorException.class, HttpStatus.INTERNAL_SERVER_ERROR);

        // ---- Spring Data/JDBC: Not found and conflict ----
        EXCEPTION_TO_STATUS_MAP.put(EmptyResultDataAccessException.class, HttpStatus.NOT_FOUND);
        EXCEPTION_TO_STATUS_MAP.put(DataIntegrityViolationException.class, HttpStatus.CONFLICT);
        EXCEPTION_TO_STATUS_MAP.put(DuplicateKeyException.class, HttpStatus.CONFLICT);

        // ---- Top-level fallbacks (should be last due to inheritance hierarchy) ----
        EXCEPTION_TO_STATUS_MAP.put(RuntimeException.class, HttpStatus.INTERNAL_SERVER_ERROR);
        EXCEPTION_TO_STATUS_MAP.put(Exception.class, HttpStatus.INTERNAL_SERVER_ERROR);
        EXCEPTION_TO_STATUS_MAP.put(Throwable.class, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private HttpStatusMapper() {
        // Utility class - prevent instantiation
    }

    /**
     * Maps a FailureResultDescription to appropriate HTTP status.
     *
     * This method provides comprehensive mapping by:
     * 1. Special handling for ResponseStatusException (extracts embedded status)
     * 2. Exception-specific mapping using inheritance hierarchy traversal
     * 3. Fallback to error code mapping
     * 4. Final fallback to 500 Internal Server Error
     *
     * @param failure The failure description to map
     * @return The appropriate HttpStatus, never null
     */
    public static HttpStatus mapToHttpStatus(FailureResultDescription failure) {
        // Special case: Spring's ResponseStatusException contains its own status
        if (failure.exception() instanceof ResponseStatusException rse) {
            return HttpStatus.valueOf(rse.getStatusCode().value());
        }

        // If we have an exception, try to map it directly (more specific than error code)
        if (failure.exception() != null) {
            HttpStatus status = mapExceptionToStatus(failure.exception());
            if (status != null) {
                return status;
            }
        }

        // Fall back to error code mapping
        return mapErrorCodeToStatus(failure.code());
    }

    /**
     * Maps an exception directly to HTTP status using inheritance hierarchy.
     */
    private static HttpStatus mapExceptionToStatus(Exception exception) {
        Class<?> cls = exception.getClass();
        while (cls != null && cls != Object.class) {
            HttpStatus status = EXCEPTION_TO_STATUS_MAP.get(cls);
            if (status != null) {
                return status;
            }
            cls = cls.getSuperclass();
        }
        return null; // Let error code mapping handle it
    }

    /**
     * Maps Railway framework error codes to HTTP status codes.
     * MANDATORY: Exhaustive switch ensures all error codes are mapped.
     */
    private static HttpStatus mapErrorCodeToStatus(FailureResultDescription.ErrorCode errorCode) {
        return switch (errorCode) {
            // Client-side errors (4xx HTTP range)
            case VALIDATION_ERROR -> HttpStatus.BAD_REQUEST;           // 400
            case AUTHENTICATION_ERROR -> HttpStatus.UNAUTHORIZED;      // 401
            case AUTHORIZATION_ERROR -> HttpStatus.FORBIDDEN;          // 403
            case NOT_FOUND -> HttpStatus.NOT_FOUND;                    // 404
            case BUSINESS_RULE_ERROR -> HttpStatus.CONFLICT;           // 409
            case RATE_LIMIT_ERROR -> HttpStatus.TOO_MANY_REQUESTS;     // 429

            // Server-side errors (5xx HTTP range)
            case TECHNICAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;  // 500
            case DATABASE_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;   // 500
            case CONFIGURATION_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR; // 500
            case EXTERNAL_SERVICE_ERROR -> HttpStatus.BAD_GATEWAY;     // 502
            case SERVICE_UNAVAILABLE_ERROR -> HttpStatus.SERVICE_UNAVAILABLE; // 503
            case TIMEOUT_ERROR -> HttpStatus.GATEWAY_TIMEOUT;          // 504
            case UNKNOWN_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;    // 500
        };
    }
}