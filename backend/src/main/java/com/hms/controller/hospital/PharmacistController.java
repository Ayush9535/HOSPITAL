package com.hms.controller.hospital;

import com.hms.entity.User;
import com.hms.service.hospital.PharmacistService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.Map;

@RestController
@RequestMapping({"/hospital/pharmacists", "/clinic/pharmacists", "/pharmacy/pharmacists"})
@PreAuthorize("hasRole('HOSPITAL_ADMIN')")
public class PharmacistController {

    @Autowired
    private PharmacistService pharmacistService;

    @PostMapping
    public ResponseEntity<?> createPharmacist(@RequestBody Map<String, String> payload) {
        String name = payload.get("name");
        String email = payload.get("email");
        String password = payload.get("password");

        if (name == null || email == null || password == null) {
            return ResponseEntity.badRequest().body("Name, Email, and Password are required");
        }

        // phone was previously dropped here — the admin form sends it, the API ignored it.
        User created = pharmacistService.createPharmacist(name, email, password, payload.get("phone"));
        return ResponseEntity.ok(pharmacistService.toView(created));
    }

    @GetMapping
    public ResponseEntity<?> getAllPharmacists(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(pharmacistService.getAllPharmacists(search, pageable));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deletePharmacist(@PathVariable String id,
            @RequestParam(required = false) String reason) {
        pharmacistService.deletePharmacist(id, reason);
        return ResponseEntity.ok("Pharmacist deleted successfully");
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getPharmacistById(@PathVariable String id) {
        return ResponseEntity.ok(pharmacistService.getPharmacistViewByPublicId(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updatePharmacist(@PathVariable String id, @RequestBody Map<String, String> payload) {
        String name = payload.get("name");
        if (name == null || name.trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Name is required");
        }
        User updated = pharmacistService.updatePharmacist(id, name, payload.get("phone"));
        return ResponseEntity.ok(pharmacistService.toView(updated));
    }

    @PostMapping("/{id}/reset-password")
    public ResponseEntity<?> resetPharmacistPassword(@PathVariable String id, @RequestBody java.util.Map<String, String> body) {
        String newPassword = body.get("newPassword");
        if (newPassword == null || newPassword.trim().length() < 6) {
            return ResponseEntity.badRequest().body("Password must be at least 6 characters");
        }
        pharmacistService.resetPharmacistPassword(id, newPassword);
        return ResponseEntity.ok(java.util.Map.of("message", "Password reset successfully"));
    }
}
