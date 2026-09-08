package com.hms.controller.hospital;

import com.hms.entity.Appointment;
import com.hms.service.hospital.AppointmentService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.hms.security.RequireModule;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * AppointmentController - REST controller for appointment management
 *
 * This controller provides endpoints for:
 * - Creating appointments (Hospital Admin only)
 * - Listing all appointments (Hospital Admin)
 * - Listing doctor's own appointments (Doctor)
 * - Getting appointment details (Hospital Admin and Doctor)
 *
 * All operations are automatically filtered by hospital_id.
 *
 * @author HMS Team
 * @version Phase-1
 */
@RestController
@RequestMapping({"/hospital/appointments", "/clinic/appointments", "/pharmacy/appointments"})
public class AppointmentController {

    // Module gating is per-method, deliberately, and NOT on the class.
    //
    // Appointments are an optional tenant capability: a hospital that works walk-in only has the
    // APPOINTMENTS module withheld from its plan. The rule for that tenant is "gate writes,
    // preserve historical reads":
    //
    //   * every MUTATION (create, update, status, delete) carries @RequireModule("APPOINTMENTS")
    //     and 403s, so no new appointment can be booked or altered once the module is withdrawn;
    //   * every READ stays open, so a tenant that used appointments before the module was removed
    //     can still open a past appointment, a patient's history and the bill that references it.
    //     Historical clinical information must never disappear because a plan changed.
    //
    // The class-level gate this replaces 403'd the reads too — including /stats, /today and
    // /my-appointments, which the admin, receptionist and doctor dashboards call while loading
    // unrelated tabs. One optional module thereby took down three whole dashboards.
    //
    // ModuleAccessAspect resolves the method annotation before the class one, so if a class-level
    // gate is ever reintroduced here it would be overridden on these methods but would silently
    // re-close the reads. Add new mutations with the annotation; do not move it back up.

    @Autowired
    private AppointmentService appointmentService;

    /**
     * Create a new appointment
     * Accessible by Hospital Admin and Receptionist
     */
    @PostMapping
    @RequireModule("APPOINTMENTS")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'RECEPTIONIST')")
    public ResponseEntity<?> createAppointment(@Valid @RequestBody Appointment appointment) {
        Appointment createdAppointment = appointmentService.createAppointment(appointment);
        return ResponseEntity.ok(createdAppointment);
    }

    /**
     * Get all appointments for the current hospital
     * Accessible by Hospital Admin and Receptionist
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'RECEPTIONIST')")
    public ResponseEntity<?> getAllAppointments(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String view) {
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(appointmentService.getAllAppointments(search, pageable, view));
    }

    /**
     * Get today's appointments for Overview dashboard
     * Accessible by Hospital Admin, Receptionist, and Doctor
     */
    @GetMapping("/today")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'RECEPTIONIST', 'DOCTOR')")
    public ResponseEntity<List<Appointment>> getTodaysAppointments() {
        List<Appointment> appointments = appointmentService.getTodaysAppointments();
        return ResponseEntity.ok(appointments);
    }

    /**
     * Get appointments for the current logged-in doctor with pagination, search,
     * and view filter
     */
    @GetMapping("/my-appointments")
    @PreAuthorize("hasAnyRole('DOCTOR', 'HOSPITAL_ADMIN')")
    public ResponseEntity<?> getMyAppointments(@RequestParam(required = false) String view,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Pageable pageable = PageRequest.of(page, size);
        org.springframework.data.domain.Page<Appointment> appointments = appointmentService.getMyAppointments(view,
                search, pageable);
        return ResponseEntity.ok(appointments);
    }

    /**
     * Get appointments for a specific doctor
     * Accessible by Admin, Doctor, and Receptionist
     */
    /**
     * Get appointments for a specific doctor
     * Accessible by Admin, Doctor, and Receptionist
     */
    @GetMapping("/doctor/{doctorId}")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR', 'RECEPTIONIST')")
    public ResponseEntity<?> getAppointmentsByDoctor(@PathVariable Long doctorId,
            @RequestParam(required = false) String view) {
        List<Appointment> appointments = appointmentService.getAppointmentsByDoctor(doctorId, view);
        return ResponseEntity.ok(appointments);
    }

    /**
     * Get appointments for a specific patient (History)
     * Accessible by Admin, Doctor, and Receptionist
     */
    @GetMapping("/patient/{patientId}")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR', 'RECEPTIONIST')")
    public ResponseEntity<?> getAppointmentsByPatient(@PathVariable String patientId) {
        List<Appointment> appointments = appointmentService.getAppointmentsByPatient(patientId);
        return ResponseEntity.ok(appointments);
    }

    /**
     * Get appointment by ID
     * Accessible by all roles
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR', 'RECEPTIONIST')")
    public ResponseEntity<?> getAppointmentById(@PathVariable String id) {
        Appointment appointment = appointmentService.getAppointmentByPublicId(id);
        return ResponseEntity.ok(appointment);
    }

    /**
     * Delete (Soft Delete) an appointment
     * Only Hospital Admin can delete appointments
     */
    @DeleteMapping("/{id}")
    @RequireModule("APPOINTMENTS")
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<?> deleteAppointment(@PathVariable String id, @RequestParam(required = false) String reason) {
        appointmentService.deleteAppointment(id, reason);
        return ResponseEntity.ok("Appointment deleted successfully");
    }

    /**
     * Get dashboard statistics (Today's, Pending, Total)
     */
    @GetMapping("/stats")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR', 'RECEPTIONIST')")
    public ResponseEntity<?> getDashboardStats() {
        java.util.Map<String, Long> stats = appointmentService.getDashboardStats();
        return ResponseEntity.ok(stats);
    }

    /**
     * Update appointment status
     */
    /**
     * Update appointment details (Status & Notes)
     */
    @PutMapping("/{id}")
    @RequireModule("APPOINTMENTS")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR', 'RECEPTIONIST')")
    public ResponseEntity<?> updateAppointment(@PathVariable String id,
            @RequestBody java.util.Map<String, String> payload) {
        String status = payload.get("status");
        String notes = payload.get("notes");

        // Date and time used to be read off this payload by nobody at all: a reschedule was
        // accepted with 200 and quietly discarded. They are parsed here in the wire format the
        // entity itself declares (yyyy-MM-dd and HH:mm), so a value the UI can produce is a value
        // this endpoint accepts, and anything else is reported instead of ignored.
        java.time.LocalDate newDate = parseDate(payload.get("appointmentDate"));
        java.time.LocalTime newTime = parseTime(payload.get("appointmentTime"));

        Appointment updatedAppointment = appointmentService.updateDetails(id, status, notes, newDate, newTime);
        return ResponseEntity.ok(updatedAppointment);
    }

    private java.time.LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return java.time.LocalDate.parse(raw.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("Appointment date must be in yyyy-MM-dd format");
        }
    }

    private java.time.LocalTime parseTime(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return java.time.LocalTime.parse(raw.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("Appointment time must be in HH:mm format");
        }
    }

    /**
     * Update appointment status (Legacy/Specific)
     */
    @PutMapping("/{id}/status")
    @RequireModule("APPOINTMENTS")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR', 'RECEPTIONIST')")
    public ResponseEntity<?> updateAppointmentStatus(@PathVariable String id,
            @RequestBody java.util.Map<String, String> payload) {
        String status = payload.get("status");
        String reason = payload.get("reason");
        if (status == null) {
            throw new IllegalArgumentException("Status is required");
        }
        Appointment updatedAppointment = appointmentService.updateStatus(id, status, reason);
        return ResponseEntity.ok(updatedAppointment);
    }
}

