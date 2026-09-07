package com.hms.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JwtUtil - Utility class for JWT token generation and validation
 * 
 * This class handles:
 * - Generating JWT tokens with user information (userId, role, hospitalId)
 * - Validating JWT tokens
 * - Extracting claims from JWT tokens
 * 
 * The JWT token contains:
 * - userId: User's unique identifier
 * - role: User's role (SUPER_ADMIN, HOSPITAL_ADMIN, DOCTOR)
 * - hospitalId: Hospital ID for multi-tenant isolation (null for Super Admin)
 * 
 * @author HMS Team
 * @version Phase-1
 */
@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private Long expiration;

    /**
     * Generate a secret key from the configured secret string
     * 
     * @return SecretKey for JWT signing
     */
    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Generate JWT token for a user
     * 
     * @param userId     User's ID
     * @param email      User's email
     * @param role       User's role (SUPER_ADMIN, HOSPITAL_ADMIN, DOCTOR)
     * @param hospitalId Hospital ID (null for Super Admin)
     * @return JWT token string
     */
    public String generateToken(Long userId, String email, String role, Long hospitalId,
            java.util.List<String> modules) {
        return generateToken(userId, email, role, hospitalId, modules, null, null);
    }

    public String generateToken(Long userId, String email, String role, Long hospitalId,
            java.util.List<String> modules, Long branchId) {
        return generateToken(userId, email, role, hospitalId, modules, branchId, null);
    }

    public String generateToken(Long userId, String email, String role, Long hospitalId,
            java.util.List<String> modules, Long branchId, String hospitalType) {
        return generateToken(userId, email, role, hospitalId, modules, branchId, hospitalType, null);
    }

    public String generateToken(Long userId, String email, String role, Long hospitalId,
            java.util.List<String> modules, Long branchId, String hospitalType,
            java.util.Collection<String> permissions) {
        return generateToken(userId, email, role, hospitalId, modules, branchId, hospitalType,
                permissions, 0);
    }

    /**
     * @param tokenVersion the user's session generation at login. The filter refuses a token whose
     *                     value no longer matches the user row, which is what makes a password
     *                     reset or role change end sessions already in flight.
     */
    public String generateToken(Long userId, String email, String role, Long hospitalId,
            java.util.List<String> modules, Long branchId, String hospitalType,
            java.util.Collection<String> permissions, Integer tokenVersion) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("tokenVersion", tokenVersion == null ? 0 : tokenVersion);
        claims.put("userId", userId);
        claims.put("role", role);
        claims.put("hospitalId", hospitalId); // null for Super Admin
        claims.put("modules", modules);
        claims.put("branchId", branchId); // Multi Pharmacy branch login; null otherwise
        claims.put("hospitalType", hospitalType); // HOSPITAL | CLINIC | PHARMACY; null for Super Admin
        // OT permissions, minted at login. Absent for non-hospital tenants; a token issued
        // before this claim existed falls back to the role defaults in the filter.
        claims.put("permissions", permissions == null ? null : new java.util.ArrayList<>(permissions));

        return Jwts.builder()
                .claims(claims)
                .subject(email)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * Extract all claims from a JWT token
     * 
     * @param token JWT token string
     * @return Claims object containing all token data
     */
    public Claims extractClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Extract email (subject) from JWT token
     * 
     * @param token JWT token string
     * @return Email address
     */
    public String extractEmail(String token) {
        return extractClaims(token).getSubject();
    }

    /**
     * Extract user ID from JWT token
     * 
     * @param token JWT token string
     * @return User ID
     */
    public Long extractUserId(String token) {
        return extractClaims(token).get("userId", Long.class);
    }

    /**
     * Extract role from JWT token
     * 
     * @param token JWT token string
     * @return Role (SUPER_ADMIN, HOSPITAL_ADMIN, DOCTOR)
     */
    public String extractRole(String token) {
        return extractClaims(token).get("role", String.class);
    }

    /**
     * Extract hospital ID from JWT token
     * Returns null for Super Admin users
     * 
     * @param token JWT token string
     * @return Hospital ID or null
     */
    public Long extractHospitalId(String token) {
        Object hospitalId = extractClaims(token).get("hospitalId");
        return hospitalId != null ? ((Number) hospitalId).longValue() : null;
    }

    /**
     * OT permissions carried by the token. Null (not empty) when the claim is absent,
     * which distinguishes "a token minted before permissions existed" from "this user
     * was granted nothing" -- the filter treats the two differently.
     */
    @SuppressWarnings("unchecked")
    public java.util.List<String> extractPermissions(String token) {
        Object permissions = extractClaims(token).get("permissions");
        return permissions instanceof java.util.List ? (java.util.List<String>) permissions : null;
    }

    /**
     * Tenant type of the logged-in session: HOSPITAL, CLINIC or PHARMACY.
     * Null for Super Admin, and null for tokens issued before this claim existed —
     * callers must treat null as "unknown", never as a particular type.
     */
    public String extractHospitalType(String token) {
        Object type = extractClaims(token).get("hospitalType");
        return type != null ? type.toString() : null;
    }

    /**
     * Extract the Multi Pharmacy branch ID from the token. Null for non-branch users.
     */
    public Long extractBranchId(String token) {
        Object branchId = extractClaims(token).get("branchId");
        return branchId != null ? ((Number) branchId).longValue() : null;
    }

    /**
     * Extract enabled modules from JWT token
     * 
     * @param token JWT token string
     * @return List of module names
     */
    /**
     * The session generation stamped into this token, or null when the claim is absent.
     *
     * <p>Null means the token predates the mechanism. The filter treats that as a mismatch: those
     * sessions end at deploy, which is the agreed compatibility strategy rather than an accident.
     */
    public Integer extractTokenVersion(String token) {
        Object v = extractClaims(token).get("tokenVersion");
        return v instanceof Number n ? n.intValue() : null;
    }

    public java.util.List<String> extractModules(String token) {
        Object modules = extractClaims(token).get("modules");
        if (modules instanceof java.util.List) {
            return (java.util.List<String>) modules;
        }
        return new java.util.ArrayList<>();
    }

    /**
     * Validate JWT token
     * Checks if token is expired
     * 
     * @param token JWT token string
     * @return true if token is valid, false otherwise
     */
    public boolean validateToken(String token) {
        try {
            Claims claims = extractClaims(token);
            return !claims.getExpiration().before(new Date());
        } catch (Exception e) {
            return false;
        }
    }
}
