package com.hms.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JwtAuthenticationFilter - Filter to validate JWT tokens and set security
 * context
 * 
 * This filter:
 * 1. Extracts JWT token from Authorization header
 * 2. Validates the token
 * 3. Extracts user information (userId, role, hospitalId)
 * 4. Sets Spring Security context for authorization
 * 
 * The hospitalId from the token is stored in the security context
 * and used by services to filter data for multi-tenant isolation.
 * 
 * @author HMS Team
 * @version Phase-1
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private com.hms.repository.UserRepository userRepository;
    // required=false: this filter is constructed in every @WebMvcTest slice (it is part of
    // the security filter chain, not something those tests mock), and none of them load the
    // service layer. Those tests use @WithMockUser and never send a real token, so
    // resolvePermissions() -- the only caller -- never runs in that context anyway.
    @Autowired(required = false)
    private com.hms.service.hospital.ot.OtPermissionService otPermissionService;

    @Autowired
    private com.hms.repository.HospitalRepository hospitalRepository;

    /** Only /ws/** may carry its credential in the query string. */
    private boolean isWebSocketHandshake(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) {
            return false;
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return path.equals("/ws") || path.startsWith("/ws/");
    }

    /**
     * Whether the session behind this token still exists.
     *
     * <p>A signature and an expiry only prove the token was minted by us and is not yet stale. They
     * say nothing about what has happened since: the tenant may have been blocked, the subscription
     * may have expired, the account may have been deactivated, the password may have been reset.
     * None of those could reach a signed-in user before, because this filter read nothing but the
     * token — so revoking access took effect only when the token expired, up to twelve hours later.
     *
     * <p>Fail-closed by construction. Every branch returns false unless it positively establishes
     * that the session is still good, so a missing user, a missing tenant, or a lookup that throws
     * all deny rather than admit. Note the contrast with ModuleAccessAspect, which falls back to
     * the token's claim when the hospital row is gone: that is the right trade for an entitlement
     * check and the wrong one for authentication.
     */
    private boolean sessionIsStillValid(String token) {
        try {
            Long userId = jwtUtil.extractUserId(token);
            if (userId == null) {
                return false;
            }

            // Absent for tokens minted before this mechanism existed, which is treated as a
            // mismatch: those sessions end at deploy. Deliberate, and agreed — the alternative
            // (accepting a missing claim) would leave a revocation-proof token valid for 12 hours
            // after the very release that closes the hole.
            Integer presented = jwtUtil.extractTokenVersion(token);
            if (presented == null) {
                return false;
            }

            // Empty for a user that no longer exists OR has been deactivated. Both deny.
            java.util.Optional<Integer> current = userRepository.findActiveTokenVersion(userId);
            if (current.isEmpty() || !current.get().equals(presented)) {
                return false;
            }

            // Super Admin has no tenant, so there is no tenant to be blocked.
            Long hospitalId = jwtUtil.extractHospitalId(token);
            return hospitalId == null || hospitalRepository.isActiveTenant(hospitalId);
        } catch (Exception e) {
            logger.warn("Session revalidation failed; denying the request: " + e.getMessage());
            return false;
        }
    }

    /**
     * Filter method to validate JWT and set security context
     * 
     * @param request     HTTP request
     * @param response    HTTP response
     * @param filterChain Filter chain
     * @throws ServletException if servlet error occurs
     * @throws IOException      if I/O error occurs
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");
        String token = null;

        // Check if Authorization header exists and starts with "Bearer "
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            // Extract token (remove "Bearer " prefix)
            token = authHeader.substring(7);
        } else if (isWebSocketHandshake(request)) {
            // A browser cannot set headers on a WebSocket handshake, so the token has to travel in
            // the query string for /ws/**. Nothing else may: a query-string credential ends up in
            // access logs, browser history and Referer headers, and this token is a bearer
            // credential valid for hours. Every other caller — including every PDF and file
            // download, which all use axios with responseType 'blob' — sends the Authorization
            // header, so restricting this costs no caller anything.
            token = request.getParameter("token");
        }

        if (token != null) {
            try {
                // Validate token
                if (jwtUtil.validateToken(token) && sessionIsStillValid(token)) {
                    // Extract user information from token
                    String email = jwtUtil.extractEmail(token);
                    Long userId = jwtUtil.extractUserId(token);
                    String role = jwtUtil.extractRole(token);
                    Long hospitalId = jwtUtil.extractHospitalId(token);

                    // ROLE_<role> is unchanged: Clinic and Pharmacy authorize on it and must
                    // never see a permission. OT permissions are ADDED as plain authorities,
                    // so @PreAuthorize("hasAuthority('OT_SCHEDULE')") works with no custom
                    // PermissionEvaluator.
                    java.util.List<org.springframework.security.core.GrantedAuthority> authorities =
                            new java.util.ArrayList<>();
                    authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
                    for (String permission : resolvePermissions(token, role, hospitalId)) {
                        authorities.add(new SimpleGrantedAuthority(permission));
                    }

                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                            email, null, authorities);

                    // Store additional details (userId, hospitalId, modules, branchId) in authentication
                    java.util.List<String> modules = jwtUtil.extractModules(token);
                    Long branchId = jwtUtil.extractBranchId(token);

                    // Multi Pharmacy branch impersonation for Hospital Admin
                    String branchHeader = request.getHeader("X-Branch-ID");
                    if (branchHeader != null && !branchHeader.trim().isEmpty() && "HOSPITAL_ADMIN".equals(role)) {
                        try {
                            branchId = Long.parseLong(branchHeader.trim());
                        } catch (NumberFormatException e) {
                            // ignore malformed branch header
                        }
                    }

                    UserAuthenticationDetails details = new UserAuthenticationDetails(userId, role, hospitalId,
                            modules);
                    details.setBranchId(branchId);
                    details.setHospitalType(jwtUtil.extractHospitalType(token));
                    authentication.setDetails(details);

                    // Set authentication in security context
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            } catch (Exception e) {
                // Token validation failed - continue without authentication
                logger.error("JWT validation failed: " + e.getMessage());
            }
        }

        // Continue filter chain
        filterChain.doFilter(request, response);
    }

    /**
     * Permissions are minted into the token at login (HospitalAuthService reads the hospital's
     * persisted matrix at that moment). A token issued before the claim existed carries none;
     * falling straight to OtPermissions.defaultsFor(role) for that legacy population would
     * ignore any matrix the hospital has since saved -- authorization would silently regress to
     * the built-in defaults for exactly the hospitals that customised them. Ask the
     * persisted-aware resolver instead, which itself falls back to the defaults when the
     * hospital has never customised (identical result for the common case, correct result for
     * the customised one). An explicitly empty claim on a current-format token means the
     * hospital granted this role nothing -- honour it as-is, no fallback.
     *
     * This does not make every request re-fetch a live session: current-format tokens (the
     * normal case after login or re-login) always carry a claim and never reach this fallback.
     */
    private java.util.List<String> resolvePermissions(String token, String role, Long hospitalId) {
        java.util.List<String> claim = jwtUtil.extractPermissions(token);
        if (claim != null) return claim;
        if (hospitalId == null || otPermissionService == null) {
            return new java.util.ArrayList<>(OtPermissions.defaultsFor(role));
        }
        return new java.util.ArrayList<>(otPermissionService.effectiveFor(hospitalId, role));
    }
}
