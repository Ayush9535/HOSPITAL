package com.hms.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.GenericFilter;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(1)
public class CorrelationIdFilter extends GenericFilter {

    private static final String HEADER = "X-Correlation-ID";
    public static final String MDC_KEY = "correlationId";

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        String correlationId = request.getHeader(HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        MDC.put("correlationId", correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove("correlationId");
        }
    }

    /** The current request's id, or null outside a request. Used to stamp error bodies. */
    public static String currentId() {
        return MDC.get(MDC_KEY);
    }
}
