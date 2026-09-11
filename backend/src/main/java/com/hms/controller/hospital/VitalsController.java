package com.hms.controller.hospital;

import jakarta.validation.Valid;

import com.hms.dto.VitalsRequest;
import com.hms.service.hospital.VitalsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * VitalsController - IPD vitals (Phase 1 Nurse module). HOSPITAL-tenant only,
 * NURSING-gated. Writes are nurse-only (and assignment-gated in the service);
 * reads are also available to doctors and admins.
 */
@RestController
@RequestMapping("/hospital/nurse/vitals")
public class VitalsController {

    @Autowired
    private VitalsService vitalsService;

    @PostMapping
    @PreAuthorize("hasAnyRole('NURSE','NURSE_INCHARGE','DOCTOR','HOSPITAL_ADMIN','RECEPTIONIST')")
    public ResponseEntity<?> create(@Valid @RequestBody VitalsRequest req) {
        return ResponseEntity.ok(vitalsService.create(req));
    }

    @GetMapping("/admission/{admissionId}")
    @PreAuthorize("hasAnyRole('NURSE','NURSE_INCHARGE','DOCTOR','HOSPITAL_ADMIN','RECEPTIONIST')")
    public ResponseEntity<?> getByAdmission(@PathVariable Long admissionId) {
        return ResponseEntity.ok(vitalsService.getByAdmission(admissionId));
    }

    @PutMapping("/{publicId}")
    @PreAuthorize("hasAnyRole('NURSE','NURSE_INCHARGE','DOCTOR','HOSPITAL_ADMIN','RECEPTIONIST')")
    public ResponseEntity<?> update(@PathVariable String publicId, @Valid @RequestBody VitalsRequest req) {
        return ResponseEntity.ok(vitalsService.update(publicId, req));
    }

    /**
     * ICU Phase 4 — corrects an observation recorded during an ICU stay by writing a new record
     * that supersedes it. The original is preserved.
     *
     * <p>Same roles as the edit above, and the service applies the same VITALS gate,
     * recording-nurse rule and edit window: this is not a wider door, it is a different one that
     * does not destroy history.
     */
    @PostMapping("/{publicId}/correction")
    @PreAuthorize("hasAnyRole('NURSE','NURSE_INCHARGE','DOCTOR','HOSPITAL_ADMIN','RECEPTIONIST')")
    public ResponseEntity<?> correct(@PathVariable String publicId, @Valid @RequestBody VitalsRequest req) {
        return ResponseEntity.ok(vitalsService.correct(publicId, req));
    }
}
