package com.hms.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * HealthCheckFilter - short-circuits requests to /api/public/health.
 *
 * Purpose:
 * - Bypass controller advices, response wrappers, tenant resolution, and any
 *   service/database calls that may run later in the filter/handler chain.
 * - Ensures a stable, plain-text 200 response for anonymous uptime checks
 *   performed by Render or UptimeRobot.
 *
 * This filter is intentionally minimal and only handles GET /api/public/health.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HealthCheckFilter extends OncePerRequestFilter {

    private static final String HEALTH_PATH = "/api/public/health";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // Only handle exact GET health probe; let other requests continue.
        if ("GET".equalsIgnoreCase(request.getMethod()) && HEALTH_PATH.equals(request.getRequestURI())) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("text/plain");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            // Helpful for external probes; exact body required by uptime checks.
            response.getWriter().write("HMS Backend Running");
            return; // short-circuit: do not call the rest of the filter chain
        }

        filterChain.doFilter(request, response);
    }
}
