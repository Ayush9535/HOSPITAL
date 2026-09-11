package com.hms.controller.hospital;

import com.hms.exception.ResourceNotFoundException;

import com.hms.security.RequireModule;
import com.hms.service.hospital.AppointmentService;
import com.hms.service.hospital.DoctorService;
import com.hms.service.hospital.PatientService;
import com.hms.repository.HospitalRepository;
import com.hms.service.PdfService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HospitalStatsController - Provides dashboard statistics for Hospital Admin
 * 
 * This controller provides aggregate stats for the Overview dashboard:
 * - Total Registered Patients
 * - Patients This Month
 * - Patients Today
 * - Patient activity by date (OPD / Appointment / IPD)
 * 
 * @author HMS Team
 * @version Phase-1
 */
@RestController
@RequestMapping({"/hospital/stats", "/clinic/stats", "/pharmacy/stats"})
@PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'RECEPTIONIST', 'DOCTOR')")
public class HospitalStatsController {

    @Autowired
    private com.hms.service.hospital.HospitalStatsService statsService;

    @Autowired
    private com.hms.security.SecurityContextHelper securityHelper;

    @Autowired
    private HospitalRepository hospitalRepository;

    @Autowired
    private PdfService pdfService;

    /**
     * Get dashboard statistics for Hospital Admin Overview
     * Returns total patients, total doctors, today's appointments count,
     * plus patient-focused stats (totalRegisteredPatients, patientsThisMonth, patientsToday)
     */
    @GetMapping
    public ResponseEntity<Map<String, Long>> getDashboardStats() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            throw new com.hms.exception.UnauthorizedException("Hospital context not found");
        }
        return ResponseEntity.ok(statsService.getStats(hospitalId));
    }

    /**
     * Get detailed analytics for Hospital Admin
     */
    @GetMapping("/analytics")
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    @RequireModule("REPORTS")
    public ResponseEntity<?> getAnalytics() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            throw new com.hms.exception.UnauthorizedException("Hospital context not found");
        }
        return ResponseEntity.ok(statsService.getAnalytics(hospitalId));
    }

    /**
     * Get patient activity for a specific date.
     * Returns list of patients who had OPD, Appointment, or IPD activity on that date,
     * sorted by activity time descending (latest first).
     */
    @GetMapping("/patient-activity")
    public ResponseEntity<?> getPatientActivityByDate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            throw new com.hms.exception.UnauthorizedException("Hospital context not found");
        }
        List<Map<String, Object>> activities = statsService.getPatientActivityByDate(hospitalId, date);
        return ResponseEntity.ok(activities);
    }

    /**
     * Download patient activity PDF report for a specific date.
     */
    @GetMapping("/patient-activity/pdf")
    public ResponseEntity<?> downloadPatientActivityPdf(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            throw new com.hms.exception.UnauthorizedException("Hospital context not found");
        }
        List<Map<String, Object>> activities = statsService.getPatientActivityByDate(hospitalId, date);

        com.hms.entity.Hospital hospital = hospitalRepository.findById(hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Hospital not found"));

        ByteArrayInputStream pdfStream = pdfService.generatePatientActivityPdf(hospital, date, activities);
        org.springframework.core.io.InputStreamResource resource = new org.springframework.core.io.InputStreamResource(pdfStream);

        String filename = "Patient_Activity_Report_" + date.toString() + ".pdf";
        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename)
                .contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                .body(resource);
    }
}

