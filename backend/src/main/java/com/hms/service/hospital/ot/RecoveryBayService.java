package com.hms.service.hospital.ot;

import com.hms.entity.RecoveryBay;
import com.hms.repository.RecoveryBayRepository;
import com.hms.repository.RecoveryEpisodeRepository;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RecoveryBayService - CRUD for the hospital's named recovery locations, mirroring
 * OtRoomService's shape for a much smaller resource (no scheduling intervals: a bay is either
 * occupied by the one active episode referencing it, or it is not).
 */
@Service
public class RecoveryBayService {
    private static final Logger logger = LoggerFactory.getLogger(RecoveryBayService.class);
    @Autowired private RecoveryBayRepository bayRepository;
    @Autowired private RecoveryEpisodeRepository episodeRepository;
    @Autowired private SecurityContextHelper securityHelper;
    @Autowired private AuditLogService auditLogService;

    /** Every active bay, with whether it is currently occupied -- what the admit-to-recovery
     *  picker and the admin config screen both need. */
    public List<Map<String, Object>> list() {
        Long hospitalId = requireHospitalId();
        return bayRepository.findByHospitalIdAndIsActiveTrueOrderByNameAsc(hospitalId).stream()
                .map(b -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("publicId", b.getPublicId());
                    row.put("name", b.getName());
                    row.put("occupied", episodeRepository.existsActiveByRecoveryBayId(b.getId()));
                    return row;
                })
                .collect(Collectors.toList());
    }

    @Transactional
    public RecoveryBay create(String name) {
        Long hospitalId = requireHospitalId();
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Recovery bay name is required");
        }
        String clean = name.trim();
        if (bayRepository.existsByHospitalIdAndName(hospitalId, clean)) {
            throw new IllegalArgumentException("A recovery bay with that name already exists");
        }
        RecoveryBay bay = new RecoveryBay();
        bay.setHospitalId(hospitalId);
        bay.setName(clean);
        RecoveryBay saved = bayRepository.save(bay);
        audit("RECOVERY_BAY_CREATED", "Recovery bay created: " + clean, hospitalId);
        return saved;
    }

    @Transactional
    public RecoveryBay update(String publicId, String name, Boolean isActive) {
        Long hospitalId = requireHospitalId();
        RecoveryBay bay = requireBay(publicId, hospitalId);
        if (name != null && !name.trim().isEmpty()) bay.setName(name.trim());
        if (isActive != null) bay.setIsActive(isActive);
        RecoveryBay saved = bayRepository.save(bay);
        audit("RECOVERY_BAY_UPDATED", "Recovery bay updated: " + saved.getName(), hospitalId);
        return saved;
    }

    /** Soft delete: a historic recovery episode still references the bay it happened in. */
    @Transactional
    public void deactivate(String publicId) {
        Long hospitalId = requireHospitalId();
        RecoveryBay bay = requireBay(publicId, hospitalId);
        if (episodeRepository.existsActiveByRecoveryBayId(bay.getId())) {
            throw new IllegalArgumentException("This bay currently has a patient in recovery");
        }
        bay.setIsActive(false);
        bayRepository.save(bay);
        audit("RECOVERY_BAY_DEACTIVATED", "Recovery bay deactivated: " + bay.getName(), hospitalId);
    }

    private RecoveryBay requireBay(String publicId, Long hospitalId) {
        return bayRepository.findByPublicIdAndHospitalId(publicId, hospitalId)
                .orElseThrow(() -> new IllegalArgumentException("Recovery bay not found"));
    }

    private Long requireHospitalId() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) throw new com.hms.exception.UnauthorizedException("Hospital ID not found in context");
        return hospitalId;
    }

    private void audit(String action, String details, Long hospitalId) {
        try {
            auditLogService.logAction(action, details, securityHelper.getCurrentUserEmail(), hospitalId,
                    "RECOVERY_BAY", null, null);
        } catch (Exception e) {
            logger.warn("Failed to audit {}: {}", action, e.getMessage());
        }
    }
}
