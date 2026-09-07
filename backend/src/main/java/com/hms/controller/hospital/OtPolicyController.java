package com.hms.controller.hospital;

import com.hms.entity.HospitalType;
import com.hms.security.RequireModule;
import com.hms.security.TenantType;
import com.hms.service.hospital.ot.OtAnalyticsService;
import com.hms.service.hospital.ot.OtPolicies;
import com.hms.service.hospital.ot.OtPolicyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OT workflow policies and the day-one analytics. HOSPITAL tenant only, OT-gated.
 *
 * Policies are how one codebase serves a 10-bed clinic and a 1000-bed chain: the engine
 * reads policy, never a role.
 */
@RestController
@RequestMapping("/hospital/ot")
@TenantType(HospitalType.HOSPITAL)
@RequireModule("OT")
public class OtPolicyController {

    @Autowired private OtPolicyService policyService;
    @Autowired private OtAnalyticsService analyticsService;

    /** The catalogue plus the hospital's current values and any emergency overrides. */
    @GetMapping("/policies")
    @PreAuthorize("hasAuthority('OT_SETTINGS')")
    public ResponseEntity<?> policies() {
        List<Map<String, Object>> catalogue = new java.util.ArrayList<>();
        for (OtPolicies.Policy p : OtPolicies.all()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", p.key());
            row.put("label", p.label());
            row.put("values", p.values());
            row.put("defaultValue", p.defaultValue());
            catalogue.add(row);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("catalogue", catalogue);
        body.put("values", policyService.effectiveDefaults());
        body.put("emergencyOverrides", policyService.emergencyOverrides());
        body.put("archetypes", OtPolicies.archetypeNames());
        return ResponseEntity.ok(body);
    }

    @PutMapping("/policies")
    @PreAuthorize("hasAuthority('OT_SETTINGS')")
    public ResponseEntity<?> update(@RequestBody Map<String, String> values) {
        return ResponseEntity.ok(policyService.updateDefaults(values));
    }

    @PostMapping("/policies/archetype/{name}")
    @PreAuthorize("hasAuthority('OT_SETTINGS')")
    public ResponseEntity<?> applyArchetype(@PathVariable String name) {
        return ResponseEntity.ok(policyService.applyArchetype(name));
    }

    @PostMapping("/policies/reset")
    @PreAuthorize("hasAuthority('OT_SETTINGS')")
    public ResponseEntity<?> reset() {
        return ResponseEntity.ok(policyService.resetToDefaults());
    }

    /** Today's four numbers. The first thing an owner asks for. */
    @GetMapping("/analytics/summary")
    @PreAuthorize("hasAuthority('OT_VIEW')")
    public ResponseEntity<?> analytics(
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(
                    iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate date) {
        return ResponseEntity.ok(analyticsService.summary(date));
    }

    /** NABH surgical-care indicators over a range: WHO compliance %, cancellation rate by reason. */
    @GetMapping("/analytics/nabh")
    @PreAuthorize("hasAuthority('OT_VIEW')")
    public ResponseEntity<?> nabh(
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(
                    iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(
                    iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to) {
        return ResponseEntity.ok(analyticsService.nabhIndicators(from, to));
    }
}
