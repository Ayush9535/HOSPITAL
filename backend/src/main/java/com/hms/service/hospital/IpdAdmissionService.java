package com.hms.service.hospital;

import com.hms.entity.Bed;
import com.hms.entity.Billing;
import com.hms.entity.IpdAdmission;
import com.hms.entity.Opd;
import com.hms.repository.BedRepository;
import com.hms.repository.BillingRepository;
import com.hms.repository.IpdAdmissionRepository;
import com.hms.repository.OpdRepository;
import com.hms.security.SecurityContextHelper;

import com.hms.exception.ResourceNotFoundException;
import com.hms.exception.UnauthorizedException;
import com.hms.exception.ConflictException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
public class IpdAdmissionService {

    private static final Logger logger = LoggerFactory.getLogger(IpdAdmissionService.class);

    @Autowired
    private IpdAdmissionRepository ipdAdmissionRepository;

    @Autowired
    private com.hms.repository.PatientRepository patientRepository;

    // Nurse module (Phase 1): close active nurse assignments on discharge.
    @Autowired
    private com.hms.repository.PatientNurseAssignmentRepository patientNurseAssignmentRepository;

    // Nurse module: auto-assign the admitted patient to a ward nurse.
    @Autowired
    private NurseAssignmentService nurseAssignmentService;

    @Autowired
    private PatientAssignmentService patientAssignmentService;

    @Autowired
    private com.hms.repository.HospitalSettingRepository hospitalSettingRepository;

    @Autowired
    private com.hms.repository.DoctorRepository doctorRepository;

    @Autowired
    private com.hms.repository.WardRepository wardRepository;

    @Autowired
    private OpdRepository opdRepository;

    @Autowired
    private BedRepository bedRepository;

    @Autowired
    private BillingRepository billingRepository;

    @Autowired
    private BillingService billingService;

    @Autowired
    private com.hms.service.AuditLogService auditLogService;

    @Autowired
    private com.hms.repository.MedicalRecordRepository medicalRecordRepository;

    @Autowired
    private com.hms.repository.PrescriptionRepository prescriptionRepository;
    @Autowired
    private com.hms.repository.MedicineRepository medicineRepository;
    @Autowired
    private com.hms.repository.BillingItemRepository billingItemRepository;
    @Autowired
    private com.hms.repository.BillingMedicineRepository billingMedicineRepository;
    @Autowired
    private com.hms.repository.DischargeSummaryRepository dischargeSummaryRepository;
    @Autowired
    private com.hms.service.hospital.NotificationService notificationService;
    @Autowired
    private com.hms.repository.BillingPaymentRepository billingPaymentRepository;

    @Autowired
    private com.hms.repository.AppointmentRepository appointmentRepository;

    @Autowired
    private com.hms.repository.HospitalRepository hospitalRepository;

    @Autowired
    private com.hms.repository.IpdBedHistoryRepository ipdBedHistoryRepository;

    @Autowired
    private SecurityContextHelper securityHelper;

    @Autowired
    private com.hms.repository.QueueEntryRepository queueEntryRepository;

    @Autowired
    private com.hms.repository.HospitalInventoryRepository hospitalInventoryRepository;

    @Autowired
    private HospitalInventoryService hospitalInventoryService;

    @Autowired
    private com.hms.repository.HospitalServiceRepository hospitalServiceRepository;

    @Autowired
    private com.hms.security.HospitalWebSocketHandler webSocketHandler;

    // Nursing Mgmt Phase C2: all bed status writes go through the audited service.
    @Autowired
    private BedStatusService bedStatusService;

    /**
     * ICU Phase 3. MANDATORY propagation — it joins the transaction opened here and never starts
     * one, so an ICU stay can never commit apart from the bed movement that caused it.
     */
    @Autowired
    private com.hms.service.hospital.icu.IcuStayService icuStayService;

    @Transactional
    public IpdAdmission admitFromOpd(Long opdId, Long wardId, Long bedId, String admissionType, String primaryDiagnosis) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) throw new UnauthorizedException("Hospital ID not found in context");

        // IPD numbers are globally unique today. Lock one durable global hospital row before
        // reading MAX+1 so admissions from different tenants cannot allocate the same number.
        hospitalRepository.findGlobalIpdNumberLock()
                .orElseThrow(() -> new IllegalStateException("No hospital available for IPD number allocation"));

        Opd opd = opdRepository.findByIdAndHospitalIdWithPatientAndDoctorForUpdate(opdId, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("OPD not found"));

        com.hms.entity.Patient patient = patientRepository
                .findByIdAndHospitalIdForUpdate(opd.getPatient().getId(), hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found"));
        if (ipdAdmissionRepository.existsByHospitalIdAndPatientIdAndStatusIn(hospitalId, patient.getId(),
                java.util.List.of("ADMITTED", "DISCHARGE_PLANNED"))) {
            throw new ConflictException("Patient already has an active IPD admission");
        }
        if (Opd.Status.IN_IPD.equals(opd.getStatus())) {
            throw new ConflictException("OPD is already admitted to IPD");
        }

        // Lock the selected tenant-scoped bed before checking availability. The lock remains
        // held through the admission and status audit, so exactly one concurrent request wins.
        Bed bed = bedRepository.findByBedIdAndHospitalIdForUpdate(bedId, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Bed not found"));
        if (!bed.getStatus().equalsIgnoreCase("available")) {
            throw new ConflictException("Bed is no longer available");
        }

        // Resolve the selected ward tenant-scoped before creating an admission. Nurse assignment
        // remains best-effort below, so staffing metadata cannot make an available bed unusable.
        com.hms.entity.Ward ward = wardRepository.findByWardIdAndHospitalId(wardId, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Ward not found"));
        if (!wardId.equals(bed.getWardId())) {
            throw new IllegalArgumentException("Selected bed does not belong to the selected ward");
        }

        // Create IPD admission with sequential IPD-1, IPD-2, IPD-3...
        IpdAdmission ipd = new IpdAdmission();
        long maxIpd = java.util.Optional.ofNullable(ipdAdmissionRepository.findMaxIpdSequence()).orElse(0L);
        if (maxIpd == Long.MAX_VALUE) {
            throw new IllegalStateException("IPD number sequence is exhausted");
        }
        long nextIpd = maxIpd + 1;
        ipd.setIpdNumber("IPD-" + nextIpd);
        ipd.setPatientId(patient.getId());
        ipd.setDoctorId(opd.getDoctor() != null ? opd.getDoctor().getId() : null);
        ipd.setHospitalId(hospitalId);
        ipd.setSourceOpdId(opd.getId());
        ipd.setAdmissionType(admissionType != null ? admissionType : "ELECTIVE");
        ipd.setStatus("ADMITTED");
        ipd.setAdmissionDatetime(LocalDateTime.now());
        ipd.setWardId(wardId);
        ipd.setBedId(bedId);
        ipd.setPrimaryDiagnosis(primaryDiagnosis != null ? primaryDiagnosis : "");
        ipd.setAdmittedByUserId(securityHelper.getCurrentUserId());

        IpdAdmission saved = ipdAdmissionRepository.save(ipd);

        // Bed history is part of the admission record, not best-effort telemetry.
        com.hms.entity.IpdBedHistory initialHist = new com.hms.entity.IpdBedHistory();
        initialHist.setIpdAdmissionId(saved.getId());
        initialHist.setWardId(wardId);
        initialHist.setBedId(bedId);
        initialHist.setAssignedAt(LocalDateTime.now());
        ipdBedHistoryRepository.save(initialHist);

        // ICU Phase 3: a direct admission into a critical-care ward opens an ICU stay. Critical
        // state, deliberately NOT best-effort — a patient in an ICU bed with no stay record breaks
        // the board's central invariant silently and loses the admission time for good.
        // EMERGENCY vs OPD is derived from the admission type rather than asked again.
        icuStayService.onWardSettled(saved, null,
                "EMERGENCY".equalsIgnoreCase(saved.getAdmissionType())
                        ? com.hms.entity.IcuStay.SRC_EMERGENCY
                        : com.hms.entity.IcuStay.SRC_OPD,
                null, null);

        // Mark bed occupied (Nursing Mgmt Phase C2: audited bed status change)
        Bed occupiedBed = bedStatusService.changeLocked(bed, com.hms.entity.BedStatus.OCCUPIED, "IPD admission");
        occupiedBed.setCurrentIpdAdmissionId(saved.getId());
        bedRepository.save(occupiedBed);

        // Primary assignment is optional, but an attempted persisted assignment must not be
        // silently lost. With zero or multiple eligible nurses this is a no-op.
        patientAssignmentService.onAdmission(saved);

        // Mark OPD as completed/closed
        // OPD status is stored as a string in many places; set to string to avoid enum mismatch
        try {
            opd.setStatus(Opd.Status.IN_IPD);
        } catch (Exception ex) {
            // fallback if OPD uses enum type
            opd.setStatus(Opd.Status.COMPLETED);
        }
        opdRepository.save(opd);

        // Remove from doctor's active queue
        try {
            queueEntryRepository.deleteByOpdId(opdId);
        } catch (Exception e) {
            logger.warn("Failed to delete queue entry for OPD ID during IPD admission", e);
        }

        com.hms.entity.Hospital hospital = hospitalRepository.findById(hospitalId).orElse(null);
        boolean hasBillingModule = hospital != null && hospital.getModules() != null && hospital.getModules().contains("BILLING");

        if (hasBillingModule) {
            // Create initial IPD billing (empty / started)
            java.math.BigDecimal bedPrice = java.math.BigDecimal.ZERO;
            if (ward.getBedPrice() != null) {
                bedPrice = ward.getBedPrice();
            }

            Long appointmentId = null;
            try {
                java.util.List<com.hms.entity.Appointment> appointments = appointmentRepository.findByPatientIdAndHospitalIdAndIsActiveTrueOrderByAppointmentDateDesc(saved.getPatientId(), hospitalId);
                if (appointments != null && !appointments.isEmpty()) {
                    appointmentId = appointments.get(0).getId();
                }
            } catch (Exception e) {
                logger.debug("Could not resolve latest appointment for patient during IPD admission billing", e);
            }

            Billing bill = new Billing();
            bill.setHospitalId(hospitalId);
            bill.setPatientId(saved.getPatientId());
            bill.setDoctorId(saved.getDoctorId());
            bill.setIpdAdmissionId(saved.getId());
            bill.setOpdId(opd.getId());
            bill.setAppointmentId(appointmentId);
            bill.setBillingType("IPD");
            bill.setAmount(bedPrice);
            bill.setDescription("Bed Price");
            bill.setPaymentStatus("PENDING");
            Billing savedBill = billingRepository.save(bill);

            // Create billing item for bed price
            if (bedPrice != null) {
                com.hms.entity.BillingItem item = new com.hms.entity.BillingItem();
                item.setBillingId(savedBill.getId());
                item.setHospitalId(hospitalId);
                item.setDescription("Bed Price");
                item.setAmount(bedPrice);
                billingItemRepository.save(item);
            }
        }

        logger.info("Created IPD admission {} for OPD {}", saved.getIpdNumber(), opdId);

        // Audit log
        try {
            auditLogService.logAction(
                    "IPD_ADMISSION_CREATED",
                    "Patient was admitted to IPD (Case: " + saved.getIpdNumber() + ").",
                    securityHelper.getCurrentUserEmail(),
                    hospitalId,
                    "IPD",
                    saved.getId().toString(),
                    null);
        } catch (Exception e) {
            logger.warn("Failed to create audit log for IPD admission", e);
        }

        try {
            webSocketHandler.broadcast(hospitalId, "{\"type\":\"REFRESH_DATA\"}");
        } catch (Exception e) {
            logger.warn("Failed to broadcast WebSocket refresh data from admitFromOpd", e);
        }

        return saved;
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<java.util.Map<String, Object>> listIpdAdmissions(int page, int size, String search) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) throw new UnauthorizedException("Hospital ID not found in context");

        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(page, size, org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "admissionDatetime"));
        org.springframework.data.domain.Page<IpdAdmission> p = ipdAdmissionRepository.findByHospitalId(hospitalId, pageable);

        java.util.List<java.util.Map<String,Object>> rows = new java.util.ArrayList<>();
        for (IpdAdmission ipd : p.getContent()) {
            java.util.Map<String,Object> m = new java.util.HashMap<>();
            m.put("ipd", ipd);
            patientRepository.findById(ipd.getPatientId()).ifPresent(patient -> m.put("patient", patient));
            doctorRepository.findById(ipd.getDoctorId()).ifPresent(doc -> m.put("doctor", doc));
            wardRepository.findById(ipd.getWardId()).ifPresent(ward -> m.put("ward", ward));
            bedRepository.findById(ipd.getBedId()).ifPresent(bed -> m.put("bed", bed));
            rows.add(m);
        }

        return new org.springframework.data.domain.PageImpl<>(rows, pageable, p.getTotalElements());
    }

    @Transactional(readOnly = true)
    public java.util.List<java.util.Map<String, Object>> listMyIpdAdmissionsForDoctor() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) throw new UnauthorizedException("Hospital ID not found in context");

        // Resolve current doctor's entity using authenticated user's email
        String email = securityHelper.getCurrentUserEmail();
        com.hms.entity.Doctor doctor = doctorRepository.findByEmailAndHospitalId(email, hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException("Doctor profile not found for current user"));
        Long doctorId = doctor.getId();

        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(0, 100, org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "admissionDatetime"));
        org.springframework.data.domain.Page<IpdAdmission> p = ipdAdmissionRepository.findByHospitalIdAndDoctorIdAndStatus(hospitalId, doctorId, "ADMITTED", pageable);

        java.util.List<java.util.Map<String,Object>> rows = new java.util.ArrayList<>();
        for (IpdAdmission ipd : p.getContent()) {
            java.util.Map<String,Object> m = new java.util.HashMap<>();
            m.put("ipd", ipd);
            patientRepository.findById(ipd.getPatientId()).ifPresent(patient -> m.put("patient", patient));
            doctorRepository.findById(ipd.getDoctorId()).ifPresent(doc -> m.put("doctor", doc));
            wardRepository.findById(ipd.getWardId()).ifPresent(ward -> m.put("ward", ward));
            bedRepository.findById(ipd.getBedId()).ifPresent(bed -> m.put("bed", bed));
            rows.add(m);
        }

        return rows;
    }

    /**
     * Role-aware fetch of currently ADMITTED IPD admissions.
     * Returns only ADMITTED patients. Receptionist sees all hospital admissions,
     * Doctor sees only their assigned admissions.
     */
    @Transactional(readOnly = true)
    public java.util.List<com.hms.dto.IpdAdmissionSummaryDTO> getAdmittedIpdSummariesForCurrentUser() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) throw new UnauthorizedException("Hospital ID not found in context");

        String role = securityHelper.getCurrentUserRole();
        java.util.List<IpdAdmission> admissions;

        if ("DOCTOR".equalsIgnoreCase(role)) {
            String email = securityHelper.getCurrentUserEmail();
            com.hms.entity.Doctor doctor = doctorRepository.findByEmailAndHospitalId(email, hospitalId)
                    .orElseThrow(() -> new ResourceNotFoundException("Doctor profile not found for current user"));
            Long doctorId = doctor.getId();
            admissions = ipdAdmissionRepository.findByHospitalIdAndDoctorIdAndStatus(hospitalId, doctorId, "ADMITTED");
        } else if ("RECEPTIONIST".equalsIgnoreCase(role) || "HOSPITAL_ADMIN".equalsIgnoreCase(role)) {
            admissions = ipdAdmissionRepository.findByHospitalIdAndStatusIn(hospitalId, java.util.Arrays.asList("ADMITTED", "DISCHARGE_PLANNED"));
        } else {
            throw new org.springframework.security.access.AccessDeniedException("Not allowed");
        }

        java.util.List<com.hms.dto.IpdAdmissionSummaryDTO> result = new java.util.ArrayList<>();
        for (IpdAdmission ipd : admissions) {
            com.hms.dto.IpdAdmissionSummaryDTO dto = new com.hms.dto.IpdAdmissionSummaryDTO();
            dto.setIpdId(ipd.getId());
            dto.setIpdNumber(ipd.getIpdNumber());
            // patient
            patientRepository.findById(ipd.getPatientId()).ifPresent(p -> {
                dto.setPatientName(p.getName());
                try { dto.setAge(p.getAge()); } catch (Exception e) { logger.debug("Could not map patient age to DTO", e); }
                dto.setGender(p.getGender());
            });
            // ward/bed
            wardRepository.findById(ipd.getWardId()).ifPresent(w -> dto.setWardName(w.getWardName()));
            bedRepository.findById(ipd.getBedId()).ifPresent(b -> dto.setBedNumber(b.getBedCode()));
            // doctor
            doctorRepository.findById(ipd.getDoctorId()).ifPresent(d -> dto.setDoctorName(d.getName()));
            dto.setAdmissionConfirmed(Boolean.TRUE.equals(ipd.getAdmissionConfirmed()));
            dto.setAdmissionDateTime(ipd.getAdmissionDatetime());
            dto.setStatus(ipd.getStatus());
            result.add(dto);
        }

        // sort by admissionDatetime desc
        result.sort((a,b) -> b.getAdmissionDateTime().compareTo(a.getAdmissionDateTime()));
        return result;
    }

    @Transactional(readOnly = true)
    /**
     * Load an admission by id and prove it belongs to the caller's hospital. Every
     * user-facing method here takes a raw numeric ipdId from the URL; using this instead
     * of a bare findById is what stops one hospital reading or mutating another's admission
     * (and, in the discharge/bed paths, silently adopting the victim's tenant context via
     * hospitalId = ipd.getHospitalId()). A cross-tenant id is reported as not-found.
     */
    private IpdAdmission requireOwnedAdmission(Long ipdId) {
        Long callerHospitalId = securityHelper.getCurrentHospitalId();
        if (callerHospitalId == null) throw new ResourceNotFoundException("IPD admission not found");
        return ipdAdmissionRepository.findByIdAndHospitalId(ipdId, callerHospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("IPD admission not found"));
    }

    /** Re-reads the admission under lock before a lifecycle or bed mutation. */
    private IpdAdmission requireOwnedAdmissionForUpdate(Long ipdId) {
        Long callerHospitalId = securityHelper.getCurrentHospitalId();
        if (callerHospitalId == null) throw new ResourceNotFoundException("IPD admission not found");
        return ipdAdmissionRepository.findByIdAndHospitalIdForUpdate(ipdId, callerHospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("IPD admission not found"));
    }

    public com.hms.dto.IpdAdmissionDetailsDTO getIpdAdmissionDetails(Long ipdId) {
        IpdAdmission ipd = requireOwnedAdmission(ipdId);

        com.hms.dto.IpdAdmissionDetailsDTO dto = new com.hms.dto.IpdAdmissionDetailsDTO();
        dto.setIpdNumber(ipd.getIpdNumber());
        dto.setStatus(ipd.getStatus());

        // patient
        com.hms.entity.Patient patient = patientRepository.findById(ipd.getPatientId()).orElse(null);
        if (patient != null) {
            com.hms.dto.IpdAdmissionDetailsDTO.PatientDTO p = new com.hms.dto.IpdAdmissionDetailsDTO.PatientDTO();
            p.id = patient.getId();
            p.name = patient.getName();
            try { p.age = patient.getAge(); } catch (Exception e) { logger.debug("Could not map patient age to details DTO", e); }
            p.gender = patient.getGender();
            dto.setPatient(p);
        }

        // admission info
        com.hms.dto.IpdAdmissionDetailsDTO.AdmissionDTO adm = new com.hms.dto.IpdAdmissionDetailsDTO.AdmissionDTO();
        adm.admissionDateTime = ipd.getAdmissionDatetime();
        adm.admissionType = ipd.getAdmissionType();
        adm.primaryDiagnosis = ipd.getPrimaryDiagnosis();
        wardRepository.findById(ipd.getWardId()).ifPresent(w -> adm.ward = w.getWardName());
        bedRepository.findById(ipd.getBedId()).ifPresent(b -> adm.bed = b.getBedCode());
        doctorRepository.findById(ipd.getDoctorId()).ifPresent(d -> adm.doctor = d.getName());
        dto.setAdmission(adm);

        // medical records for this IPD
        java.util.List<com.hms.entity.MedicalRecord> mrs = new java.util.ArrayList<>();
        try {
            mrs = medicalRecordRepository.findByIpdAdmissionIdOrderByCreatedAtDesc(ipdId);
        } catch (Exception ex) {
            // fallback to empty
            mrs = java.util.Collections.emptyList();
        }
        java.util.List<com.hms.dto.IpdAdmissionDetailsDTO.MedicalRecordDTO> mrDtos = new java.util.ArrayList<>();
        for (com.hms.entity.MedicalRecord mr : mrs) {
            com.hms.dto.IpdAdmissionDetailsDTO.MedicalRecordDTO mrd = new com.hms.dto.IpdAdmissionDetailsDTO.MedicalRecordDTO();
            mrd.date = mr.getCreatedAt() != null ? mr.getCreatedAt().toLocalDate().toString() : null;
            doctorRepository.findById(mr.getDoctorId()).ifPresent(d -> mrd.doctor = d.getName());
            mrd.diagnosis = mr.getDiagnosis();
            mrd.notes = mr.getTreatmentNotes();
            mrDtos.add(mrd);
        }
        dto.setMedicalRecords(mrDtos);

        // active prescriptions -> fetch by IPD and status
        java.util.List<com.hms.dto.IpdAdmissionDetailsDTO.PrescriptionDTO> active = new java.util.ArrayList<>();
        try {
            java.util.List<com.hms.entity.Prescription> activeList = prescriptionRepository.findByIpdAdmissionIdAndStatus(ipdId, "ACTIVE");
            for (com.hms.entity.Prescription p : activeList) {
                com.hms.dto.IpdAdmissionDetailsDTO.PrescriptionDTO pd = new com.hms.dto.IpdAdmissionDetailsDTO.PrescriptionDTO();
                pd.id = p.getId();
                pd.name = p.getMedicineName();
                pd.type = p.getType();
                pd.route = p.getRoute();
                pd.frequency = p.getFrequency();
                pd.status = p.getStatus();
                pd.startDate = p.getStartDate() != null ? p.getStartDate().toString() : null;
                pd.dosage = p.getDosage();
                pd.durationDays = p.getDurationDays();
                active.add(pd);
            }
        } catch (Exception e) {
            logger.warn("Failed to load active prescriptions for IPD {}", ipdId, e);
        }
        dto.setActivePrescriptions(active);

        // all prescriptions history for this IPD (ordered)
        java.util.List<com.hms.dto.IpdAdmissionDetailsDTO.PrescriptionDTO> all = new java.util.ArrayList<>();
        try {
            java.util.List<com.hms.entity.Prescription> allList = prescriptionRepository.findByIpdAdmissionIdOrderByStartDate(ipdId);
            for (com.hms.entity.Prescription p : allList) {
                com.hms.dto.IpdAdmissionDetailsDTO.PrescriptionDTO pd = new com.hms.dto.IpdAdmissionDetailsDTO.PrescriptionDTO();
                pd.id = p.getId();
                pd.name = p.getMedicineName();
                pd.type = p.getType();
                pd.route = p.getRoute();
                pd.frequency = p.getFrequency();
                pd.status = p.getStatus();
                pd.startDate = p.getStartDate() != null ? p.getStartDate().toString() : null;
                pd.dosage = p.getDosage();
                pd.durationDays = p.getDurationDays();
                all.add(pd);
            }
        } catch (Exception e) {
            logger.warn("Failed to load all prescription history for IPD {}", ipdId, e);
        }
        dto.setAllPrescriptions(all);

        // billing summary (aggregate)
        java.util.List<Billing> bills = billingRepository.findByIpdAdmissionId(ipdId);
        if (bills != null && !bills.isEmpty()) {
            java.math.BigDecimal total = java.math.BigDecimal.ZERO;
            java.math.BigDecimal paid = java.math.BigDecimal.ZERO;
            for (Billing b : bills) {
                // Add BillingItems and BillingMedicines for this bill
                java.util.List<com.hms.entity.BillingItem> items = billingItemRepository.findByBillingId(b.getId());
                java.util.List<com.hms.entity.BillingMedicine> medicines = billingMedicineRepository.findByBillingId(b.getId());
                if ((items != null && !items.isEmpty()) || (medicines != null && !medicines.isEmpty())) {
                    if (items != null) {
                        for (com.hms.entity.BillingItem it : items) {
                            if (it.getAmount() != null) {
                                total = total.add(it.getAmount());
                            }
                        }
                    }
                    if (medicines != null) {
                        for (com.hms.entity.BillingMedicine med : medicines) {
                            if (med.getAmount() != null) {
                                total = total.add(med.getAmount());
                            }
                        }
                    }
                } else {
                    java.math.BigDecimal bAmt = b.getAmount() != null ? b.getAmount() : java.math.BigDecimal.ZERO;
                    total = total.add(bAmt);
                }

                // Sum all BillingPayments made for this bill
                java.util.List<com.hms.entity.BillingPayment> payments = billingPaymentRepository.findByBillingId(b.getId());
                if (payments != null) {
                    for (com.hms.entity.BillingPayment pay : payments) {
                        if (pay.getAmount() != null) {
                            paid = paid.add(pay.getAmount());
                        }
                    }
                }
            }
            com.hms.dto.IpdAdmissionDetailsDTO.BillingDTO bd = new com.hms.dto.IpdAdmissionDetailsDTO.BillingDTO();
            bd.totalAmount = total;
            bd.paidAmount = paid;
            bd.balance = total.subtract(paid);
            dto.setBilling(bd);
        } else {
            dto.setBilling(null);
        }

        // administered stock items — sourced from BillingMedicine rows for this IPD
        java.util.List<com.hms.dto.IpdAdmissionDetailsDTO.AdministeredItemDTO> administeredDtos = new java.util.ArrayList<>();
        try {
            if (bills != null) {
                for (Billing b : bills) {
                    java.util.List<com.hms.entity.BillingMedicine> bMeds = billingMedicineRepository.findByBillingId(b.getId());
                    if (bMeds != null) {
                        for (com.hms.entity.BillingMedicine bm : bMeds) {
                            com.hms.dto.IpdAdmissionDetailsDTO.AdministeredItemDTO ad = new com.hms.dto.IpdAdmissionDetailsDTO.AdministeredItemDTO();
                            ad.name = bm.getMedicineName();
                            ad.quantity = bm.getQuantity();
                            ad.administeredAt = bm.getCreatedAt() != null ? bm.getCreatedAt().toLocalDate().toString() : null;
                            administeredDtos.add(ad);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to load administered billing medicines for IPD {}", ipdId, e);
        }
        dto.setAdministeredItems(administeredDtos);

        return dto;
    }

    @Transactional
    public com.hms.entity.MedicalRecord addIpdFollowup(Long ipdId, String diagnosis, String notes, java.util.List<com.hms.dto.ConsultationRequest.AdministeredItem> administeredItems) {
        String role = securityHelper.getCurrentUserRole();
        if (!"DOCTOR".equalsIgnoreCase(role) && !"HOSPITAL_ADMIN".equalsIgnoreCase(role)) {
            throw new org.springframework.security.access.AccessDeniedException("Only doctors can add follow-ups");
        }

        IpdAdmission ipd = requireOwnedAdmissionForUpdate(ipdId);
        if (ipd.getStatus() == null || !ipd.getStatus().equalsIgnoreCase("ADMITTED")) {
            throw new IllegalArgumentException("Cannot add follow-up to non-admitted IPD");
        }

        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) throw new UnauthorizedException("Hospital ID not found in context");

        String email = securityHelper.getCurrentUserEmail();
        com.hms.entity.Doctor doctor = doctorRepository.findByEmailAndHospitalId(email, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor profile not found for current user"));

        com.hms.entity.MedicalRecord mr = new com.hms.entity.MedicalRecord();
        mr.setHospitalId(hospitalId);
        mr.setPatientId(ipd.getPatientId());
        mr.setDoctorId(doctor.getId());
        mr.setIpdAdmissionId(ipdId);
        mr.setVisitType("IPD");
        mr.setDiagnosis(diagnosis);
        mr.setTreatmentNotes(notes);

        if (administeredItems != null && !administeredItems.isEmpty()) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                mr.setAdministeredItemsJson(mapper.writeValueAsString(administeredItems));
            } catch (Exception e) {
                logger.warn("Failed to serialize administered items JSON for IPD follow-up", e);
            }
        }

        com.hms.entity.MedicalRecord saved = medicalRecordRepository.save(mr);

        // IPD follow-ups do NOT add a consultation fee – doctors visit their own admitted patients.

        // --- Process Administered Items (Stock Deductions & Billing) ---
        com.hms.entity.Hospital hospital = hospitalRepository.findById(hospitalId).orElse(null);
        boolean hasBillingModule = hospital != null && hospital.getModules() != null && hospital.getModules().contains("BILLING");

        // Resolve billing record once (not per-item) to avoid N+1 queries
        Billing ipdBill = null;
        if (hasBillingModule) {
            java.util.List<Billing> bills = billingRepository.findByIpdAdmissionId(ipdId);
            ipdBill = (bills != null && !bills.isEmpty()) ? bills.get(0) : null;
        }

        if (administeredItems != null && !administeredItems.isEmpty()) {
            for (com.hms.dto.ConsultationRequest.AdministeredItem item : administeredItems) {
                if (item.getQuantity() == null || item.getQuantity() <= 0) {
                    throw new IllegalArgumentException("Administered item quantity must be positive");
                }
                if (item.getMedicineId() != null) {
                    // Tenant-scoped: medicineId arrives from the request body, so an unscoped
                    // findById let a caller in one hospital decrement another hospital's stock.
                    // DoctorService's copy of this block always carried the check; these two did not.
                    com.hms.entity.Medicine med = medicineRepository
                            .findByIdAndHospitalId(item.getMedicineId(), hospitalId)
                            .orElseThrow(() -> new ResourceNotFoundException("Medicine not found in active inventory: ID " + item.getMedicineId()));

                    if (med.getStockQuantity() < item.getQuantity()) {
                        throw new IllegalArgumentException("Insufficient stock for: " + med.getName() + " (Requested: " + item.getQuantity() + ", Available: " + med.getStockQuantity() + ")");
                    }

                    // Deduct Stock
                    int oldStock = med.getStockQuantity();
                    med.setStockQuantity(oldStock - item.getQuantity());
                    medicineRepository.save(med);

                    // Audit Log for Stock deduction
                    try {
                        auditLogService.logAction(
                                "INVENTORY_DEDUCTED",
                                "Deducted " + item.getQuantity() + " units of " + med.getName() + " for patient. Stock: " + oldStock + " -> " + med.getStockQuantity(),
                                securityHelper.getCurrentUserEmail(),
                                hospitalId,
                                "MEDICINE",
                                med.getId().toString(),
                                null
                        );
                    } catch (Exception e) {
                        logger.warn("Failed to write audit log for IPD follow-up medicine deduction", e);
                    }

                    if (hasBillingModule && ipdBill != null) {
                        // Create BillingMedicine charge
                        com.hms.entity.BillingMedicine bm = new com.hms.entity.BillingMedicine();
                        bm.setBillingId(ipdBill.getId());
                        bm.setHospitalId(hospitalId);
                        bm.setMedicineId(med.getId());
                        bm.setMedicineName(med.getName());
                        bm.setQuantity(item.getQuantity());
                        bm.setUnitPrice(java.math.BigDecimal.valueOf(med.getUnitPrice()));
                        bm.setAmount(bm.getUnitPrice().multiply(java.math.BigDecimal.valueOf(item.getQuantity())));
                        billingMedicineRepository.save(bm);
                    }
                }
            }
        }

        // Recalculate bill total (incorporates consultation fee + medicines + bed fees)
        if (hasBillingModule && ipdBill != null) {
            try {
                billingService.recalculateTotal(ipdBill.getId());
            } catch (Exception e) {
                logger.warn("Failed to recalculate billing total after IPD follow-up", e);
            }
        }

        try {
            webSocketHandler.broadcast(hospitalId, "{\"type\":\"REFRESH_DATA\"}");
        } catch (Exception e) {
            logger.warn("Failed to broadcast WebSocket refresh data from addIpdFollowup", e);
        }

        return saved;
    }

    @Transactional
    public void administerItems(Long ipdId, java.util.List<com.hms.dto.ConsultationRequest.AdministeredItem> administeredItems) {
        String role = securityHelper.getCurrentUserRole();
        if (!"DOCTOR".equalsIgnoreCase(role) && !"HOSPITAL_ADMIN".equalsIgnoreCase(role)) {
            throw new org.springframework.security.access.AccessDeniedException("Only doctors can administer items");
        }

        IpdAdmission ipd = requireOwnedAdmissionForUpdate(ipdId);
        if (ipd.getStatus() == null || !ipd.getStatus().equalsIgnoreCase("ADMITTED")) {
            throw new IllegalArgumentException("Cannot administer items to non-admitted IPD");
        }

        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) throw new UnauthorizedException("Hospital ID not found in context");

        com.hms.entity.Hospital hospital = hospitalRepository.findById(hospitalId).orElse(null);
        boolean hasBillingModule = hospital != null && hospital.getModules() != null && hospital.getModules().contains("BILLING");

        // Resolve billing record once (not per-item) to avoid N+1 queries
        Billing ipdBill = null;
        if (hasBillingModule) {
            java.util.List<Billing> bills = billingRepository.findByIpdAdmissionId(ipdId);
            ipdBill = (bills != null && !bills.isEmpty()) ? bills.get(0) : null;
        }

        if (administeredItems != null && !administeredItems.isEmpty()) {
            for (com.hms.dto.ConsultationRequest.AdministeredItem item : administeredItems) {
                if (item.getQuantity() == null || item.getQuantity() <= 0) {
                    throw new IllegalArgumentException("Administered item quantity must be positive");
                }
                if (item.getMedicineId() != null) {
                    // Tenant-scoped: medicineId arrives from the request body, so an unscoped
                    // findById let a caller in one hospital decrement another hospital's stock.
                    // DoctorService's copy of this block always carried the check; these two did not.
                    com.hms.entity.Medicine med = medicineRepository
                            .findByIdAndHospitalId(item.getMedicineId(), hospitalId)
                            .orElseThrow(() -> new ResourceNotFoundException("Medicine not found in active inventory: ID " + item.getMedicineId()));

                    if (med.getStockQuantity() < item.getQuantity()) {
                        throw new IllegalArgumentException("Insufficient stock for: " + med.getName() + " (Requested: " + item.getQuantity() + ", Available: " + med.getStockQuantity() + ")");
                    }

                    // Deduct Stock
                    int oldStock = med.getStockQuantity();
                    med.setStockQuantity(oldStock - item.getQuantity());
                    medicineRepository.save(med);

                    // Audit Log for Stock deduction
                    try {
                        auditLogService.logAction(
                                "INVENTORY_DEDUCTED",
                                "Deducted " + item.getQuantity() + " units of " + med.getName() + " for patient. Stock: " + oldStock + " -> " + med.getStockQuantity(),
                                securityHelper.getCurrentUserEmail(),
                                hospitalId,
                                "MEDICINE",
                                med.getId().toString(),
                                null
                        );
                    } catch (Exception e) {
                        logger.warn("Failed to write audit log for IPD medicine administration deduction", e);
                    }

                    if (hasBillingModule && ipdBill != null) {
                        // Create BillingMedicine charge
                        com.hms.entity.BillingMedicine bm = new com.hms.entity.BillingMedicine();
                        bm.setBillingId(ipdBill.getId());
                        bm.setHospitalId(hospitalId);
                        bm.setMedicineId(med.getId());
                        bm.setMedicineName(med.getName());
                        bm.setQuantity(item.getQuantity());
                        bm.setUnitPrice(java.math.BigDecimal.valueOf(med.getUnitPrice()));
                        bm.setAmount(bm.getUnitPrice().multiply(java.math.BigDecimal.valueOf(item.getQuantity())));
                        billingMedicineRepository.save(bm);
                    }
                }
            }

            if (hasBillingModule && ipdBill != null) {
                // Recalculate bill total (incorporates consultation fee + medicines + bed fees)
                try {
                    billingService.recalculateTotal(ipdBill.getId());
                } catch (Exception e) {
                    logger.warn("Failed to recalculate billing total after IPD medicine administration", e);
                }
            }
        }

        try {
            webSocketHandler.broadcast(hospitalId, "{\"type\":\"REFRESH_DATA\"}");
        } catch (Exception e) {
            logger.warn("Failed to broadcast WebSocket refresh data from administerItems", e);
        }
    }

    @Transactional
    public void administerHospitalItems(Long ipdId, java.util.List<com.hms.dto.AdministerHospitalItemsRequest.HospitalItem> items) {
        String role = securityHelper.getCurrentUserRole();
        if (!"DOCTOR".equalsIgnoreCase(role) && !"HOSPITAL_ADMIN".equalsIgnoreCase(role)) {
            throw new org.springframework.security.access.AccessDeniedException("Only doctors can administer items");
        }

        IpdAdmission ipd = requireOwnedAdmissionForUpdate(ipdId);
        if (ipd.getStatus() == null || (!ipd.getStatus().equalsIgnoreCase("ADMITTED") && !ipd.getStatus().equalsIgnoreCase("DISCHARGE_PLANNED"))) {
            throw new IllegalArgumentException("Cannot administer items to non-admitted IPD");
        }

        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) throw new UnauthorizedException("Hospital ID not found in context");

        com.hms.entity.Hospital hospital = hospitalRepository.findById(hospitalId).orElse(null);
        boolean hasBillingModule = hospital != null && hospital.getModules() != null && hospital.getModules().contains("BILLING");

        // Resolve billing record once (not per-item) to avoid N+1 queries
        Billing ipdBill = null;
        if (hasBillingModule) {
            java.util.List<Billing> bills = billingRepository.findByIpdAdmissionId(ipdId);
            ipdBill = (bills != null && !bills.isEmpty()) ? bills.get(0) : null;
        }

        if (items != null && !items.isEmpty()) {
            for (com.hms.dto.AdministerHospitalItemsRequest.HospitalItem item : items) {
                administerSingleHospitalItem(item, hospitalId, hasBillingModule, ipdBill);
            }
            if (hasBillingModule && ipdBill != null) {
                recalcBillTotalQuietly(ipdBill.getId());
            }
        }

        try {
            webSocketHandler.broadcast(hospitalId, "{\"type\":\"REFRESH_DATA\"}");
        } catch (Exception e) {
            logger.warn("Failed to broadcast WebSocket refresh data from administerHospitalItems", e);
        }
    }

    /** Consumes one hospital service and, when billing is active, appends a billing line for it. */
    private void administerSingleHospitalItem(com.hms.dto.AdministerHospitalItemsRequest.HospitalItem item,
            Long hospitalId, boolean hasBillingModule, Billing ipdBill) {
        java.math.BigDecimal serviceCharge = hospitalInventoryService.consumeService(
                item.getServiceId(), item.getQuantity(), hospitalId);
        if (hasBillingModule && ipdBill != null) {
            com.hms.entity.HospitalServiceEntity svc = hospitalServiceRepository.findByIdAndHospitalId(item.getServiceId(), hospitalId).orElse(null);
            String svcName = svc != null ? svc.getName() : ("Service #" + item.getServiceId());
            com.hms.entity.BillingItem bi = new com.hms.entity.BillingItem();
            bi.setBillingId(ipdBill.getId());
            bi.setHospitalId(hospitalId);
            bi.setDescription(svcName + " (Qty: " + item.getQuantity() + ")");
            bi.setAmount(serviceCharge);
            billingItemRepository.save(bi);
        }
    }

    /** Recalculates a bill's total (consultation + medicines + bed fees + hospital items); best-effort. */
    private void recalcBillTotalQuietly(Long billingId) {
        try {
            billingService.recalculateTotal(billingId);
        } catch (Exception e) {
            logger.warn("Failed to recalculate billing total after hospital item administration", e);
        }
    }

    @Transactional
    public com.hms.entity.Prescription addIpdPrescription(Long ipdId, com.hms.dto.AddIpdPrescriptionRequest req) {
        String role = securityHelper.getCurrentUserRole();
        if (!"DOCTOR".equalsIgnoreCase(role) && !"HOSPITAL_ADMIN".equalsIgnoreCase(role)) {
            throw new org.springframework.security.access.AccessDeniedException("Only doctors can add prescriptions");
        }

        IpdAdmission ipd = requireOwnedAdmissionForUpdate(ipdId);
        if (ipd.getStatus() == null || !ipd.getStatus().equalsIgnoreCase("ADMITTED")) {
            throw new IllegalArgumentException("Cannot add prescription to non-admitted IPD");
        }

        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) throw new UnauthorizedException("Hospital ID not found in context");

        // Resolve latest medical record for this IPD
        java.util.List<com.hms.entity.MedicalRecord> mrs = medicalRecordRepository.findByIpdAdmissionIdOrderByCreatedAtDesc(ipdId);
        com.hms.entity.MedicalRecord latest;
        if (mrs == null || mrs.isEmpty()) {
            latest = new com.hms.entity.MedicalRecord();
            latest.setHospitalId(hospitalId);
            latest.setPatientId(ipd.getPatientId());
            Long resolvedDocId = ipd.getDoctorId();
            if (resolvedDocId == null) {
                try {
                    java.util.Optional<com.hms.entity.Doctor> dopt = doctorRepository.findByEmailAndHospitalId(securityHelper.getCurrentUserEmail(), hospitalId);
                    if (dopt.isPresent()) {
                        resolvedDocId = dopt.get().getId();
                    }
                } catch (Exception e) {
                    logger.debug("Could not resolve doctor during IPD prescription creation", e);
                }
            }
            latest.setDoctorId(resolvedDocId);
            latest.setIpdAdmissionId(ipdId);
            latest.setVisitType("IPD");
            latest.setDiagnosis(ipd.getPrimaryDiagnosis() != null && !ipd.getPrimaryDiagnosis().trim().isEmpty() ? ipd.getPrimaryDiagnosis() : "IPD Admission");
            latest.setTreatmentNotes("Initial IPD Admission Medical Record");
            latest = medicalRecordRepository.save(latest);
        } else {
            latest = mrs.get(0);
        }

        String medicineName = req.getMedicineName() != null && !req.getMedicineName().trim().isEmpty()
                ? req.getMedicineName().trim()
                : null;
        if ((req.getMedicineName() == null || req.getMedicineName().trim().isEmpty()) && req.getMedicineId() == null) {
            throw new IllegalArgumentException("Either Medicine Name or Medicine ID is required");
        }
        if (req.getDose() == null || req.getDose().trim().isEmpty()) {
            throw new IllegalArgumentException("Prescription dose is required");
        }
        if (req.getFrequency() == null || req.getFrequency().trim().isEmpty()) {
            throw new IllegalArgumentException("Prescription frequency is required");
        }
        if (req.getType() == null || req.getType().trim().isEmpty()) {
            throw new IllegalArgumentException("Prescription type is required");
        }
        if (req.getRoute() == null || req.getRoute().trim().isEmpty()) {
            throw new IllegalArgumentException("Prescription route is required");
        }
        if (req.getStartDate() == null || req.getStartDate().isBefore(java.time.LocalDate.now(java.time.ZoneId.systemDefault()))) {
            throw new IllegalArgumentException("Prescription start date cannot be in the past");
        }
        if (req.getDurationDays() == null || req.getDurationDays() <= 0) {
            throw new IllegalArgumentException("Prescription duration must be at least 1 day");
        }

        // Resolve the medicine, and with it the optional link to stock. After validation, so a
        // badly-formed order is reported as badly formed rather than as a lookup failure.
        //
        // A medicineId means the prescriber picked a row out of this facility's inventory, and
        // keeping that link is what later lets the nurse's chart show real stock instead of
        // guessing. It is scoped to the caller's facility because the id arrives in the request
        // body: unscoped, it read another hospital's medicine and copied its name onto this order.
        //
        // No medicineId means the doctor typed a name. That is a valid order and is stored as
        // typed; nothing here goes looking for an inventory row that might match the text.
        com.hms.entity.Medicine linkedMedicine = null;
        if (req.getMedicineId() != null) {
            linkedMedicine = medicineRepository.findByIdAndHospitalId(req.getMedicineId(), hospitalId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Medicine not found in this facility's inventory: ID " + req.getMedicineId()));
        }
        if (medicineName == null) {
            medicineName = linkedMedicine.getName();
        }

        com.hms.entity.Prescription p = new com.hms.entity.Prescription();
        p.setHospitalId(hospitalId);
        p.setMedicalRecordId(latest.getId());
        p.setMedicineName(medicineName);
        p.setMedicineId(linkedMedicine == null ? null : linkedMedicine.getId());
        p.setType(req.getType() != null ? req.getType() : "TABLET");
        p.setRoute(req.getRoute() != null ? req.getRoute() : "ORAL");
        p.setFoodTiming(com.hms.entity.FoodTiming.normalize(req.getFoodTiming()));
        p.setDosage(req.getDose());
        p.setFrequency(req.getFrequency());
        p.setDurationDays(req.getDurationDays());
        p.setStartDate(req.getStartDate());
        p.setStatus("ACTIVE");

        com.hms.entity.Prescription saved = prescriptionRepository.save(p);

        // Trigger notification to the assigned nurse, if any
        try {
            patientNurseAssignmentRepository.findByIpdAdmissionIdAndIsActiveTrue(ipdId)
                .ifPresent(assignment -> {
                    notificationService.create(
                        assignment.getNurseUserId(),
                        hospitalId,
                        "PRESCRIPTION_ACTIVE",
                        "New Active Prescription",
                        "A new prescription for " + saved.getDosage() + " of " + saved.getMedicineName() + " has been added.",
                        "PRESCRIPTION",
                        saved.getId()
                    );
                });
        } catch (Exception e) {
            logger.error("Failed to trigger prescription add notification: {}", e.getMessage(), e);
        }

        // Standard prescriptions are now strictly informative (no auto-deduction/billing)
        return saved;
    }

    @Transactional
    public com.hms.entity.Prescription stopPrescription(Long prescriptionId) {
        String role = securityHelper.getCurrentUserRole();
        if (!"DOCTOR".equalsIgnoreCase(role) && !"HOSPITAL_ADMIN".equalsIgnoreCase(role)) {
            throw new org.springframework.security.access.AccessDeniedException("Only doctors can stop prescriptions");
        }

        Long hospitalId = securityHelper.getCurrentHospitalId();
        com.hms.entity.Prescription pres = prescriptionRepository.findByIdAndHospitalId(prescriptionId, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Prescription not found"));

        // Verify it belongs to an IPD by looking up medical record
        com.hms.entity.MedicalRecord mr = medicalRecordRepository.findByIdAndHospitalId(pres.getMedicalRecordId(), hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Related medical record not found"));
        if (mr.getIpdAdmissionId() == null) throw new IllegalArgumentException("Prescription is not linked to an IPD admission");

        pres.setStatus("STOPPED");
        com.hms.entity.Prescription saved = prescriptionRepository.save(pres);

        // Trigger notification to the assigned nurse, if any
        try {
            patientNurseAssignmentRepository.findByIpdAdmissionIdAndIsActiveTrue(mr.getIpdAdmissionId())
                .ifPresent(assignment -> {
                    notificationService.create(
                        assignment.getNurseUserId(),
                        saved.getHospitalId(),
                        "PRESCRIPTION_STOPPED",
                        "Prescription Stopped",
                        "The prescription for " + saved.getMedicineName() + " has been stopped.",
                        "PRESCRIPTION",
                        saved.getId()
                    );
                });
        } catch (Exception e) {
            logger.error("Failed to trigger stop prescription notification: {}", e.getMessage(), e);
        }

        return saved;
    }

    @Transactional
    public com.hms.entity.DischargeSummary planDischarge(Long ipdId, com.hms.dto.PlanDischargeRequest req) {
        String role = securityHelper.getCurrentUserRole();
        if (!"DOCTOR".equalsIgnoreCase(role) && !"HOSPITAL_ADMIN".equalsIgnoreCase(role)) {
            throw new org.springframework.security.access.AccessDeniedException("Only doctors can plan discharge");
        }

        IpdAdmission ipd = requireOwnedAdmissionForUpdate(ipdId);
        if (ipd.getStatus() == null || !ipd.getStatus().equalsIgnoreCase("ADMITTED")) {
            throw new IllegalArgumentException("Can only plan discharge for ADMITTED patients");
        }

        com.hms.entity.DischargeSummary ds = new com.hms.entity.DischargeSummary();
        ds.setIpdAdmissionId(ipdId);
        ds.setFinalDiagnosis(req.getFinalDiagnosis());
        ds.setTreatmentGiven(req.getTreatmentGiven());
        ds.setDischargeNotes(req.getDischargeNotes());
        ds.setFollowUpDate(req.getFollowUpDate());
        dischargeSummaryRepository.save(ds);

        ipd.setStatus("DISCHARGE_PLANNED");
        ipdAdmissionRepository.save(ipd);

        // Audit log
        try {
            auditLogService.logAction(
                    "IPD_DISCHARGE_PLANNED",
                    "Planned discharge for IPD Case: " + ipd.getIpdNumber() + ".",
                    securityHelper.getCurrentUserEmail(),
                    ipd.getHospitalId(),
                    "IPD",
                    ipd.getId().toString(),
                    null);
        } catch (Exception e) {
            logger.warn("Failed to create audit log for IPD planned discharge", e);
        }

        try {
            webSocketHandler.broadcast(ipd.getHospitalId(), "{\"type\":\"REFRESH_DATA\"}");
        } catch (Exception e) {
            logger.warn("Failed to broadcast WebSocket refresh data from planDischarge", e);
        }

        return ds;
    }

    @Transactional
    public IpdAdmission confirmDischarge(Long ipdId) {
        IpdAdmission ipd = requireOwnedAdmissionForUpdate(ipdId);

        String role = securityHelper.getCurrentUserRole();
        Long hospitalId = ipd.getHospitalId();
        com.hms.entity.HospitalSetting settings = hospitalSettingRepository.findByHospital_Id(hospitalId).orElse(null);
        boolean isSolo = settings != null && "SOLO".equalsIgnoreCase(settings.getReceptionMode());

        if (!"RECEPTIONIST".equalsIgnoreCase(role) && 
            !"HOSPITAL_ADMIN".equalsIgnoreCase(role) && 
            !("DOCTOR".equalsIgnoreCase(role) && isSolo)) {
            throw new org.springframework.security.access.AccessDeniedException("Only receptionists (or doctors under Solo Doctor mode) can confirm discharge");
        }
        if (ipd.getStatus() == null || !ipd.getStatus().equalsIgnoreCase("DISCHARGE_PLANNED")) {
            throw new IllegalArgumentException("Discharge is not planned for this IPD");
        }

        com.hms.entity.Hospital hospital = hospitalRepository.findById(ipd.getHospitalId()).orElse(null);
        boolean hasBillingModule = hospital != null && hospital.getModules() != null && hospital.getModules().contains("BILLING");

        java.util.List<Billing> bills = billingRepository.findByIpdAdmissionId(ipdId);

        if (hasBillingModule) {
            // Check billing balance across bills for this IPD
            java.math.BigDecimal total = java.math.BigDecimal.ZERO;
            java.math.BigDecimal paid = java.math.BigDecimal.ZERO;
            if (bills != null) {
                for (Billing b : bills) {
                    // include billing items and medicines
                    try {
                        java.util.List<com.hms.entity.BillingItem> items = billingItemRepository.findByBillingId(b.getId());
                        java.util.List<com.hms.entity.BillingMedicine> medicines = billingMedicineRepository.findByBillingId(b.getId());
                        if ((items != null && !items.isEmpty()) || (medicines != null && !medicines.isEmpty())) {
                            if (items != null) {
                                for (com.hms.entity.BillingItem it : items) {
                                    if (it.getAmount() != null) total = total.add(it.getAmount());
                                }
                            }
                            if (medicines != null) {
                                for (com.hms.entity.BillingMedicine med : medicines) {
                                    if (med.getAmount() != null) total = total.add(med.getAmount());
                                }
                            }
                        } else {
                            if (b.getAmount() != null) total = total.add(b.getAmount());
                        }
                    } catch (Exception e) {
                        logger.warn("Failed to aggregate billing items/medicines for IPD discharge check", e);
                        if (b.getAmount() != null) total = total.add(b.getAmount());
                    }

                    // payments
                    try {
                        java.util.List<com.hms.entity.BillingPayment> pays = billingPaymentRepository.findByBillingId(b.getId());
                        for (com.hms.entity.BillingPayment p : pays) {
                            if (p.getAmount() != null) paid = paid.add(p.getAmount());
                        }
                    } catch (Exception e) {
                        logger.warn("Failed to sum billing payments for IPD discharge check", e);
                    }
                }
            }

            java.math.BigDecimal balance = total.subtract(paid);
            if (balance.compareTo(java.math.BigDecimal.ZERO) > 0) {
                throw new IllegalArgumentException("Outstanding balance: ₹" + balance + ". Please collect payment before discharge.");
            }
        }

        // Stop all active prescriptions as part of the discharge transaction.
        java.util.List<com.hms.entity.Prescription> active = prescriptionRepository.findByIpdAdmissionIdAndStatus(ipdId, "ACTIVE");
        if (active != null) {
            for (com.hms.entity.Prescription pr : active) {
                pr.setStatus("COMPLETED");
                prescriptionRepository.save(pr);
            }
        }

        // Mark bed for cleaning (Nursing Mgmt Phase C2: vacated beds await cleaning
        // before they can be reused, rather than becoming immediately available).
        if (ipd.getBedId() == null) {
            throw new IllegalStateException("Active IPD admission has no bed to release");
        }
        Bed bed = bedRepository.findByBedIdAndHospitalIdForUpdate(ipd.getBedId(), hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Bed not found"));
        if (!com.hms.entity.BedStatus.OCCUPIED.equalsIgnoreCase(bed.getStatus())
                || !ipd.getId().equals(bed.getCurrentIpdAdmissionId())) {
            throw new ConflictException("IPD bed is not occupied by this admission");
        }
        bedStatusService.changeLocked(bed, com.hms.entity.BedStatus.CLEANING, "IPD discharge");

        // Finalize billing records for this IPD
        if (hasBillingModule && bills != null) {
            for (Billing b : bills) {
                b.setPaymentStatus("CLOSED");
                billingRepository.save(b);
            }
        }

        // ICU Phase 3: discharge ends any open ICU stay in the same transaction.
        icuStayService.onDischarged(ipd, com.hms.entity.IcuStay.DISP_HOME);

        // Update IPD status and discharge datetime
        ipd.setStatus("DISCHARGED");
        ipd.setDischargeDatetime(LocalDateTime.now());
        ipdAdmissionRepository.save(ipd);

        // Nurse module (Phase 1): auto-close any active nurse assignment for this
        // admission so the patient drops off the nurse's "My Patients" list.
        patientNurseAssignmentRepository.findByIpdAdmissionIdAndIsActiveTrue(ipd.getId())
                .ifPresent(assignment -> {
                    assignment.setIsActive(false);
                    assignment.setUnassignedAt(LocalDateTime.now());
                    patientNurseAssignmentRepository.save(assignment);
                });

        // Release the active bed history record
        java.util.Optional<com.hms.entity.IpdBedHistory> activeHistOpt = ipdBedHistoryRepository
                .findByIpdAdmissionIdAndReleasedAtIsNull(ipd.getId());
        if (activeHistOpt.isEmpty()) {
            throw new IllegalStateException("IPD admission has no active bed history to release");
        }
        com.hms.entity.IpdBedHistory activeHist = activeHistOpt.get();
        activeHist.setReleasedAt(LocalDateTime.now());
        ipdBedHistoryRepository.save(activeHist);

        // Audit log
        try {
            auditLogService.logAction(
                    "IPD_DISCHARGED",
                    "Confirmed discharge for IPD Case: " + ipd.getIpdNumber() + ".",
                    securityHelper.getCurrentUserEmail(),
                    ipd.getHospitalId(),
                    "IPD",
                    ipd.getId().toString(),
                    null);
        } catch (Exception e) {
            logger.warn("Failed to create audit log for IPD discharge confirmation", e);
        }

        try {
            webSocketHandler.broadcast(ipd.getHospitalId(), "{\"type\":\"REFRESH_DATA\"}");
        } catch (Exception e) {
            logger.warn("Failed to broadcast WebSocket refresh data from confirmDischarge", e);
        }

        return ipd;
    }

    @org.springframework.transaction.annotation.Transactional
    public IpdAdmission changeBed(Long ipdId, Long newBedId) {
        IpdAdmission ipd = requireOwnedAdmissionForUpdate(ipdId);

        String role = securityHelper.getCurrentUserRole();
        Long hospitalId = ipd.getHospitalId();
        com.hms.entity.HospitalSetting settings = hospitalSettingRepository.findByHospital_Id(hospitalId).orElse(null);
        boolean isSolo = settings != null && "SOLO".equalsIgnoreCase(settings.getReceptionMode());

        if (!"RECEPTIONIST".equalsIgnoreCase(role) && 
            !"HOSPITAL_ADMIN".equalsIgnoreCase(role) && 
            !("DOCTOR".equalsIgnoreCase(role) && isSolo)) {
            throw new org.springframework.security.access.AccessDeniedException("Only receptionists (or doctors under Solo Doctor mode) can change beds");
        }

        if (!"ADMITTED".equalsIgnoreCase(ipd.getStatus()) && !"DISCHARGE_PLANNED".equalsIgnoreCase(ipd.getStatus())) {
            throw new IllegalArgumentException("Bed change allowed only for active admissions");
        }

        Long oldBedId = ipd.getBedId();
        if (oldBedId == null) throw new IllegalStateException("Active IPD admission has no current bed");
        if (oldBedId.equals(newBedId)) throw new IllegalArgumentException("Patient is already assigned to the selected bed");

        // A stable order prevents two opposing transfers from deadlocking. The admission row
        // was locked first, followed by its bed resources.
        Long firstBedId = Math.min(oldBedId, newBedId);
        Long secondBedId = Math.max(oldBedId, newBedId);
        Bed first = bedRepository.findByBedIdAndHospitalIdForUpdate(firstBedId, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Bed not found"));
        Bed second = bedRepository.findByBedIdAndHospitalIdForUpdate(secondBedId, hospitalId)
                // Same wording as the first lookup on purpose. The two beds are locked in id
                // order, so which of them is "the new one" depends on the ids involved; a
                // distinct message here would let a caller tell a foreign bed from a missing one
                // by watching which sentence comes back (C4).
                .orElseThrow(() -> new ResourceNotFoundException("Bed not found"));
        Bed oldBed = oldBedId.equals(firstBedId) ? first : second;
        Bed newBed = newBedId.equals(firstBedId) ? first : second;
        if (!com.hms.entity.BedStatus.AVAILABLE.equalsIgnoreCase(newBed.getStatus())) {
            throw new ConflictException("Requested bed is no longer available");
        }
        if (!com.hms.entity.BedStatus.OCCUPIED.equalsIgnoreCase(oldBed.getStatus())
                || !ipd.getId().equals(oldBed.getCurrentIpdAdmissionId())) {
            throw new ConflictException("Current IPD bed is not occupied by this admission");
        }

        String oldBedCode = oldBed.getBedCode();
        com.hms.entity.Ward oldWard = wardRepository.findByWardIdAndHospitalId(oldBed.getWardId(), hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Ward not found"));
        String oldWardName = oldWard.getWardName();
        bedStatusService.changeLocked(oldBed, com.hms.entity.BedStatus.CLEANING, "Bed transfer (vacated)");

        Bed occupiedNewBed = bedStatusService.changeLocked(newBed, com.hms.entity.BedStatus.OCCUPIED, "Bed transfer");
        occupiedNewBed.setCurrentIpdAdmissionId(ipd.getId());
        bedRepository.save(occupiedNewBed);

        String newBedCode = newBed.getBedCode();
        com.hms.entity.Ward newWardEntity = wardRepository.findByWardIdAndHospitalId(newBed.getWardId(), hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Ward not found"));
        String newWardName = newWardEntity.getWardName();

        // A chargeable upgrade must post together with the transfer; silently losing it is a
        // financial inconsistency, not an optional notification.
        {
            java.math.BigDecimal oldRate = oldWard.getBedPrice() != null ? oldWard.getBedPrice() : java.math.BigDecimal.ZERO;
            java.math.BigDecimal newRate = newWardEntity.getBedPrice() != null ? newWardEntity.getBedPrice() : java.math.BigDecimal.ZERO;

            java.math.BigDecimal diff = newRate.subtract(oldRate);
            // If positive difference (upgrade), add to bill immediately
            if (diff.compareTo(java.math.BigDecimal.ZERO) > 0) {
                java.util.List<Billing> bills = billingRepository.findByIpdAdmissionId(ipd.getId());
                Billing bill = (bills != null && !bills.isEmpty()) ? bills.get(0) : null;
                if (bill != null) {
                    com.hms.entity.BillingItem item = new com.hms.entity.BillingItem();
                    item.setBillingId(bill.getId());
                    item.setHospitalId(bill.getHospitalId());
                    item.setDescription("Bed Upgrade Price Adjustment");
                    item.setAmount(diff);
                    billingItemRepository.save(item);

                    billingService.recalculateTotal(bill.getId());
                }
            }
        }

        ipd.setBedId(newBed.getBedId());
        ipd.setWardId(newBed.getWardId());
        
        IpdAdmission saved = ipdAdmissionRepository.save(ipd);

        // ICU Phase 3: one call covers every case, because they are one question — is the patient
        // in critical care now, and were they before? Ward -> ICU opens, ICU -> ward closes,
        // ICU -> a different ICU closes and reopens (readmission is real), and a bed change inside
        // the same unit does nothing, since the stay is bounded by the ward and ipd_bed_history
        // already records the bed. Same transaction as the transfer.
        icuStayService.onWardSettled(saved, oldWard.getWardId(), null, oldWard.getWardId(), null);

        // A cross-ward transfer ends primary ownership; destination visibility is derived
        // from the admission's new ward and therefore needs no manufactured row.
        if (!java.util.Objects.equals(oldWard.getWardId(), newWardEntity.getWardId())) {
            patientNurseAssignmentRepository.findByIpdAdmissionIdAndIsActiveTrue(ipd.getId()).ifPresent(assignment -> {
                assignment.setIsActive(false);
                assignment.setUnassignedAt(LocalDateTime.now());
                patientNurseAssignmentRepository.save(assignment);
            });
        }

        // Transfer history is required for a defensible inpatient bed record.
        java.util.Optional<com.hms.entity.IpdBedHistory> activeHistOpt = ipdBedHistoryRepository
                .findByIpdAdmissionIdAndReleasedAtIsNull(ipd.getId());
        if (activeHistOpt.isEmpty()) {
            throw new IllegalStateException("IPD admission has no active bed history to transfer");
        }
        com.hms.entity.IpdBedHistory activeHist = activeHistOpt.get();
        activeHist.setReleasedAt(LocalDateTime.now());
        ipdBedHistoryRepository.save(activeHist);

        com.hms.entity.IpdBedHistory newHist = new com.hms.entity.IpdBedHistory();
        newHist.setIpdAdmissionId(ipd.getId());
        newHist.setWardId(newBed.getWardId());
        newHist.setBedId(newBed.getBedId());
        newHist.setAssignedAt(LocalDateTime.now());
        ipdBedHistoryRepository.save(newHist);

        try {
            String details = "Transferred from Bed " + oldBedCode + " (" + oldWardName + ") to Bed " + newBedCode + " (" + newWardName + ").";
            auditLogService.logAction(
                    "BED_CHANGED",
                    details,
                    securityHelper.getCurrentUserEmail(),
                    hospitalId,
                    "IPD",
                    ipd.getId().toString(),
                    null);
        } catch (Exception e) {
            logger.warn("Failed to create audit log for bed change", e);
        }

        try {
            webSocketHandler.broadcast(ipd.getHospitalId(), "{\"type\":\"REFRESH_DATA\"}");
        } catch (Exception e) {
            logger.warn("Failed to broadcast WebSocket refresh data from changeBed", e);
        }
        return saved;
    }
}
