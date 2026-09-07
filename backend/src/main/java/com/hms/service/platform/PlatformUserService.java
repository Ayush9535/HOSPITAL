package com.hms.service.platform;

import com.hms.dto.UserSummaryDTO;
import com.hms.entity.User;
import com.hms.entity.AuditLog;
import com.hms.exception.ResourceNotFoundException;
import com.hms.repository.UserRepository;
import com.hms.repository.AuditLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.context.SecurityContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;
import java.util.HashMap;

/**
 * PlatformUserService - Service for Super Admin user management
 * 
 * Handles listing all users and user-specific actions.
 */
@Service
public class PlatformUserService {

    private static final Logger logger = LoggerFactory.getLogger(PlatformUserService.class);

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AuditLogRepository auditLogRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    public Page<UserSummaryDTO> getAllUsers(String role, String hospitalId, String search, Pageable pageable) {
        return userRepository.findAllSummary("HOSPITAL_ADMIN", hospitalId, search, pageable);
    }

    @Transactional
    public Map<String, String> resetUserPassword(String idStr) {
        // Try finding by Public ID first
        User user = userRepository.findByPublicId(idStr).orElse(null);

        // If not found, try as Database ID (Self-Healing Fallback)
        if (user == null) {
            try {
                Long dbId = Long.parseLong(idStr);
                user = userRepository.findById(dbId).orElse(null);

                if (user != null) {
                    // Check and Fix Public ID if missing
                    if (user.getPublicId() == null || user.getPublicId().trim().isEmpty()
                            || "null".equals(user.getPublicId())) {
                        user.setPublicId(java.util.UUID.randomUUID().toString());
                        // Save happens automatically at end of transaction
                    }
                }
            } catch (NumberFormatException e) {
                // Not a valid Long, so it was an invalid Public ID
            }
        }

        if (user == null) {
            throw new ResourceNotFoundException("User not found with ID: " + idStr);
        }

        String newPassword = java.util.UUID.randomUUID().toString().substring(0, 8);
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        logAction("PASSWORD_RESET", "Reset password for user: " + user.getEmail() + " (" + user.getRole() + ")");

        Map<String, String> result = new HashMap<>();
        result.put("email", user.getEmail());
        result.put("password", newPassword);
        return result;
    }

    private void logAction(String action, String details) {
        try {
            String currentUsername = SecurityContextHolder.getContext().getAuthentication().getName();
            AuditLog log = new AuditLog();
            log.setAction(action);
            log.setDetails(details);
            log.setPerformedBy(currentUsername);
            auditLogRepository.save(log);
        } catch (Exception e) {
            logger.error("Failed to save audit log: {}", e.getMessage(), e);
        }
    }

}
