package com.hms.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hms.exception.ErrorCode;

import java.util.Map;

/**
 * The one error body the HMS API returns. Defined in {@code docs/HMS_ERROR_CONTRACT.md}.
 *
 * <p>Deliberately a superset of the older {@link ApiResponse} error shape rather than a
 * replacement: {@code success} and {@code error} keep their exact previous meaning, so the
 * ~136 frontend call sites that read {@code err.response.data.error} as a plain string, and
 * the {@code errors} map that validation already returned, all continue to work untouched.
 * The additions — {@code code}, {@code requestId}, {@code details} — are what new code
 * should read.
 *
 * <p>{@code details}/{@code errors} carry the same field→message map under both names for
 * that reason; {@code message} mirrors {@code error} for callers that read either.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(
        boolean success,
        ErrorCode code,
        String error,
        String message,
        Map<String, String> details,
        Map<String, String> errors,
        String requestId
) {
    public static ApiErrorResponse of(ErrorCode code, String message, String requestId) {
        return new ApiErrorResponse(false, code, message, message, null, null, requestId);
    }

    public static ApiErrorResponse of(ErrorCode code, String message,
                                      Map<String, String> fieldErrors, String requestId) {
        return new ApiErrorResponse(false, code, message, message, fieldErrors, fieldErrors, requestId);
    }
}
