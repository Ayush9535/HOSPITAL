package com.hms.config;

import com.hms.filter.RateLimitFilter;
import com.hms.security.JwtAuthenticationFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * SecurityConfig - Spring Security configuration
 * 
 * This configuration:
 * 1. Defines public endpoints (login endpoints)
 * 2. Protects platform and hospital endpoints
 * 3. Configures JWT authentication filter
 * 4. Enables CORS for frontend communication
 * 5. Disables session management (stateless JWT)
 * 
 * @author HMS Team
 * @version Phase-1
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {
    private static final String HOSPITAL_ADMIN = "HOSPITAL_ADMIN";
    private static final String DOCTOR = "DOCTOR";
    private static final String RECEPTIONIST = "RECEPTIONIST";
    private static final String PHARMACIST = "PHARMACIST";


    @Autowired
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Autowired
    private RateLimitFilter rateLimitFilter;

    @Value("${cors.allowed.origins}")
    private String allowedOrigins;

    @Autowired private Environment environment;

    /**
     * Configure HTTP security
     * 
     * @param http HttpSecurity object
     * @return SecurityFilterChain
     * @throws Exception if configuration fails
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Disable CSRF (not needed for stateless JWT)
                .csrf(csrf -> csrf.disable())

                // Enable CORS
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                // Configure authorization rules
                .authorizeHttpRequests(auth -> auth
                        // Public endpoints - no authentication required
                        .requestMatchers("/platform/login", "/login").permitAll()
                        .requestMatchers("/api/public/health").permitAll()
                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/health/liveness",
                                "/actuator/health/readiness",
                                "/actuator/info")
                        .permitAll()

                        // Platform endpoints - only Super Admin
                        .requestMatchers("/platform/**").hasRole("SUPER_ADMIN")

                        // WebSocket endpoints - authenticated standard HMS roles & Super Admin
                        .requestMatchers("/ws/**").hasAnyRole(HOSPITAL_ADMIN, DOCTOR, RECEPTIONIST, PHARMACIST, "NURSE", "NURSE_INCHARGE", "SUPER_ADMIN", "OT_INCHARGE")

                        // Module namespaces - only standard HMS roles allowed.
                        // /hospital/** = hospital tenants, /clinic/** = clinic tenants,
                        // /pharmacy/** = standalone pharmacy tenants (ERP + shared admin endpoints).
                        // NURSE is hospital-only: it is authorized on /hospital/** but never on
                        // /clinic/** or /pharmacy/**, keeping the Nurse role out of those tenants.
                        .requestMatchers("/hospital/**")
                        .hasAnyRole(HOSPITAL_ADMIN, DOCTOR, RECEPTIONIST, PHARMACIST, "NURSE", "NURSE_INCHARGE", "OT_INCHARGE")
                        .requestMatchers("/clinic/**", "/pharmacy/**")
                        .hasAnyRole(HOSPITAL_ADMIN, DOCTOR, RECEPTIONIST, PHARMACIST)
                        // All other requests require authentication
                        .anyRequest().authenticated())

                // Return 401 (not 403) for unauthenticated requests — Http403ForbiddenEntryPoint is Spring's default.
                // Both handlers emit the canonical error body (docs/HMS_ERROR_CONTRACT.md); sendError()
                // used to hand the client Tomcat's HTML page, which no API consumer can parse.
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) ->
                                com.hms.exception.SecurityErrorResponder.write(res,
                                        com.hms.exception.ErrorCode.AUTHENTICATION_ERROR,
                                        "Authentication required"))
                        .accessDeniedHandler((req, res, ex) ->
                                com.hms.exception.SecurityErrorResponder.write(res,
                                        com.hms.exception.ErrorCode.AUTHORIZATION_ERROR,
                                        "Access Denied")))

                // Stateless session management (JWT-based)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // RateLimitFilter → JwtAuthenticationFilter → UsernamePasswordAuthenticationFilter
                // Both custom filters reference the same built-in anchor. Java's stable sort
                // preserves insertion order, so rateLimitFilter executes first.
                .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Configure CORS to allow frontend requests
     * 
     * @return CorsConfigurationSource
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // Dynamically split by comma, trim, and strip trailing slashes
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .map(o -> o.replaceAll("/++$", ""))
                .filter(o -> !o.isEmpty())
                .collect(Collectors.toList());

        if (!isProduction()) {
            if (!origins.contains("http://localhost:5173")) origins.add("http://localhost:5173");
            if (!origins.contains("http://localhost:3000")) origins.add("http://localhost:3000");
        }

        configuration.setAllowedOrigins(origins);

        // Allow all HTTP methods
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));

        // Allow all headers
        configuration.setAllowedHeaders(Arrays.asList("*"));

        // Allow credentials (cookies, authorization headers)
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);

        return source;
    }

    /**
     * Password encoder bean for encrypting passwords
     * Uses BCrypt hashing algorithm
     * 
     * @return PasswordEncoder
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * HMS authenticates through its login endpoints and JWT filter, not Spring's form/basic-login
     * user store. Supplying this fail-closed service prevents Boot from creating and logging an
     * unrelated generated in-memory password at startup.
     */
    @Bean
    public UserDetailsService userDetailsService() {
        return username -> {
            throw new UsernameNotFoundException("No Spring Security user store is configured");
        };
    }

    private boolean isProduction() {
        return Arrays.stream(environment.getActiveProfiles())
                .anyMatch(p -> "prod".equalsIgnoreCase(p) || "production".equalsIgnoreCase(p));
    }
}
