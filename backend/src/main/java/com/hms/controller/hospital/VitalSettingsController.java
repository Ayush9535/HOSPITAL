package com.hms.controller.hospital;

import jakarta.validation.Valid;

import com.hms.dto.VitalSettingRequest;
import com.hms.service.hospital.VitalSettingsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * VitalSettingsController - per-hospital OPD vitals config. Admin manages the
 * list; any staff role that fills or reads an OPD case reads the enabled set.
 * Not module-gated: OPD exists for hospital and clinic tenants.
 */
@RestController
@RequestMapping({"/hospital/vitals", "/clinic/vitals"})
public class VitalSettingsController {

    @Autowired private VitalSettingsService vitalSettingsService;

    @GetMapping
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(vitalSettingsService.list());
    }

    @GetMapping("/enabled")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','DOCTOR','RECEPTIONIST','NURSE','NURSE_INCHARGE')")
    public ResponseEntity<?> enabled() {
        return ResponseEntity.ok(vitalSettingsService.enabledVitals());
    }

    @PutMapping("/{vitalKey}")
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<?> toggle(@PathVariable String vitalKey, @Valid @RequestBody VitalSettingRequest req) {
        return ResponseEntity.ok(vitalSettingsService.toggle(vitalKey, req));
    }

    @PostMapping("/custom")
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<?> addCustom(@Valid @RequestBody VitalSettingRequest req) {
        return ResponseEntity.ok(vitalSettingsService.addCustom(req));
    }

    @DeleteMapping("/custom/{publicId}")
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<?> deleteCustom(@PathVariable String publicId) {
        vitalSettingsService.deleteCustom(publicId);
        return ResponseEntity.ok(Map.of("message", "Vital deleted"));
    }
}
