package com.hms.controller.hospital;

import com.hms.entity.HospitalType;
import com.hms.security.OtPermissions;
import com.hms.security.RequireModule;
import com.hms.security.TenantType;
import com.hms.service.hospital.ot.OtPermissionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OT permission matrix: which role may do what, per hospital.
 * HOSPITAL tenant only, OT-gated. Never aliased onto /clinic or /pharmacy.
 */
@RestController
@RequestMapping("/hospital/ot/permissions")
@TenantType(HospitalType.HOSPITAL)
@RequireModule("OT")
public class OtPermissionController {

    @Autowired
    private OtPermissionService service;

    /** The caller's own effective permissions, so the UI renders by capability, not by role. */
    @GetMapping("/me")
    public ResponseEntity<?> myPermissions() {
        return ResponseEntity.ok(service.myPermissions());
    }

    /** The permission catalogue, for the admin matrix header. */
    @GetMapping("/catalogue")
    @PreAuthorize("hasAuthority('OT_SETTINGS')")
    public ResponseEntity<?> catalogue() {
        List<Map<String, String>> out = new ArrayList<>();
        for (String code : OtPermissions.ALL) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("code", code);
            row.put("description", OtPermissions.describe(code));
            out.add(row);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("permissions", out);
        body.put("roles", OtPermissions.ROLES);
        return ResponseEntity.ok(body);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('OT_SETTINGS')")
    public ResponseEntity<?> matrix() {
        return ResponseEntity.ok(service.matrix());
    }

    /** Whole-matrix replace: a partial write cannot express "this role now has nothing". */
    @PutMapping
    @PreAuthorize("hasAuthority('OT_SETTINGS')")
    public ResponseEntity<?> update(@RequestBody Map<String, List<String>> matrix) {
        return ResponseEntity.ok(service.updateMatrix(matrix));
    }

    @PostMapping("/reset")
    @PreAuthorize("hasAuthority('OT_SETTINGS')")
    public ResponseEntity<?> reset() {
        return ResponseEntity.ok(service.resetToDefaults());
    }
}
