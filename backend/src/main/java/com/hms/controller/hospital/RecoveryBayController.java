package com.hms.controller.hospital;

import com.hms.entity.HospitalType;
import com.hms.security.RequireModule;
import com.hms.security.TenantType;
import com.hms.service.hospital.ot.RecoveryBayService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Recovery bays. HOSPITAL tenant only, OT-gated, never aliased onto /clinic.
 *
 * The list is OT_VIEW because whoever admits a patient to recovery (OT_RECOVERY) must first see
 * which bays are free -- exactly the reasoning OtRoomController gives for its own theatre list.
 */
@RestController
@RequestMapping("/hospital/ot/recovery-bays")
@TenantType(HospitalType.HOSPITAL)
@RequireModule("OT")
public class RecoveryBayController {
    @Autowired
    private RecoveryBayService service;

    @GetMapping
    @PreAuthorize("hasAuthority('OT_VIEW')")
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(service.list());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('OT_SETTINGS')")
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(service.create((String) body.get("name")));
    }

    @PutMapping("/{publicId}")
    @PreAuthorize("hasAuthority('OT_SETTINGS')")
    public ResponseEntity<?> update(@PathVariable String publicId, @RequestBody Map<String, Object> body) {
        Boolean isActive = body.get("isActive") == null ? null : Boolean.valueOf(String.valueOf(body.get("isActive")));
        return ResponseEntity.ok(service.update(publicId, (String) body.get("name"), isActive));
    }

    @DeleteMapping("/{publicId}")
    @PreAuthorize("hasAuthority('OT_SETTINGS')")
    public ResponseEntity<?> deactivate(@PathVariable String publicId) {
        service.deactivate(publicId);
        return ResponseEntity.ok(Map.of("message", "Recovery bay deactivated"));
    }
}
