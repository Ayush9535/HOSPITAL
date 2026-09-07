package com.hms.controller.hospital;

import jakarta.validation.Valid;

import com.hms.dto.AppointmentSlotRequest;
import com.hms.dto.ShiftTemplateRequest;
import com.hms.security.RequireModule;
import com.hms.service.hospital.AppointmentSlotService;
import com.hms.service.hospital.ShiftTemplateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/hospital/time-slots")
@RequireModule("NURSING")
public class TimeSlotController {
    @Autowired private ShiftTemplateService shiftTemplateService;
    @Autowired private AppointmentSlotService appointmentSlotService;

    @GetMapping("/shift-templates")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')")
    public ResponseEntity<?> listShiftTemplates(@RequestParam(defaultValue = "false") boolean activeOnly) {
        return ResponseEntity.ok(shiftTemplateService.list(activeOnly));
    }
    @PostMapping("/shift-templates")
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<?> createShiftTemplate(@Valid @RequestBody ShiftTemplateRequest req) {
        return ResponseEntity.ok(shiftTemplateService.create(req));
    }
    @PutMapping("/shift-templates/{publicId}")
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<?> updateShiftTemplate(@PathVariable String publicId, @Valid @RequestBody ShiftTemplateRequest req) {
        return ResponseEntity.ok(shiftTemplateService.update(publicId, req));
    }
    @DeleteMapping("/shift-templates/{publicId}")
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<?> deactivateShiftTemplate(@PathVariable String publicId) {
        shiftTemplateService.deactivate(publicId);
        return ResponseEntity.ok(Map.of("message", "Shift template deactivated"));
    }

    @PostMapping("/shift-templates/{publicId}/activate")
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<?> activateShiftTemplate(@PathVariable String publicId) {
        shiftTemplateService.activate(publicId);
        return ResponseEntity.ok(Map.of("message", "Shift template activated"));
    }

    @GetMapping("/appointment-slots")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','RECEPTIONIST','NURSE_INCHARGE')")
    public ResponseEntity<?> listSlots(@RequestParam(defaultValue = "false") boolean activeOnly) {
        return ResponseEntity.ok(appointmentSlotService.list(activeOnly));
    }
    @PostMapping("/appointment-slots")
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<?> createSlot(@Valid @RequestBody AppointmentSlotRequest req) {
        return ResponseEntity.ok(appointmentSlotService.create(req));
    }
    @PutMapping("/appointment-slots/{publicId}")
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<?> updateSlot(@PathVariable String publicId, @Valid @RequestBody AppointmentSlotRequest req) {
        return ResponseEntity.ok(appointmentSlotService.update(publicId, req));
    }
    @DeleteMapping("/appointment-slots/{publicId}")
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<?> deactivateSlot(@PathVariable String publicId) {
        appointmentSlotService.deactivate(publicId);
        return ResponseEntity.ok(Map.of("message", "Slot deactivated"));
    }
}
