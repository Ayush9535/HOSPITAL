package com.hms.controller.hospital;

import com.hms.security.RequireModule;
import com.hms.dto.icu.IcuInfusionRequest;
import com.hms.service.hospital.icu.IcuInfusionService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/**
 * IcuInfusionController - continuous infusions and their rate history (ICU Phase 6).
 *
 * <p>Under {@code /hospital/nurse/**} beside vitals and I/O: an infusion is an admission-scoped
 * clinical record gated by the existing {@code MEDICATION} Files &amp; Access key (D-3), not by
 * the ICU module. Same roles as the medication chart it sits next to.
 *
 * <p>Every endpoint is additive. Nothing about the MAR, vitals, I/O, the ICU board or the stay
 * lifecycle changes shape.
 */
@RestController
@RequestMapping("/hospital/nurse/infusions")
@RequireModule("ICU")
public class IcuInfusionController {

    @Autowired
    private IcuInfusionService icuInfusionService;

    @PostMapping
    @PreAuthorize("hasAnyRole('NURSE','NURSE_INCHARGE','DOCTOR','HOSPITAL_ADMIN')")
    public ResponseEntity<?> start(@Valid @RequestBody IcuInfusionRequest req) {
        return ResponseEntity.ok(icuInfusionService.start(req));
    }

    /** Every infusion for the admission, running and stopped, newest first. */
    @GetMapping("/admission/{admissionId}")
    @PreAuthorize("hasAnyRole('NURSE','NURSE_INCHARGE','DOCTOR','HOSPITAL_ADMIN','RECEPTIONIST')")
    public ResponseEntity<?> getByAdmission(@PathVariable Long admissionId) {
        return ResponseEntity.ok(icuInfusionService.getByAdmission(admissionId));
    }

    @GetMapping("/admission/{admissionId}/running")
    @PreAuthorize("hasAnyRole('NURSE','NURSE_INCHARGE','DOCTOR','HOSPITAL_ADMIN','RECEPTIONIST')")
    public ResponseEntity<?> getRunning(@PathVariable Long admissionId) {
        return ResponseEntity.ok(icuInfusionService.getRunning(admissionId));
    }

    /** The rate history, newest first, including rows a correction superseded. */
    @GetMapping("/{publicId}/rates")
    @PreAuthorize("hasAnyRole('NURSE','NURSE_INCHARGE','DOCTOR','HOSPITAL_ADMIN','RECEPTIONIST')")
    public ResponseEntity<?> rateHistory(@PathVariable String publicId) {
        return ResponseEntity.ok(icuInfusionService.rateHistory(publicId));
    }

    /** The rate in force at a given instant, which is the point of keeping a history. */
    @GetMapping("/{publicId}/rate-at")
    @PreAuthorize("hasAnyRole('NURSE','NURSE_INCHARGE','DOCTOR','HOSPITAL_ADMIN','RECEPTIONIST')")
    public ResponseEntity<?> rateAt(
            @PathVariable String publicId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime at) {
        return ResponseEntity.ok(icuInfusionService.rateAt(publicId, at));
    }

    /** Titrating APPENDS a rate; the previous one stays in the history untouched. */
    @PostMapping("/{publicId}/rate")
    @PreAuthorize("hasAnyRole('NURSE','NURSE_INCHARGE','DOCTOR','HOSPITAL_ADMIN')")
    public ResponseEntity<?> titrate(@PathVariable String publicId,
                                     @Valid @RequestBody IcuInfusionRequest req) {
        return ResponseEntity.ok(icuInfusionService.titrate(publicId, req));
    }

    @PostMapping("/{publicId}/stop")
    @PreAuthorize("hasAnyRole('NURSE','NURSE_INCHARGE','DOCTOR','HOSPITAL_ADMIN')")
    public ResponseEntity<?> stop(@PathVariable String publicId,
                                  @RequestBody(required = false) IcuInfusionRequest req) {
        return ResponseEntity.ok(
                icuInfusionService.stop(publicId, req != null ? req : new IcuInfusionRequest()));
    }

    /** Corrects a recorded rate; the original stays readable. */
    @PostMapping("/rate/{ratePublicId}/correction")
    @PreAuthorize("hasAnyRole('NURSE','NURSE_INCHARGE','DOCTOR','HOSPITAL_ADMIN')")
    public ResponseEntity<?> correctRate(@PathVariable String ratePublicId,
                                         @Valid @RequestBody IcuInfusionRequest req) {
        return ResponseEntity.ok(icuInfusionService.correctRate(ratePublicId, req));
    }

    /** The rate-unit catalogue for the entry form. Units are stored as entered, never converted. */
    @GetMapping("/rate-units")
    @PreAuthorize("hasAnyRole('NURSE','NURSE_INCHARGE','DOCTOR','HOSPITAL_ADMIN','RECEPTIONIST')")
    public ResponseEntity<?> rateUnits() {
        return ResponseEntity.ok(icuInfusionService.rateUnits());
    }
}
