package com.hms.controller.hospital;

import jakarta.validation.Valid;

import com.hms.dto.SubstitutionRequest;
import com.hms.dto.TempWardAssignmentRequest;
import com.hms.security.RequireModule;
import com.hms.service.hospital.NurseCoverageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * NurseCoverageController - incharge/admin manage temporary ward assignments and
 * nurse substitutions (Nursing Mgmt Phase F). Nurse-facing {@code /mine} exposes
 * the logged-in nurse's active coverage for the dashboard banner.
 */
@RestController
@RequestMapping("/hospital/nurse-coverage")
@RequireModule("NURSING")
public class NurseCoverageController {

    @Autowired private NurseCoverageService coverageService;

    @GetMapping("/temp-assignments")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')")
    public ResponseEntity<?> listTempAssignments() {
        return ResponseEntity.ok(coverageService.listTempAssignments());
    }

    @PostMapping("/temp-assignments")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')")
    public ResponseEntity<?> createTempAssignment(@Valid @RequestBody TempWardAssignmentRequest req) {
        return ResponseEntity.ok(coverageService.createTempAssignment(
                req.getNurseProfileId(), req.getTempWardId(), req.getFromDate(), req.getToDate(), req.getReason()));
    }

    @DeleteMapping("/temp-assignments/{publicId}")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')")
    public ResponseEntity<?> removeTempAssignment(@PathVariable String publicId) {
        coverageService.removeTempAssignment(publicId);
        return ResponseEntity.ok(Map.of("message", "Temporary assignment removed"));
    }

    @GetMapping("/substitutions")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')")
    public ResponseEntity<?> listSubstitutions() {
        return ResponseEntity.ok(coverageService.listSubstitutions());
    }

    @PostMapping("/substitutions")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')")
    public ResponseEntity<?> createSubstitution(@Valid @RequestBody SubstitutionRequest req) {
        return ResponseEntity.ok(coverageService.createSubstitution(
                req.getPrimaryNurseProfileId(), req.getReplacementNurseProfileId(),
                req.getFromDate(), req.getToDate(), req.getReason()));
    }

    @DeleteMapping("/substitutions/{publicId}")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')")
    public ResponseEntity<?> removeSubstitution(@PathVariable String publicId) {
        coverageService.removeSubstitution(publicId);
        return ResponseEntity.ok(Map.of("message", "Substitution removed"));
    }

    @GetMapping("/mine")
    @PreAuthorize("hasAnyRole('NURSE','NURSE_INCHARGE')")
    public ResponseEntity<?> myCoverage() {
        return ResponseEntity.ok(coverageService.myActiveCoverage());
    }
}
