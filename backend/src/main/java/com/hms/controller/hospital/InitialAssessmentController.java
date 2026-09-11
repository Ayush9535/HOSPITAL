package com.hms.controller.hospital;

import jakarta.validation.Valid;

import com.hms.entity.InitialAssessment;
import com.hms.service.hospital.InitialAssessmentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * InitialAssessmentController - nurse captures the Admission History & Initial
 * Assessment clinical fields (Phase 1). HOSPITAL-tenant only, NURSING-gated,
 * nurse-only (assignment-gated in the service).
 */
@RestController
@RequestMapping("/hospital/nurse/initial-assessment")
@PreAuthorize("hasAnyRole('NURSE','NURSE_INCHARGE','DOCTOR','HOSPITAL_ADMIN','RECEPTIONIST')")
public class InitialAssessmentController {

    @Autowired
    private InitialAssessmentService service;

    @GetMapping("/admission/{admissionId}")
    public ResponseEntity<?> get(@PathVariable Long admissionId) {
        return ResponseEntity.ok(service.getOrDraft(admissionId));
    }

    @PostMapping("/admission/{admissionId}")
    public ResponseEntity<?> save(@PathVariable Long admissionId, @Valid @RequestBody InitialAssessment body) {
        return ResponseEntity.ok(service.save(admissionId, body));
    }
}
