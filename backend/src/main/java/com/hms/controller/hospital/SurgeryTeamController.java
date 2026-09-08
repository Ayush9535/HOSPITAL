package com.hms.controller.hospital;

import com.hms.entity.HospitalType;
import com.hms.security.RequireModule;
import com.hms.security.TenantType;
import com.hms.service.hospital.ot.SurgeryTeamService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Surgical team on a case, and a hospital's custom case roles.
 * HOSPITAL tenant only, OT-gated, never aliased onto /clinic.
 */
@RestController
@RequestMapping("/hospital/ot")
@TenantType(HospitalType.HOSPITAL)
@RequireModule("OT")
public class SurgeryTeamController {

    @Autowired
    private SurgeryTeamService service;

    /** Built-in roles merged with the hospital's custom ones. */
    @GetMapping("/case-roles")
    @PreAuthorize("hasAuthority('OT_VIEW')")
    public ResponseEntity<?> roles() {
        return ResponseEntity.ok(service.availableRoles());
    }

    @PostMapping("/case-roles")
    @PreAuthorize("hasAuthority('OT_SETTINGS')")
    public ResponseEntity<?> addRole(@RequestBody Map<String, String> body) {
        return ResponseEntity.ok(service.addCustomRole(body.get("label")));
    }

    @GetMapping("/surgeries/{surgeryId}/team")
    @PreAuthorize("hasAuthority('OT_VIEW')")
    public ResponseEntity<?> team(@PathVariable Long surgeryId) {
        return ResponseEntity.ok(service.team(surgeryId));
    }

    @PostMapping("/surgeries/{surgeryId}/team")
    @PreAuthorize("hasAuthority('OT_ASSIGN_TEAM')")
    public ResponseEntity<?> assign(@PathVariable Long surgeryId, @RequestBody Map<String, Object> body) {
        String roleCode = (String) body.get("caseRoleCode");
        Long userId;
        try {
            userId = body.get("userId") == null ? null : Long.valueOf(String.valueOf(body.get("userId")));
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("message", "Invalid userId"));
        }
        String externalName = (String) body.get("externalName");
        return ResponseEntity.ok(service.assign(surgeryId, roleCode, userId, externalName));
    }

    @DeleteMapping("/surgeries/{surgeryId}/team/{memberId}")
    @PreAuthorize("hasAuthority('OT_ASSIGN_TEAM')")
    public ResponseEntity<?> remove(@PathVariable Long surgeryId, @PathVariable Long memberId) {
        service.remove(surgeryId, memberId);
        return ResponseEntity.ok(Map.of("message", "Removed"));
    }
}
