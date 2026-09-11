package com.hms.controller.hospital;

import com.hms.security.RequireModule;
import com.hms.dto.icu.IcuVentilatorRequest;
import com.hms.service.hospital.icu.IcuVentilatorService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/**
 * IcuVentilatorController - timed ventilator snapshots (ICU Phase 7).
 *
 * <p>Under {@code /hospital/nurse/**} beside vitals, I/O and infusions: a ventilator record is an
 * admission-scoped clinical record gated by the {@code VENTILATOR} Files &amp; Access key (D-3),
 * not by the ICU module.
 *
 * <p>Every endpoint is additive. Nothing about vitals, I/O, the MAR, infusions, the ICU board or
 * the stay lifecycle changes shape.
 */
@RestController
@RequestMapping("/hospital/nurse/ventilator")
@RequireModule("ICU")
public class IcuVentilatorController {

    @Autowired
    private IcuVentilatorService icuVentilatorService;

    @PostMapping
    @PreAuthorize("hasAnyRole('NURSE','NURSE_INCHARGE','DOCTOR','HOSPITAL_ADMIN')")
    public ResponseEntity<?> record(@Valid @RequestBody IcuVentilatorRequest req) {
        return ResponseEntity.ok(icuVentilatorService.record(req));
    }

    /**
     * The whole chart: every entry with its parsed values, the label map for every key any entry
     * holds, and the superseded ids. One call, so a disabled or renamed parameter can never be
     * rendered from a stale catalogue.
     */
    @GetMapping("/admission/{admissionId}")
    @PreAuthorize("hasAnyRole('NURSE','NURSE_INCHARGE','DOCTOR','HOSPITAL_ADMIN','RECEPTIONIST')")
    public ResponseEntity<?> chart(@PathVariable Long admissionId) {
        return ResponseEntity.ok(icuVentilatorService.chartFor(admissionId));
    }

    @GetMapping("/admission/{admissionId}/current")
    @PreAuthorize("hasAnyRole('NURSE','NURSE_INCHARGE','DOCTOR','HOSPITAL_ADMIN','RECEPTIONIST')")
    public ResponseEntity<?> current(@PathVariable Long admissionId) {
        return ResponseEntity.ok(icuVentilatorService.current(admissionId));
    }

    /** The setting in force at a given instant, which is the point of keeping a history. */
    @GetMapping("/admission/{admissionId}/at")
    @PreAuthorize("hasAnyRole('NURSE','NURSE_INCHARGE','DOCTOR','HOSPITAL_ADMIN','RECEPTIONIST')")
    public ResponseEntity<?> settingAt(
            @PathVariable Long admissionId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime at) {
        return ResponseEntity.ok(icuVentilatorService.settingAt(admissionId, at));
    }

    /** Corrects a recorded snapshot; the original stays readable. */
    @PostMapping("/{publicId}/correction")
    @PreAuthorize("hasAnyRole('NURSE','NURSE_INCHARGE','DOCTOR','HOSPITAL_ADMIN')")
    public ResponseEntity<?> correct(@PathVariable String publicId,
                                     @Valid @RequestBody IcuVentilatorRequest req) {
        return ResponseEntity.ok(icuVentilatorService.correct(publicId, req));
    }
}
