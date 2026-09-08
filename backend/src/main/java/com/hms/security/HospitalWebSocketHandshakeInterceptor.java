package com.hms.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HospitalWebSocketHandshakeInterceptor - Enforces multi-tenant isolation,
 * authentication, and token verification during the WebSocket handshake.
 */
@Component
public class HospitalWebSocketHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(HospitalWebSocketHandshakeInterceptor.class);

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private com.hms.repository.UserRepository userRepository;

    @Autowired
    private com.hms.repository.HospitalRepository hospitalRepository;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) throws Exception {
        
        if (request instanceof ServletServerHttpRequest) {
            ServletServerHttpRequest servletRequest = (ServletServerHttpRequest) request;
            HttpServletRequest httpServletRequest = servletRequest.getServletRequest();
            
            // Extract token from query parameters
            String token = httpServletRequest.getParameter("token");
            if (token == null || token.trim().isEmpty()) {
                log.warn("Rejecting WebSocket handshake: token is missing or empty.");
                return false;
            }

            // Validate token
            if (!jwtUtil.validateToken(token)) {
                log.warn("Rejecting WebSocket handshake: invalid or expired token.");
                return false;
            }

            // Same revocation rules as REST. A socket opened with a revoked token would otherwise
            // be a bypass: it survives for the life of the connection and pushes live tenant data
            // to someone whose access was withdrawn.
            if (!sessionIsStillValid(token)) {
                log.warn("Rejecting WebSocket handshake: the session behind this token is no longer valid.");
                return false;
            }

            // Extract hospitalId from path
            Long pathHospitalId = getHospitalIdFromPath(request.getURI().getPath());
            if (pathHospitalId == null) {
                log.warn("Rejecting WebSocket handshake: invalid or missing hospitalId in path.");
                return false;
            }

            // Extract hospitalId from token
            Long tokenHospitalId = jwtUtil.extractHospitalId(token);
            String role = jwtUtil.extractRole(token);

            // Allow platform Super Admin, otherwise enforce strict tenant validation
            if ("SUPER_ADMIN".equals(role)) {
                log.info("Allowing Super Admin connection to hospital path ID: {}", pathHospitalId);
            } else {
                if (tokenHospitalId == null || !tokenHospitalId.equals(pathHospitalId)) {
                    log.warn("Rejecting WebSocket handshake: tenant ID mismatch. Path ID: {}, Token ID: {}", pathHospitalId, tokenHospitalId);
                    return false;
                }
            }

            // Store tenant details in session attributes for easy reference in handler
            attributes.put("hospitalId", pathHospitalId);
            attributes.put("email", jwtUtil.extractEmail(token));
            attributes.put("role", role);
            
            log.info("WebSocket handshake successful for user: {}, role: {}, hospitalId: {}", 
                     jwtUtil.extractEmail(token), role, pathHospitalId);
            return true;
        }
        
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // No post-handshake hooks required
    }

    /**
     * Mirrors {@link JwtAuthenticationFilter}'s check: the user must still exist and be active, the
     * token's session generation must still match, and a tenant user's hospital must still be
     * active. Fail-closed, for the same reasons.
     */
    private boolean sessionIsStillValid(String token) {
        try {
            Long userId = jwtUtil.extractUserId(token);
            Integer presented = jwtUtil.extractTokenVersion(token);
            if (userId == null || presented == null) {
                return false;
            }
            java.util.Optional<Integer> current = userRepository.findActiveTokenVersion(userId);
            if (current.isEmpty() || !current.get().equals(presented)) {
                return false;
            }
            Long hospitalId = jwtUtil.extractHospitalId(token);
            return hospitalId == null || hospitalRepository.isActiveTenant(hospitalId);
        } catch (Exception e) {
            log.warn("WebSocket session revalidation failed; rejecting the handshake: {}", e.getMessage());
            return false;
        }
    }

    private Long getHospitalIdFromPath(String path) {
        try {
            String[] parts = path.split("/");
            return Long.parseLong(parts[parts.length - 1]);
        } catch (Exception e) {
            return null;
        }
    }
}
