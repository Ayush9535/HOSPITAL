package com.hms.exception;

/**
 * The caller is authenticated but is not permitted to perform this action — a role or
 * feature restriction, not an identity problem.
 *
 * <p>Maps to 403. Use this instead of {@link UnauthorizedException} whenever the caller's
 * identity is established: a 401 tells the frontend the session is dead and logs the user
 * out, which is wrong and destructive for an ordinary permission refusal.
 *
 * <p>Do <em>not</em> use this for cross-tenant access. A resource owned by another hospital
 * must be reported as {@link ResourceNotFoundException} so its existence is not confirmed.
 */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
