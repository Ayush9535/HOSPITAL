package com.hms.controller.hospital;

import com.hms.entity.HospitalType;
import com.hms.security.RequireModule;
import com.hms.security.TenantType;
import com.hms.service.hospital.ot.OtRoomService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Operation theatres. HOSPITAL tenant only, OT-gated, never aliased onto /clinic.
 *
 * The theatre list is a server concern: it used to be a client-side filter over ward
 * names, which made "FOOT WARD" a theatre.
 */
@RestController
@RequestMapping("/hospital/ot/rooms")
@TenantType(HospitalType.HOSPITAL)
@RequireModule("OT")
public class OtRoomController {
    private static final String TURNOVER_MINUTES = "turnoverMinutes";


    @Autowired
    private OtRoomService service;

    /** Anyone who can see the OT board needs the theatre list. */
    @GetMapping
    @PreAuthorize("hasAuthority('OT_VIEW')")
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(service.list());
    }

    /** Wards that look like theatres, for an admin to confirm. Never auto-converted. */
    @GetMapping("/suggestions")
    @PreAuthorize("hasAuthority('OT_SETTINGS')")
    public ResponseEntity<?> suggestions() {
        return ResponseEntity.ok(service.suggestFromWards());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('OT_SETTINGS')")
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        String name = (String) body.get("name");
        Integer turnover = body.get(TURNOVER_MINUTES) == null ? null
                : Integer.valueOf(String.valueOf(body.get(TURNOVER_MINUTES)));
        Long sourceWardId = body.get("sourceWardId") == null ? null
                : Long.valueOf(String.valueOf(body.get("sourceWardId")));
        return ResponseEntity.ok(service.create(name, turnover, sourceWardId));
    }

    @PutMapping("/{publicId}")
    @PreAuthorize("hasAuthority('OT_SETTINGS')")
    public ResponseEntity<?> update(@PathVariable String publicId, @RequestBody Map<String, Object> body) {
        String name = (String) body.get("name");
        Integer turnover = body.get(TURNOVER_MINUTES) == null ? null
                : Integer.valueOf(String.valueOf(body.get(TURNOVER_MINUTES)));
        String status = (String) body.get("status");
        return ResponseEntity.ok(service.update(publicId, name, turnover, status));
    }

    /** Soft delete: historic surgeries still reference the theatre they ran in. */
    @DeleteMapping("/{publicId}")
    @PreAuthorize("hasAuthority('OT_SETTINGS')")
    public ResponseEntity<?> deactivate(@PathVariable String publicId) {
        service.deactivate(publicId);
        return ResponseEntity.ok(Map.of("message", "Theatre deactivated"));
    }
}
