package com.hms.service.hospital;
import com.hms.util.LogSanitizer;

import com.hms.entity.Patient;
import com.hms.entity.Billing;
import com.hms.entity.BillingItem;
import com.hms.entity.BillingMedicine;
import com.hms.repository.PatientRepository;
import com.hms.security.SecurityContextHelper;
import com.hms.security.HospitalWebSocketHandler;
import com.hms.service.AuditLogService;

import com.hms.exception.ResourceNotFoundException;
import com.hms.exception.UnauthorizedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.List;

@Service
public class PatientService {
    private static final String HOSPITAL_NOT_FOUND = "Hospital not found";


    private static final Logger logger = LoggerFactory.getLogger(PatientService.class);

    @Autowired
    private BusinessClock businessClock;

    @Autowired
    private PatientRepository patientRepository;

    @Autowired
    private org.springframework.cache.CacheManager cacheManager;

    @Autowired
    private SecurityContextHelper securityHelper;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private HospitalWebSocketHandler webSocketHandler;

    @Autowired
    private com.hms.repository.BillingRepository billingRepository;

    @Autowired
    private com.hms.repository.PrescriptionRepository prescriptionRepository;

    @Autowired
    private com.hms.repository.DoctorRepository doctorRepository;

    @Autowired
    private com.hms.repository.MedicalRecordRepository medicalRecordRepository;

    @Autowired
    private com.hms.repository.IpdAdmissionRepository ipdAdmissionRepository;

    @Autowired
    private com.hms.repository.BillingItemRepository billingItemRepository;

    @Autowired
    private com.hms.repository.BillingMedicineRepository billingMedicineRepository;

    @Autowired
    private com.hms.repository.AuditLogRepository auditLogRepository;

    @Autowired
    private com.hms.repository.BedRepository bedRepository;

    @Autowired
    private com.hms.repository.WardRepository wardRepository;

    @Autowired
    private com.hms.repository.IpdBedHistoryRepository ipdBedHistoryRepository;

    @Autowired
    private com.hms.repository.OpdRepository opdRepository;

    @Autowired
    private com.hms.repository.HospitalRepository hospitalRepository;

    @Autowired
    private com.hms.repository.DischargeSummaryRepository dischargeSummaryRepository;

    @Autowired
    private com.hms.repository.InventoryItemRepository inventoryItemRepository;

    @Autowired
    private com.hms.service.PdfService pdfService;

    private void evictStatsCache(Long hospitalId) {
        if (hospitalId == null || cacheManager == null) {
            return;
        }
        try {
            org.springframework.cache.Cache cache = cacheManager.getCache("hospitalStats");
            if (cache != null) {
                cache.evict(hospitalId);
                logger.info("Evicted hospitalStats cache for hospitalId: {}", hospitalId);
            }
        } catch (Exception e) {
            // Cache eviction is a best-effort side effect (same tolerance as audit
            // logging/websocket broadcast below) — a Redis outage must not fail the
            // underlying patient operation.
            logger.warn("Failed to evict hospitalStats cache for hospitalId: {}", hospitalId, e);
        }
    }

    /**
     * "Always required" for dateOfBirth is enforced here rather than at the
     * DB/entity level — see the comment on Patient.dateOfBirth for why.
     */
    private void validateDateOfBirth(LocalDate dateOfBirth) {
        if (dateOfBirth == null) {
            throw new IllegalArgumentException("Date of birth is required");
        }
        if (dateOfBirth.isAfter(LocalDate.now(java.time.ZoneId.systemDefault()))) {
            throw new IllegalArgumentException("Date of birth cannot be in the future");
        }
        if (dateOfBirth.isBefore(LocalDate.now(java.time.ZoneId.systemDefault()).minusYears(120))) {
            throw new IllegalArgumentException("Date of birth cannot be more than 120 years ago");
        }
    }

    /**
     * Add a new patient
     * Automatically sets hospital_id from the authenticated user's context
     *
     * @param patient Patient entity to create
     * @return Created Patient entity
     */
    public Patient addPatient(Patient patient) {
        // Validate phone number
        if (patient.getPhone() == null || !patient.getPhone().matches("^[0-9]{10}$")) {
            throw new IllegalArgumentException("Phone number must be exactly 10 digits");
        }
        validateDateOfBirth(patient.getDateOfBirth());

        // Get hospital_id from security context (multi-tenant isolation)
        Long hospitalId = securityHelper.getCurrentHospitalId();

        if (hospitalId == null) {
            throw new UnauthorizedException("Hospital ID not found in context");
        }

        // Set hospital_id to ensure multi-tenant isolation
        patient.setHospitalId(hospitalId);

        logger.info("Hospital {} creating new patient: {}", hospitalId, LogSanitizer.clean(patient.getName()));
        Patient savedPatient = patientRepository.save(patient);

        // Set sequential customId using the auto-increment id: PAT1, PAT2, PAT3...
        savedPatient.setCustomId("PAT" + savedPatient.getId());
        savedPatient = patientRepository.save(savedPatient);

        evictStatsCache(hospitalId);

        // Create audit log
        try {
            String performedBy = securityHelper.getCurrentUserEmail();
            auditLogService.logAction(
                    "PATIENT_CREATED",
                    "Patient " + savedPatient.getName() + " was created.",
                    performedBy,
                    hospitalId,
                    "PATIENT",
                    savedPatient.getPublicId(),
                    null); // No reason for creation
        } catch (Exception e) {
            logger.warn("Failed to create audit log for patient creation", e);
        }

        // Broadcast real-time refresh
        try {
            webSocketHandler.broadcast(hospitalId, "{\"type\":\"REFRESH_DATA\"}");
        } catch (Exception e) {
            logger.warn("Failed to broadcast WebSocket refresh after patient creation", e);
        }

        return savedPatient;
    }

    /**
     * Update an active patient's details
     * Validates hospital ownership before updating
     * 
     * @param publicId    Patient Public ID
     * @param updatedData New patient data
     * @return Updated Patient entity
     */
    public Patient updatePatient(Long publicId, Patient updatedData) {
        // Validate phone number
        if (updatedData.getPhone() == null || !updatedData.getPhone().matches("^[0-9]{10}$")) {
            throw new IllegalArgumentException("Phone number must be exactly 10 digits");
        }

        // Ensure patient exists and belongs to this hospital
        Patient existingPatient = getPatientById(publicId);

        validateDateOfBirth(updatedData.getDateOfBirth());

        existingPatient.setName(updatedData.getName());
        existingPatient.setDateOfBirth(updatedData.getDateOfBirth());
        existingPatient.setGender(updatedData.getGender());
        existingPatient.setPhone(updatedData.getPhone());
        existingPatient.setAddress(updatedData.getAddress());
        existingPatient.setMedicalHistory(updatedData.getMedicalHistory());

        Patient saved = patientRepository.save(existingPatient);
        evictStatsCache(saved.getHospitalId());

        // Create audit log
        try {
            Long hid = securityHelper.getCurrentHospitalId();
            if (hid != null) {
                auditLogService.logAction(
                        "PATIENT_UPDATED",
                        "Patient " + saved.getName() + " details were updated.",
                        securityHelper.getCurrentUserEmail(),
                        hid,
                        "PATIENT",
                        saved.getPublicId(),
                        null);
            }
        } catch (Exception e) {
            logger.warn("Failed to create audit log for patient update", e);
        }

        // Broadcast real-time refresh
        try {
            Long hid = securityHelper.getCurrentHospitalId();
            if (hid != null) webSocketHandler.broadcast(hid, "{\"type\":\"REFRESH_DATA\"}");
        } catch (Exception e) {
            logger.warn("Failed to broadcast WebSocket refresh after patient update", e);
        }

        return saved;
    }

    /**
     * Get all active patients for the current hospital with optional filter
     * 
     * @param view Optional filter view ('today', 'history')
     * @return Page of active patients
     */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<Patient> getAllPatients(String search, String view, java.time.LocalDate date,
            org.springframework.data.domain.Pageable pageable) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            throw new UnauthorizedException("Hospital ID not found in context");
        }

        org.springframework.data.domain.Page<Patient> patients;

        if (date != null || "today".equalsIgnoreCase(view)) {
            LocalDate businessDate = date != null ? date : businessClock.today();
            // Existing DATETIME values are IST wall time. Keep bounds in that representation.
            patients = patientRepository.findActiveInDateRange(
                    hospitalId, businessDate.atStartOfDay(),
                    businessDate.plusDays(1).atStartOfDay(), pageable);
        } else {
            // Default / History
            patients = patientRepository.findByHospitalIdAndIsActiveTrueOrderByCreatedAtDesc(hospitalId, pageable);
        }

        // Populate latest bill for each patient
        populateLatestBills(patients.getContent());

        return patients;
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<Patient> getAllPatients(String search, String view,
            org.springframework.data.domain.Pageable pageable) {
        return getAllPatients(search, view, null, pageable);
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<Patient> getAllPatients(
            org.springframework.data.domain.Pageable pageable) {
        return getAllPatients(null, null, null, pageable);
    }

    @Transactional(readOnly = true)
    public long getPatientCount() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            throw new UnauthorizedException("Hospital ID not found in context");
        }
        return patientRepository.countByHospitalIdAndIsActiveTrue(hospitalId);
    }

    private void populateLatestBills(List<Patient> patients) {
        if (patients == null || patients.isEmpty()) return;
        List<Long> patientIds = patients.stream()
                .map(Patient::getId)
                .collect(java.util.stream.Collectors.toList());
        List<com.hms.entity.Billing> latestBills = billingRepository.findLatestBillForPatients(patientIds);
        java.util.Map<Long, com.hms.entity.Billing> billMap = latestBills.stream()
                .collect(java.util.stream.Collectors.toMap(com.hms.entity.Billing::getPatientId, b -> b, (b1, b2) -> b1));
        patients.forEach(patient -> {
            com.hms.entity.Billing bill = billMap.get(patient.getId());
            if (bill != null) {
                patient.setLatestBill(bill);
            }
        });
    }

    // Kept for backward compatibility/internal use (e.g. stats)
    @Transactional(readOnly = true)
    public List<Patient> getAllPatients() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null)
            throw new UnauthorizedException("Hospital ID not found in context");
        List<Patient> patients = patientRepository.findByHospitalIdAndIsActiveTrueOrderByCreatedAtDesc(hospitalId);

        // Populate latest bill
        populateLatestBills(patients);

        return patients;
    }

    /**
     * Search active patients by name or phone
     * 
     * @param query Search term
     * @return List of matching active patients
     */
    @Transactional(readOnly = true)
    public List<Patient> searchPatients(String query) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            throw new UnauthorizedException("Hospital ID not found in context");
        }

        if (query == null || query.trim().isEmpty()) {
            return getAllPatients();
        }

        List<Patient> patients = patientRepository.searchActiveByNameOrPhone(hospitalId, query.trim());

        // Populate latest bill
        populateLatestBills(patients);

        return patients;
    }

    @Transactional(readOnly = true)
    public Patient getPatientByPublicId(String publicId) {
        // Get hospital_id from security context (multi-tenant isolation)
        Long hospitalId = securityHelper.getCurrentHospitalId();

        if (hospitalId == null) {
            throw new UnauthorizedException("Hospital ID not found in context");
        }

        // Find patient only if it belongs to this hospital and is active
        return patientRepository.findByPublicIdAndHospitalIdAndIsActiveTrue(publicId, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found"));
    }

    @Transactional(readOnly = true)
    public Patient getPatientById(Long id) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            throw new UnauthorizedException("Hospital ID not found in context");
        }
        return patientRepository.findById(id)
                .filter(p -> p.getHospitalId().equals(hospitalId))
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found"));
    }

    /**
     * Soft delete a patient
     * 
     * @param id Patient ID
     */
    public void deletePatient(String publicId, String reason) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null)
            throw new UnauthorizedException("Hospital ID not found in context");

        Patient patient = patientRepository.findByPublicIdAndHospitalIdAndIsActiveTrue(publicId, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found"));

        logger.info("Hospital {} soft deleting patient ID: {}. Reason: {}", hospitalId, LogSanitizer.clean(publicId), LogSanitizer.clean(reason));
        patient.setIsActive(false);
        patientRepository.save(patient);
        evictStatsCache(hospitalId);

        try {
            auditLogService.logAction(
                    "PATIENT_DELETED",
                    "Patient " + patient.getName() + " was deleted. Reason: "
                            + (reason != null ? reason : "No reason provided"),
                    securityHelper.getCurrentUserEmail(),
                    hospitalId,
                    "PATIENT",
                    publicId,
                    reason);
        } catch (Exception e) {
            logger.warn("Failed to create audit log for patient deletion", e);
        }

        // Broadcast real-time refresh
        try {
            webSocketHandler.broadcast(hospitalId, "{\"type\":\"REFRESH_DATA\"}");
        } catch (Exception e) {
            logger.warn("Failed to broadcast WebSocket refresh after patient deletion", e);
        }
    }

    /**
     * Update patient status
     * 
     * @param publicId Patient public ID
     * @param status   New status
     * @return Updated patient
     */
    public Patient updatePatientStatus(String publicId, com.hms.entity.PatientStatus status) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            throw new UnauthorizedException("Hospital ID not found in context");
        }

        Patient patient = patientRepository.findByPublicIdAndHospitalIdAndIsActiveTrue(publicId, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found"));

        logger.info("Hospital {} updating patient {} status from {} to {}",
                hospitalId, LogSanitizer.clean(publicId), LogSanitizer.clean(patient.getStatus()), LogSanitizer.clean(status));

        patient.setStatus(status);
        Patient saved = patientRepository.save(patient);

        try {
            webSocketHandler.broadcast(hospitalId, "{\"type\":\"REFRESH_DATA\"}");
        } catch (Exception e) {
            logger.warn("Failed to broadcast WebSocket refresh after patient status update", e);
        }

        return saved;
    }

    /**
     * Start consultation for a patient
     * Changes status from REGISTERED to CONSULTING
     * 
     * @param publicId Patient public ID
     * @return Updated patient
     */
    public Patient startConsultation(String publicId) {
        return updatePatientStatus(publicId, com.hms.entity.PatientStatus.CONSULTING);
    }

    /**
     * Mark consultation as completed
     * Changes status to COMPLETED
     * 
     * @param publicId Patient public ID
     * @return Updated patient
     */
    public Patient completeConsultation(String publicId) {
        return updatePatientStatus(publicId, com.hms.entity.PatientStatus.COMPLETED);
    }

    /**
     * Get complete patient consultation details
     * Includes demographics, medical history, and current visit info
     * 
     * @param publicId Patient public ID
     * @return Map containing patient details and medical history
     */
    @Transactional(readOnly = true)
    public java.util.Map<String, Object> getPatientConsultationDetails(String patientIdentifier) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            throw new UnauthorizedException("Hospital ID not found in context");
        }

        // Resolve patient by numeric id or publicId
        Patient patient;
        if (patientIdentifier != null && patientIdentifier.matches("^\\d+$")) {
            patient = getPatientById(Long.parseLong(patientIdentifier));
        } else {
            patient = getPatientByPublicId(patientIdentifier);
        }

        // Get medical history (all consultations sorted newest to oldest)
        List<com.hms.entity.MedicalRecord> medicalHistory = medicalRecordRepository
                .findByPatientIdOrderByCreatedAtDesc(patient.getId());

        // Get IPD admissions
        List<com.hms.entity.IpdAdmission> ipdAdmissions = ipdAdmissionRepository
                .findByPatientIdOrderByAdmissionDatetimeDesc(patient.getId());

        // Group IPD Admissions by ID
        java.util.List<Long> ipdAdmissionIds = new java.util.ArrayList<>();
        java.util.List<String> ipdAdmissionIdStrings = new java.util.ArrayList<>();
        for (com.hms.entity.IpdAdmission ipd : ipdAdmissions) {
            ipdAdmissionIds.add(ipd.getId());
            ipdAdmissionIdStrings.add(ipd.getId().toString());
        }

        // Batch-fetch medical records for IPD admissions
        java.util.Map<Long, List<com.hms.entity.MedicalRecord>> ipdRecordMap = new java.util.HashMap<>();
        java.util.List<com.hms.entity.MedicalRecord> ipdRecords = java.util.Collections.emptyList();
        if (!ipdAdmissionIds.isEmpty()) {
            ipdRecords = medicalRecordRepository.findByIpdAdmissionIdIn(ipdAdmissionIds);
            for (com.hms.entity.MedicalRecord mr : ipdRecords) {
                ipdRecordMap.computeIfAbsent(mr.getIpdAdmissionId(), k -> new java.util.ArrayList<>()).add(mr);
            }
        }

        // Build response
        java.util.Map<String, Object> response = new java.util.HashMap<>();

        // Patient demographics
        java.util.Map<String, Object> patientData = new java.util.HashMap<>();
        patientData.put("id", patient.getId());
        patientData.put("customId", patient.getCustomId());
        patientData.put("publicId", patient.getPublicId());
        patientData.put("name", patient.getName());
        patientData.put("age", patient.getAge());
        patientData.put("dateOfBirth", patient.getDateOfBirth());
        patientData.put("gender", patient.getGender());
        patientData.put("phone", patient.getPhone());
        patientData.put("address", patient.getAddress());
        patientData.put("status", patient.getStatus() != null ? patient.getStatus().toString() : "REGISTERED");
        response.put("patient", patientData);

        // --- Batch-fetch doctors and prescriptions to avoid N+1 queries ---

        // Collect all unique doctor IDs and medical record IDs
        java.util.Set<Long> doctorIds = new java.util.HashSet<>();
        java.util.List<Long> medicalRecordIds = new java.util.ArrayList<>();
        for (com.hms.entity.MedicalRecord record : medicalHistory) {
            if (record.getDoctorId() != null) {
                doctorIds.add(record.getDoctorId());
            }
            medicalRecordIds.add(record.getId());
        }
        for (com.hms.entity.IpdAdmission ipd : ipdAdmissions) {
            if (ipd.getDoctorId() != null) {
                doctorIds.add(ipd.getDoctorId());
            }
        }
        for (com.hms.entity.MedicalRecord mr : ipdRecords) {
            if (mr.getDoctorId() != null) {
                doctorIds.add(mr.getDoctorId());
            }
            if (!medicalRecordIds.contains(mr.getId())) {
                medicalRecordIds.add(mr.getId());
            }
        }

        // Batch fetch all doctors in one query
        java.util.Map<Long, String> doctorNameMap = new java.util.HashMap<>();
        if (!doctorIds.isEmpty()) {
            List<com.hms.entity.Doctor> doctors = doctorRepository.findAllById(doctorIds);
            for (com.hms.entity.Doctor doc : doctors) {
                doctorNameMap.put(doc.getId(), doc.getName());
            }
        }

        // Batch fetch all prescriptions in one query
        java.util.Map<Long, List<com.hms.entity.Prescription>> prescriptionMap = new java.util.HashMap<>();
        if (!medicalRecordIds.isEmpty()) {
            List<com.hms.entity.Prescription> allPrescriptions = prescriptionRepository
                    .findByMedicalRecordIdIn(medicalRecordIds);
            for (com.hms.entity.Prescription p : allPrescriptions) {
                prescriptionMap.computeIfAbsent(p.getMedicalRecordId(), k -> new java.util.ArrayList<>()).add(p);
            }
        }

        // 1. Flat medical history - map from pre-fetched data (for backward compatibility)
        List<java.util.Map<String, Object>> historyList = new java.util.ArrayList<>();
        for (com.hms.entity.MedicalRecord mr : medicalHistory) {
            java.util.Map<String, Object> historyItem = new java.util.HashMap<>();
            historyItem.put("id", mr.getId());
            historyItem.put("date", mr.getCreatedAt());
            historyItem.put("symptoms", mr.getSymptoms());
            historyItem.put("diagnosis", mr.getDiagnosis());
            historyItem.put("treatment", mr.getTreatmentNotes());
            historyItem.put("followUpDate", mr.getFollowUpDate());

            String doctorName = "Unknown Doctor";
            if (mr.getDoctorId() != null) {
                doctorName = doctorNameMap.getOrDefault(mr.getDoctorId(), "Unknown Doctor");
            }
            historyItem.put("doctorName", doctorName);

            historyItem.put("prescriptions", prescriptionMap.getOrDefault(mr.getId(), java.util.Collections.emptyList()));

            historyList.add(historyItem);
        }
        response.put("medicalHistory", historyList);

        // 2. Populate OPD History (detailed)
        List<Billing> patientBills = billingRepository.findByPatientIdOrderByCreatedAtDesc(patient.getId());
        java.util.Map<Long, Billing> opdToBillMap = new java.util.HashMap<>();
        for (Billing b : patientBills) {
            if (b.getOpdId() != null) {
                opdToBillMap.put(b.getOpdId(), b);
            }
        }

        java.util.List<Long> opdBillIds = new java.util.ArrayList<>();
        for (Billing b : opdToBillMap.values()) {
            opdBillIds.add(b.getId());
        }

        java.util.Map<Long, java.util.List<com.hms.entity.BillingItem>> opdBillItemsMap = new java.util.HashMap<>();
        java.util.Map<Long, java.util.List<com.hms.entity.BillingMedicine>> opdBillMedsMap = new java.util.HashMap<>();
        if (!opdBillIds.isEmpty()) {
            java.util.List<com.hms.entity.BillingItem> items = billingItemRepository.findByBillingIdIn(opdBillIds);
            for (com.hms.entity.BillingItem item : items) {
                opdBillItemsMap.computeIfAbsent(item.getBillingId(), k -> new java.util.ArrayList<>()).add(item);
            }
            java.util.List<com.hms.entity.BillingMedicine> meds = billingMedicineRepository.findByBillingIdIn(opdBillIds);
            for (com.hms.entity.BillingMedicine med : meds) {
                opdBillMedsMap.computeIfAbsent(med.getBillingId(), k -> new java.util.ArrayList<>()).add(med);
            }
        }

        List<java.util.Map<String, Object>> opdHistoryList = new java.util.ArrayList<>();
        for (com.hms.entity.MedicalRecord record : medicalHistory) {
            String vt = record.getVisitType();
            if (vt == null || vt.equalsIgnoreCase("OPD")) {
                java.util.Map<String, Object> opdItem = new java.util.HashMap<>();
                opdItem.put("id", record.getId());
                opdItem.put("date", record.getCreatedAt());
                opdItem.put("symptoms", record.getSymptoms());
                opdItem.put("diagnosis", record.getDiagnosis());
                opdItem.put("treatment", record.getTreatmentNotes());
                opdItem.put("followUpDate", record.getFollowUpDate());
                opdItem.put("opdId", record.getOpdId());

                String doctorName = "Unknown Doctor";
                if (record.getDoctorId() != null) {
                    doctorName = doctorNameMap.getOrDefault(record.getDoctorId(), "Unknown Doctor");
                }
                opdItem.put("doctorName", doctorName);
                opdItem.put("prescriptions", prescriptionMap.getOrDefault(record.getId(), java.util.Collections.emptyList()));

                // Billed Items & In-Clinic Medicines
                java.util.List<java.util.Map<String, Object>> hospitalItems = new java.util.ArrayList<>();
                java.util.List<java.util.Map<String, Object>> inClinicMedicines = new java.util.ArrayList<>();
                if (record.getOpdId() != null) {
                    Billing b = opdToBillMap.get(record.getOpdId());
                    if (b != null) {
                        java.util.List<com.hms.entity.BillingItem> items = opdBillItemsMap.getOrDefault(b.getId(), java.util.Collections.emptyList());
                        for (com.hms.entity.BillingItem it : items) {
                            java.util.Map<String, Object> itemMap = new java.util.HashMap<>();
                            itemMap.put("description", it.getDescription());
                            itemMap.put("amount", it.getAmount());
                            hospitalItems.add(itemMap);
                        }
                        java.util.List<com.hms.entity.BillingMedicine> meds = opdBillMedsMap.getOrDefault(b.getId(), java.util.Collections.emptyList());
                        for (com.hms.entity.BillingMedicine me : meds) {
                            java.util.Map<String, Object> medMap = new java.util.HashMap<>();
                            medMap.put("medicineName", me.getMedicineName());
                            medMap.put("quantity", me.getQuantity());
                            medMap.put("unitPrice", me.getUnitPrice());
                            medMap.put("amount", me.getAmount());
                            inClinicMedicines.add(medMap);
                        }
                    }
                }
                opdItem.put("hospitalItems", hospitalItems);
                opdItem.put("inClinicMedicines", inClinicMedicines);

                opdHistoryList.add(opdItem);
            }
        }
        response.put("opdHistory", opdHistoryList);

        // Fetch beds & wards to construct O(1) maps for resolving names
        java.util.List<com.hms.entity.Bed> allBeds = bedRepository.findAll();
        java.util.List<com.hms.entity.Ward> allWards = wardRepository.findAll();
        java.util.Map<Long, String> bedCodeMap = new java.util.HashMap<>();
        for (com.hms.entity.Bed b : allBeds) {
            bedCodeMap.put(b.getBedId(), b.getBedCode());
        }
        java.util.Map<Long, String> wardNameMap = new java.util.HashMap<>();
        for (com.hms.entity.Ward w : allWards) {
            wardNameMap.put(w.getWardId(), w.getWardName());
        }

        // 3. Populate IPD History (consolidated) from IpdBedHistory table
        java.util.Map<Long, java.util.List<java.util.Map<String, Object>>> ipdBedLogsMap = new java.util.HashMap<>();
        if (!ipdAdmissionIds.isEmpty()) {
            java.util.List<com.hms.entity.IpdBedHistory> bedHistories = ipdBedHistoryRepository.findByIpdAdmissionIdInOrderByAssignedAtAsc(ipdAdmissionIds);
            for (com.hms.entity.IpdBedHistory hist : bedHistories) {
                try {
                    String bedCode = bedCodeMap.getOrDefault(hist.getBedId(), "Unknown");
                    String wardName = wardNameMap.getOrDefault(hist.getWardId(), "Unknown");
                    String details;
                    if (hist.getReleasedAt() == null) {
                        details = "Current Assignment: Bed " + bedCode + " in " + wardName;
                    } else {
                        details = "Transferred from Bed " + bedCode + " in " + wardName + " (Released: " + hist.getReleasedAt().format(java.time.format.DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm")) + ")";
                    }

                    java.util.Map<String, Object> logMap = new java.util.HashMap<>();
                    logMap.put("timestamp", hist.getAssignedAt());
                    logMap.put("details", details);
                    logMap.put("id", hist.getId());
                    logMap.put("wardId", hist.getWardId());
                    logMap.put("bedId", hist.getBedId());
                    logMap.put("wardName", wardName);
                    logMap.put("bedCode", bedCode);
                    logMap.put("assignedAt", hist.getAssignedAt());
                    logMap.put("releasedAt", hist.getReleasedAt());
                    ipdBedLogsMap.computeIfAbsent(hist.getIpdAdmissionId(), k -> new java.util.ArrayList<>()).add(logMap);
                } catch (Exception e) {
                    logger.warn("Failed to log bed assignment history: {}", e.getMessage());
                }
            }
        }

        java.util.List<java.util.Map<String, Object>> ipdHistoryList = new java.util.ArrayList<>();
        for (com.hms.entity.IpdAdmission ipd : ipdAdmissions) {
            java.util.Map<String, Object> ipdItem = new java.util.HashMap<>();
            ipdItem.put("id", ipd.getId());
            ipdItem.put("ipdNumber", ipd.getIpdNumber());
            ipdItem.put("admissionDatetime", ipd.getAdmissionDatetime());
            ipdItem.put("dischargeDatetime", ipd.getDischargeDatetime());
            ipdItem.put("status", ipd.getStatus());
            ipdItem.put("primaryDiagnosis", ipd.getPrimaryDiagnosis());
            ipdItem.put("notes", ipd.getNotes());

            String primaryDoc = "Unknown Doctor";
            if (ipd.getDoctorId() != null) {
                primaryDoc = doctorNameMap.getOrDefault(ipd.getDoctorId(), "Unknown Doctor");
            }
            ipdItem.put("doctorName", primaryDoc);

            String currentBedCode = ipd.getBedId() != null ? bedCodeMap.getOrDefault(ipd.getBedId(), "N/A") : "N/A";
            String currentWardName = ipd.getWardId() != null ? wardNameMap.getOrDefault(ipd.getWardId(), "N/A") : "N/A";
            ipdItem.put("currentBed", currentBedCode);
            ipdItem.put("currentWard", currentWardName);

            // Fetch doctor entries for this IPD admission
            java.util.List<com.hms.entity.MedicalRecord> records = ipdRecordMap.getOrDefault(ipd.getId(), java.util.Collections.emptyList());
            java.util.List<com.hms.entity.MedicalRecord> sortedRecords = new java.util.ArrayList<>(records);
            sortedRecords.sort(java.util.Comparator.comparing(com.hms.entity.MedicalRecord::getCreatedAt));

            java.util.List<java.util.Map<String, Object>> doctorEntries = new java.util.ArrayList<>();
            for (com.hms.entity.MedicalRecord record : sortedRecords) {
                java.util.Map<String, Object> recordMap = new java.util.HashMap<>();
                recordMap.put("id", record.getId());
                recordMap.put("date", record.getCreatedAt());
                recordMap.put("symptoms", record.getSymptoms());
                recordMap.put("diagnosis", record.getDiagnosis());
                recordMap.put("treatmentNotes", record.getTreatmentNotes());

                String recordDocName = "Unknown Doctor";
                if (record.getDoctorId() != null) {
                    recordDocName = doctorNameMap.getOrDefault(record.getDoctorId(), "Unknown Doctor");
                }
                recordMap.put("doctorName", recordDocName);

                // Parse administered items JSON
                java.util.List<java.util.Map<String, Object>> administeredItems = new java.util.ArrayList<>();
                if (record.getAdministeredItemsJson() != null && !record.getAdministeredItemsJson().isEmpty()) {
                    try {
                        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                        java.util.List<?> items = mapper.readValue(record.getAdministeredItemsJson(), java.util.List.class);
                        for (Object item : items) {
                            if (item instanceof java.util.Map) {
                                java.util.Map<?, ?> im = (java.util.Map<?, ?>) item;
                                java.util.Map<String, Object> amMap = new java.util.HashMap<>();
                                amMap.put("medicineName", im.get("medicineName"));
                                amMap.put("quantity", im.get("quantity"));
                                amMap.put("dosage", im.get("dosage"));
                                amMap.put("frequency", im.get("frequency"));
                                amMap.put("duration", im.get("duration"));
                                amMap.put("instructions", im.get("instructions"));
                                administeredItems.add(amMap);
                            }
                        }
                    } catch (Exception e) {
                        logger.warn("Failed to parse administered items JSON: {}", e.getMessage());
                    }
                }
                recordMap.put("administeredItems", administeredItems);
                doctorEntries.add(recordMap);
            }
            ipdItem.put("doctorEntries", doctorEntries);

            // Bed occupancy changes list
            java.util.List<java.util.Map<String, Object>> bedHistory = ipdBedLogsMap.getOrDefault(ipd.getId(), java.util.Collections.emptyList());
            ipdItem.put("bedHistory", bedHistory);

            // Discharge summary (final diagnosis, treatment, notes, follow-up date)
            try {
                dischargeSummaryRepository.findByIpdAdmissionId(ipd.getId()).ifPresent(ds -> {
                    java.util.Map<String, Object> dsMap = new java.util.HashMap<>();
                    dsMap.put("finalDiagnosis", ds.getFinalDiagnosis());
                    dsMap.put("treatmentGiven", ds.getTreatmentGiven());
                    dsMap.put("dischargeNotes", ds.getDischargeNotes());
                    dsMap.put("followUpDate", ds.getFollowUpDate());
                    dsMap.put("createdAt", ds.getCreatedAt());
                    ipdItem.put("dischargeSummary", dsMap);
                });
            } catch (Exception e) {
                logger.warn("Failed to retrieve or map discharge summary: {}", e.getMessage());
            }

            ipdHistoryList.add(ipdItem);
        }
        response.put("ipdHistory", ipdHistoryList);

        return response;
    }

    /**
     * Get latest consultation details including prescription
     */
    @Transactional(readOnly = true)
    public java.util.Map<String, Object> getLatestPrescription(String publicId) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        com.hms.entity.Patient patient = patientRepository.findByPublicIdAndHospitalIdAndIsActiveTrue(publicId, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found"));

        com.hms.entity.MedicalRecord record = medicalRecordRepository
                .findTopByPatientIdOrderByCreatedAtDesc(patient.getId())
                .orElseThrow(() -> new RuntimeException("No consultation records found"));

        List<com.hms.entity.Prescription> prescriptions = prescriptionRepository.findByMedicalRecordId(record.getId());

        java.util.Map<String, Object> result = new java.util.HashMap<>();
        result.put("medicalRecord", record);
        result.put("prescriptions", prescriptions);
        return result;
    }

    @Transactional(readOnly = true)
    public List<Patient> getPatientsByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return java.util.Collections.emptyList();
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            throw new UnauthorizedException("Hospital ID not found in context");
        }
        return patientRepository.findAllById(ids).stream()
                .filter(p -> p.getHospitalId().equals(hospitalId))
                .collect(java.util.stream.Collectors.toList());
    }

    @Transactional(readOnly = true)
    public java.io.ByteArrayInputStream getOpdMedicinesPdf(Long opdId) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        com.hms.entity.Hospital hospital = hospitalRepository.findById(hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException(HOSPITAL_NOT_FOUND));
        
        com.hms.entity.MedicalRecord record = medicalRecordRepository.findByOpdId(opdId)
                .orElseThrow(() -> new ResourceNotFoundException("Medical record not found for OPD"));

        // hospitalId above was only used to brand the PDF header. Enforce that the record
        // actually belongs to the caller's hospital, or another tenant's prescription is
        // rendered onto this hospital's letterhead.
        if (record.getHospitalId() == null || !record.getHospitalId().equals(hospitalId)) {
            throw new ResourceNotFoundException("Medical record not found for OPD");
        }

        com.hms.entity.Patient patient = patientRepository.findById(record.getPatientId())
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found"));

        com.hms.entity.Doctor doctor = (record.getDoctorId() != null)
                ? doctorRepository.findById(record.getDoctorId()).orElse(null)
                : null;
        // Scoped: the record's tenant was verified above, and the OPD is now fetched under the
        // same tenant rather than by raw id. A foreign OPD yields null and the case number falls
        // back exactly as it does when the OPD is absent -- no behaviour change for real data.
        com.hms.entity.Opd opd = opdRepository
                .findByIdAndHospitalIdWithPatientAndDoctor(opdId, hospitalId).orElse(null);
        String customNo = (opd != null) ? opd.getCaseId() : "-";
        java.time.LocalDateTime createdAt = (opd != null) ? opd.getCreatedAt() : record.getCreatedAt();

        java.util.List<String[]> itemsList = new java.util.ArrayList<>();
        if (record.getAdministeredItemsJson() != null && !record.getAdministeredItemsJson().isEmpty()) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                java.util.List<?> items = mapper.readValue(record.getAdministeredItemsJson(), java.util.List.class);
                for (Object item : items) {
                    if (item instanceof java.util.Map) {
                        java.util.Map<?, ?> im = (java.util.Map<?, ?>) item;
                        String name = im.get("medicineName") != null ? String.valueOf(im.get("medicineName")) : "";
                        String dosage = im.get("dosage") != null ? String.valueOf(im.get("dosage")) : "";
                        String frequency = im.get("frequency") != null ? String.valueOf(im.get("frequency")) : "";
                        String duration = im.get("duration") != null ? String.valueOf(im.get("duration")) : "";
                        String instructions = im.get("instructions") != null ? String.valueOf(im.get("instructions")) : "";
                        String qty = im.get("quantity") != null ? String.valueOf(im.get("quantity")) : "";
                        itemsList.add(new String[]{name, dosage, frequency, duration, instructions, qty});
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to parse medicines list JSON: {}", e.getMessage());
            }
        }

        if (itemsList.isEmpty()) {
            com.hms.entity.Billing bill = billingRepository.findByOpdId(opdId).orElse(null);
            if (bill != null) {
                java.util.List<com.hms.entity.BillingItem> items = billingItemRepository.findByBillingId(bill.getId());
                for (com.hms.entity.BillingItem it : items) {
                    String desc = it.getDescription();
                    if (desc != null) {
                        String[] parsed = parseBillingItemDescription(desc);
                        if (parsed != null) {
                            String baseName = parsed[0];
                            String qty = parsed[1];
                            if (inventoryItemRepository.existsByNameAndHospitalId(baseName, hospitalId)) {
                                itemsList.add(new String[]{baseName, "", "", "", "", qty});
                            }
                        }
                    }
                }
                java.util.List<com.hms.entity.BillingMedicine> meds = billingMedicineRepository.findByBillingId(bill.getId());
                for (com.hms.entity.BillingMedicine me : meds) {
                    itemsList.add(new String[]{me.getMedicineName() != null ? me.getMedicineName() : "Medicine", "", "", "", "", String.valueOf(me.getQuantity())});
                }
            }
        }
        return pdfService.generateMedicinesListPdf(hospital, doctor, patient, "OPD MEDICINES & ITEMS LIST", customNo, createdAt, itemsList);
    }

    @Transactional(readOnly = true)
    public java.io.ByteArrayInputStream getIpdMedicinesPdf(Long ipdId) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        com.hms.entity.Hospital hospital = hospitalRepository.findById(hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException(HOSPITAL_NOT_FOUND));
        
        com.hms.entity.IpdAdmission ipd = ipdAdmissionRepository.findById(ipdId)
                .orElseThrow(() -> new ResourceNotFoundException("IPD Admission not found"));

        if (ipd.getHospitalId() == null || !ipd.getHospitalId().equals(hospitalId)) {
            throw new ResourceNotFoundException("IPD Admission not found");
        }

        com.hms.entity.Patient patient = patientRepository.findById(ipd.getPatientId())
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found"));

        com.hms.entity.Doctor doctor = (ipd.getDoctorId() != null)
                ? doctorRepository.findById(ipd.getDoctorId()).orElse(null)
                : null;
        String customNo = ipd.getIpdNumber();
        java.time.LocalDateTime createdAt = ipd.getAdmissionDatetime();

        java.util.List<Billing> bills = billingRepository.findByIpdAdmissionId(ipdId);
        java.util.List<String[]> itemsList = new java.util.ArrayList<>();
        
        java.util.List<com.hms.entity.MedicalRecord> records = medicalRecordRepository.findByIpdAdmissionIdOrderByCreatedAtDesc(ipdId);
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (com.hms.entity.MedicalRecord record : records) {
            if (record.getAdministeredItemsJson() != null && !record.getAdministeredItemsJson().isEmpty()) {
                try {
                    java.util.List<?> items = mapper.readValue(record.getAdministeredItemsJson(), java.util.List.class);
                    for (Object item : items) {
                        if (item instanceof java.util.Map) {
                            java.util.Map<?, ?> im = (java.util.Map<?, ?>) item;
                            String name = im.get("medicineName") != null ? String.valueOf(im.get("medicineName")) : "";
                            String dosage = im.get("dosage") != null ? String.valueOf(im.get("dosage")) : "";
                            String frequency = im.get("frequency") != null ? String.valueOf(im.get("frequency")) : "";
                            String duration = im.get("duration") != null ? String.valueOf(im.get("duration")) : "";
                            String instructions = im.get("instructions") != null ? String.valueOf(im.get("instructions")) : "";
                            String qty = im.get("quantity") != null ? String.valueOf(im.get("quantity")) : "";
                            itemsList.add(new String[]{name, dosage, frequency, duration, instructions, qty});
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Failed to parse administered items JSON: {}", e.getMessage());
                }
            }
        }

        if (itemsList.isEmpty() && bills != null && !bills.isEmpty()) {
            Billing bill = bills.get(0);
            java.util.List<com.hms.entity.BillingItem> items = billingItemRepository.findByBillingId(bill.getId());
            for (com.hms.entity.BillingItem it : items) {
                String desc = it.getDescription();
                if (desc != null) {
                    String[] parsed = parseBillingItemDescription(desc);
                    if (parsed != null) {
                        String baseName = parsed[0];
                        String qty = parsed[1];
                        if (inventoryItemRepository.existsByNameAndHospitalId(baseName, hospitalId)) {
                            itemsList.add(new String[]{baseName, "", "", "", "", qty});
                        }
                    }
                }
            }
            java.util.List<com.hms.entity.BillingMedicine> meds = billingMedicineRepository.findByBillingId(bill.getId());
            for (com.hms.entity.BillingMedicine me : meds) {
                itemsList.add(new String[]{me.getMedicineName() != null ? me.getMedicineName() : "Medicine", "", "", "", "", String.valueOf(me.getQuantity())});
            }
        }
        return pdfService.generateMedicinesListPdf(hospital, doctor, patient, "IPD MEDICINES & ADMINISTERED ITEMS", customNo, createdAt, itemsList);
    }

    private String[] parseBillingItemDescription(String desc) {
        if (desc == null) return null;
        String baseName = desc.trim();
        String qty = "1";
        if (baseName.contains(" (Qty: ")) {
            int idx = baseName.indexOf(" (Qty: ");
            String rest = baseName.substring(idx + 7);
            baseName = baseName.substring(0, idx).trim();
            if (rest.contains(")")) {
                qty = rest.substring(0, rest.indexOf(")")).trim();
            }
        } else if (baseName.contains(" x")) {
            int idx = baseName.lastIndexOf(" x");
            qty = baseName.substring(idx + 2).trim();
            baseName = baseName.substring(0, idx).trim();
        }
        return new String[]{baseName, qty};
    }

    @Transactional(readOnly = true)
    public java.io.ByteArrayInputStream getIpdPrescriptionPdf(Long ipdId) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        com.hms.entity.Hospital hospital = hospitalRepository.findById(hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException(HOSPITAL_NOT_FOUND));
        
        com.hms.entity.IpdAdmission ipd = ipdAdmissionRepository.findById(ipdId)
                .orElseThrow(() -> new ResourceNotFoundException("IPD Admission not found"));

        if (ipd.getHospitalId() == null || !ipd.getHospitalId().equals(hospitalId)) {
            throw new ResourceNotFoundException("IPD Admission not found");
        }

        com.hms.entity.Patient patient = patientRepository.findById(ipd.getPatientId())
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found"));

        java.util.List<com.hms.entity.Prescription> prescriptions = prescriptionRepository.findByIpdAdmissionIdOrderByStartDate(ipdId);
        
        return pdfService.generateIpdPrescriptionPdf(hospital, patient, ipd, prescriptions);
    }
}

