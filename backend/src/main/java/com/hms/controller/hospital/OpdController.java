package com.hms.controller.hospital;

import jakarta.validation.Valid;

import com.hms.exception.ResourceNotFoundException;

import com.hms.dto.CreateOpdRequest;
import com.hms.entity.Opd;
import com.hms.entity.Hospital;
import com.hms.entity.MedicalRecord;
import com.hms.service.hospital.OpdService;
import com.hms.security.SecurityContextHelper;
import com.hms.repository.DoctorRepository;
import com.hms.repository.HospitalRepository;
import com.hms.repository.MedicalRecordRepository;
import com.hms.entity.Doctor;
import java.util.Collections;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import java.io.ByteArrayOutputStream;
import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;

@RestController
@RequestMapping({"/hospital/opd", "/clinic/opd", "/pharmacy/opd"})
public class OpdController {

    @Autowired
    private com.hms.service.hospital.OpdIdempotencyService opdIdempotencyService;

    @Autowired
    private com.hms.repository.OpdRepository opdIdempotencyOpdRepository;

    @Autowired
    private com.hms.service.PdfService pdfService;

    @org.springframework.beans.factory.annotation.Autowired
    private com.hms.repository.HospitalSettingRepository hospitalSettingRepository;


    private static final Logger logger = LoggerFactory.getLogger(OpdController.class);

    private final OpdService opdService;
    private final SecurityContextHelper securityHelper;
    private final DoctorRepository doctorRepository;
    private final MedicalRecordRepository medicalRecordRepository;
    private final HospitalRepository hospitalRepository;
    private final com.hms.repository.LabOrderRepository labOrderRepository;
    private final com.hms.repository.BillingRepository billingRepository;
    private final com.hms.repository.PrescriptionRepository prescriptionRepository;
    private final com.hms.repository.UserRepository userRepository;
    private final com.hms.service.hospital.PatientService patientService;

    public OpdController(OpdService opdService,
                         SecurityContextHelper securityHelper, DoctorRepository doctorRepository,
                         MedicalRecordRepository medicalRecordRepository, HospitalRepository hospitalRepository,
                         com.hms.repository.LabOrderRepository labOrderRepository,
                         com.hms.repository.BillingRepository billingRepository,
                         com.hms.repository.PrescriptionRepository prescriptionRepository,
                         com.hms.repository.UserRepository userRepository,
                         com.hms.service.hospital.PatientService patientService) {
        this.opdService = opdService;
        this.securityHelper = securityHelper;
        this.doctorRepository = doctorRepository;
        this.medicalRecordRepository = medicalRecordRepository;
        this.hospitalRepository = hospitalRepository;
        this.billingRepository = billingRepository;
        this.prescriptionRepository = prescriptionRepository;
        this.userRepository = userRepository;
        this.patientService = patientService;
        this.labOrderRepository = labOrderRepository;
    }

    /**
     * Register an OPD visit — once per logical submission.
     *
     * <p>Registering inserts the OPD, a queue entry and, under "bill before OPD", a PAID bill.
     * None of that is repeatable, so a double-clicked button or a retried request charged the
     * patient twice and queued them twice with nothing able to detect it afterwards.
     *
     * <p>The key is claimed here rather than inside OpdService on purpose. Idempotency is a fact
     * about the REQUEST, not about the clinical work, and the claim needs its own transaction —
     * a unique-key violation marks the surrounding transaction rollback-only, so claiming inside
     * the service's transaction would poison the very work being protected. Doing it at this
     * layer also keeps the service's own transaction boundary exactly as it was.
     *
     * <p>A caller that sends no key behaves exactly as before, so no existing client breaks.
     */
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR', 'RECEPTIONIST')")
    @PostMapping
    public ResponseEntity<Opd> createOpd(@Valid @RequestBody CreateOpdRequest req) {
        String key = req.getIdempotencyKey() == null ? null : req.getIdempotencyKey().trim();
        if (key == null || key.isEmpty()) {
            return ResponseEntity.ok(opdService.createOpd(req));
        }

        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            throw new com.hms.exception.UnauthorizedException("Hospital ID not found in context");
        }

        com.hms.service.hospital.OpdIdempotencyService.Claim claim = opdIdempotencyService.claim(hospitalId, key);
        if (claim.isReplay()) {
            // The same submission arriving again: hand back what it already produced.
            // Tenant-scoped, like every other read: the claim was matched on hospital_id, and the
            // OPD it points at must belong to the same facility before it is handed back.
            return ResponseEntity.ok(
                    opdIdempotencyOpdRepository.findByIdAndHospitalIdWithPatientAndDoctor(claim.existingOpdId(), hospitalId)
                            .orElseThrow(() -> new com.hms.exception.ResourceNotFoundException("OPD not found")));
        }
        try {
            Opd created = opdService.createOpd(req);
            opdIdempotencyService.complete(hospitalId, key, created.getId());
            return ResponseEntity.ok(created);
        } catch (RuntimeException failed) {
            // Release the key so a corrected retry is not told forever that it is a duplicate.
            opdIdempotencyService.release(hospitalId, key);
            throw failed;
        }
    }

    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR', 'RECEPTIONIST')")
    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> getOpdPdf(@PathVariable String id) {
        Long opdId;
        try {
            if (id.startsWith("OPD-")) {
                opdId = Long.parseLong(id.substring(4));
            } else {
                opdId = Long.parseLong(id);
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
        Opd opd = opdService.getOpdById(opdId);
        if (opd == null) return ResponseEntity.notFound().build();

        com.hms.entity.Patient patient = opd.getPatient();
        Doctor doctor = opd.getDoctor();
        Hospital hospital = null;
        if (patient != null && patient.getHospitalId() != null) {
            hospital = hospitalRepository.findById(patient.getHospitalId()).orElse(null);
        }
        MedicalRecord medicalRecord = medicalRecordRepository.findByOpdId(opdId).orElse(null);
        // Lab tests the doctor advised at this consultation, so they print on the case paper.
        java.util.List<com.hms.entity.LabOrder> labOrders = medicalRecord != null
                ? labOrderRepository.findByMedicalRecordId(medicalRecord.getId())
                : java.util.List.of();

        try (java.io.ByteArrayInputStream pdfStream = pdfService.generateCasePaperPdf(hospital, doctor, patient, opd, medicalRecord, labOrders)) {
            byte[] pdfBytes = pdfStream.readAllBytes();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.add("Content-Disposition", "inline; filename=case_" + opd.getCaseId() + ".pdf");
            return ResponseEntity.ok().headers(headers).body(pdfBytes);
        } catch (Exception e) {
            logger.error("Failed to generate case-paper PDF", e);
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * All consultation documents as ONE multi-page PDF: case paper, then the bill, then the
     * prescription (only when medicines were prescribed). Printed as a single job so the
     * browser shows one print dialog and every page comes out — firing a dialog per document
     * was unreliable and dropped pages.
     */
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR', 'RECEPTIONIST')")
    @GetMapping("/{id}/documents/pdf")
    public ResponseEntity<byte[]> getOpdDocumentsPdf(@PathVariable String id) {
        Long opdId;
        try {
            opdId = Long.parseLong(id.startsWith("OPD-") ? id.substring(4) : id);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
        Opd opd = opdService.getOpdById(opdId);
        if (opd == null) return ResponseEntity.notFound().build();

        com.hms.entity.Patient patient = opd.getPatient();
        Doctor doctor = opd.getDoctor();
        Hospital hospital = (patient != null && patient.getHospitalId() != null)
                ? hospitalRepository.findById(patient.getHospitalId()).orElse(null) : null;
        MedicalRecord medicalRecord = medicalRecordRepository.findByOpdId(opdId).orElse(null);

        // Print Settings — which pages this hospital includes in the consultation print.
        // Missing row / null ⇒ include (today's behaviour).
        com.hms.entity.HospitalSetting printSettings = (hospital != null)
                ? hospitalSettingRepository.findByHospital_Id(hospital.getId()).orElse(null) : null;
        boolean incCasePaper    = printSettings == null || !Boolean.FALSE.equals(printSettings.getPrintCasePaper());
        boolean incBill         = printSettings == null || !Boolean.FALSE.equals(printSettings.getPrintBill());
        boolean incPrescription = printSettings == null || !Boolean.FALSE.equals(printSettings.getPrintPrescription());
        boolean incInClinic     = printSettings == null || !Boolean.FALSE.equals(printSettings.getPrintInClinic());

        java.util.List<byte[]> parts = new java.util.ArrayList<>();
        try {
            // 1. Case paper — with lab tests + follow-up.
            if (incCasePaper) {
                java.util.List<com.hms.entity.LabOrder> labOrders = medicalRecord != null
                        ? labOrderRepository.findByMedicalRecordId(medicalRecord.getId()) : java.util.List.of();
                parts.add(pdfService.generateCasePaperPdf(hospital, doctor, patient, opd, medicalRecord, labOrders)
                        .readAllBytes());
            }

            // 2. Bill (when one exists for this OPD).
            if (incBill) {
                com.hms.entity.Billing bill = billingRepository.findByOpdId(opdId).orElse(null);
                if (bill != null) {
                    parts.add(pdfService.generateBillingReceiptPdf(hospital, patient, bill).readAllBytes());
                }
            }

            // 3. Prescription (only when medicines were prescribed at this consultation).
            if (incPrescription && medicalRecord != null) {
                java.util.List<com.hms.entity.Prescription> prescriptions =
                        prescriptionRepository.findByMedicalRecordId(medicalRecord.getId());
                if (prescriptions != null && !prescriptions.isEmpty()) {
                    Doctor rxDoctor = doctor;
                    if (rxDoctor == null && medicalRecord.getDoctorId() != null) {
                        rxDoctor = doctorRepository.findByIdOrUserId(medicalRecord.getDoctorId(), userRepository)
                                .orElse(null);
                    }
                    parts.add(pdfService.generatePrescriptionPdf(hospital, rxDoctor, patient, medicalRecord,
                            prescriptions).readAllBytes());
                }
            }

            // 4. In-clinic medicines slip (only when items were administered at this consultation).
            if (incInClinic && medicalRecord != null) {
                String adminJson = medicalRecord.getAdministeredItemsJson();
                boolean hasAdministered = adminJson != null && !adminJson.trim().isEmpty()
                        && !adminJson.trim().equals("[]");
                if (hasAdministered) {
                    parts.add(patientService.getOpdMedicinesPdf(opdId).readAllBytes());
                }
            }

            if (parts.isEmpty()) {
                // Every page is toggled off (or nothing to print) — nothing to merge.
                return ResponseEntity.noContent().build();
            }

            byte[] merged = pdfService.mergePdfs(parts).readAllBytes();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.add("Content-Disposition", "inline; filename=consultation_" + opd.getCaseId() + ".pdf");
            return ResponseEntity.ok().headers(headers).body(merged);
        } catch (Exception e) {
            logger.error("Failed to build combined consultation PDF for OPD {}", opdId, e);
            return ResponseEntity.status(500).build();
        }
    }


    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR', 'RECEPTIONIST')")
    @GetMapping
    public ResponseEntity<?> listOpds(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String date,
            @RequestParam(required = false) com.hms.entity.Opd.Status status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(page, size);
        var result = opdService.getOpds(search, date, status, pageable);
        return ResponseEntity.ok(result);
    }

    /**
     * Pending IPD requests -- doctor recommended admission, reception has not admitted yet.
     * Reception acts on these, so the filtering belongs here and not in the browser: the
     * dashboard previously paged 1000 OPDs and filtered client-side, which silently lost any
     * request beyond that page.
     */
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'RECEPTIONIST')")
    @GetMapping("/ipd-requests/count")
    public ResponseEntity<?> getPendingIpdRequestCount() {
        return ResponseEntity.ok(java.util.Map.of("count", opdService.getPendingIpdRequestCount()));
    }

    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'RECEPTIONIST')")
    @GetMapping("/ipd-requests")
    public ResponseEntity<?> getPendingIpdRequests(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        org.springframework.data.domain.Pageable pageable =
                org.springframework.data.domain.PageRequest.of(page, size);
        return ResponseEntity.ok(opdService.getPendingIpdRequests(pageable));
    }

    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR', 'RECEPTIONIST')")
    @GetMapping("/queue/doctor/{doctorId}")
    public ResponseEntity<java.util.List<?>> getDoctorQueue(@PathVariable Long doctorId) {
        java.util.List<?> queue = opdService.getQueueForDoctor(doctorId);
        return ResponseEntity.ok(queue);
    }

    /**
     * Get queue for the currently authenticated doctor.
     * This maps the authenticated user (by email + hospital) to a Doctor record
     * and returns that doctor's queue. Useful for doctor clients that only have
     * the user authentication context.
     */
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR')")
    @GetMapping("/queue/my")
    public ResponseEntity<java.util.List<?>> getMyQueue() {
        try {
            Long hospitalId = securityHelper.getCurrentHospitalId();
            String email = securityHelper.getCurrentUserEmail();
            logger.debug("/hospital/opd/queue/my called - hospitalId={}, email={}", hospitalId, email);
            Optional<Doctor> d = doctorRepository.findByEmailAndHospitalId(email, hospitalId);
            if (d.isPresent()) {
                Long docId = d.get().getId();
                java.util.List<?> queue = opdService.getQueueForDoctor(docId);
                logger.debug("Doctor id={} -> queue size={}", docId, queue == null ? 0 : queue.size());
                return ResponseEntity.ok(queue == null ? java.util.List.of() : queue);
            }
            logger.debug("No doctor record found for email={} hospitalId={}", email, hospitalId);
            return ResponseEntity.ok(Collections.emptyList());
        } catch (Exception e) {
            logger.error("Failed to fetch my queue", e);
            return ResponseEntity.ok(Collections.emptyList());
        }
    }

    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR', 'RECEPTIONIST')")
    @GetMapping("/{id}")
    public ResponseEntity<Opd> getOpd(@PathVariable String id) {
        Long opdId;
        try {
            if (id.startsWith("OPD-")) {
                opdId = Long.parseLong(id.substring(4));
            } else {
                opdId = Long.parseLong(id);
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
        Opd opd = opdService.getOpdById(opdId);
        if (opd == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(opd);
    }

    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR', 'RECEPTIONIST')")
    @GetMapping("/queue")
    public ResponseEntity<java.util.List<?>> getHospitalQueue() {
        java.util.List<?> queue = opdService.getHospitalQueue();
        return ResponseEntity.ok(queue);
    }

    // GET /today-followups is gone. It triggered follow-up encounter creation from a read, and
    // resolved each patient and doctor one row at a time. Outstanding follow-ups now come from
    // FollowUpController, which reads in a single query and writes nothing. No caller existed in
    // the frontend or backend at the time of removal.

@PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    @GetMapping("/report/pdf")
    public ResponseEntity<?> downloadOpdReportPdf(
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate date,
            @RequestParam(required = false) com.hms.entity.Opd.Status status,
            @RequestParam(required = false) String reportType) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            throw new com.hms.exception.UnauthorizedException("Hospital context not found");
        }

        String dateStr = (date != null) ? date.toString() : null;
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(0, 1000);
        java.util.List<Opd> opds = opdService.getOpds(null, dateStr, status, pageable).getContent();

        com.hms.entity.Hospital hospital = hospitalRepository.findById(hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Hospital not found"));

        java.io.ByteArrayInputStream pdfStream = pdfService.generateOpdReportPdf(hospital, date, opds, reportType);
        org.springframework.core.io.InputStreamResource resource = new org.springframework.core.io.InputStreamResource(pdfStream);

        String filename = "OPD_Report_" + (date != null ? date.toString() : "AllTime") + ".pdf";
        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename)
                .contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                .body(resource);
    }
}

