package com.hms.controller.hospital;

import com.hms.entity.HospitalType;
import com.hms.entity.User;
import com.hms.security.TenantType;
import com.hms.service.hospital.OtInchargeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.Map;

/**
 * OT incharge staff CRUD. HOSPITAL tenants only: this was previously aliased onto
 * /clinic/** and /pharmacy/**, which let a clinic admin manage OT staff.
 */
@RestController
@RequestMapping("/hospital/ot-incharges")
@TenantType(HospitalType.HOSPITAL)
@PreAuthorize("hasRole('HOSPITAL_ADMIN')")
public class OtInchargeController {

    @Autowired
    private OtInchargeService otInchargeService;

    @PostMapping
    public ResponseEntity<?> createOtIncharge(@RequestBody Map<String, String> payload) {
        String name = payload.get("name");
        String email = payload.get("email");
        String password = payload.get("password");

        if (name == null || email == null || password == null) {
            return ResponseEntity.badRequest().body("Name, Email, and Password are required");
        }

        User created = otInchargeService.createOtIncharge(name, email, password);
        return ResponseEntity.ok(created);
    }

    @GetMapping
    public ResponseEntity<?> getAllOtIncharges(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(otInchargeService.getAllOtIncharges(search, pageable));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteOtIncharge(@PathVariable String id,
            @RequestParam(required = false) String reason) {
        otInchargeService.deleteOtIncharge(id, reason);
        return ResponseEntity.ok("OT Incharge deleted successfully");
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getOtInchargeById(@PathVariable String id) {
        User otIncharge = otInchargeService.getOtInchargeByPublicId(id);
        return ResponseEntity.ok(otIncharge);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateOtIncharge(@PathVariable String id, @RequestBody Map<String, String> payload) {
        String name = payload.get("name");
        if (name == null || name.trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Name is required");
        }
        User updated = otInchargeService.updateOtIncharge(id, name);
        return ResponseEntity.ok(updated);
    }

    @PostMapping("/{id}/reset-password")
    public ResponseEntity<?> resetOtInchargePassword(@PathVariable String id, @RequestBody java.util.Map<String, String> body) {
        String newPassword = body.get("newPassword");
        if (newPassword == null || newPassword.trim().length() < 6) {
            return ResponseEntity.badRequest().body("Password must be at least 6 characters");
        }
        otInchargeService.resetOtInchargePassword(id, newPassword);
        return ResponseEntity.ok(java.util.Map.of("message", "Password reset successfully"));
    }
}
