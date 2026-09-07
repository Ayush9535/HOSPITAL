package com.hms.exception;

import com.hms.filter.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Writes the canonical error body for failures that happen in the servlet filter chain,
 * before Spring MVC and therefore before {@link GlobalExceptionHandler} can see them:
 * an unauthenticated request rejected by Spring Security, a URL-level authorization
 * refusal, and rate limiting.
 *
 * <p>Without this those three produced Tomcat's HTML error page or a hand-rolled fragment,
 * so a client parsing errors needed a special case for exactly the responses it is most
 * likely to hit. The contract in {@code docs/HMS_ERROR_CONTRACT.md} has no exceptions.
 */
public final class SecurityErrorResponder {

    private SecurityErrorResponder() {
    }

    public static void write(HttpServletResponse response, ErrorCode code, String message)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(code.status().value());
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        String requestId = CorrelationIdFilter.currentId();
        StringBuilder json = new StringBuilder(160);
        json.append("{\"success\":false")
            .append(",\"code\":\"").append(code.name()).append('"')
            .append(",\"error\":\"").append(escape(message)).append('"')
            .append(",\"message\":\"").append(escape(message)).append('"');
        if (requestId != null) {
            json.append(",\"requestId\":\"").append(escape(requestId)).append('"');
        }
        json.append('}');
        response.getWriter().write(json.toString());
    }

    /** These messages are constants today, but a broken quote here would break every client. */
    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ");
    }
}
