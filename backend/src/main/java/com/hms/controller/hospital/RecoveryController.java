package com.hms.controller.hospital;

import com.hms.entity.HospitalType;
import com.hms.security.RequireModule;
import com.hms.security.TenantType;
import com.hms.service.hospital.ot.RecoveryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * PACU recovery for a case. HOSPITAL tenant only, OT-gated, never aliased onto /clinic.
 * Recovery is a record, not a case state: the theatre is free while the patient is here.
 */
@RestController
@RequestMapping("/hospital/ot/surgeries/{surgeryId}/recovery")
@TenantType(HospitalType.HOSPITAL)
@RequireModule("OT")
public class RecoveryController {

    @Autowired
    private RecoveryService service;

    @GetMapping
    @PreAuthorize("hasAuthority('OT_VIEW')")
    public ResponseEntity<?> episode(@PathVariable Long surgeryId) {
        return ResponseEntity.ok(service.episode(surgeryId));
    }

    @PostMapping("/admit")
    @PreAuthorize("hasAuthority('OT_RECOVERY')")
    public ResponseEntity<?> admit(@PathVariable Long surgeryId, @RequestBody Map<String, Object> body) {
        // The bay picker (GET /hospital/ot/recovery-bays) addresses bays by publicId, matching
        // every other resource in this codebase (OtRoom, etc.) -- never a raw internal id.
        String recoveryBayPublicId = body.get("recoveryBayId") == null ? null
                : String.valueOf(body.get("recoveryBayId"));
        return ResponseEntity.ok(service.admit(surgeryId, recoveryBayPublicId));
    }

    @PostMapping("/observe")
    @PreAuthorize("hasAuthority('OT_RECOVERY')")
    public ResponseEntity<?> observe(@PathVariable Long surgeryId, @RequestBody Map<String, Object> body) {
        Integer score = body.get("aldreteScore") == null ? null
                : Integer.valueOf(String.valueOf(body.get("aldreteScore")));
        Long nurseId = body.get("performedByNurseId") == null ? null
                : Long.valueOf(String.valueOf(body.get("performedByNurseId")));
        return ResponseEntity.ok(service.observe(surgeryId, score, nurseId, (String) body.get("note")));
    }

    @PostMapping("/discharge")
    @PreAuthorize("hasAuthority('OT_TRANSFER')")
    public ResponseEntity<?> discharge(@PathVariable Long surgeryId, @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(service.discharge(surgeryId, body.get("destination")));
    }
}
