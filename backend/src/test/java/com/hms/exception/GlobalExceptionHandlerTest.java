package com.hms.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The status code a caller sees for a missing resource is a contract, not a detail.
 *
 * Before this was fixed, 140 "not found" conditions threw a bare RuntimeException and so
 * fell through to the catch-all 500 handler. That made a perfectly ordinary miss — and,
 * importantly, every cross-tenant access attempt, which is deliberately indistinguishable
 * from a miss — look like a server crash to the client and to monitoring.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void resourceNotFoundMapsTo404() {
        ResponseEntity<?> res = handler.handleNotFound(
                new ResourceNotFoundException("Patient not found"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void forbiddenExceptionMapsTo403InsteadOfTheGeneric500() {
        ResponseEntity<?> res = handler.handleForbidden(
                new ForbiddenException("Nurse login is disabled for this hospital"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(((com.hms.dto.ApiResponse<?>) res.getBody()).error())
                .contains("Nurse login is disabled");
    }

    @Test
    void aBareRuntimeExceptionIsStillA500() {
        // The catch-all must keep reporting genuine faults as 500 — the fix narrowed what
        // reaches it, it did not turn real server errors into 404s.
        ResponseEntity<?> res = handler.handleRuntimeException(new RuntimeException("boom"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void aMalformedPathVariableIsA400NotA500() {
        // e.g. a UUID passed to a handler that declares Long id.
        MethodArgumentTypeMismatchException ex =
                new MethodArgumentTypeMismatchException("abc", Long.class, "id", null, null);

        ResponseEntity<?> res = handler.handleTypeMismatch(ex);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aMissingRequiredParamIsA400NotA500() {
        var ex = new org.springframework.web.bind.MissingServletRequestParameterException("status", "String");

        ResponseEntity<?> res = handler.handleMissingParam(ex);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aWrongHttpMethodIsA405NotA500() {
        var ex = new org.springframework.web.HttpRequestMethodNotSupportedException("POST");

        ResponseEntity<?> res = handler.handleMethodNotSupported(ex);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    void anUnreadableBodyIsA400NotA500() {
        ResponseEntity<?> res = handler.handleUnreadableBody();

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void databaseConstraintViolationsMapToSafeConflictWithoutLeakingDatabaseDetails() {
        ResponseEntity<?> res = handler.handleDataIntegrityViolation(
                new DataIntegrityViolationException("Duplicate entry 'secret-bed-code' for key 'uk_bed'"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var body = (com.hms.dto.ApiErrorResponse) res.getBody();
        assertThat(body.code()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(body.error()).doesNotContain("secret-bed-code").doesNotContain("uk_bed");
    }
}
