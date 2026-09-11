package com.hms.exception;

import org.springframework.http.HttpStatus;

/**
 * The canonical HMS error taxonomy. Every error response carries exactly one of these codes,
 * and the code — not the human message — is what a client may branch on.
 *
 * <p>Adding a value here is a change to a published contract: update
 * {@code docs/HMS_ERROR_CONTRACT.md} in the same commit.
 */
public enum ErrorCode {

    /** The request was malformed or failed field/business validation. */
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),

    /** No usable credentials: absent, expired or invalid token. The only code that may log a user out. */
    AUTHENTICATION_ERROR(HttpStatus.UNAUTHORIZED),

    /** Authenticated, but this principal may not perform this action. Never logs a user out. */
    AUTHORIZATION_ERROR(HttpStatus.FORBIDDEN),

    /**
     * The resource does not exist — or belongs to another tenant, which the API deliberately
     * reports identically. See {@code docs/HMS_ERROR_CONTRACT.md} §"Tenant isolation".
     */
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND),

    /** The HTTP verb is not supported by this route. */
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),

    /**
     * The request conflicts with current state: a duplicate key, a lost optimistic-lock race,
     * a lock that could not be acquired, or a business precondition that has since changed.
     * Retryable by a human, not by the client automatically.
     */
    CONFLICT(HttpStatus.CONFLICT),

    /** Too many requests from this caller. */
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),

    /**
     * The unit of work could not be committed. Distinct from UNEXPECTED_ERROR because it
     * carries a specific guarantee: nothing was persisted, so the caller may safely retry.
     */
    TRANSACTION_FAILURE(HttpStatus.INTERNAL_SERVER_ERROR),

    /** An unhandled server fault. The client is told nothing beyond the request id. */
    UNEXPECTED_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
