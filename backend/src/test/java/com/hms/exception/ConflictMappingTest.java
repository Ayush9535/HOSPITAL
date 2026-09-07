package com.hms.exception;

import com.hms.dto.ApiErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A conflict is not a server fault.
 *
 * <p>ConflictException exists so the bed and theatre acquisition paths can say "someone else
 * got there first" — a request that was well-formed and would have succeeded a moment ago.
 * Nothing mapped it, so it fell through the advice chain and surfaced as a 500: the caller was
 * told the server broke, and monitoring counted an ordinary race as an outage.
 *
 * <p>Scope is deliberately one code path. The other handlers still answer in the older
 * ApiResponse shape and the last test pins that, so the inconsistency is a recorded decision
 * rather than something discovered later. Converting them is the wider error-contract change.
 */
class ConflictMappingTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void aConflictIs409() {
        ResponseEntity<ApiErrorResponse> res =
                handler.handleConflict(new ConflictException("That theatre is already in use by another case"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void theBodyCarriesTheCanonicalCodeAndTheThrownMessage() {
        ApiErrorResponse body =
                handler.handleConflict(new ConflictException("OT theatre is busy or has no available bed")).getBody();

        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(body.success()).isFalse();
        // error and message carry the same text: `error` is what the ~136 existing frontend
        // call sites already read, `message` is what new code should read.
        assertThat(body.error()).isEqualTo("OT theatre is busy or has no available bed");
        assertThat(body.message()).isEqualTo("OT theatre is busy or has no available bed");
    }

    @Test
    void theTaxonomyPinsConflictTo409() {
        assertThat(ErrorCode.CONFLICT.status()).isEqualTo(HttpStatus.CONFLICT);
    }

    /**
     * Outside a request there is no correlation id, and the body simply omits it
     * (@JsonInclude NON_NULL) rather than failing. currentId() is the only reason this slice
     * touches CorrelationIdFilter at all.
     */
    @Test
    void aMissingCorrelationIdIsOmittedRatherThanFatal() {
        ApiErrorResponse body = handler.handleConflict(new ConflictException("boom")).getBody();

        assertThat(body).isNotNull();
        assertThat(body.requestId()).isNull();
    }

    /**
     * The remaining inconsistency, still deliberate: 409 and 404 answer in the canonical body,
     * everything else does not. 400 is the witness. If a later change converts it as a side
     * effect rather than as a decision, this test says so.
     */
    @Test
    void theUnconvertedHandlersStillAnswerInTheHeadShape() {
        ResponseEntity<?> badRequest = handler.handleIllegalArgument(new IllegalArgumentException("bad"));

        assertThat(badRequest.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badRequest.getBody()).isNotInstanceOf(ApiErrorResponse.class);
    }
}
