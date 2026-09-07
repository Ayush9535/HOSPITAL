package com.hms.exception;

/**
 * The request cannot be applied to the current state: the bed was taken, the surgery already
 * started, the record already exists. Maps to 409.
 *
 * <p>Distinct from {@link IllegalArgumentException} (400): the request was well-formed and
 * would have been valid a moment ago. That distinction is what lets the frontend say "someone
 * else changed this — reload" rather than "you typed something wrong".
 */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }

    public ConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
