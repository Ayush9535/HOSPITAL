package com.hms.service.hospital;
import com.hms.util.LogSanitizer;

import com.hms.entity.Receptionist;
import com.hms.entity.User;
import com.hms.repository.ReceptionistProfileRepository;
import com.hms.repository.UserRepository;
import com.hms.security.SecurityContextHelper;
import com.hms.security.HospitalWebSocketHandler;

import com.hms.exception.ResourceNotFoundException;
import com.hms.exception.UnauthorizedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;

import com.hms.entity.AuditLog;
import com.hms.repository.AuditLogRepository;

import org.springframework.security.core.context.SecurityContextHolder;

/**
 * ReceptionistService - Service for managing receptionists
 * 
 * Receptionists are treated as Users with role 'RECEPTIONIST'.
 * This service handles creation, listing, and deletion of receptionists.
 * 
 * All operations are automatically filtered by hospital_id.
 * 
 * @author HMS Team
 * @version Phase-1
 */
@Service
public class ReceptionistService {

    private static final Logger logger = LoggerFactory.getLogger(ReceptionistService.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ReceptionistProfileRepository receptionistProfileRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private SecurityContextHelper securityHelper;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private HospitalWebSocketHandler webSocketHandler;

    /**
     * Create a new receptionist
     * 
     * @param name     Name
     * @param email    Email (Login ID)
     * @param password Password
     * @return Created User entity
     */
    @Transactional
    public User createReceptionist(String name, String email, String password) {
        return createReceptionist(name, email, password, null);
    }

    @Transactional
    public User createReceptionist(String name, String email, String password, String phone) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            throw new UnauthorizedException("Hospital ID not found in context");
        }

        if (userRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("Email already exists");
        }

        User receptionist = new User();
        receptionist.setName(name);
        receptionist.setEmail(email);
        receptionist.setPassword(passwordEncoder.encode(password));
        receptionist.setRole("RECEPTIONIST");
        receptionist.setHospitalId(hospitalId);
        receptionist.setIsActive(true);

        User saved = userRepository.save(receptionist);

        // Set sequential customId: REC1, REC2, REC3...
        Integer maxSeq = userRepository.findMaxReceptionistSequence();
        int nextSeq = (maxSeq != null ? maxSeq : 0) + 1;
        saved.setCustomId("REC" + nextSeq);
        saved = userRepository.save(saved);

        // Create receptionist profile record. The phone the admin typed is stored here (the
        // `receptionists` row is the single source of truth for it — the self-service profile
        // screen writes to the same place); it used to be hardcoded to "" and silently lost.
        Receptionist receptionistProfile = new Receptionist();
        receptionistProfile.setHospitalId(hospitalId);
        receptionistProfile.setName(name);
        receptionistProfile.setEmail(email);
        receptionistProfile.setPhone(phone != null ? phone.trim() : "");
        receptionistProfile.setCustomId(saved.getCustomId());
        receptionistProfile.setIsActive(true);
        receptionistProfileRepository.save(receptionistProfile);


        logger.info("Created receptionist: {} for hospital: {}", LogSanitizer.clean(email), hospitalId);

        logAction("RECEPTIONIST_CREATED", "Created receptionist: " + email, null, hospitalId);

        // Broadcast real-time refresh
        try {
            webSocketHandler.broadcast(hospitalId, "{\"type\":\"REFRESH_DATA\"}");
        } catch (Exception e) {
            logger.warn("Failed to broadcast WebSocket refresh after receptionist creation", e);
        }

        return saved;
    }

    /**
     * Get all active receptionists for the current hospital
     */
    public org.springframework.data.domain.Page<java.util.Map<String, Object>> getAllReceptionists(String search,
            org.springframework.data.domain.Pageable pageable) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            logger.error("getAllReceptionists: Hospital ID not found in context");
            throw new UnauthorizedException("Hospital ID not found in context");
        }
        org.springframework.data.domain.Page<User> page =
                org.springframework.util.StringUtils.hasText(search)
                        ? userRepository.searchReceptionists(hospitalId, "RECEPTIONIST", search, pageable)
                        : userRepository.findByHospitalIdAndRoleAndIsActiveTrue(hospitalId, "RECEPTIONIST", pageable);
        return page.map(this::toView);
    }

    public List<User> getAllReceptionists() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null)
            throw new UnauthorizedException("Hospital ID not found in context");
        return userRepository.findByHospitalIdAndRoleAndIsActiveTrue(hospitalId, "RECEPTIONIST");
    }

    /**
     * Flatten a receptionist User + its `receptionists` row into the shape the admin UI
     * consumes — mirroring {@code NurseService.toNurseView}.
     *
     * The phone is NOT on `users`; it lives on the `receptionists` row (the same row the
     * receptionist's own profile screen writes to). Returning the raw User meant the admin
     * could never see a phone at all. A @Transient field on User cannot work either: the
     * Hibernate6Module registered in JacksonConfig honours JPA's @Transient and strips such
     * properties from the JSON. This also stops the endpoint leaking the bcrypt password hash.
     */
    public java.util.Map<String, Object> toView(User u) {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put("id", u.getId());
        m.put("publicId", u.getPublicId());
        m.put("customId", u.getCustomId());
        m.put("name", u.getName());
        m.put("email", u.getEmail());
        m.put("role", u.getRole());
        m.put("isActive", u.getIsActive());
        m.put("createdAt", u.getCreatedAt());
        m.put("phone", receptionistProfileRepository.findByEmail(u.getEmail())
                .map(Receptionist::getPhone).orElse(""));
        return m;
    }

    /**
     * Delete (Soft Delete) a receptionist
     */
    public void deleteReceptionist(String publicId, String reason) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            throw new UnauthorizedException("Hospital ID not found in context");
        }

        User user = userRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("Receptionist not found"));

        if (!user.getHospitalId().equals(hospitalId)) {
            throw new UnauthorizedException("Access denied: User belongs to another hospital");
        }
        if (!"RECEPTIONIST".equals(user.getRole())) {
            throw new UnauthorizedException("Target user is not a receptionist");
        }

        user.setIsActive(false);
        userRepository.save(user);
        logger.info("Deleted receptionist ID: {}", LogSanitizer.clean(publicId));

        logAction("RECEPTIONIST_DELETED", "Deleted (soft) receptionist: " + user.getEmail(), reason, hospitalId);

        // Broadcast real-time refresh
        try {
            webSocketHandler.broadcast(hospitalId, "{\"type\":\"REFRESH_DATA\"}");
        } catch (Exception e) {
            logger.warn("Failed to broadcast WebSocket refresh after receptionist deletion", e);
        }
    }

    public void deleteReceptionist(String publicId) {
        deleteReceptionist(publicId, null);
    }

    /**
     * Get a receptionist by public ID
     */
    public User getReceptionistByPublicId(String publicId) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            throw new UnauthorizedException("Hospital ID not found in context");
        }

        User user = userRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("Receptionist not found"));

        if (!user.getHospitalId().equals(hospitalId)) {
            throw new UnauthorizedException("Access denied: User belongs to another hospital");
        }
        if (!"RECEPTIONIST".equals(user.getRole())) {
            throw new UnauthorizedException("Target user is not a receptionist");
        }
        return user;
    }

    /** Same lookup, in the shape the admin UI consumes (includes phone, omits the password). */
    public java.util.Map<String, Object> getReceptionistViewByPublicId(String publicId) {
        return toView(getReceptionistByPublicId(publicId));
    }


    /**
     * Reset a receptionist's password (Hospital Admin only)
     */
    @Transactional
    public void resetReceptionistPassword(String publicId, String newPassword) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            throw new UnauthorizedException("Hospital ID not found in context");
        }

        User user = userRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("Receptionist not found"));

        if (!user.getHospitalId().equals(hospitalId)) {
            throw new UnauthorizedException("Access denied: User belongs to another hospital");
        }
        if (!"RECEPTIONIST".equals(user.getRole())) {
            throw new UnauthorizedException("Target user is not a receptionist");
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        logger.info("Reset password for receptionist: {}", user.getEmail());
        logAction("PASSWORD_RESET", "Reset password for receptionist: " + user.getName() + " (" + user.getEmail() + ")", null, hospitalId);
    }

    @Transactional
    public User updateReceptionist(String publicId, String name) {
        return updateReceptionist(publicId, name, null);
    }

    /**
     * Update a receptionist's name and phone.
     *
     * The phone lives on the `receptionists` row, not on `users` — the same row the
     * receptionist's own profile screen writes to — so both edit paths stay in agreement.
     * A null phone means "not supplied": leave the stored value alone.
     */
    @Transactional
    public User updateReceptionist(String publicId, String name, String phone) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            throw new UnauthorizedException("Hospital ID not found in context");
        }

        User user = userRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("Receptionist not found"));

        if (!user.getHospitalId().equals(hospitalId)) {
            throw new UnauthorizedException("Access denied: User belongs to another hospital");
        }
        if (!"RECEPTIONIST".equals(user.getRole())) {
            throw new UnauthorizedException("Target user is not a receptionist");
        }

        user.setName(name);
        User saved = userRepository.save(user);

        // Mirror name/phone onto the actor row. Upsert, because a receptionist created before
        // the profile row existed (or by an older build) may not have one yet.
        final String finalName = name;
        Receptionist profile = receptionistProfileRepository.findByEmail(user.getEmail())
                .orElseGet(() -> {
                    Receptionist r = new Receptionist();
                    r.setHospitalId(user.getHospitalId());
                    r.setEmail(user.getEmail());
                    r.setCustomId(user.getCustomId());
                    r.setPhone("");
                    r.setIsActive(true);
                    return r;
                });
        profile.setName(finalName);
        if (phone != null) profile.setPhone(phone.trim());
        receptionistProfileRepository.save(profile);

        logger.info("Updated receptionist: {}", user.getEmail());
        logAction("RECEPTIONIST_UPDATED", "Updated receptionist: " + user.getName(), null, hospitalId);

        // REFRESH_DATA reloads the lists the receptionist appears in; SETTINGS_UPDATED makes
        // each client re-fetch its own profile, so the edited receptionist's own dashboard
        // picks up the new name without a page reload.
        try {
            webSocketHandler.broadcast(hospitalId, "{\"type\":\"REFRESH_DATA\"}");
            webSocketHandler.broadcast(hospitalId, "{\"type\":\"SETTINGS_UPDATED\"}");
        } catch (Exception e) {
            logger.warn("Failed to broadcast WebSocket refresh after receptionist update", e);
        }

        return saved;
    }

    /**
     * Helper to log actions
     */
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
            logger.error("Failed to save audit log: {}", e.getMessage());
        }
    }
}
