package com.hms.controller.hospital;

import jakarta.validation.Valid;

import com.hms.dto.AssignNurseRequest;
import com.hms.security.RequireModule;
import com.hms.service.hospital.NurseAssignmentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * NurseAssignmentController - Hospital Admin management of nurse-to-admission
 * assignments (Phase 1). HOSPITAL-tenant only; gated by the NURSING module.
 *
 * Writes are HOSPITAL_ADMIN only; reads are also available to DOCTOR/RECEPTIONIST.
 */
@RestController
@RequestMapping("/hospital/nurse-assignments")
@RequireModule("NURSING")
public class NurseAssignmentController {

    @Autowired
    private NurseAssignmentService assignmentService;

    @PostMapping
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<?> assign(@Valid @RequestBody AssignNurseRequest req) {
        if (req.getIpdAdmissionId() == null || req.getNurseUserId() == null) {
            return ResponseEntity.badRequest().body("ipdAdmissionId and nurseUserId are required");
        }
        return ResponseEntity.ok(assignmentService.assignNurse(
                req.getIpdAdmissionId(), req.getNurseUserId(), req.getNotes()));
    }

    @PutMapping("/{publicId}/reassign")
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<?> reassign(@PathVariable String publicId, @Valid @RequestBody AssignNurseRequest req) {
        if (req.getNurseUserId() == null) {
            return ResponseEntity.badRequest().body("nurseUserId is required");
        }
        return ResponseEntity.ok(assignmentService.reassignNurse(publicId, req.getNurseUserId(), req.getNotes()));
    }

    @DeleteMapping("/{publicId}")
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<?> unassign(@PathVariable String publicId) {
        assignmentService.unassign(publicId);
        return ResponseEntity.ok(Map.of("message", "Nurse unassigned successfully"));
    }

    @GetMapping("/overview")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR', 'RECEPTIONIST')")
    public ResponseEntity<?> overview() {
        return ResponseEntity.ok(assignmentService.getAssignmentOverview());
    }

    @GetMapping("/admission/{admissionId}")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR', 'RECEPTIONIST')")
    public ResponseEntity<?> historyForAdmission(@PathVariable Long admissionId) {
        return ResponseEntity.ok(assignmentService.getHistoryForAdmission(admissionId));
    }
}
