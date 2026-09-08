package com.hms.controller.hospital;

import jakarta.validation.Valid;

import com.hms.dto.CalendarEventRequest;
import com.hms.security.RequireModule;
import com.hms.service.hospital.HospitalCalendarService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

/**
 * HospitalCalendarController - month grid, day detail, and holiday/event CRUD
 * for Admin + Incharge (Nursing Mgmt Phase G). NURSING-gated.
 */
@RestController
@RequestMapping("/hospital/calendar")
@RequireModule("NURSING")
@PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')")
public class HospitalCalendarController {

    @Autowired private HospitalCalendarService calendarService;

    @GetMapping("/month")
    public ResponseEntity<?> month(@RequestParam int year, @RequestParam int month) {
        return ResponseEntity.ok(calendarService.monthSummary(year, month));
    }

    @GetMapping("/day")
    public ResponseEntity<?> day(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(calendarService.dayDetail(date));
    }

    @GetMapping("/events")
    public ResponseEntity<?> listEvents() {
        return ResponseEntity.ok(calendarService.listEvents());
    }

    @PostMapping("/events")
    public ResponseEntity<?> createEvent(@Valid @RequestBody CalendarEventRequest req) {
        return ResponseEntity.ok(calendarService.createEvent(req));
    }

    @PutMapping("/events/{publicId}")
    public ResponseEntity<?> updateEvent(@PathVariable String publicId, @Valid @RequestBody CalendarEventRequest req) {
        return ResponseEntity.ok(calendarService.updateEvent(publicId, req));
    }

    @DeleteMapping("/events/{publicId}")
    public ResponseEntity<?> deleteEvent(@PathVariable String publicId) {
        calendarService.deleteEvent(publicId);
        return ResponseEntity.ok(Map.of("message", "Calendar event deleted"));
    }
}
