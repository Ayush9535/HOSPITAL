package com.hms.controller.hospital;

import jakarta.validation.Valid;

import com.hms.dto.MarkAttendanceRequest;
import com.hms.security.RequireModule;
import com.hms.service.hospital.NurseAttendanceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/hospital/nurse-attendance")
@RequireModule("NURSING")
public class NurseAttendanceController {

    @Autowired private NurseAttendanceService service;

    @GetMapping("/sheet")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')")
    public ResponseEntity<?> sheet(@RequestParam Long wardId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(service.getSheet(wardId, date));
    }

    @PostMapping("/mark")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')")
    public ResponseEntity<?> mark(@Valid @RequestBody MarkAttendanceRequest req) {
        return ResponseEntity.ok(service.mark(req));
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')")
    public ResponseEntity<?> summary(@RequestParam Long wardId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(service.summary(wardId, date));
    }

    @GetMapping("/mine")
    @PreAuthorize("hasAnyRole('NURSE','NURSE_INCHARGE','HOSPITAL_ADMIN')")
    public ResponseEntity<?> mine(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(service.getMyAttendance(from, to));
    }
}
