package com.hms.service.hospital;
import com.hms.util.LogSanitizer;

import com.hms.entity.Pharmacist;
import com.hms.entity.User;
import com.hms.repository.PharmacistProfileRepository;
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

import java.util.List;

import com.hms.entity.AuditLog;
import com.hms.repository.AuditLogRepository;

import com.hms.exception.ResourceNotFoundException;
import com.hms.exception.UnauthorizedException;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * PharmacistService - Service for managing pharmacists
 * 
 * Pharmacists are treated as Users with role 'PHARMACIST'.
 */
@Service
public class PharmacistService {

    private static final Logger logger = LoggerFactory.getLogger(PharmacistService.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PharmacistProfileRepository pharmacistProfileRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private SecurityContextHelper securityHelper;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private HospitalWebSocketHandler webSocketHandler;

    @Transactional
    public User createPharmacist(String name, String email, String password) {
        return createPharmacist(name, email, password, null);
    }

    @Transactional
    public User createPharmacist(String name, String email, String password, String phone) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            throw new UnauthorizedException("Hospital ID not found in context");
        }

        if (userRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("Email already exists");
        }

        User pharmacist = new User();
        pharmacist.setName(name);
        pharmacist.setEmail(email);
        pharmacist.setPassword(passwordEncoder.encode(password));
        pharmacist.setRole("PHARMACIST");
        pharmacist.setHospitalId(hospitalId);
        pharmacist.setIsActive(true);

        User saved = userRepository.save(pharmacist);

        // Create pharmacist profile record. The phone lives here (same row the pharmacist's own
        // profile screen writes to); it used to be hardcoded to "" and the admin's input lost.
        Pharmacist pharmacistProfile = new Pharmacist();
        pharmacistProfile.setHospitalId(hospitalId);
        pharmacistProfile.setName(name);
        pharmacistProfile.setEmail(email);
        pharmacistProfile.setPhone(phone != null ? phone.trim() : "");
        pharmacistProfile.setIsActive(true);
        pharmacistProfileRepository.save(pharmacistProfile);


        logger.info("Created pharmacist: {} for hospital: {}", LogSanitizer.clean(email), hospitalId);

        logAction("PHARMACIST_CREATED", "Created pharmacist: " + email, null, hospitalId);

        // Broadcast real-time refresh
        try {
            webSocketHandler.broadcast(hospitalId, "{\"type\":\"REFRESH_DATA\"}");
        } catch (Exception e) {
            logger.warn("Failed to broadcast WebSocket refresh after pharmacist creation", e);
        }

        return saved;
    }

    public org.springframework.data.domain.Page<java.util.Map<String, Object>> getAllPharmacists(String search,
            org.springframework.data.domain.Pageable pageable) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            throw new UnauthorizedException("Hospital ID not found in context");
        }
        org.springframework.data.domain.Page<User> page =
                org.springframework.util.StringUtils.hasText(search)
                        ? userRepository.searchPharmacists(hospitalId, "PHARMACIST", search, pageable)
                        : userRepository.findByHospitalIdAndRoleAndIsActiveTrue(hospitalId, "PHARMACIST", pageable);
        return page.map(this::toView);
    }

    /**
     * Flatten a pharmacist User + its `pharmacists` row into the shape the admin UI consumes
     * (mirrors NurseService.toNurseView). The phone lives on the `pharmacists` row, not on
     * `users`, so the raw User could never carry it — and a @Transient field can't help
     * either, because the Hibernate6Module in JacksonConfig strips @Transient properties from
     * the JSON. Also stops the endpoint leaking the bcrypt password hash.
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
        m.put("phone", pharmacistProfileRepository.findByEmail(u.getEmail())
                .map(Pharmacist::getPhone).orElse(""));
        return m;
    }

    /** Same lookup, in the shape the admin UI consumes (includes phone, omits the password). */
    public java.util.Map<String, Object> getPharmacistViewByPublicId(String publicId) {
        return toView(getPharmacistByPublicId(publicId));
    }

    public void deletePharmacist(String publicId, String reason) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            throw new UnauthorizedException("Hospital ID not found in context");
        }

        User user = userRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("Pharmacist not found"));

        if (!user.getHospitalId().equals(hospitalId)) {
            throw new UnauthorizedException("Access denied: User belongs to another hospital");
        }
        if (!"PHARMACIST".equals(user.getRole())) {
            throw new UnauthorizedException("Target user is not a pharmacist");
        }

        user.setIsActive(false);
        userRepository.save(user);
        logger.info("Deleted pharmacist ID: {}", LogSanitizer.clean(publicId));

        logAction("PHARMACIST_DELETED", "Deleted (soft) pharmacist: " + user.getEmail(), reason, hospitalId);

        // Broadcast real-time refresh
        try {
            webSocketHandler.broadcast(hospitalId, "{\"type\":\"REFRESH_DATA\"}");
        } catch (Exception e) {
            logger.warn("Failed to broadcast WebSocket refresh after pharmacist deletion", e);
        }
    }

    /**
     * Get a pharmacist by public ID
     */
    public User getPharmacistByPublicId(String publicId) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            throw new UnauthorizedException("Hospital ID not found in context");
        }

        User user = userRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("Pharmacist not found"));

        if (!user.getHospitalId().equals(hospitalId)) {
            throw new UnauthorizedException("Access denied: User belongs to another hospital");
        }
        if (!"PHARMACIST".equals(user.getRole())) {
            throw new UnauthorizedException("Target user is not a pharmacist");
        }
        return user;
    }


    /**
     * Reset a pharmacist's password (Hospital Admin only)
     */
    @Transactional
    public void resetPharmacistPassword(String publicId, String newPassword) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            throw new UnauthorizedException("Hospital ID not found in context");
        }

        User user = userRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("Pharmacist not found"));

        if (!user.getHospitalId().equals(hospitalId)) {
            throw new UnauthorizedException("Access denied: User belongs to another hospital");
        }
        if (!"PHARMACIST".equals(user.getRole())) {
            throw new UnauthorizedException("Target user is not a pharmacist");
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        logger.info("Reset password for pharmacist: {}", user.getEmail());
        logAction("PASSWORD_RESET", "Reset password for pharmacist: " + user.getName() + " (" + user.getEmail() + ")", null, hospitalId);
    }

    /**
     * Update a pharmacist's name
     */
    @Transactional
    public User updatePharmacist(String publicId, String name) {
        return updatePharmacist(publicId, name, null);
    }

    /**
     * Update a pharmacist's name and phone. The phone lives on the `pharmacists` row — the same
     * row the pharmacist's own profile screen writes to — so both edit paths agree. A null
     * phone means "not supplied": leave the stored value alone.
     */
    @Transactional
    public User updatePharmacist(String publicId, String name, String phone) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            throw new UnauthorizedException("Hospital ID not found in context");
        }

        User user = userRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("Pharmacist not found"));

        if (!user.getHospitalId().equals(hospitalId)) {
            throw new UnauthorizedException("Access denied: User belongs to another hospital");
        }
        if (!"PHARMACIST".equals(user.getRole())) {
            throw new UnauthorizedException("Target user is not a pharmacist");
        }

        user.setName(name);
        User saved = userRepository.save(user);

        // Mirror name/phone onto the actor row (upsert — an older pharmacist may lack one).
        final String finalName = name;
        Pharmacist profile = pharmacistProfileRepository.findByEmail(user.getEmail())
                .orElseGet(() -> {
                    Pharmacist p = new Pharmacist();
                    p.setHospitalId(user.getHospitalId());
                    p.setEmail(user.getEmail());
                    p.setPhone("");
                    p.setIsActive(true);
                    return p;
                });
        profile.setName(finalName);
        if (phone != null) profile.setPhone(phone.trim());
        pharmacistProfileRepository.save(profile);

        logger.info("Updated pharmacist: {}", user.getEmail());
        logAction("PHARMACIST_UPDATED", "Updated pharmacist: " + user.getName(), null, hospitalId);

        // REFRESH_DATA reloads the lists the pharmacist appears in; SETTINGS_UPDATED makes
        // each client re-fetch its own profile, so the edited pharmacist's own dashboard
        // picks up the new name without a page reload.
        try {
            webSocketHandler.broadcast(hospitalId, "{\"type\":\"REFRESH_DATA\"}");
            webSocketHandler.broadcast(hospitalId, "{\"type\":\"SETTINGS_UPDATED\"}");
        } catch (Exception e) {
            logger.warn("Failed to broadcast WebSocket refresh after pharmacist update", e);
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

