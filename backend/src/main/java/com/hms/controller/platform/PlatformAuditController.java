package com.hms.controller.platform;

import com.hms.entity.AuditLog;
import com.hms.service.platform.PlatformHospitalService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * PlatformAuditController - Controller for fetching audit logs
 * 
 * Provides endpoints for Super Admin to view system audit logs.
 * 
 * @author HMS Team
 * @version Phase-1
 */
@RestController
@RequestMapping("/platform/audit-logs")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class PlatformAuditController {

    @Autowired
    private PlatformHospitalService hospitalService;

    /**
     * Get all audit logs
     * 
     * @return List of audit logs
     */
    @GetMapping
    public ResponseEntity<List<AuditLog>> getAuditLogs() {
        List<AuditLog> logs = hospitalService.getAuditLogs();
        return ResponseEntity.ok(logs);
    }
}
