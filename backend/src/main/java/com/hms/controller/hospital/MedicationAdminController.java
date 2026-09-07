package com.hms.controller.hospital;

import jakarta.validation.Valid;

import com.hms.dto.MedicationAdminRequest;
import com.hms.service.hospital.MedicationAdministrationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * MedicationAdminController - Medication Administration Record (Phase 1 Nurse
 * module). HOSPITAL-tenant only, NURSING-gated. Recording is nurse-only
 * (assignment-gated in the service); reads also open to doctors/admins.
 */
@RestController
@RequestMapping("/hospital/nurse/medication")
public class MedicationAdminController {

    @Autowired
    private MedicationAdministrationService medicationService;

    @GetMapping("/admission/{admissionId}/prescriptions")
    @PreAuthorize("hasAnyRole('NURSE','NURSE_INCHARGE','DOCTOR','HOSPITAL_ADMIN','RECEPTIONIST')")
    public ResponseEntity<?> getActivePrescriptions(@PathVariable Long admissionId) {
        return ResponseEntity.ok(medicationService.getActivePrescriptions(admissionId));
    }

    /** Full medication chart: all orders (active/stopped/completed) + reminders. */
    @GetMapping("/admission/{admissionId}/chart")
    @PreAuthorize("hasAnyRole('NURSE','NURSE_INCHARGE','DOCTOR','HOSPITAL_ADMIN','RECEPTIONIST')")
    public ResponseEntity<?> getMedicationChart(@PathVariable Long admissionId) {
        return ResponseEntity.ok(medicationService.getMedicationChart(admissionId));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('NURSE','NURSE_INCHARGE')")
    public ResponseEntity<?> record(@Valid @RequestBody MedicationAdminRequest req) {
        return ResponseEntity.ok(medicationService.record(req));
    }

    @GetMapping("/admission/{admissionId}")
    @PreAuthorize("hasAnyRole('NURSE','NURSE_INCHARGE','DOCTOR','HOSPITAL_ADMIN','RECEPTIONIST')")
    public ResponseEntity<?> getByAdmission(@PathVariable Long admissionId) {
        return ResponseEntity.ok(medicationService.getByAdmission(admissionId));
    }
}
