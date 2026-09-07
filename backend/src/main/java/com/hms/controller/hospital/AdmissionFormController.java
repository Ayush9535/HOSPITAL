package com.hms.controller.hospital;

import jakarta.validation.Valid;

import com.hms.entity.AdmissionForm;
import com.hms.service.hospital.AdmissionFormService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * AdmissionFormController - nurse fills the IPD admission form to complete an
 * admission (Phase 1 Nurse module). HOSPITAL-tenant only, NURSING-gated.
 * Reading the form is open to doctors/admins too — the clinical form panels use
 * it for the patient header — while filling/confirming stays a nurse workflow.
 * Assignment scoping is enforced in the service.
 */
@RestController
@RequestMapping("/hospital/nurse/admission-form")
public class AdmissionFormController {

    @Autowired
    private AdmissionFormService admissionFormService;

    @GetMapping("/admission/{admissionId}")
    @PreAuthorize("hasAnyRole('NURSE','NURSE_INCHARGE','DOCTOR','HOSPITAL_ADMIN','RECEPTIONIST')")
    public ResponseEntity<?> get(@PathVariable Long admissionId) {
        return ResponseEntity.ok(admissionFormService.getOrDraft(admissionId));
    }

    @PostMapping("/admission/{admissionId}")
    @PreAuthorize("hasRole('NURSE')")
    public ResponseEntity<?> save(@PathVariable Long admissionId, @Valid @RequestBody AdmissionForm form) {
        return ResponseEntity.ok(admissionFormService.save(admissionId, form));
    }

    @PostMapping("/admission/{admissionId}/confirm")
    @PreAuthorize("hasRole('NURSE')")
    public ResponseEntity<?> markAdmitted(@PathVariable Long admissionId) {
        admissionFormService.markAdmitted(admissionId);
        return ResponseEntity.ok(java.util.Map.of("admissionConfirmed", true));
    }
}
