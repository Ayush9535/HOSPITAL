package com.hms.controller.hospital;

import jakarta.validation.Valid;

import com.hms.dto.AssignShiftRequest;
import com.hms.dto.RangeFillShiftRequest;
import com.hms.security.RequireModule;
import com.hms.service.hospital.NurseShiftScheduleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/hospital/nurse-schedule")
@RequireModule("NURSING")
public class NurseScheduleController {
    @Autowired private NurseShiftScheduleService service;

    @PostMapping("/assign")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')")
    public ResponseEntity<?> assign(@Valid @RequestBody AssignShiftRequest req) { return ResponseEntity.ok(service.assign(req)); }

    @PostMapping("/range-fill")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')")
    public ResponseEntity<?> rangeFill(@Valid @RequestBody RangeFillShiftRequest req) {
        return ResponseEntity.ok(Map.of("created", service.rangeFill(req)));
    }

    @DeleteMapping("/{publicId}")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')")
    public ResponseEntity<?> remove(@PathVariable String publicId) { service.remove(publicId); return ResponseEntity.ok(Map.of("message","Removed")); }

    @GetMapping("/ward")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')")
    public ResponseEntity<?> ward(@RequestParam Long wardId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(service.getWardSchedule(wardId, from, to));
    }

    @GetMapping("/mine")
    @PreAuthorize("hasAnyRole('NURSE','NURSE_INCHARGE','HOSPITAL_ADMIN')")
    public ResponseEntity<?> mine(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(service.getMySchedule(from, to));
    }
}
