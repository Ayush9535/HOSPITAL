package com.hms.controller.hospital;

import com.hms.security.RequireModule;
import com.hms.dto.icu.IcuSeverityScoreRequest;
import com.hms.service.hospital.icu.IcuSeverityScoreService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/**
 * IcuSeverityScoreController - timed severity scores (ICU Phase 8).
 *
 * <p>Under {@code /hospital/nurse/**} beside vitals, I/O, infusions and the ventilator chart: a
 * score is an admission-scoped clinical record gated by the {@code SEVERITY_SCORE} Files &amp;
 * Access key (D-3), not by the ICU module.
 *
 * <p>Every endpoint is additive. Nothing about vitals (where GCS stays, D-1), I/O, the MAR,
 * infusions, the ventilator chart, the ICU board or the stay lifecycle changes shape.
 */
@RestController
@RequestMapping("/hospital/nurse/severity-scores")
@RequireModule("ICU")
public class IcuSeverityScoreController {

    @Autowired
    private IcuSeverityScoreService severityScoreService;

    @PostMapping
    @PreAuthorize("hasAnyRole('NURSE','NURSE_INCHARGE','DOCTOR','HOSPITAL_ADMIN')")
    public ResponseEntity<?> record(@Valid @RequestBody IcuSeverityScoreRequest req) {
        return ResponseEntity.ok(severityScoreService.record(req));
    }

    /**
     * The whole chart: every scoring with its parsed components, the score-type registry as it
     * stands, the latest per type, and the superseded ids. One call, so the panel never renders
     * from a component list of its own.
     */
    @GetMapping("/admission/{admissionId}")
    @PreAuthorize("hasAnyRole('NURSE','NURSE_INCHARGE','DOCTOR','HOSPITAL_ADMIN','RECEPTIONIST')")
    public ResponseEntity<?> chart(@PathVariable Long admissionId) {
        return ResponseEntity.ok(severityScoreService.chartFor(admissionId));
    }

    @GetMapping("/admission/{admissionId}/latest")
    @PreAuthorize("hasAnyRole('NURSE','NURSE_INCHARGE','DOCTOR','HOSPITAL_ADMIN','RECEPTIONIST')")
    public ResponseEntity<?> latest(@PathVariable Long admissionId) {
        return ResponseEntity.ok(severityScoreService.latestByType(admissionId));
    }

    /** The score of a type in force at a given instant, which is the point of keeping a history. */
    @GetMapping("/admission/{admissionId}/at")
    @PreAuthorize("hasAnyRole('NURSE','NURSE_INCHARGE','DOCTOR','HOSPITAL_ADMIN','RECEPTIONIST')")
    public ResponseEntity<?> scoreAt(
            @PathVariable Long admissionId,
            @RequestParam String scoreType,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime at) {
        return ResponseEntity.ok(severityScoreService.scoreAt(admissionId, scoreType, at));
    }

    /** Corrects a recorded scoring; the original stays readable. */
    @PostMapping("/{publicId}/correction")
    @PreAuthorize("hasAnyRole('NURSE','NURSE_INCHARGE','DOCTOR','HOSPITAL_ADMIN')")
    public ResponseEntity<?> correct(@PathVariable String publicId,
                                     @Valid @RequestBody IcuSeverityScoreRequest req) {
        return ResponseEntity.ok(severityScoreService.correct(publicId, req));
    }

    /** The enabled score types with their components and ranges, for the entry form. */
    @GetMapping("/types")
    @PreAuthorize("hasAnyRole('NURSE','NURSE_INCHARGE','DOCTOR','HOSPITAL_ADMIN','RECEPTIONIST')")
    public ResponseEntity<?> types() {
        return ResponseEntity.ok(severityScoreService.enabledTypes());
    }
}
