package com.hms.controller.platform;

import com.hms.dto.AssignPlanRequest;
import com.hms.dto.CreatePlanRequest;
import com.hms.entitlement.EntitlementRegistry;
import com.hms.entity.HospitalType;
import com.hms.entity.Plan;
import com.hms.service.platform.PlatformPlanService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/platform/plans")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class PlatformPlanController {

    @Autowired
    private PlatformPlanService planService;

    @GetMapping
    public ResponseEntity<List<Plan>> getAllPlans(
            @RequestParam(required = false) String type) {
        if (type != null && !type.isBlank()) {
            return ResponseEntity.ok(planService.getPlansByType(HospitalType.valueOf(type)));
        }
        return ResponseEntity.ok(planService.getAllPlans());
    }

    @GetMapping("/capabilities")
    public ResponseEntity<?> getCapabilities(@RequestParam String type) {
        try {
            return ResponseEntity.ok(EntitlementRegistry.catalogFor(HospitalType.valueOf(type)));
        } catch (Exception e) {
            return com.hms.util.ApiErrors.handle(e);
        }
    }

    @PostMapping
    public ResponseEntity<?> createPlan(@Valid @RequestBody CreatePlanRequest request) {
        try {
            Plan plan = planService.createPlan(request);
            return ResponseEntity.ok(plan);
        } catch (Exception e) {
            return com.hms.util.ApiErrors.handle(e);
        }
    }

    @PutMapping("/{publicId}")
    public ResponseEntity<?> updatePlan(
            @PathVariable String publicId,
            @Valid @RequestBody CreatePlanRequest request) {
        try {
            Plan plan = planService.updatePlan(publicId, request);
            return ResponseEntity.ok(plan);
        } catch (Exception e) {
            return com.hms.util.ApiErrors.handle(e);
        }
    }

    @DeleteMapping("/{publicId}")
    public ResponseEntity<?> deletePlan(@PathVariable String publicId) {
        try {
            planService.deletePlan(publicId);
            return ResponseEntity.ok("Plan deleted successfully");
        } catch (Exception e) {
            return com.hms.util.ApiErrors.handle(e);
        }
    }

    @PostMapping("/{publicId}/assign")
    public ResponseEntity<?> assignPlan(
            @PathVariable String publicId,
            @Valid @RequestBody AssignPlanRequest request) {
        try {
            return ResponseEntity.ok(planService.assignPlan(publicId, request));
        } catch (Exception e) {
            return com.hms.util.ApiErrors.handle(e);
        }
    }
}
