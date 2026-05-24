package es.bluesolution.dlq_streaming.functional_framework;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.FileNotFoundException;
import java.net.ConnectException;
import java.util.NoSuchElementException;

import static es.bluesolution.dlq_streaming.functional_framework.FailureResultDescription.ErrorCode.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ResultFailures}, {@link ExceptionToErrorCodeMapper},
 * {@link HttpStatusMapper}, and {@link ErrorResponse}.
 *
 * <p>These classes form the error-handling infrastructure of the Railway framework and
 * are tested here together because they compose a coherent error-classification pipeline.</p>
 */
class RailwayFrameworkTest {

    // ── ResultFailures ────────────────────────────────────────────────────────

    @Test
    void resultFailuresFromExceptionWithMessageUsesMapper() {
        var ex = new IllegalArgumentException("bad arg");
        Result<String> result = ResultFailures.fromException("mapped error", ex);

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().code()).isEqualTo(VALIDATION_ERROR);
        assertThat(result.failure().message()).isEqualTo("mapped error");
    }

    @Test
    void resultFailuresFromExceptionOnlyUsesExceptionMessage() {
        var ex = new ConnectException("connection refused");
        Result<String> result = ResultFailures.fromException(ex);

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().code()).isEqualTo(EXTERNAL_SERVICE_ERROR);
    }

    @Test
    void resultFailuresValidationErrorCreatesValidationFailure() {
        Result<String> result = ResultFailures.validationError("name is required");

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().code()).isEqualTo(VALIDATION_ERROR);
        assertThat(result.failure().message()).isEqualTo("name is required");
    }

    @Test
    void resultFailuresBusinessRuleErrorCreatesBusinessFailure() {
        Result<String> result = ResultFailures.businessRuleError("invariant violated");

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().code()).isEqualTo(BUSINESS_RULE_ERROR);
    }

    @Test
    void resultFailuresNotFoundWithResourceAndIdFormatsMessage() {
        Result<String> result = ResultFailures.notFound("Order", "abc-123");

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().code()).isEqualTo(NOT_FOUND);
        assertThat(result.failure().message()).contains("Order").contains("abc-123");
    }

    @Test
    void resultFailuresNotFoundWithMessageOnly() {
        Result<String> result = ResultFailures.notFound("generic not found");

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure().code()).isEqualTo(NOT_FOUND);
    }

    @Test
    void resultFailuresAuthenticationError() {
        Result<String> result = ResultFailures.authenticationError("token expired");

        assertThat(result.failure().code()).isEqualTo(AUTHENTICATION_ERROR);
    }

    @Test
    void resultFailuresConfigurationError() {
        Result<String> result = ResultFailures.configurationError("datasource missing");

        assertThat(result.failure().code()).isEqualTo(CONFIGURATION_ERROR);
    }

    @Test
    void resultFailuresExternalServiceError() {
        Result<String> result = ResultFailures.externalServiceError("ExternalApi", "fetchUser");

        assertThat(result.failure().code()).isEqualTo(EXTERNAL_SERVICE_ERROR);
        assertThat(result.failure().message()).contains("ExternalApi").contains("fetchUser");
    }

    @Test
    void resultFailuresDatabaseError() {
        Result<String> result = ResultFailures.databaseError("INSERT failed");

        assertThat(result.failure().code()).isEqualTo(DATABASE_ERROR);
        assertThat(result.failure().message()).contains("INSERT failed");
    }

    // ── ExceptionToErrorCodeMapper ────────────────────────────────────────────

    @Test
    void mapperReturnUnknownErrorForNullException() {
        var code = ExceptionToErrorCodeMapper.mapException(null);

        assertThat(code).isEqualTo(UNKNOWN_ERROR);
    }

    @Test
    void mapperMapsIllegalArgumentToValidationError() {
        assertThat(ExceptionToErrorCodeMapper.mapException(new IllegalArgumentException("x")))
                .isEqualTo(VALIDATION_ERROR);
    }

    @Test
    void mapperMapsNoSuchElementToNotFound() {
        assertThat(ExceptionToErrorCodeMapper.mapException(new NoSuchElementException()))
                .isEqualTo(NOT_FOUND);
    }

    @Test
    void mapperMapsFileNotFoundToNotFound() {
        assertThat(ExceptionToErrorCodeMapper.mapException(new FileNotFoundException("f")))
                .isEqualTo(NOT_FOUND);
    }

    @Test
    void mapperMapsConnectExceptionToExternalServiceError() {
        assertThat(ExceptionToErrorCodeMapper.mapException(new ConnectException()))
                .isEqualTo(EXTERNAL_SERVICE_ERROR);
    }

    @Test
    void mapperMapsSecurityExceptionToAuthenticationError() {
        assertThat(ExceptionToErrorCodeMapper.mapException(new SecurityException("forbidden")))
                .isEqualTo(AUTHENTICATION_ERROR);
    }

    @Test
    void mapperMapsIllegalStateToBusinessRuleError() {
        assertThat(ExceptionToErrorCodeMapper.mapException(new IllegalStateException("bad state")))
                .isEqualTo(BUSINESS_RULE_ERROR);
    }

    @Test
    void mapperMapsNullPointerToConfigurationError() {
        assertThat(ExceptionToErrorCodeMapper.mapException(new NullPointerException()))
                .isEqualTo(CONFIGURATION_ERROR);
    }

    @Test
    void mapperMapsResponseStatusException400ToValidationError() {
        var ex = new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST);
        assertThat(ExceptionToErrorCodeMapper.mapException(ex)).isEqualTo(VALIDATION_ERROR);
    }

    @Test
    void mapperMapsResponseStatusException422ToValidationError() {
        var ex = new ResponseStatusException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(ExceptionToErrorCodeMapper.mapException(ex)).isEqualTo(VALIDATION_ERROR);
    }

    @Test
    void mapperMapsResponseStatusException401ToAuthenticationError() {
        var ex = new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED);
        assertThat(ExceptionToErrorCodeMapper.mapException(ex)).isEqualTo(AUTHENTICATION_ERROR);
    }

    @Test
    void mapperMapsResponseStatusException403ToAuthorizationError() {
        var ex = new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN);
        assertThat(ExceptionToErrorCodeMapper.mapException(ex)).isEqualTo(AUTHORIZATION_ERROR);
    }

    @Test
    void mapperMapsResponseStatusException404ToNotFound() {
        var ex = new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
        assertThat(ExceptionToErrorCodeMapper.mapException(ex)).isEqualTo(NOT_FOUND);
    }

    @Test
    void mapperMapsResponseStatusException409ToBusinessRule() {
        var ex = new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT);
        assertThat(ExceptionToErrorCodeMapper.mapException(ex)).isEqualTo(BUSINESS_RULE_ERROR);
    }

    @Test
    void mapperMapsResponseStatusException503ToExternalServiceError() {
        var ex = new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(ExceptionToErrorCodeMapper.mapException(ex)).isEqualTo(EXTERNAL_SERVICE_ERROR);
    }

    @Test
    void mapperMapsResponseStatusException502ToExternalServiceError() {
        var ex = new ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY);
        assertThat(ExceptionToErrorCodeMapper.mapException(ex)).isEqualTo(EXTERNAL_SERVICE_ERROR);
    }

    @Test
    void mapperMapsResponseStatusException504ToExternalServiceError() {
        var ex = new ResponseStatusException(org.springframework.http.HttpStatus.GATEWAY_TIMEOUT);
        assertThat(ExceptionToErrorCodeMapper.mapException(ex)).isEqualTo(EXTERNAL_SERVICE_ERROR);
    }

    @Test
    void mapperMapsResponseStatusExceptionDefaultToUnknownError() {
        var ex = new ResponseStatusException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(ExceptionToErrorCodeMapper.mapException(ex)).isEqualTo(UNKNOWN_ERROR);
    }

    @Test
    void mapperMapsUnknownExceptionTypeViaInheritance() {
        // Custom exception with no direct mapping — inherits from RuntimeException
        var ex = new RuntimeException("custom");
        assertThat(ExceptionToErrorCodeMapper.mapException(ex)).isEqualTo(UNKNOWN_ERROR);
    }

    @Test
    void mapToFailureWithMessageAndExceptionPreservesMessage() {
        var failure = ExceptionToErrorCodeMapper.mapToFailure("ctx message", new IllegalArgumentException("arg"));

        assertThat(failure.message()).isEqualTo("ctx message");
        assertThat(failure.code()).isEqualTo(VALIDATION_ERROR);
    }

    @Test
    void mapToFailureFromExceptionOnlyUsesExceptionMessage() {
        var ex = new IllegalArgumentException("exception message");
        var failure = ExceptionToErrorCodeMapper.mapToFailure(ex);

        assertThat(failure.message()).isEqualTo("exception message");
        assertThat(failure.code()).isEqualTo(VALIDATION_ERROR);
    }

    // ── HttpStatusMapper ──────────────────────────────────────────────────────

    @Test
    void httpMapperMapsValidationErrorTo400() {
        var failure = new FailureResultDescription(VALIDATION_ERROR, "bad");
        assertThat(HttpStatusMapper.mapToHttpStatus(failure)).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void httpMapperMapsNotFoundErrorCodeTo404() {
        var failure = new FailureResultDescription(NOT_FOUND, "not found");
        assertThat(HttpStatusMapper.mapToHttpStatus(failure)).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void httpMapperMapsAuthErrorTo401() {
        var failure = new FailureResultDescription(AUTHENTICATION_ERROR, "unauth");
        assertThat(HttpStatusMapper.mapToHttpStatus(failure)).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void httpMapperMapsAuthorizationErrorTo403() {
        var failure = new FailureResultDescription(AUTHORIZATION_ERROR, "forbidden");
        assertThat(HttpStatusMapper.mapToHttpStatus(failure)).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void httpMapperMapsBusinessRuleErrorTo409() {
        var failure = new FailureResultDescription(BUSINESS_RULE_ERROR, "conflict");
        assertThat(HttpStatusMapper.mapToHttpStatus(failure)).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void httpMapperMapsDatabaseErrorTo500() {
        var failure = new FailureResultDescription(DATABASE_ERROR, "db error");
        assertThat(HttpStatusMapper.mapToHttpStatus(failure)).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void httpMapperMapsServiceUnavailableTo503() {
        var failure = new FailureResultDescription(SERVICE_UNAVAILABLE_ERROR, "svc unavailable");
        assertThat(HttpStatusMapper.mapToHttpStatus(failure)).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void httpMapperMapsExternalServiceErrorTo502() {
        var failure = new FailureResultDescription(EXTERNAL_SERVICE_ERROR, "gateway");
        assertThat(HttpStatusMapper.mapToHttpStatus(failure)).isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    void httpMapperMapsUnknownErrorTo500() {
        var failure = new FailureResultDescription(UNKNOWN_ERROR, "unknown");
        assertThat(HttpStatusMapper.mapToHttpStatus(failure)).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void httpMapperUsesResponseStatusExceptionEmbeddedStatus() {
        var rse = new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "not found");
        var failure = new FailureResultDescription(NOT_FOUND, "not found", rse);
        assertThat(HttpStatusMapper.mapToHttpStatus(failure)).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void httpMapperUsesExceptionTypeWhenExceptionPresent() {
        // ConnectException → SERVICE_UNAVAILABLE (via exception mapping)
        var ex = new ConnectException("refused");
        var failure = new FailureResultDescription(UNKNOWN_ERROR, "conn failed", ex);
        assertThat(HttpStatusMapper.mapToHttpStatus(failure)).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void httpMapperMapsConfigurationErrorTo500() {
        var failure = new FailureResultDescription(CONFIGURATION_ERROR, "missing config");
        assertThat(HttpStatusMapper.mapToHttpStatus(failure)).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void httpMapperMapsTechnicalErrorTo500() {
        var failure = new FailureResultDescription(TECHNICAL_ERROR, "tech error");
        assertThat(HttpStatusMapper.mapToHttpStatus(failure)).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void httpMapperMapsRateLimitTo429() {
        var failure = new FailureResultDescription(RATE_LIMIT_ERROR, "too many requests");
        assertThat(HttpStatusMapper.mapToHttpStatus(failure)).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void httpMapperMapsTimeoutErrorTo504() {
        var failure = new FailureResultDescription(TIMEOUT_ERROR, "timeout");
        assertThat(HttpStatusMapper.mapToHttpStatus(failure)).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
    }

    // ── ErrorResponse ─────────────────────────────────────────────────────────

    @Test
    void errorResponseOfSetsCodeAndMessageAndTimestamp() {
        var response = ErrorResponse.of("VALIDATION_ERROR", "name required");

        assertThat(response.code()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.message()).isEqualTo("name required");
        assertThat(response.timestamp()).isNotNull();
        assertThat(response.stackTrace()).isNull();
        assertThat(response.exceptionType()).isNull();
    }

    @Test
    void errorResponseOfWithStackTraceSetsStackTrace() {
        var response = ErrorResponse.ofWithStackTrace("DB_ERROR", "query failed", "at line 42");

        assertThat(response.code()).isEqualTo("DB_ERROR");
        assertThat(response.stackTrace()).isEqualTo("at line 42");
    }

    @Test
    void errorResponseOfWithExceptionSetsExceptionType() {
        var response = ErrorResponse.ofWithException("UNKNOWN_ERROR", "oops", new IllegalStateException("bad"));

        assertThat(response.exceptionType()).isEqualTo("IllegalStateException");
    }

    @Test
    void errorResponseOfWithNullExceptionSetsNullExceptionType() {
        var response = ErrorResponse.ofWithException("UNKNOWN_ERROR", "oops", null);

        assertThat(response.exceptionType()).isNull();
    }
}


