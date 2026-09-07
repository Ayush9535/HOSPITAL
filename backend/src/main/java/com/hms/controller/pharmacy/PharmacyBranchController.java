package com.hms.controller.pharmacy;

import com.hms.service.pharmacy.PharmacyBranchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Multi Pharmacy branch management. HOSPITAL_ADMIN only — the owner creates and
 * manages the medical shops (outlets) under their tenant.
 */
@RestController
@RequestMapping("/pharmacy/branches")
@PreAuthorize("hasRole('HOSPITAL_ADMIN')")
public class PharmacyBranchController {

    @Autowired
    private PharmacyBranchService service;

    @GetMapping
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(service.list());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, String> body) {
        return ResponseEntity.ok(service.create(
                body.get("name"), body.get("address"), body.get("phone"),
                body.get("email"), body.get("password")));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(service.update(id, body.get("name"), body.get("address"), body.get("phone")));
    }

    @PostMapping("/{id}/reset-password")
    public ResponseEntity<?> resetPassword(@PathVariable Long id, @RequestBody Map<String, String> body) {
        service.resetPassword(id, body.get("password"));
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
