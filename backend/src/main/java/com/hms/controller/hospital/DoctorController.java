package com.hms.controller.hospital;
import com.hms.util.LogSanitizer;

import com.hms.exception.ResourceNotFoundException;

import com.hms.dto.AddDoctorRequest;
import com.hms.entity.Doctor;
import com.hms.repository.OpdRepository;
import com.hms.service.hospital.DoctorService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@RestController
@RequestMapping({"/hospital/doctors", "/clinic/doctors", "/pharmacy/doctors"})
public class DoctorController {

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(DoctorController.class);

    @Autowired
    private DoctorService doctorService;

    @Autowired
    private OpdRepository opdRepository;

    @Autowired
    private com.hms.repository.BillingRepository billingRepository;

    @Autowired
    private com.hms.security.SecurityContextHelper securityHelper;

    @Autowired
    private com.hms.repository.UserRepository userRepository;

    @PostMapping
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<?> addDoctor(@Valid @RequestBody AddDoctorRequest request) {
        // Create Doctor entity from request
        Doctor doctor = new Doctor();
        doctor.setName(request.getName());
        doctor.setSpecialization(request.getSpecialization());
        doctor.setPhone(request.getPhone());
        doctor.setEmail(request.getEmail());

        Doctor createdDoctor = doctorService.addDoctor(doctor, request.getPassword());
        return ResponseEntity.ok(createdDoctor);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<?> updateDoctor(@PathVariable String id, @Valid @RequestBody Doctor doctor) {
        // Note: Not validating full request as password/email might not be sent
        Doctor updatedDoctor = doctorService.updateDoctor(id, doctor);
        return ResponseEntity.ok(updatedDoctor);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR', 'RECEPTIONIST')")
    public ResponseEntity<?> getAllDoctors(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        if (search != null && !search.trim().isEmpty()) {
            return ResponseEntity.ok(doctorService.searchDoctors(search));
        }
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(doctorService.getAllDoctors(pageable));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR', 'RECEPTIONIST')")
    public ResponseEntity<?> getDoctorById(@PathVariable String id) {
        Doctor doctor = doctorService.getDoctorByPublicId(id);
        return ResponseEntity.ok(doctor);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<?> deleteDoctor(@PathVariable String id, @RequestParam(required = false) String reason) {
        doctorService.deleteDoctor(id, reason);
        return ResponseEntity.ok("Doctor deleted successfully");
    }

    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<?> resetDoctorPassword(@PathVariable String id, @RequestBody java.util.Map<String, String> body) {
        String newPassword = body.get("newPassword");
        if (newPassword == null || newPassword.trim().length() < 6) {
            return ResponseEntity.badRequest().body("Password must be at least 6 characters");
        }
        doctorService.resetDoctorPassword(id, newPassword);
        return ResponseEntity.ok(java.util.Map.of("message", "Password reset successfully"));
    }


    @PostMapping("/consultation")
    @PreAuthorize("hasAnyRole('DOCTOR', 'HOSPITAL_ADMIN')")
    public ResponseEntity<?> submitConsultation(@jakarta.validation.Valid @RequestBody com.hms.dto.ConsultationRequest request) {
        com.hms.entity.Opd opd = doctorService.submitConsultation(request);
        java.util.Map<String, Object> response = new java.util.HashMap<>();
        response.put("message", "Consultation submitted successfully");
        if (opd != null) {
            response.put("opdId", opd.getId());
            
            // Return a clean map representation of OPD to prevent lazy initialization exception during Jackson serialization
            java.util.Map<String, Object> opdMap = new java.util.HashMap<>();
            opdMap.put("id", opd.getId());
            opdMap.put("caseId", opd.getCaseId());
            opdMap.put("problem", opd.getProblem());
            opdMap.put("status", opd.getStatus());
            response.put("opd", opdMap);

            // Fetch generated bill ID (by opdId or by appointmentId fallback)
            java.util.Optional<com.hms.entity.Billing> billOpt = billingRepository.findByOpdId(opd.getId());
            if (billOpt.isEmpty() && request.getAppointmentId() != null) {
                billOpt = billingRepository.findByAppointmentId(request.getAppointmentId());
            }
            if (billOpt.isPresent()) {
                response.put("billId", billOpt.get().getId());
            }
        }
        boolean hasPrescription = request.getPrescription() != null && !request.getPrescription().isEmpty();
        boolean hasAdministered = (request.getAdministeredItems() != null && !request.getAdministeredItems().isEmpty())
                || (request.getHospitalInventoryItems() != null && !request.getHospitalInventoryItems().isEmpty());
        response.put("hasPrescription", hasPrescription);
        response.put("hasAdministered", hasAdministered);
        return ResponseEntity.ok(response);
    }


    @GetMapping("/consultation/{appointmentId}")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR', 'RECEPTIONIST')")
    public ResponseEntity<?> getConsultationDetails(@PathVariable String appointmentId) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        // Resolve Appointment ID
        java.util.Optional<com.hms.entity.Appointment> apptOpt = appointmentRepository
                .findByPublicIdAndHospitalIdAndIsActiveTrue(appointmentId, hospitalId);
        if (apptOpt.isEmpty()) {
            try {
                Long id = Long.parseLong(appointmentId);
                apptOpt = appointmentRepository.findByIdAndHospitalIdAndIsActiveTrue(id, hospitalId);
            } catch (NumberFormatException e) {
            }
        }
        com.hms.entity.Appointment appointment = apptOpt
                .orElseThrow(() -> new ResourceNotFoundException("Appointment not found"));

        com.hms.entity.MedicalRecord record = medicalRecordRepository.findByAppointmentId(appointment.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Consultation record not found"));

        java.util.List<com.hms.entity.Prescription> prescriptions = prescriptionRepository
                .findByMedicalRecordId(record.getId());

        java.util.Map<String, Object> response = new java.util.HashMap<>();
        response.put("medicalRecord", record);
        response.put("prescriptions", prescriptions);

        return ResponseEntity.ok(response);
    }

    /**
     * The same consultation details, addressed by OPD instead of by appointment.
     *
     * <p>Reception's prescription viewer could only reach a consultation through its appointment,
     * so a walk-in visit — the normal path for a hospital without the APPOINTMENTS module, and
     * already the majority path for one with it — had no prescription view at reception at all.
     * The doctor's side never had this problem: it has read by OPD since
     * {@code /prescription/opd/{opdId}/pdf}. This is that same reading, for the JSON viewer.
     *
     * <p>Deliberately not a new consultation architecture: it returns the identical
     * {medicalRecord, prescriptions} shape as the appointment-keyed endpoint above and reads the
     * same two repositories. Only the key differs. Both remain, because an appointment-origin
     * consultation is still addressed by appointment everywhere it already was.
     *
     * <p>Ungated by module, like its sibling: a consultation is a clinical record, not an
     * appointment, and it must stay readable whether or not the tenant sells appointments.
     *
     * <p>Tenant scope comes from the authenticated principal and is checked on the medical record,
     * which carries hospital_id directly — the same check {@code downloadPrescriptionByOpd} makes.
     * Another tenant's OPD is reported as not found, indistinguishable from one that never existed.
     */
    @GetMapping("/consultation/opd/{opdId}")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR', 'RECEPTIONIST')")
    public ResponseEntity<?> getConsultationDetailsByOpd(@PathVariable Long opdId) {
        Long hospitalId = securityHelper.getCurrentHospitalId();

        com.hms.entity.MedicalRecord record = medicalRecordRepository.findByOpdId(opdId)
                .orElseThrow(() -> new ResourceNotFoundException("Consultation record not found"));

        if (hospitalId == null || !hospitalId.equals(record.getHospitalId())) {
            throw new ResourceNotFoundException("Consultation record not found");
        }

        java.util.List<com.hms.entity.Prescription> prescriptions = prescriptionRepository
                .findByMedicalRecordId(record.getId());

        java.util.Map<String, Object> response = new java.util.HashMap<>();
        response.put("medicalRecord", record);
        response.put("prescriptions", prescriptions);

        return ResponseEntity.ok(response);
    }

    @Autowired
    private com.hms.service.PdfService pdfService;
    @Autowired
    private com.hms.repository.MedicalRecordRepository medicalRecordRepository;
    @Autowired
    private com.hms.repository.PrescriptionRepository prescriptionRepository;
    @Autowired
    private com.hms.service.hospital.PatientService patientService;
    @Autowired
    private com.hms.repository.HospitalRepository hospitalRepository;
    @Autowired
    private com.hms.repository.DoctorRepository doctorRepository;

    @Autowired
    private com.hms.repository.AppointmentRepository appointmentRepository;

    @GetMapping("/prescription/{appointmentId}/pdf")
    public ResponseEntity<?> downloadPrescription(@PathVariable String appointmentId) {
        try {
            Long hospitalId = securityHelper.getCurrentHospitalId();
            logger.info("Downloading prescription for appointment: {} in hospital: {}", LogSanitizer.clean(appointmentId), hospitalId);

            // Resolve Appointment ID
            java.util.Optional<com.hms.entity.Appointment> apptOpt = appointmentRepository
                    .findByPublicIdAndHospitalIdAndIsActiveTrue(appointmentId, hospitalId);
            if (apptOpt.isEmpty()) {
                try {
                    Long id = Long.parseLong(appointmentId);
                    apptOpt = appointmentRepository.findByIdAndHospitalIdAndIsActiveTrue(id, hospitalId);
                } catch (NumberFormatException e) {
                }
            }
            com.hms.entity.Appointment appointment = apptOpt
                    .orElseThrow(() -> new ResourceNotFoundException("Appointment not found"));

            com.hms.entity.MedicalRecord record = medicalRecordRepository.findByAppointmentId(appointment.getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Consultation not found for this appointment"));
            java.util.List<com.hms.entity.Prescription> prescriptions = prescriptionRepository
                    .findByMedicalRecordId(record.getId());

            Doctor doctor = doctorRepository.findByIdOrUserId(record.getDoctorId(), userRepository)
                    .orElseThrow(() -> new ResourceNotFoundException("Doctor not found"));

            com.hms.entity.Patient patient = patientService.getPatientById(record.getPatientId());
            com.hms.entity.Hospital hospital = hospitalRepository.findById(record.getHospitalId())
                    .orElseThrow(() -> new ResourceNotFoundException("Hospital not found"));

            java.io.ByteArrayInputStream pdf = pdfService.generatePrescriptionPdf(hospital, doctor, patient, record,
                    prescriptions);

            HttpHeaders headers = new HttpHeaders();
            headers.add("Content-Disposition", "inline; filename=prescription_" + appointmentId + ".pdf");

            return ResponseEntity
                    .ok()
                    .headers(headers)
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(new InputStreamResource(pdf));
        } catch (Exception e) {
            logger.error("Error downloading prescription for appointment {}", LogSanitizer.clean(appointmentId), e);
            return ResponseEntity.status(500).body("Failed to generate PDF");
        }
    }

        @GetMapping("/prescription/opd/{opdId}/pdf")
        public ResponseEntity<?> downloadPrescriptionByOpd(@PathVariable Long opdId) {
        try {
            Long hospitalId = securityHelper.getCurrentHospitalId();

            // Scoped at the lookup. The OPD is loaded by raw id no longer: a foreign id simply is
            // not found, which is the same 404 this already returned after the medical-record
            // check below -- so the contract is unchanged and the guarantee moves earlier.
            com.hms.entity.Opd opd = opdRepository
                .findByIdAndHospitalIdWithPatientAndDoctor(opdId, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("OPD not found"));

            com.hms.entity.MedicalRecord record = medicalRecordRepository.findByOpdId(opd.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Consultation not found for this OPD"));

            // hospitalId was read but never compared: the consultation, prescriptions and
            // doctor were all fetched by raw id, and a foreign tenant only failed because
            // PatientService happens to scope its own lookup — protected by accident.
            // Check the tenant explicitly, before any patient data is loaded.
            if (hospitalId == null || !hospitalId.equals(record.getHospitalId())) {
                throw new ResourceNotFoundException("OPD not found");
            }

            java.util.List<com.hms.entity.Prescription> prescriptions = prescriptionRepository
                .findByMedicalRecordId(record.getId());

            Doctor doctor = doctorRepository.findByIdOrUserId(record.getDoctorId(), userRepository)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found"));

            com.hms.entity.Patient patient = patientService.getPatientById(record.getPatientId());
            com.hms.entity.Hospital hospital = hospitalRepository.findById(record.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException("Hospital not found"));

            java.io.ByteArrayInputStream pdf = pdfService.generatePrescriptionPdf(hospital, doctor, patient, record,
                prescriptions);

            HttpHeaders headers = new HttpHeaders();
            headers.add("Content-Disposition", "inline; filename=prescription_opd_" + opdId + ".pdf");

            return ResponseEntity
                .ok()
                .headers(headers)
                .contentType(MediaType.APPLICATION_PDF)
                .body(new InputStreamResource(pdf));
        } catch (ResourceNotFoundException e) {
            // A missing (or foreign-tenant) OPD is a 404, not a server fault. Letting this
            // fall into the catch-all below reported it as "Failed to generate PDF" / 500.
            throw e;
        } catch (Exception e) {
            logger.error("Error downloading prescription for opd {}", opdId, e);
            return ResponseEntity.status(500).body("Failed to generate PDF");
        }
        }
}
