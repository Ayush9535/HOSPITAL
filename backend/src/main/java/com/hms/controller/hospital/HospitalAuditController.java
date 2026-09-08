package com.hms.controller.hospital;

import com.hms.entity.AuditLog;

import com.hms.service.AuditLogService;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Controller for retrieving Audit Logs for Hospital Admins.
 */
@RestController
@RequestMapping({"/hospital/audit-logs", "/clinic/audit-logs", "/pharmacy/audit-logs"})
@RequiredArgsConstructor
@PreAuthorize("hasRole('HOSPITAL_ADMIN')")
public class HospitalAuditController {

    private static final Logger log = LoggerFactory.getLogger(HospitalAuditController.class);

    private final AuditLogService auditLogService;
    private final com.hms.security.SecurityContextHelper securityHelper;

    /**
     * Get all recent activity for the current hospital.
     */
    @GetMapping
    public ResponseEntity<List<AuditLog>> getRecentActivity(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Integer limit) {
        try {
            Long hospitalId = securityHelper.getCurrentHospitalId();

            if (hospitalId == null) {
                log.warn("Hospital ID is null for current user - returning empty activity feed");
                return ResponseEntity.ok(java.util.Collections.emptyList());
            }

            String mappedRole = null;
            if (role != null && !role.trim().isEmpty() && !"ALL".equalsIgnoreCase(role)) {
                mappedRole = role.trim();
            }

            List<AuditLog> logs = auditLogService.getLogsByHospitalId(hospitalId, search, mappedRole);
            if (limit != null && logs.size() > limit) {
                logs = logs.subList(0, limit);
            }
            return ResponseEntity.ok(logs);
        } catch (Exception e) {
            log.warn("Could not retrieve hospital context - returning empty activity feed", e);
            return ResponseEntity.ok(java.util.Collections.emptyList());
        }
    }

    /**
     * Get history for a specific entity.
     */
    @GetMapping("/{entityType}/{entityId}")
    public ResponseEntity<List<AuditLog>> getEntityHistory(
            @PathVariable String entityType,
            @PathVariable String entityId) {
        try {
            Long hospitalId = securityHelper.getCurrentHospitalId();
            if (hospitalId == null) {
                return ResponseEntity.ok(java.util.Collections.emptyList());
            }
            List<AuditLog> logs = auditLogService.getLogsByEntityAndHospitalId(entityType.toUpperCase(), entityId, hospitalId);
            return ResponseEntity.ok(logs);
        } catch (Exception e) {
            return ResponseEntity.ok(java.util.Collections.emptyList());
        }
    }

    /**
     * Get pharmacy-specific audit logs (SUPPLIER, MEDICINE_BATCH, PURCHASE_INVOICE, PHARMACY_SALE).
     * Filters by role based on pharmacy plan type (SINGLE_PHARMACIST_ADMIN, SINGLE_PHARMACY, MULTI_PHARMACY).
     */
    @GetMapping("/pharmacy")
    public ResponseEntity<List<AuditLog>> getPharmacyLogs(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String role) {
        try {
            Long hospitalId = securityHelper.getCurrentHospitalId();
            Long branchId = securityHelper.getCurrentBranchId();

            if (hospitalId == null) {
                return ResponseEntity.ok(java.util.Collections.emptyList());
            }

            String mappedRole = null;
            if (role != null && !role.trim().isEmpty() && !"ALL".equalsIgnoreCase(role)) {
                mappedRole = role.trim();
            }

            List<AuditLog> logs = auditLogService.getPharmacyLogs(hospitalId, branchId, mappedRole, search);
            return ResponseEntity.ok(logs);
        } catch (Exception e) {
            log.warn("Could not retrieve pharmacy audit logs", e);
            return ResponseEntity.ok(java.util.Collections.emptyList());
        }
    }
}
