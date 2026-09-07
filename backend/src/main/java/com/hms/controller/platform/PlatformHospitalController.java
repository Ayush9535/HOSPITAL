package com.hms.controller.platform;

import com.hms.dto.CreateHospitalRequest;
import com.hms.entity.Hospital;
import com.hms.service.platform.PlatformHospitalService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@RestController
@RequestMapping("/platform/hospitals")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class PlatformHospitalController {

    @Autowired
    private PlatformHospitalService hospitalService;

    @PostMapping
    public ResponseEntity<?> createHospital(@Valid @RequestBody CreateHospitalRequest request) {
        Hospital hospital = hospitalService.createHospital(request);
        return ResponseEntity.ok(hospital);
    }

    @GetMapping
    public ResponseEntity<Page<Hospital>> getAllHospitals(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String type) {
        Pageable pageable = PageRequest.of(page, size);
        Page<Hospital> hospitals = hospitalService.getAllHospitals(pageable, type);
        return ResponseEntity.ok(hospitals);
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Map<String, Long>>> getHospitalStats() {
        Map<String, Map<String, Long>> stats = hospitalService.getHospitalStats();
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getHospitalById(@PathVariable String id) {
        com.hms.dto.HospitalDetailsDTO hospital = hospitalService.getHospitalDetails(id);
        return ResponseEntity.ok(hospital);
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<?> updateHospitalStatus(
            @PathVariable String id,
            @RequestBody Map<String, Object> request) {
        Boolean isActive = (Boolean) request.get("isActive");
        String reason = (String) request.get("reason");
        if (isActive == null) {
            return ResponseEntity.badRequest().body("isActive field is required");
        }
        Hospital hospital = hospitalService.updateHospitalStatus(id, isActive, reason);
        return ResponseEntity.ok(hospital);
    }

    @PutMapping("/{id}/details")
    public ResponseEntity<?> updateHospitalDetails(
            @PathVariable String id,
            @RequestBody Map<String, Object> request) {
        String name = (String) request.get("name");
        String adminEmail = (String) request.get("adminEmail");
        String adminName = (String) request.get("adminName");
        String reason = (String) request.get("reason");
        Boolean isSingleDoctor = (Boolean) request.get("isSingleDoctor");

        if (name == null || name.trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Hospital name is required");
        }
        if (adminEmail == null || adminEmail.trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Admin email is required");
        }
        if (adminName == null || adminName.trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Admin name is required");
        }

        Hospital hospital = hospitalService.updateHospitalDetails(id, name, adminEmail, adminName, reason, isSingleDoctor);
        return ResponseEntity.ok(hospital);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteHospital(@PathVariable String id) {
        hospitalService.deleteHospital(id);
        return ResponseEntity.ok(Map.of("message", "Hospital deleted successfully"));
    }

    @PostMapping("/{id}/reset-password")
    public ResponseEntity<?> resetTenantAdminPassword(@PathVariable String id,
            @RequestBody(required = false) Map<String, String> body) {
        String password = body != null ? body.get("password") : null;
        String reason   = body != null ? body.get("reason")   : null;
        if (password == null || password.trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Password is required");
        }
        Map<String, String> result = hospitalService.resetTenantAdminPassword(id, password, reason);
        return ResponseEntity.ok(result);
    }
}
