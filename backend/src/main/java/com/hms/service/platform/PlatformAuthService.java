package com.hms.service.platform;

import com.hms.exception.ForbiddenException;

import com.hms.dto.LoginRequest;
import com.hms.dto.LoginResponse;
import com.hms.entity.User;
import com.hms.exception.UnauthorizedException;
import com.hms.repository.UserRepository;
import com.hms.security.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.hms.util.LogSanitizer;

/**
 * PlatformAuthService - Authentication service for Super Admin
 * 
 * This service handles Super Admin login.
 * Super Admin users have:
 * - role = SUPER_ADMIN
 * - hospital_id = NULL
 * 
 * @author HMS Team
 * @version Phase-1
 */
@Service
public class PlatformAuthService {

    private static final Logger logger = LoggerFactory.getLogger(PlatformAuthService.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    /**
     * Authenticate Super Admin and generate JWT token
     * 
     * @param request LoginRequest containing email and password
     * @return LoginResponse with JWT token and user details
     * @throws RuntimeException if credentials are invalid or user is not Super
     *                          Admin
     */
    public LoginResponse login(LoginRequest request) {
        logger.info("Platform login attempt for email: {}", LogSanitizer.clean(request.getEmail()));

        // Find user by email — same message as wrong password to prevent user enumeration
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> {
                    logger.warn("Login failed - user not found: {}", LogSanitizer.clean(request.getEmail()));
                    return new UnauthorizedException("Invalid credentials");
                });

        // Verify password
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            logger.warn("Login failed - invalid password for user: {}", LogSanitizer.clean(request.getEmail()));
            throw new UnauthorizedException("Invalid credentials");
        }

        logger.debug("Password verified for user: {}", LogSanitizer.clean(request.getEmail()));

        // Verify user is Super Admin
        if (!"SUPER_ADMIN".equals(user.getRole())) {
            logger.warn("Login failed - non-Super Admin tried to login via platform endpoint: {} (role: {})",
                    LogSanitizer.clean(request.getEmail()), user.getRole());
            throw new ForbiddenException("Access denied. Super Admin only.");
        }

        // Verify hospital_id is null (Super Admin should not belong to any hospital)
        if (user.getHospitalId() != null) {
            logger.error("Login failed - Super Admin has non-null hospital_id: {} (hospital_id: {})",
                    LogSanitizer.clean(request.getEmail()), user.getHospitalId());
            throw new UnauthorizedException("Invalid Super Admin account");
        }

        // Verify user account is active (handle null as active for backward compatibility)
        if (user.getIsActive() != null && !user.getIsActive()) {
            logger.warn("Login failed - Super Admin account is inactive: {}", LogSanitizer.clean(request.getEmail()));
            throw new UnauthorizedException("User account is inactive. Please contact administrator.");
        }

        logger.info("Login successful for Super Admin: {}", LogSanitizer.clean(request.getEmail()));

        // Generate JWT token (no hospital_id for super admin)
        String token = jwtUtil.generateToken(
                user.getId(),
                user.getEmail(),
                user.getRole(),
                null, // No hospitalId for super admin
                null, // No modules for super admin
                null, // no branch
                null, // no tenant type
                null, // no OT permissions
                user.getTokenVersion());

        // Create response
        LoginResponse response = new LoginResponse();
        response.setToken(token);
        response.setUserId(user.getId());
        response.setName(user.getName());
        response.setEmail(user.getEmail());
        response.setRole(user.getRole());
        response.setHospitalId(null);
        response.setHospitalName(null);

        return response;
    }
}
