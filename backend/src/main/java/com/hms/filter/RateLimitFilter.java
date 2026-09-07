package com.hms.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tiered, configurable request rate limiting (token-bucket via bucket4j).
 *
 * Three tiers, each with its own configurable capacity + refill window:
 *   - AUTH   (paths ending in /login): strictest. Enforced BOTH per-IP and per-account
 *            (the login email), so neither a single IP nor a single targeted account can be
 *            brute-forced. The account bucket has a longer window than the IP bucket.
 *   - PUBLIC (/api/public/**): moderate, per-IP.
 *   - AUTHENTICATED (everything else): loosest, per-IP — normal signed-in app usage.
 *
 * All thresholds are overridable via application.properties (ratelimit.*). Set ratelimit.enabled
 * to false to disable entirely (e.g. for load tests).
 *
 * Client IP honours X-Forwarded-For (first hop) so the limit is per real client behind the nginx
 * reverse proxy, not the proxy's own address.
 *
 * Buckets are held in an in-memory map — correct for a single instance. For a multi-instance
 * deployment move the store to Redis (bucket4j-redis) so limits are shared; the tier logic here
 * is unchanged by that swap.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(RateLimitFilter.class);

    @Value("${ratelimit.enabled:true}")
    private boolean enabled;

    // AUTH tier — per-IP
    @Value("${ratelimit.auth.ip.capacity:10}")
    private int authIpCapacity;
    @Value("${ratelimit.auth.ip.refill-minutes:1}")
    private int authIpRefillMinutes;

    // AUTH tier — per-account
    @Value("${ratelimit.auth.account.capacity:5}")
    private int authAccountCapacity;
    @Value("${ratelimit.auth.account.refill-minutes:15}")
    private int authAccountRefillMinutes;

    // PUBLIC tier — per-IP
    @Value("${ratelimit.public.capacity:60}")
    private int publicCapacity;
    @Value("${ratelimit.public.refill-minutes:1}")
    private int publicRefillMinutes;

    // AUTHENTICATED tier — per-IP
    @Value("${ratelimit.authenticated.capacity:300}")
    private int authenticatedCapacity;
    @Value("${ratelimit.authenticated.refill-minutes:1}")
    private int authenticatedRefillMinutes;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private Bucket bucketFor(String key, int capacity, int refillMinutes) {
        return buckets.computeIfAbsent(key, k -> Bucket.builder()
                .addLimit(Bandwidth.classic(capacity,
                        Refill.intervally(capacity, Duration.ofMinutes(refillMinutes))))
                .build());
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /** Best-effort extraction of the login email from a JSON body; null if absent/unparseable. */
    private String loginAccount(byte[] body) {
        if (body == null || body.length == 0) return null;
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode email = node.get("email");
            if (email != null && email.isTextual() && !email.asText().isBlank()) {
                return email.asText().trim().toLowerCase();
            }
        } catch (Exception ignored) {
            // Not JSON / no email — fall back to IP-only limiting.
        }
        return null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!enabled) {
            chain.doFilter(request, response);
            return;
        }

        String uri = request.getRequestURI();
        String ip = clientIp(request);

        // ---- AUTH tier: per-IP AND per-account ----
        if (uri.endsWith("/login")) {
            HttpServletRequest downstream = request;
            String account = null;
            if ("POST".equalsIgnoreCase(request.getMethod())) {
                try {
                    CachedBodyHttpServletRequest cached = new CachedBodyHttpServletRequest(request);
                    account = loginAccount(cached.getBody());
                    downstream = cached; // controller reads the buffered body
                } catch (IOException e) {
                    logger.warn("Rate limiter could not buffer login body; falling back to IP-only", e);
                }
            }
            Bucket ipBucket = bucketFor("auth:ip:" + ip, authIpCapacity, authIpRefillMinutes);
            Bucket accountBucket = account == null ? null
                    : bucketFor("auth:acct:" + account, authAccountCapacity, authAccountRefillMinutes);
            boolean ipOk = ipBucket.tryConsume(1);
            boolean accountOk = accountBucket == null || accountBucket.tryConsume(1);
            if (ipOk && accountOk) {
                chain.doFilter(downstream, response);
                // Refund on success: these buckets throttle BRUTE FORCE, and a successful
                // authentication is not an attack. Charging it locked real users out -- the
                // account bucket is 5 per 15 minutes, so a sixth legitimate sign-in inside that
                // window (switching roles, re-login after token expiry, a second device) returned
                // 429 and stayed locked for up to the whole window. Hospitals also NAT an entire
                // site behind one address, so the per-IP bucket caught shift changes, where many
                // different staff sign in successfully within a minute.
                //
                // Failed attempts still consume and are never refunded, so what this filter
                // exists to stop is unchanged: password guessing is throttled exactly as before.
                // Traffic from an already-authenticated session is bounded by the AUTHENTICATED
                // tier below, not by these buckets.
                if (isSuccessful(response)) {
                    ipBucket.addTokens(1);
                    if (accountBucket != null) {
                        accountBucket.addTokens(1);
                    }
                }
            } else {
                reject(response, "Too many login attempts. Please try again later.");
            }
            return;
        }

        // ---- PUBLIC tier ----
        if (uri.startsWith("/api/public/")) {
            if (bucketFor("public:" + ip, publicCapacity, publicRefillMinutes).tryConsume(1)) {
                chain.doFilter(request, response);
            } else {
                reject(response, "Too many requests. Please slow down.");
            }
            return;
        }

        // ---- AUTHENTICATED tier (default) ----
        if (bucketFor("app:" + ip, authenticatedCapacity, authenticatedRefillMinutes).tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            reject(response, "Too many requests. Please slow down.");
        }
    }

    /**
     * Did the login actually succeed? Only 2xx counts -- 401/403 (bad credentials, blocked or
     * inactive account) and 5xx must keep their token consumed so repeated guessing stays
     * throttled.
     */
    private boolean isSuccessful(HttpServletResponse response) {
        int status = response.getStatus();
        return status >= 200 && status < 300;
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
