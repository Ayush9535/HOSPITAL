package com.hms.controller.hospital;

import com.hms.security.RequireModule;
import com.hms.dto.icu.IcuIoRequest;
import com.hms.service.hospital.icu.IcuIoService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/**
 * IcuIoController - fluid intake/output entries and their balance (ICU Phase 5).
 *
 * <p>Sits under {@code /hospital/nurse/**} beside vitals rather than under {@code /hospital/icu/**}
 * because the I/O chart is an admission-scoped IPD form gated by {@code IO_CHART} in Files &amp;
 * Access, not by the ICU module. That keeps it usable for a ward patient in a hospital that wants
 * it, exactly as the printed chart already is.
 *
 * <p>Roles mirror the vitals controller; the service applies the same form gate and the same
 * recording-nurse and edit-window rules on correction.
 */
@RestController
@RequestMapping("/hospital/nurse/io")
@RequireModule("ICU")
public class IcuIoController {

    @Autowired
    private IcuIoService icuIoService;

    @PostMapping
    @PreAuthorize("hasAnyRole('NURSE','NURSE_INCHARGE','DOCTOR','HOSPITAL_ADMIN')")
    public ResponseEntity<?> record(@Valid @RequestBody IcuIoRequest req) {
        return ResponseEntity.ok(icuIoService.record(req));
    }

    /** Every entry for the admission, newest first, including superseded ones. */
    @GetMapping("/admission/{admissionId}")
    @PreAuthorize("hasAnyRole('NURSE','NURSE_INCHARGE','DOCTOR','HOSPITAL_ADMIN','RECEPTIONIST')")
    public ResponseEntity<?> getByAdmission(@PathVariable Long admissionId) {
        return ResponseEntity.ok(icuIoService.getByAdmission(admissionId));
    }

    /** Intake total, output total and net. Computed from the entries, never stored. */
    @GetMapping("/admission/{admissionId}/balance")
    @PreAuthorize("hasAnyRole('NURSE','NURSE_INCHARGE','DOCTOR','HOSPITAL_ADMIN','RECEPTIONIST')")
    public ResponseEntity<?> balance(
            @PathVariable Long admissionId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return ResponseEntity.ok(icuIoService.balance(admissionId, from, to));
    }

    /**
     * Corrects an entry by writing a new one that supersedes it. The original is preserved and
     * stays readable; nothing is overwritten.
     */
    @PostMapping("/{publicId}/correction")
    @PreAuthorize("hasAnyRole('NURSE','NURSE_INCHARGE','DOCTOR','HOSPITAL_ADMIN')")
    public ResponseEntity<?> correct(@PathVariable String publicId, @Valid @RequestBody IcuIoRequest req) {
        return ResponseEntity.ok(icuIoService.correct(publicId, req));
    }
}
