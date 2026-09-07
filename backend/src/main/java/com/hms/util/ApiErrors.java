package com.hms.util;

import com.hms.dto.ApiResponse;
import com.hms.exception.ResourceNotFoundException;
import com.hms.exception.UnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Maps an exception caught at a controller boundary to a safe client response.
 *
 * Typed, developer-authored exceptions keep their (user-safe) message and a meaningful status:
 * IllegalArgumentException → 400, ResourceNotFoundException → 404, UnauthorizedException → 401.
 * Anything else is unexpected — its full detail is logged server-side and the client gets a
 * generic 500 with no stack trace, internal path or raw database message. This mirrors what
 * GlobalExceptionHandler does for uncaught exceptions, so controllers that catch broadly stay
 * consistent and never leak internals.
 */
public final class ApiErrors {

    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

    private ApiErrors() {
    }

    public static ResponseEntity<Object> handle(Exception e) {
        if (e instanceof IllegalArgumentException) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(e.getMessage()));
        }
        if (e instanceof ResourceNotFoundException) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.getMessage()));
        }
        if (e instanceof UnauthorizedException) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error(e.getMessage()));
        }
        log.error("Unhandled error at controller boundary", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("An unexpected error occurred. Please try again or contact support."));
    }
}
