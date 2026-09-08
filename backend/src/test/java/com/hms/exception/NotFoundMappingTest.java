package com.hms.exception;

import com.hms.dto.ApiErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A missing resource -- and, deliberately, a resource owned by another tenant -- answers 404 in
 * the canonical body.
 *
 * <p>The status was already 404; what was missing was the machine-readable {@code code}. Callers
 * had to branch on prose, and the tenant-isolation tests could assert only the status, not that
 * the refusal was the intended "not found" rather than some other 404 on the way to the handler.
 *
 * <p>ApiErrorResponse is a superset of the previous ApiResponse error shape -- {@code success}
 * and {@code error} keep their exact meaning -- so the ~136 frontend sites reading
 * {@code err.response.data.error} are unaffected by this conversion.
 */
class NotFoundMappingTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void aMissingResourceIs404() {
        ResponseEntity<ApiErrorResponse> res =
                handler.handleNotFound(new ResourceNotFoundException("Patient not found"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void theBodyCarriesTheCanonicalCodeAndTheThrownMessage() {
        ApiErrorResponse body =
                handler.handleNotFound(new ResourceNotFoundException("Ward not found")).getBody();

        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(body.success()).isFalse();
        assertThat(body.error()).isEqualTo("Ward not found");
        assertThat(body.message()).isEqualTo("Ward not found");
    }

    /** The exact string the tenant-isolation suite asserts on. */
    @Test
    void theSerialisedCodeIsTheOneCallersMatchOn() {
        assertThat(ErrorCode.RESOURCE_NOT_FOUND.name()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(ErrorCode.RESOURCE_NOT_FOUND.status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aMissingCorrelationIdIsOmittedRatherThanFatal() {
        ApiErrorResponse body =
                handler.handleNotFound(new ResourceNotFoundException("gone")).getBody();

        assertThat(body).isNotNull();
        assertThat(body.requestId()).isNull();
    }
}
