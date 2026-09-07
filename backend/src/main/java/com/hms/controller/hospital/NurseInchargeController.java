package com.hms.controller.hospital;

import jakarta.validation.Valid;

import com.hms.dto.AssignPatientNurseRequest;
import com.hms.security.RequireModule;
import com.hms.service.hospital.NurseWorkspaceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/hospital/nurse-incharge")
@RequireModule("NURSING")
public class NurseInchargeController {

    @Autowired private NurseWorkspaceService workspaceService;

    @GetMapping("/dashboard")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')")
    public ResponseEntity<?> dashboard() {
        return ResponseEntity.ok(workspaceService.getInchargeDashboard());
    }

    @GetMapping("/patients")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')")
    public ResponseEntity<?> wardPatients() {
        return ResponseEntity.ok(workspaceService.getWardPatients());
    }

    @GetMapping("/unassigned-patients")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')")
    public ResponseEntity<?> unassignedPatients() {
        return ResponseEntity.ok(workspaceService.getUnassignedPatients());
    }

    /**
     * One ward patient's bedside view, so the incharge can open a chart from their ward list.
     *
     * <p>Ward-scoped, not assignment-scoped: an incharge supervises a ward rather than being
     * assigned to patients. The staff-nurse endpoint at {@code /hospital/nurse/patients/...} is
     * untouched.
     */
    @GetMapping("/patients/{admissionId}")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')")
    public ResponseEntity<?> wardPatientDetail(@PathVariable Long admissionId) {
        return ResponseEntity.ok(workspaceService.getWardPatientDetail(admissionId));
    }

    @GetMapping("/wards/{wardId}/nurses")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')")
    public ResponseEntity<?> wardStaffNurses(@PathVariable Long wardId) {
        return ResponseEntity.ok(workspaceService.getWardStaffNurses(wardId));
    }

    @PostMapping("/assign")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')")
    public ResponseEntity<?> assign(@Valid @RequestBody AssignPatientNurseRequest req) {
        workspaceService.assignPatientNurse(req.getIpdAdmissionId(), req.getNurseProfileId());
        return ResponseEntity.ok(Map.of("message", "Nurse assigned"));
    }

    @GetMapping("/wards")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')")
    public ResponseEntity<?> myWards() { return ResponseEntity.ok(workspaceService.getMyWards()); }

    @GetMapping("/nurses")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')")
    public ResponseEntity<?> myNurses() { return ResponseEntity.ok(workspaceService.getMyNurses()); }
}
