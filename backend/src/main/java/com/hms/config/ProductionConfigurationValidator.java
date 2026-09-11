package com.hms.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Refuse to start a production instance that is configured unsafely.
 *
 * <p>{@code application.properties} ships working defaults so a developer can clone and run:
 * {@code jwt.secret} falls back to a placeholder that is committed to this repository, and
 * {@code cors.allowed.origins} falls back to {@code http://localhost:5173}. Both are correct for
 * development and catastrophic in production — the first lets anyone holding this public repo mint
 * a valid token for any hospital, and the second either breaks the real frontend or, if someone
 * "fixes" it by widening the origin, opens the API to a page served over plain HTTP.
 *
 * <p>Nothing checked either. The failure mode is silent: the application starts, serves traffic,
 * and looks healthy. This turns that into a startup that stops, which is the only outcome that
 * cannot be missed.
 *
 * <p>Production only, using the same profile test the security and websocket configuration
 * already use ({@code prod} or {@code production}). Development, test and staging are untouched
 * deliberately — staging runs against real infrastructure but is not the boundary this protects,
 * and breaking every developer's first run would get the check deleted rather than fixed.
 *
 * <p>Runs at construction rather than on {@code ApplicationReadyEvent} so the context fails to
 * refresh. A ready-event check runs after the server is already accepting requests, which for a
 * credential problem is too late to be worth much.
 *
 * <p>No message here contains a secret, only a description of what is wrong with it.
 */
@Component
public class ProductionConfigurationValidator {

    private static final Logger logger = LoggerFactory.getLogger(ProductionConfigurationValidator.class);

    /** The placeholder committed in application.properties. Public knowledge by definition. */
    static final String DEFAULT_JWT_SECRET = "YOUR_SECRET_KEY_HERE_MUST_BE_VERY_LONG_FOR_SECURITY";

    /**
     * HS256 keys shorter than this are rejected outright by the JJWT signer, and a shorter value
     * is a weak key regardless of what any library accepts.
     */
    static final int MIN_JWT_SECRET_BYTES = 32;

    private static final Set<String> WEAK_SECRETS = Set.of(
            "changeme", "change-me", "secret", "jwt_secret", "jwt-secret",
            "your-secret", "password", "test", "dev");

    private final Environment environment;
    private final String jwtSecret;
    private final String allowedOrigins;

    public ProductionConfigurationValidator(
            Environment environment,
            @Value("${jwt.secret:}") String jwtSecret,
            @Value("${cors.allowed.origins:}") String allowedOrigins) {
        this.environment = environment;
        this.jwtSecret = jwtSecret;
        this.allowedOrigins = allowedOrigins;
    }

    @PostConstruct
    public void validate() {
        if (!isProduction()) {
            return;
        }
        validateJwtSecret(jwtSecret);
        validateFrontendOrigins(allowedOrigins);
        logger.info("Production configuration validated: JWT secret and frontend origins are acceptable.");
    }

    /** Package-private so the rules can be tested without booting a production context. */
    static void validateJwtSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT_SECRET is not set. Production requires an explicit signing secret of at least "
                            + MIN_JWT_SECRET_BYTES + " characters.");
        }
        if (DEFAULT_JWT_SECRET.equals(secret)) {
            throw new IllegalStateException(
                    "JWT_SECRET is still the placeholder committed in application.properties. "
                            + "Anyone with the source can forge tokens for any hospital. Set a real secret.");
        }
        if (WEAK_SECRETS.contains(secret.trim().toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("JWT_SECRET is a well-known placeholder value. Set a real secret.");
        }
        if (secret.length() < MIN_JWT_SECRET_BYTES) {
            // The length is not a secret; the value is. Naming it helps the operator fix this.
            throw new IllegalStateException(
                    "JWT_SECRET is " + secret.length() + " characters; production requires at least "
                            + MIN_JWT_SECRET_BYTES + ".");
        }
    }

    /** Package-private for the same reason. */
    static void validateFrontendOrigins(String origins) {
        if (origins == null || origins.isBlank()) {
            throw new IllegalStateException(
                    "FRONTEND_URL is not set. Production requires an explicit HTTPS frontend origin.");
        }
        List<String> values = Arrays.stream(origins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (values.isEmpty()) {
            throw new IllegalStateException("FRONTEND_URL is not set. Production requires an HTTPS frontend origin.");
        }
        for (String origin : values) {
            String lower = origin.toLowerCase(Locale.ROOT);
            if ("*".equals(origin)) {
                throw new IllegalStateException(
                        "FRONTEND_URL is '*'. A wildcard origin lets any site call this API with credentials.");
            }
            if (!lower.startsWith("https://")) {
                throw new IllegalStateException(
                        "FRONTEND_URL origin '" + origin + "' is not HTTPS. Production must not accept a "
                                + "plain-HTTP origin: tokens would travel in clear text.");
            }
            if (isLocal(lower)) {
                throw new IllegalStateException(
                        "FRONTEND_URL origin '" + origin + "' points at the local machine. That is a "
                                + "development value and cannot be the production frontend.");
            }
        }
    }

    private static boolean isLocal(String lowerOrigin) {
        String host = lowerOrigin.substring("https://".length());
        int slash = host.indexOf('/');
        if (slash >= 0) host = host.substring(0, slash);
        int colon = host.indexOf(':');
        if (colon >= 0) host = host.substring(0, colon);
        return host.equals("localhost")
                || host.equals("127.0.0.1")
                || host.equals("0.0.0.0")
                || host.equals("::1")
                || host.endsWith(".local");
    }

    /** Same profile test SecurityConfig and WebSocketConfig already use. */
    private boolean isProduction() {
        return Arrays.stream(environment.getActiveProfiles())
                .anyMatch(p -> "prod".equalsIgnoreCase(p) || "production".equalsIgnoreCase(p));
    }
}
