package com.hms.controller.hospital;

import com.hms.entity.User;
import com.hms.service.hospital.ReceptionistService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@RestController
@RequestMapping({"/hospital/receptionists", "/clinic/receptionists", "/pharmacy/receptionists"})
@PreAuthorize("hasRole('HOSPITAL_ADMIN')")
public class ReceptionistController {

    @Autowired
    private ReceptionistService receptionistService;

    @PostMapping
    public ResponseEntity<?> createReceptionist(@RequestBody Map<String, String> payload) {
        String name = payload.get("name");
        String email = payload.get("email");
        String password = payload.get("password");

        if (name == null || email == null || password == null) {
            return ResponseEntity.badRequest().body("Name, Email, and Password are required");
        }

        // phone was previously dropped here — the admin form sends it, the API ignored it,
        // and the receptionist row was saved with an empty phone while reporting success.
        User created = receptionistService.createReceptionist(name, email, password, payload.get("phone"));
        return ResponseEntity.ok(receptionistService.toView(created));
    }

    @GetMapping
    public ResponseEntity<?> getAllReceptionists(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(receptionistService.getAllReceptionists(search, pageable));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteReceptionist(@PathVariable String id,
            @RequestParam(required = false) String reason) {
        receptionistService.deleteReceptionist(id, reason);
        return ResponseEntity.ok("Receptionist deleted successfully");
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getReceptionistById(@PathVariable String id) {
        return ResponseEntity.ok(receptionistService.getReceptionistViewByPublicId(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateReceptionist(@PathVariable String id, @RequestBody Map<String, String> payload) {
        String name = payload.get("name");
        if (name == null || name.trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Name is required");
        }
        User updated = receptionistService.updateReceptionist(id, name, payload.get("phone"));
        return ResponseEntity.ok(receptionistService.toView(updated));
    }

    @PostMapping("/{id}/reset-password")
    public ResponseEntity<?> resetReceptionistPassword(@PathVariable String id, @RequestBody java.util.Map<String, String> body) {
        String newPassword = body.get("newPassword");
        if (newPassword == null || newPassword.trim().length() < 6) {
            return ResponseEntity.badRequest().body("Password must be at least 6 characters");
        }
        receptionistService.resetReceptionistPassword(id, newPassword);
        return ResponseEntity.ok(java.util.Map.of("message", "Password reset successfully"));
    }
}
