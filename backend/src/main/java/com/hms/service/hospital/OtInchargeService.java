package com.hms.service.hospital;
import com.hms.util.LogSanitizer;

import com.hms.entity.OtIncharge;
import com.hms.entity.User;
import com.hms.repository.OtInchargeProfileRepository;
import com.hms.repository.UserRepository;
import com.hms.security.SecurityContextHelper;
import com.hms.security.HospitalWebSocketHandler;
import com.hms.exception.ResourceNotFoundException;
import com.hms.exception.UnauthorizedException;
import com.hms.entity.AuditLog;
import com.hms.repository.AuditLogRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.context.SecurityContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class OtInchargeService {
    private static final String REFRESH_DATA_MESSAGE = "{\"type\":\"REFRESH_DATA\"}";
    private static final String OT_INCHARGE_NOT_FOUND = "OT Incharge not found";


    private static final Logger logger = LoggerFactory.getLogger(OtInchargeService.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OtInchargeProfileRepository otInchargeProfileRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private SecurityContextHelper securityHelper;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private HospitalWebSocketHandler webSocketHandler;

    @Transactional
    public User createOtIncharge(String name, String email, String password) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            throw new UnauthorizedException("Hospital ID not found in context");
        }

        if (userRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("Email already exists");
        }

        User otIncharge = new User();
        otIncharge.setName(name);
        otIncharge.setEmail(email);
        otIncharge.setPassword(passwordEncoder.encode(password));
        otIncharge.setRole("OT_INCHARGE");
        otIncharge.setHospitalId(hospitalId);
        otIncharge.setIsActive(true);

        User saved = userRepository.save(otIncharge);

        // Create OT Incharge profile record
        OtIncharge otInchargeProfile = new OtIncharge();
        otInchargeProfile.setHospitalId(hospitalId);
        otInchargeProfile.setName(name);
        otInchargeProfile.setEmail(email);
        otInchargeProfile.setPhone("");
        otInchargeProfile.setIsActive(true);
        otInchargeProfileRepository.save(otInchargeProfile);

        logger.info("Created OT Incharge: {} for hospital: {}", LogSanitizer.clean(email), hospitalId);
        logAction("OT_INCHARGE_CREATED", "Created OT Incharge: " + email, null, hospitalId);

        try {
            webSocketHandler.broadcast(hospitalId, REFRESH_DATA_MESSAGE);
        } catch (Exception e) {
            logger.warn("Failed to broadcast WebSocket refresh after OT Incharge creation", e);
        }

        return saved;
    }

    public org.springframework.data.domain.Page<User> getAllOtIncharges(String search,
            org.springframework.data.domain.Pageable pageable) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            throw new UnauthorizedException("Hospital ID not found in context");
        }
        if (org.springframework.util.StringUtils.hasText(search)) {
            return userRepository.searchOtIncharges(hospitalId, "OT_INCHARGE", search, pageable);
        }
        return userRepository.findByHospitalIdAndRoleAndIsActiveTrue(hospitalId, "OT_INCHARGE", pageable);
    }

    public void deleteOtIncharge(String publicId, String reason) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            throw new UnauthorizedException("Hospital ID not found in context");
        }

        User user = userRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException(OT_INCHARGE_NOT_FOUND));

        if (!user.getHospitalId().equals(hospitalId)) {
            throw new UnauthorizedException("Access denied: User belongs to another hospital");
        }
        if (!"OT_INCHARGE".equals(user.getRole())) {
            throw new UnauthorizedException("Target user is not an OT Incharge");
        }

        user.setIsActive(false);
        userRepository.save(user);
        logger.info("Deleted OT Incharge ID: {}", LogSanitizer.clean(publicId));

        logAction("OT_INCHARGE_DELETED", "Deleted (soft) OT Incharge: " + user.getEmail(), reason, hospitalId);

        try {
            webSocketHandler.broadcast(hospitalId, REFRESH_DATA_MESSAGE);
        } catch (Exception e) {
            logger.warn("Failed to broadcast WebSocket refresh after OT Incharge deletion", e);
        }
    }

    public User getOtInchargeByPublicId(String publicId) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            throw new UnauthorizedException("Hospital ID not found in context");
        }

        User user = userRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException(OT_INCHARGE_NOT_FOUND));

        if (!user.getHospitalId().equals(hospitalId)) {
            throw new UnauthorizedException("Access denied: User belongs to another hospital");
        }
        if (!"OT_INCHARGE".equals(user.getRole())) {
            throw new UnauthorizedException("Target user is not an OT Incharge");
        }
        return user;
    }

    @Transactional
    public void resetOtInchargePassword(String publicId, String newPassword) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            throw new UnauthorizedException("Hospital ID not found in context");
        }

        User user = userRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException(OT_INCHARGE_NOT_FOUND));

        if (!user.getHospitalId().equals(hospitalId)) {
            throw new UnauthorizedException("Access denied: User belongs to another hospital");
        }
        if (!"OT_INCHARGE".equals(user.getRole())) {
            throw new UnauthorizedException("Target user is not an OT Incharge");
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        logger.info("Reset password for OT Incharge: {}", user.getEmail());
        logAction("PASSWORD_RESET", "Reset password for OT Incharge: " + user.getName() + " (" + user.getEmail() + ")", null, hospitalId);
    }

    @Transactional
    public User updateOtIncharge(String publicId, String name) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            throw new UnauthorizedException("Hospital ID not found in context");
        }

        User user = userRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException(OT_INCHARGE_NOT_FOUND));

        if (!user.getHospitalId().equals(hospitalId)) {
            throw new UnauthorizedException("Access denied: User belongs to another hospital");
        }
        if (!"OT_INCHARGE".equals(user.getRole())) {
            throw new UnauthorizedException("Target user is not an OT Incharge");
        }

        user.setName(name);
        User saved = userRepository.save(user);

        logger.info("Updated OT Incharge: {}", user.getEmail());
        logAction("OT_INCHARGE_UPDATED", "Updated OT Incharge: " + user.getName(), null, hospitalId);

        try {
            webSocketHandler.broadcast(hospitalId, REFRESH_DATA_MESSAGE);
        } catch (Exception e) {
            logger.warn("Failed to broadcast WebSocket refresh after OT Incharge update", e);
        }

        return saved;
    }

    private void logAction(String action, String details, String reason, Long hospitalId) {
        try {
            String currentUsername = SecurityContextHolder.getContext().getAuthentication().getName();
            AuditLog log = new AuditLog();
            log.setAction(action);
            log.setDetails(details);
            log.setPerformedBy(currentUsername);
            log.setReason(reason);
            log.setHospitalId(hospitalId);
            auditLogRepository.save(log);
        } catch (Exception e) {
            logger.error("Failed to save audit log: {}", e.getMessage(), e);
        }
    }
}
