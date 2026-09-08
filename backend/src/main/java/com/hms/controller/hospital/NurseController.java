package com.hms.controller.hospital;

import com.hms.entity.User;
import com.hms.security.RequireModule;
import com.hms.service.hospital.NurseService;
import com.hms.service.hospital.WardService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * NurseController - Hospital Admin management of nurse staff (Phase 1).
 *
 * HOSPITAL-tenant only: mapped exclusively under /hospital/** (never /clinic or
 * /pharmacy) and gated by the NURSING module. Cloned from ReceptionistController.
 */
@RestController
@RequestMapping("/hospital/nurses")
@RequireModule("NURSING")
@PreAuthorize("hasRole('HOSPITAL_ADMIN')")
public class NurseController {

    @Autowired
    private NurseService nurseService;

    @Autowired
    private WardService wardService;

    @PostMapping
    public ResponseEntity<?> createNurse(@RequestBody Map<String, String> payload) {
        String name = payload.get("name");
        String email = payload.get("email");
        String password = payload.get("password");

        if (name == null || email == null || password == null) {
            return ResponseEntity.badRequest().body("Name, Email, and Password are required");
        }
        if (password.trim().length() < 6) {
            return ResponseEntity.badRequest().body("Password must be at least 6 characters");
        }

        User created = nurseService.createNurse(name, email, password,
                payload.get("phone"), payload.get("licenseNumber"), parseWardId(payload.get("wardId")),
                payload.get("shiftTemplatePublicId"),
                parseDate(payload.get("shiftFromDate")), parseDate(payload.get("shiftToDate")),
                parseDaysOfWeek(payload.get("shiftDaysOfWeek")));
        return ResponseEntity.ok(created);
    }

    @GetMapping
    public ResponseEntity<?> getAllNurses(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(nurseService.getAllNurses(search, pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getNurseById(@PathVariable String id) {
        return ResponseEntity.ok(nurseService.getNurseByPublicId(id));
    }

    /**
     * The body is {@code Map<String, Object>}, not {@code Map<String, String>}.
     *
     * <p>The edit form posts its whole state, which for a Nurse Incharge includes
     * {@code inchargeWardIds} - a JSON array. Jackson cannot coerce an array into a String, so
     * binding failed before the handler ran and every incharge edit answered 400 "Malformed or
     * unreadable request body". Scalars still arrive as strings via {@link #str}; fields this
     * endpoint does not own are simply ignored, as they always were.
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> updateNurse(@PathVariable String id, @RequestBody Map<String, Object> payload) {
        String name = str(payload, "name");
        if (name == null || name.trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Name is required");
        }
        return ResponseEntity.ok(nurseService.updateNurse(id, name, parseWardId(str(payload, "wardId")),
                str(payload, "phone"),
                str(payload, "shiftTemplatePublicId"),
                parseDate(str(payload, "shiftFromDate")), parseDate(str(payload, "shiftToDate")),
                parseDaysOfWeek(str(payload, "shiftDaysOfWeek"))));
    }

    /** A scalar field as text; anything non-scalar (array, object) reads as absent. */
    private String str(Map<String, Object> payload, String key) {
        Object v = payload.get(key);
        if (v == null || v instanceof java.util.Collection || v instanceof Map) return null;
        String s = String.valueOf(v);
        return s.isEmpty() ? null : s;
    }

    /** Parse an optional wardId string from the request payload. */
    private Long parseWardId(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid ward selected");
        }
    }

    /** Parse an optional ISO date (yyyy-MM-dd) from the payload; null when blank. */
    private java.time.LocalDate parseDate(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            return java.time.LocalDate.parse(raw.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid shift date");
        }
    }

    /** Parse an optional comma-separated days-of-week list (1=Mon..7=Sun); null when blank. */
    private java.util.List<Integer> parseDaysOfWeek(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        java.util.List<Integer> days = new java.util.ArrayList<>();
        for (String part : raw.split(",")) {
            if (part.trim().isEmpty()) continue;
            try {
                days.add(Integer.valueOf(part.trim()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid day-of-week value");
            }
        }
        return days;
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteNurse(@PathVariable String id,
            @RequestParam(required = false) String reason) {
        nurseService.deleteNurse(id, reason);
        return ResponseEntity.ok("Nurse deleted successfully");
    }

    @PostMapping("/{id}/reset-password")
    public ResponseEntity<?> resetNursePassword(@PathVariable String id, @RequestBody Map<String, String> body) {
        String newPassword = body.get("newPassword");
        if (newPassword == null || newPassword.trim().length() < 6) {
            return ResponseEntity.badRequest().body("Password must be at least 6 characters");
        }
        nurseService.resetNursePassword(id, newPassword);
        return ResponseEntity.ok(Map.of("message", "Password reset successfully"));
    }

    @PostMapping("/{id}/promote")
    public ResponseEntity<?> promote(@PathVariable String id) {
        nurseService.promote(nurseService.resolveProfileId(id));
        return ResponseEntity.ok(Map.of("message", "Nurse promoted to incharge"));
    }

    @PostMapping("/{id}/demote")
    public ResponseEntity<?> demote(@PathVariable String id) {
        nurseService.demote(nurseService.resolveProfileId(id));
        return ResponseEntity.ok(Map.of("message", "Incharge demoted to nurse"));
    }

    @PostMapping("/{id}/active/{active}")
    public ResponseEntity<?> setActive(@PathVariable String id, @PathVariable boolean active) {
        nurseService.setActive(nurseService.resolveProfileId(id), active);
        return ResponseEntity.ok(Map.of("message", active ? "Activated" : "Deactivated"));
    }

    @PostMapping("/ward-incharge")
    public ResponseEntity<?> setWardIncharge(@RequestBody com.hms.dto.SetWardInchargeRequest req) {
        wardService.setIncharge(req.getWardId(), req.getInchargeNurseProfileId());
        return ResponseEntity.ok(Map.of("message", "Ward incharge updated"));
    }
}
