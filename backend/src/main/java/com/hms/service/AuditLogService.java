package com.hms.service;

import com.hms.entity.AuditLog;
import com.hms.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Service for managing Audit Logs.
 */
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;
    private final com.hms.security.SecurityContextHelper securityHelper;

    /**
     * Logs a system action.
     *
     * @param action      The action performed (e.g., PATIENT_DELETED)
     * @param details     Details about the action
     * @param performedBy Who performed the action (username/email)
     * @param hospitalId  ID of the hospital (optional, can be null)
     * @param entityType  Type of entity affected (e.g., PATIENT)
     * @param entityId    ID of the entity affected
     * @param reason      Reason provided by the user (optional)
     */
    @Transactional
    public void logAction(String action, String details, String performedBy, Long hospitalId, String entityType,
            String entityId, String reason) {
        logAction(action, details, performedBy, hospitalId, null, entityType, entityId, reason);
    }

    /**
     * Logs a system action with branch scope (for Multi Pharmacy).
     *
     * @param action      The action performed (e.g., SUPPLIER_ADDED)
     * @param details     Details about the action
     * @param performedBy Who performed the action (username/email)
     * @param hospitalId  ID of the hospital (optional, can be null)
     * @param branchId    ID of the pharmacy branch (optional, can be null)
     * @param entityType  Type of entity affected (e.g., SUPPLIER)
     * @param entityId    ID of the entity affected
     * @param reason      Reason provided by the user (optional)
     */
    @Transactional
    public void logAction(String action, String details, String performedBy, Long hospitalId, Long branchId, String entityType,
            String entityId, String reason) {
        AuditLog log = new AuditLog();
        log.setAction(action);
        log.setDetails(details);
        log.setPerformedBy(performedBy);
        log.setHospitalId(hospitalId);
        log.setBranchId(branchId);
        log.setEntityType(entityType);
        log.setEntityId(entityId);
        log.setReason(reason);

        try {
            String role = securityHelper.getCurrentUserRole();
            log.setPerformedByRole(role);
        } catch (Exception ignored) {
            // Ignore if called out of authenticated request context
        }

        auditLogRepository.save(log);
    }

    public List<AuditLog> getLogsByHospitalId(Long hospitalId, String search, String role) {
        if (role != null && !role.isBlank()) {
            if (search != null && !search.isBlank()) {
                return auditLogRepository.findByHospitalIdAndPerformedByRoleAndActionContainingIgnoreCaseOrderByTimestampDesc(
                        hospitalId, role, search);
            }
            return auditLogRepository.findByHospitalIdAndPerformedByRoleOrderByTimestampDesc(hospitalId, role);
        }
        if (search != null && !search.isBlank()) {
            return auditLogRepository.findByHospitalIdAndActionContainingIgnoreCaseOrderByTimestampDesc(hospitalId,
                    search);
        }
        return auditLogRepository.findByHospitalIdOrderByTimestampDesc(hospitalId);
    }

    public List<AuditLog> getLogsByHospitalId(Long hospitalId, String search) {
        return getLogsByHospitalId(hospitalId, search, null);
    }

    public List<AuditLog> getLogsByEntity(String entityType, String entityId) {
        return auditLogRepository.findByEntityTypeAndEntityIdOrderByTimestampDesc(entityType, entityId);
    }

    public List<AuditLog> getLogsByEntityAndHospitalId(String entityType, String entityId, Long hospitalId) {
        return auditLogRepository.findByEntityTypeAndEntityIdAndHospitalIdOrderByTimestampDesc(entityType, entityId, hospitalId);
    }

    /**
     * Get pharmacy-specific audit logs filtered by plan type and optional branch.
     * Filters by entity type (only pharmacy operations) and role based on plan.
     *
     * @param hospitalId Hospital ID
     * @param branchId Branch ID (null = all branches / admin view; set = branch-specific view)
     * @param pharmacyPlanRole Filter by role: null=all, "HOSPITAL_ADMIN", "PHARMACIST"
     * @param search Optional search term in action names
     * @return List of pharmacy audit logs
     */
    public List<AuditLog> getPharmacyLogs(Long hospitalId, Long branchId, String pharmacyPlanRole, String search) {
        return auditLogRepository.findPharmacyLogs(hospitalId, branchId, pharmacyPlanRole, search);
    }

    public List<AuditLog> getPharmacyLogs(Long hospitalId, Long branchId, String search) {
        return getPharmacyLogs(hospitalId, branchId, null, search);
    }
}
