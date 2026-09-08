package com.hms.controller.hospital;

import jakarta.validation.Valid;

import com.hms.dto.SugarChartRequest;
import com.hms.service.hospital.SugarChartService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * SugarChartController - blood-sugar chart (Phase 1 Nurse module). HOSPITAL
 * tenant only, NURSING-gated. Writes are nurse-only (assignment-gated in the
 * service); reads also open to doctors and admins.
 */
@RestController
@RequestMapping("/hospital/nurse/sugar-chart")
public class SugarChartController {

    @Autowired
    private SugarChartService service;

    @PostMapping
    @PreAuthorize("hasAnyRole('NURSE','NURSE_INCHARGE','DOCTOR','HOSPITAL_ADMIN','RECEPTIONIST')")
    public ResponseEntity<?> create(@Valid @RequestBody SugarChartRequest req) {
        return ResponseEntity.ok(service.create(req));
    }

    @GetMapping("/admission/{admissionId}")
    @PreAuthorize("hasAnyRole('NURSE','NURSE_INCHARGE','DOCTOR','HOSPITAL_ADMIN','RECEPTIONIST')")
    public ResponseEntity<?> getByAdmission(@PathVariable Long admissionId) {
        return ResponseEntity.ok(service.getByAdmission(admissionId));
    }

    @PutMapping("/{publicId}")
    @PreAuthorize("hasAnyRole('NURSE','NURSE_INCHARGE','DOCTOR','HOSPITAL_ADMIN','RECEPTIONIST')")
    public ResponseEntity<?> update(@PathVariable String publicId, @Valid @RequestBody SugarChartRequest req) {
        return ResponseEntity.ok(service.update(publicId, req));
    }

    @DeleteMapping("/{publicId}")
    @PreAuthorize("hasAnyRole('NURSE','NURSE_INCHARGE','DOCTOR','HOSPITAL_ADMIN','RECEPTIONIST')")
    public ResponseEntity<?> delete(@PathVariable String publicId) {
        service.softDelete(publicId);
        return ResponseEntity.ok(Map.of("message", "Entry deleted"));
    }
}
