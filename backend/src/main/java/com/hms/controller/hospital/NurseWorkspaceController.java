package com.hms.controller.hospital;

import com.hms.security.RequireModule;
import com.hms.service.hospital.NurseWorkspaceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * NurseWorkspaceController - nurse-facing endpoints (Phase 1): dashboard,
 * "my patients", and the composite read-only patient detail. HOSPITAL-tenant
 * only; gated by the NURSING module and the NURSE role. Patient detail is
 * further gated by an active assignment (403 otherwise) in the service.
 */
@RestController
@RequestMapping("/hospital/nurse")
@RequireModule("NURSING")
@PreAuthorize("hasRole('NURSE')")
public class NurseWorkspaceController {

    @Autowired
    private NurseWorkspaceService workspaceService;

    @GetMapping("/shift/status")
    public ResponseEntity<?> getShiftStatus() {
        return ResponseEntity.ok(java.util.Map.of("onShift", workspaceService.getShiftStatus()));
    }

    @GetMapping("/dashboard")
    public ResponseEntity<?> getDashboard() {
        return ResponseEntity.ok(workspaceService.getDashboard());
    }

    @GetMapping("/my-patients")
    public ResponseEntity<?> getMyPatients() {
        return ResponseEntity.ok(workspaceService.getMyPatients());
    }

    @GetMapping("/patients/{admissionId}")
    public ResponseEntity<?> getPatientDetail(@PathVariable Long admissionId) {
        return ResponseEntity.ok(workspaceService.getPatientDetail(admissionId));
    }
}
