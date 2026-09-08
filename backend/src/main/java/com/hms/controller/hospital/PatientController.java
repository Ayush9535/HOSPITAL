package com.hms.controller.hospital;

import com.hms.exception.ResourceNotFoundException;

import com.hms.entity.Patient;
import com.hms.service.hospital.PatientService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@RestController
@RequestMapping({"/hospital/patients", "/clinic/patients", "/pharmacy/patients"})
public class PatientController {

    @Autowired
    private PatientService patientService;

    @Autowired
    private com.hms.security.SecurityContextHelper securityHelper;

    @Autowired
    private com.hms.repository.HospitalRepository hospitalRepository;

    @Autowired
    private com.hms.service.PdfService pdfService;

    @Autowired
    private com.hms.service.hospital.PatientTimelineService patientTimelineService;

    @PostMapping
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'RECEPTIONIST')")
    public ResponseEntity<?> addPatient(@Valid @RequestBody Patient patient) {
        Patient createdPatient = patientService.addPatient(patient);
        return ResponseEntity.ok(createdPatient);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'RECEPTIONIST')")
    public ResponseEntity<?> updatePatient(@PathVariable Long id, @Valid @RequestBody Patient patient) {
        Patient updatedPatient = patientService.updatePatient(id, patient);
        return ResponseEntity.ok(updatedPatient);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR', 'RECEPTIONIST')")
    public ResponseEntity<?> getAllPatients(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String view,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate date,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        if (search != null && !search.trim().isEmpty()) {
            return ResponseEntity.ok(patientService.searchPatients(search));
        }
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(patientService.getAllPatients(null, view, date, pageable));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR', 'RECEPTIONIST')")
    public ResponseEntity<?> getPatientById(@PathVariable String id) {
        Patient patient = patientService.getPatientByPublicId(id);
        return ResponseEntity.ok(patient);
    }

    /**
     * CLIN-P1: the patient's clinical timeline -- OPD/IPD/Nursing/OT/Recovery events in one
     * chronological read model, aggregated from the entities that already own each fact.
     * Clinical detail (diagnoses, medications, nursing notes), so scoped to the clinical roles
     * rather than the wider set PatientController otherwise uses.
     */
    @GetMapping("/{id}/timeline")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR')")
    public ResponseEntity<?> getPatientTimeline(@PathVariable String id) {
        Patient patient = patientService.getPatientByPublicId(id);
        return ResponseEntity.ok(patientTimelineService.forPatient(patient.getId()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<?> deletePatient(@PathVariable String id, @RequestParam(required = false) String reason) {
        patientService.deletePatient(id, reason);
        return ResponseEntity.ok("Patient deleted successfully");
    }

    @PutMapping("/{publicId}/status")
    @PreAuthorize("hasAnyRole('DOCTOR', 'RECEPTIONIST', 'HOSPITAL_ADMIN')")
    public ResponseEntity<?> updatePatientStatus(
            @PathVariable String publicId,
            @RequestParam String status) {
        try {
            com.hms.entity.PatientStatus patientStatus = com.hms.entity.PatientStatus.valueOf(status.toUpperCase());
            Patient updatedPatient = patientService.updatePatientStatus(publicId, patientStatus);
            return ResponseEntity.ok(updatedPatient);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body("Invalid status: " + status);
        }
    }

    @PostMapping("/{publicId}/start-consultation")
    @PreAuthorize("hasAnyRole('DOCTOR', 'HOSPITAL_ADMIN')")
    public ResponseEntity<?> startConsultation(@PathVariable String publicId) {
        Patient updatedPatient = patientService.startConsultation(publicId);
        return ResponseEntity.ok(updatedPatient);
    }

    @GetMapping("/{publicId}/consultation-details")
    @PreAuthorize("hasAnyRole('DOCTOR', 'RECEPTIONIST', 'HOSPITAL_ADMIN')")
    public ResponseEntity<?> getPatientConsultationDetails(@PathVariable String publicId) {
        java.util.Map<String, Object> details = patientService.getPatientConsultationDetails(publicId);
        return ResponseEntity.ok(details);
    }

    @GetMapping("/{publicId}/latest-prescription")
    @PreAuthorize("hasAnyRole('DOCTOR', 'RECEPTIONIST', 'HOSPITAL_ADMIN')")
    public ResponseEntity<?> getLatestPrescription(@PathVariable String publicId) {
        return ResponseEntity.ok(patientService.getLatestPrescription(publicId));
    }

    @GetMapping("/opd/{opdId}/medicines/pdf")
    @PreAuthorize("hasAnyRole('DOCTOR', 'RECEPTIONIST', 'HOSPITAL_ADMIN')")
    public ResponseEntity<?> getOpdMedicinesPdf(@PathVariable Long opdId) {
        java.io.ByteArrayInputStream pdf = patientService.getOpdMedicinesPdf(opdId);
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.add("Content-Disposition", "inline; filename=opd_medicines_" + opdId + ".pdf");
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                .body(new org.springframework.core.io.InputStreamResource(pdf));
    }

    @GetMapping("/ipd/{ipdId}/medicines/pdf")
    @PreAuthorize("hasAnyRole('DOCTOR', 'RECEPTIONIST', 'HOSPITAL_ADMIN')")
    public ResponseEntity<?> getIpdMedicinesPdf(@PathVariable Long ipdId) {
        java.io.ByteArrayInputStream pdf = patientService.getIpdMedicinesPdf(ipdId);
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.add("Content-Disposition", "inline; filename=ipd_medicines_" + ipdId + ".pdf");
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                .body(new org.springframework.core.io.InputStreamResource(pdf));
    }

    @GetMapping("/ipd/{ipdId}/prescription/pdf")
    @PreAuthorize("hasAnyRole('DOCTOR', 'RECEPTIONIST', 'HOSPITAL_ADMIN')")
    public ResponseEntity<?> getIpdPrescriptionPdf(@PathVariable Long ipdId) {
        java.io.ByteArrayInputStream pdf = patientService.getIpdPrescriptionPdf(ipdId);
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.add("Content-Disposition", "inline; filename=ipd_prescription_" + ipdId + ".pdf");
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                .body(new org.springframework.core.io.InputStreamResource(pdf));
    }

    @GetMapping("/report/pdf")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR', 'RECEPTIONIST')")
    public ResponseEntity<?> downloadPatientsReportPdf(
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate date) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            throw new com.hms.exception.UnauthorizedException("Hospital context not found");
        }

        // Fetch patients without pagination (up to 1000)
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(0, 1000);
        java.util.List<Patient> patients = patientService.getAllPatients(null, null, date, pageable).getContent();

        com.hms.entity.Hospital hospital = hospitalRepository.findById(hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Hospital not found"));

        java.io.ByteArrayInputStream pdfStream = pdfService.generatePatientsReportPdf(hospital, date, patients);
        org.springframework.core.io.InputStreamResource resource = new org.springframework.core.io.InputStreamResource(pdfStream);

        String filename = "Patients_Report_" + (date != null ? date.toString() : "AllTime") + ".pdf";
        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename)
                .contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                .body(resource);
    }
}

