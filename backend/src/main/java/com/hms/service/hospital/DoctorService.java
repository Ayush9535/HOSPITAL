package com.hms.service.hospital;
import com.hms.util.LogSanitizer;

import com.hms.entity.Doctor;
import com.hms.entity.User;
import com.hms.repository.DoctorRepository;
import com.hms.repository.UserRepository;
import com.hms.security.SecurityContextHelper;

import com.hms.exception.ResourceNotFoundException;
import com.hms.exception.UnauthorizedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * DoctorService - Service for managing doctors
 * 
 * This service handles doctor-related operations:
 * - Adding new doctors (creates both Doctor record and User account)
 * - Listing doctors for a hospital
 * - Getting doctor details
 * 
 * All operations are automatically filtered by hospital_id for multi-tenant
 * isolation.
 * Only Hospital Admin can add doctors.
 * 
 * @author HMS Team
 * @version Phase-1
 */
@Service
public class DoctorService {

    private static final Logger logger = LoggerFactory.getLogger(DoctorService.class);

    @Autowired
    private DoctorRepository doctorRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private SecurityContextHelper securityHelper;

    @Autowired
    private com.hms.service.AuditLogService auditLogService;

    @Autowired
    private com.hms.security.HospitalWebSocketHandler webSocketHandler;

    /**
     * Add a new doctor
     * Creates both Doctor record and User account for login
     * Automatically sets hospital_id from the authenticated user's context
     * 
     * @param doctor   Doctor entity to create
     * @param password Password for doctor's user account
     * @return Created Doctor entity
     */
    @Transactional
    public Doctor addDoctor(Doctor doctor, String password) {
        // Validate phone number
        if (doctor.getPhone() == null || !doctor.getPhone().matches("^\\d{10}$")) {
            throw new IllegalArgumentException("Phone number must be exactly 10 digits");
        }

        // Get hospital_id from security context (multi-tenant isolation)
        Long hospitalId = securityHelper.getCurrentHospitalId();

        if (hospitalId == null) {
            throw new UnauthorizedException("Hospital ID not found in context");
        }

        // Check if doctor email already exists in this hospital
        if (doctorRepository.findByEmailAndHospitalId(doctor.getEmail(), hospitalId).isPresent()) {
            throw new IllegalArgumentException("Doctor with this email already exists in your hospital");
        }

        // Check if email is already used as a user account
        if (userRepository.existsByEmail(doctor.getEmail())) {
            throw new IllegalArgumentException("Email already exists in the system");
        }

        // Set hospital_id to ensure multi-tenant isolation
        doctor.setHospitalId(hospitalId);

        // Save doctor record
        doctor = doctorRepository.save(doctor);

        // Set sequential customId using the auto-increment id: DOC1, DOC2, DOC3...
        doctor.setCustomId("DOC" + doctor.getId());
        doctor = doctorRepository.save(doctor);

        // Create user account for doctor login
        User doctorUser = new User();
        doctorUser.setEmail(doctor.getEmail());
        doctorUser.setPassword(passwordEncoder.encode(password));
        doctorUser.setName(doctor.getName());
        doctorUser.setRole("DOCTOR");
        doctorUser.setHospitalId(hospitalId);
        userRepository.save(doctorUser);

        logger.info("Hospital {} created new doctor: {}", hospitalId, LogSanitizer.clean(doctor.getEmail()));

        // Log Audit
        try {
            auditLogService.logAction(
                    "DOCTOR_CREATED",
                    "Doctor " + doctor.getName() + " was added.",
                    securityHelper.getCurrentUserEmail(),
                    hospitalId,
                    "DOCTOR",
                    doctor.getPublicId() != null ? doctor.getPublicId() : doctor.getId().toString(),
                    null);
        } catch (Exception e) {
            logger.warn("Failed to create audit log for doctor creation", e);
        }

        // Broadcast real-time refresh
        try {
            webSocketHandler.broadcast(hospitalId, "{\"type\":\"REFRESH_DATA\"}");
        } catch (Exception e) {
            logger.warn("Failed to broadcast WebSocket refresh after doctor creation", e);
        }

        return doctor;
    }

    /**
     * Get all active doctors for the current hospital
     * Automatically filters by hospital_id from security context
     * 
     * @return List of active doctors for the hospital
     */
    /**
     * Get all active doctors for the current hospital with pagination
     * Automatically filters by hospital_id from security context
     * 
     * @return Page of active doctors for the hospital
     */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<Doctor> getAllDoctors(
            org.springframework.data.domain.Pageable pageable) {
        // Get hospital_id from security context (multi-tenant isolation)
        Long hospitalId = securityHelper.getCurrentHospitalId();

        if (hospitalId == null) {
            throw new UnauthorizedException("Hospital ID not found in context");
        }

        // Return only active doctors belonging to this hospital
        return doctorRepository.findByHospitalIdAndIsActiveTrueOrderByCreatedAtDesc(hospitalId, pageable);
    }

    /**
     * Search active doctors by name or specialization
     * 
     * @param query Search term
     * @return List of matching active doctors
     */
    @Transactional(readOnly = true)
    public List<Doctor> searchDoctors(String query) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            throw new UnauthorizedException("Hospital ID not found in context");
        }

        if (query == null || query.trim().isEmpty()) {
            // Return empty list or basic list, but better to use pagination if possible
            // For now, returning top 100 or similarly limited list to avoid crashing
            return getAllDoctors(org.springframework.data.domain.PageRequest.of(0, 100)).getContent();
        }

        return doctorRepository
                .searchActiveByNameOrSpecialization(hospitalId, query.trim());
    }

    /**
     * Update an active doctor's details (Name, Spec, Phone)
     * Email/Password updates are restricted for safety
     * 
     * @param id          Doctor ID
     * @param updatedData New doctor data
     * @return Updated Doctor entity
     */
    /**
     * Update an active doctor's details (Name, Spec, Phone)
     * Email/Password updates are restricted for safety
     * 
     * @param publicId    Doctor Public ID
     * @param updatedData New doctor data
     * @return Updated Doctor entity
     */
    public Doctor updateDoctor(String publicId, Doctor updatedData) {
        // Validate phone number
        if (updatedData.getPhone() == null || !updatedData.getPhone().matches("^[0-9]{10}$")) {
            throw new IllegalArgumentException("Phone number must be exactly 10 digits");
        }

        // Ensure doctor exists and belongs to this hospital
        Doctor existingDoctor = getDoctorByPublicId(publicId);
 
        existingDoctor.setName(updatedData.getName());
        existingDoctor.setSpecialization(updatedData.getSpecialization());
        existingDoctor.setPhone(updatedData.getPhone());
        // Note: Email and Password updates are not allowed here to prevent auth
        // potential issues

        Doctor saved = doctorRepository.save(existingDoctor);

        // Create audit log
        try {
            Long hid = securityHelper.getCurrentHospitalId();
            if (hid != null) {
                auditLogService.logAction(
                        "DOCTOR_UPDATED",
                        "Doctor " + saved.getName() + " details were updated.",
                        securityHelper.getCurrentUserEmail(),
                        hid,
                        "DOCTOR",
                        saved.getPublicId(),
                        null);
            }
        } catch (Exception e) {
            logger.warn("Failed to create audit log for doctor update", e);
        }

        // Broadcast real-time refresh. REFRESH_DATA reloads the lists the doctor appears in;
        // SETTINGS_UPDATED makes each client re-fetch its own profile, so the edited doctor's
        // own dashboard picks up the new name/specialization without a page reload.
        try {
            Long hid = securityHelper.getCurrentHospitalId();
            if (hid != null) {
                webSocketHandler.broadcast(hid, "{\"type\":\"REFRESH_DATA\"}");
                webSocketHandler.broadcast(hid, "{\"type\":\"SETTINGS_UPDATED\"}");
            }
        } catch (Exception e) {
            logger.warn("Failed to broadcast WebSocket refresh after doctor update", e);
        }

        return saved;
    }

    /**
     * Get an active doctor by ID
     * Ensures the doctor belongs to the current hospital (multi-tenant isolation)
     * 
     * @param id Doctor ID
     * @return Doctor entity
     * @throws RuntimeException if doctor not found, inactive, or doesn't belong to
     *                          the
     *                          hospital
     */
    /**
     * Get an active doctor by Public ID
     * Ensures the doctor belongs to the current hospital (multi-tenant isolation)
     * 
     * @param publicId Doctor Public ID
     * @return Doctor entity
     * @throws RuntimeException if doctor not found, inactive, or doesn't belong to
     *                          the
     *                          hospital
     */
    @Transactional(readOnly = true)
    public Doctor getDoctorByPublicId(String publicId) {
        // Get hospital_id from security context (multi-tenant isolation)
        Long hospitalId = securityHelper.getCurrentHospitalId();

        if (hospitalId == null) {
            throw new UnauthorizedException("Hospital ID not found in context");
        }

        // Find doctor only if it belongs to this hospital and is active
        return doctorRepository.findByPublicIdAndHospitalIdAndIsActiveTrue(publicId, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found"));
    }

    /**
     * Soft delete a doctor and their user account
     * 
     * @param id Doctor ID
     */
    @Transactional
    public void deleteDoctor(String publicId, String reason) {
        // Check if doctor exists and belongs to this hospital
        Doctor doctor = getDoctorByPublicId(publicId);

        // Soft delete doctor
        doctor.setIsActive(false);
        doctorRepository.save(doctor);

        // Soft delete associated user account to prevent login
        Optional<User> userOpt = userRepository.findByEmail(doctor.getEmail());
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            user.setIsActive(false);
            userRepository.save(user);
        }

        // Log the deletion
        logger.info("Doctor soft-deleted: ID={}, Name={}, Email={}. Reason: {}", LogSanitizer.clean(publicId),
                LogSanitizer.clean(doctor.getName()), LogSanitizer.clean(doctor.getEmail()), LogSanitizer.clean(reason));

        auditLogService.logAction(
                "DOCTOR_DELETED",
                "Doctor " + doctor.getName() + " was deleted. Reason: "
                        + (reason != null ? reason : "No reason provided"),
                securityHelper.getCurrentUserEmail(),
                doctor.getHospitalId(),
                "DOCTOR",
                publicId,
                reason);

        // Broadcast real-time refresh
        try {
            webSocketHandler.broadcast(doctor.getHospitalId(), "{\"type\":\"REFRESH_DATA\"}");
        } catch (Exception e) {
            logger.warn("Failed to broadcast WebSocket refresh after doctor deletion", e);
        }
    }

    /**
     * Reset a doctor's password (Hospital Admin only)
     * Generates a new random password, encodes and saves it.
     *
     * @param publicId Doctor Public ID
     * @return Map with email and new password
     */
    @Transactional
    public void resetDoctorPassword(String publicId, String newPassword) {
        Doctor doctor = getDoctorByPublicId(publicId);

        java.util.Optional<User> userOpt = userRepository.findByEmail(doctor.getEmail());
        if (userOpt.isEmpty()) {
            throw new ResourceNotFoundException("User account not found for doctor: " + doctor.getEmail());
        }

        User user = userOpt.get();
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        logger.info("Reset password for doctor: {}", LogSanitizer.clean(doctor.getEmail()));

        try {
            Long hospitalId = securityHelper.getCurrentHospitalId();
            auditLogService.logAction(
                    "PASSWORD_RESET",
                    "Reset password for doctor: " + doctor.getName() + " (" + doctor.getEmail() + ")",
                    securityHelper.getCurrentUserEmail(),
                    hospitalId,
                    "DOCTOR",
                    publicId,
                    null);
        } catch (Exception e) {
            logger.warn("Failed to create audit log for doctor password reset", e);
        }
    }

    @Autowired
    private com.hms.repository.MedicalRecordRepository medicalRecordRepository;

    @Autowired
    private com.hms.repository.PrescriptionRepository prescriptionRepository;

    @Autowired
    private com.hms.repository.AppointmentRepository appointmentRepository;

    @Autowired
    private com.hms.repository.PatientRepository patientRepository;

    @Autowired
    private com.hms.service.hospital.BillingService billingService;

    // auditLogService is already injected at class level, removing duplicate

    @Autowired
    private MedicineService medicineService;

    @Autowired
    private com.hms.repository.MedicineListRepository medicineListRepository;

    @Autowired
    private com.hms.repository.MedicineRepository medicineRepository;

    @Autowired
    private com.hms.repository.BillingMedicineRepository billingMedicineRepository;

    @Autowired
    private com.hms.repository.BillingRepository billingRepository;

    @Autowired
    private com.hms.repository.BillingItemRepository billingItemRepository;

    @Autowired
    private com.hms.repository.OpdRepository opdRepository;

    @Autowired
    private com.hms.repository.QueueEntryRepository queueEntryRepository;

    @Autowired
    private com.hms.repository.LabOrderRepository labOrderRepository;

    @Autowired
    private com.hms.repository.HospitalInventoryRepository hospitalInventoryRepository;

    @Autowired
    private HospitalInventoryService hospitalInventoryService;

    @Autowired
    private com.hms.repository.HospitalServiceRepository hospitalServiceRepository;

    /**
     * Submit a consultation
     * Creates Medical Record, Prescriptions, Auto-generates Bill, and updates
     * Appointment
     */
    @Transactional
    public com.hms.entity.Opd submitConsultation(com.hms.dto.ConsultationRequest request) {
        // Validate administered items quantity
        if (request.getAdministeredItems() != null) {
            for (com.hms.dto.ConsultationRequest.AdministeredItem item : request.getAdministeredItems()) {
                if (item.getQuantity() == null || item.getQuantity() <= 0) {
                    throw new IllegalArgumentException("Administered item quantity must be positive");
                }
            }
        }
        // Validate hospital inventory items quantity
        if (request.getHospitalInventoryItems() != null) {
            for (com.hms.dto.ConsultationRequest.HospitalInventoryItem item : request.getHospitalInventoryItems()) {
                if (item.getQuantity() == null || item.getQuantity() <= 0) {
                    throw new IllegalArgumentException("Hospital inventory item quantity must be positive");
                }
            }
        }
        // Validate prescriptions
        if (request.getPrescription() != null) {
            for (com.hms.dto.ConsultationRequest.PrescriptionItem item : request.getPrescription()) {
                if (item.getMedicineName() == null || item.getMedicineName().trim().isEmpty()) {
                    throw new IllegalArgumentException("Prescription medicine name is required");
                }
                if (item.getDosage() == null || item.getDosage().trim().isEmpty()) {
                    throw new IllegalArgumentException("Prescription dosage is required");
                }
                if (item.getFrequency() == null || item.getFrequency().trim().isEmpty()) {
                    throw new IllegalArgumentException("Prescription frequency is required");
                }
                if (item.getDuration() == null || item.getDuration().trim().isEmpty()) {
                    throw new IllegalArgumentException("Prescription duration is required");
                }
            }
        }

        Long hospitalId = securityHelper.getCurrentHospitalId();
        Long currentDoctorId = securityHelper.getCurrentUserId(); // Get current doctor's user ID

        // Get patient (required) - accept numeric id or publicId string
        com.hms.entity.Patient patient;
        if (request.getPatientId() != null && !request.getPatientId().isEmpty()) {
            String pid = request.getPatientId();
            if (pid.matches("^\\d+$")) {
                // numeric id
                Long numericId = Long.parseLong(pid);
                patient = patientRepository.findByIdAndHospitalIdAndIsActiveTrue(numericId, hospitalId)
                        .orElseThrow(() -> new ResourceNotFoundException("Patient not found"));
            } else {
                // treat as publicId
                patient = patientRepository.findByPublicIdAndHospitalIdAndIsActiveTrue(pid, hospitalId)
                        .orElseThrow(() -> new ResourceNotFoundException("Patient not found"));
            }
        } else {
            throw new IllegalArgumentException("Patient ID is required");
        }

        // Get appointment (optional - for appointment-based consultations)
        com.hms.entity.Appointment appointment = null;
        if (request.getAppointmentId() != null) {
            appointment = appointmentRepository.findById(request.getAppointmentId())
                    .orElseThrow(() -> new ResourceNotFoundException("Appointment not found"));

            if (!appointment.getHospitalId().equals(hospitalId)) {
                throw new UnauthorizedException("Appointment does not belong to this hospital");
            }
        }

        // Resolve doctor id: prefer appointment.doctorId, otherwise map current user to Doctor entity
        Long resolvedDoctorId = null;
        if (appointment != null) {
            resolvedDoctorId = appointment.getDoctorId();
        } else {
            try {
                java.util.Optional<com.hms.entity.Doctor> dopt = doctorRepository.findByEmailAndHospitalId(securityHelper.getCurrentUserEmail(), hospitalId);
                if (dopt.isPresent()) {
                    resolvedDoctorId = dopt.get().getId();
                } else {
                    throw new ResourceNotFoundException("Doctor not found");
                }
            } catch (Exception e) {
                throw new ResourceNotFoundException("Doctor not found");
            }
        }

        if (resolvedDoctorId != null) {
            java.util.Optional<com.hms.entity.Doctor> docOpt = doctorRepository.findByIdOrUserId(resolvedDoctorId, userRepository);
            if (docOpt.isPresent()) {
                resolvedDoctorId = docOpt.get().getId();
            }
        }

        // Create OPD if it's an appointment consultation
        com.hms.entity.Opd opd = null;
        if (request.getOpdId() != null) {
            // Tenant comes from the authenticated principal, never from the OPD row. A
            // cross-tenant id must be indistinguishable from a missing one, so this throws
            // rather than falling through to the create-a-new-OPD branch below.
            opd = opdRepository.findByIdAndHospitalIdWithPatientAndDoctor(request.getOpdId(), hospitalId)
                    .orElseThrow(() -> new com.hms.exception.ResourceNotFoundException("OPD not found"));
            if (opd != null && request.getIpdAdmitRecommended() != null) {
                opd.setIpdAdmitRecommended(request.getIpdAdmitRecommended());
                opd = opdRepository.save(opd);
            }
        } else if (appointment != null) {
            opd = new com.hms.entity.Opd();
            opd.setPatient(patient);
            opd.setDoctor(doctorRepository.findById(resolvedDoctorId).orElse(null));
            opd.setProblem(request.getSymptoms() != null && !request.getSymptoms().isEmpty() ? request.getSymptoms() : appointment.getNotes());
            opd.setStatus(com.hms.entity.Opd.Status.COMPLETED);
            if (request.getIpdAdmitRecommended() != null) {
                opd.setIpdAdmitRecommended(request.getIpdAdmitRecommended());
            }
            opd = opdRepository.save(opd);
            opd.setCaseId("OPD-" + opd.getId());
            opd = opdRepository.save(opd);
        } else {
            opd = new com.hms.entity.Opd();
            opd.setPatient(patient);
            opd.setDoctor(doctorRepository.findById(resolvedDoctorId).orElse(null));
            opd.setProblem(request.getSymptoms() != null && !request.getSymptoms().isEmpty() ? request.getSymptoms() : "Direct Patient Consultation");
            opd.setStatus(com.hms.entity.Opd.Status.COMPLETED);
            if (request.getIpdAdmitRecommended() != null) {
                opd.setIpdAdmitRecommended(request.getIpdAdmitRecommended());
            }
            opd = opdRepository.save(opd);
            opd.setCaseId("OPD-" + opd.getId());
            opd = opdRepository.save(opd);
        }


        // 1. Create Medical Record
        com.hms.entity.MedicalRecord record = new com.hms.entity.MedicalRecord();
        record.setHospitalId(hospitalId);
        record.setPatientId(patient.getId());
        record.setDoctorId(resolvedDoctorId);
        record.setAppointmentId(appointment != null ? appointment.getId() : null);
        record.setOpdId(opd != null ? opd.getId() : null);
        record.setSymptoms(request.getSymptoms());
        record.setDiagnosis(request.getDiagnosis());
        record.setTreatmentNotes(request.getTreatmentNotes());
        record.setFollowUpDate(request.getFollowUpDate());
        record.setFollowUpInstructions(request.getFollowUpInstructions());
        // A follow-up starts open. Left null when no date was given, so a consultation with no
        // follow-up never appears in anyone's due list.
        record.setFollowUpStatus(request.getFollowUpDate() != null
                ? com.hms.entity.MedicalRecord.FOLLOW_UP_OPEN : null);

        com.hms.entity.MedicalRecord savedRecord = medicalRecordRepository.save(record);

        // Create audit log
        try {
            auditLogService.logAction(
                    "MEDICAL_RECORD_CREATED",
                    "Medical record created for patient " + patient.getName() + ".",
                    securityHelper.getCurrentUserEmail(),
                    hospitalId,
                    "PATIENT",
                    patient.getPublicId(),
                    null);
        } catch (Exception e) {
            logger.warn("Failed to create audit log for medical record", e);
        }

        // 2. Create Prescriptions
        if (request.getPrescription() != null) {
            for (com.hms.dto.ConsultationRequest.PrescriptionItem item : request.getPrescription()) {
                com.hms.entity.Prescription p = new com.hms.entity.Prescription();
                p.setHospitalId(hospitalId);
                p.setMedicalRecordId(savedRecord.getId());
                p.setMedicineName(item.getMedicineName());
                p.setDosage(item.getDosage());
                p.setFrequency(item.getFrequency());
                p.setDuration(item.getDuration());
                p.setInstructions(item.getInstructions());
                prescriptionRepository.save(p);

                // --- Dynamic Learning: Auto-add to Global Catalog ---
                try {
                    if (!medicineListRepository.existsByNameIgnoreCase(item.getMedicineName())) {
                        com.hms.entity.MedicineList newMed = new com.hms.entity.MedicineList();
                        newMed.setName(item.getMedicineName());
                        newMed.setType("Tablet"); // Default type
                        medicineListRepository.save(newMed);
                    }
                } catch (Exception e) {
                    // Ignore if already exists or fails - don't block consultation
                }
            }
        }

        // 2.b Create Lab Orders if requested
        try {
            if (request.getLabTests() != null && !request.getLabTests().isEmpty()) {
                for (String testName : request.getLabTests()) {
                    com.hms.entity.LabOrder order = new com.hms.entity.LabOrder();
                    order.setHospitalId(hospitalId);
                    order.setMedicalRecordId(savedRecord.getId());
                    // patient_id and priority are NOT NULL in lab_orders; omitting patient_id
                    // was failing the insert and poisoning the transaction (500 on complete).
                    order.setPatientId(patient.getId());
                    order.setOpdId(opd != null ? opd.getId() : null);
                    order.setTestName(testName);
                    order.setStatus("ORDERED");
                    order.setPriority("ROUTINE");
                    labOrderRepository.save(order);
                }
            }
        } catch (Exception e) {
            // don't fail the consultation for lab order persistence errors
            logger.warn("Failed to create lab orders", e);
        }

        // 3. Update Appointment Status (if appointment-based)
        if (appointment != null) {
            appointment.setStatus("COMPLETED");
            appointmentRepository.save(appointment);
        }

        // The consultation completes the OPD encounter. This is a CLINICAL transition owned by the
        // doctor — payment must never drive it (see BillingService.updateStatus).
        Long opdIdToUse = request.getOpdId() != null ? request.getOpdId() : (opd != null ? opd.getId() : null);
        if (opdIdToUse != null) {
            try {
                java.util.Optional<com.hms.entity.Opd> opdOpt = opdRepository
                        .findByIdAndHospitalIdWithPatientAndDoctor(opdIdToUse, hospitalId);
                if (opdOpt.isPresent()) {
                    com.hms.entity.Opd o = opdOpt.get();
                    o.setStatus(com.hms.entity.Opd.Status.COMPLETED);
                    if (o.getDoctor() == null && resolvedDoctorId != null) {
                        doctorRepository.findById(resolvedDoctorId).ifPresent(o::setDoctor);
                    }
                    opdRepository.save(o);

                    // Audit log for OPD status change
                    try {
                        auditLogService.logAction(
                                "OPD_STATUS_CHANGED",
                                "OPD " + (o.getCaseId() != null ? o.getCaseId() : o.getId()) + " set to COMPLETED",
                                securityHelper.getCurrentUserEmail(),
                                hospitalId,
                                "OPD",
                                o.getId().toString(),
                                null);
                    } catch (Exception e) {
                        logger.warn("Failed to write audit log for OPD status change during consultation", e);
                    }
                }

                // Remove queue entries for this OPD so it doesn't appear again
                try {
                    queueEntryRepository.deleteByOpdId(opdIdToUse);
                } catch (Exception e) {
                    logger.warn("Failed to delete queue entry for OPD during consultation completion", e);
                }
            } catch (Exception e) {
                // Don't fail consultation if OPD update fails
            }
        }

        // 4. Update Patient Status to COMPLETED
        patient.setStatus(com.hms.entity.PatientStatus.COMPLETED);
        patientRepository.save(patient);

        // 5. Auto-generate OPD Bill (unified flow)
        try {
            com.hms.entity.Billing bill = null;
            if (appointment != null) {
                bill = billingRepository.findByAppointmentId(appointment.getId()).orElse(null);
            }
            if (bill == null && opdIdToUse != null) {
                bill = billingRepository.findByOpdId(opdIdToUse).orElse(null);
            }
            if (bill == null) {
                // Always use OPD bill flow: create itemized bill (case paper + consultation)
                bill = billingService.createOpdBill(opdIdToUse, patient.getId(), resolvedDoctorId);
                if (bill != null && appointment != null) {
                    bill.setAppointmentId(appointment.getId());
                    billingRepository.save(bill);
                }
            }

            if (bill != null && bill.getOpdId() == null && opdIdToUse != null) {
                bill.setOpdId(opdIdToUse);
                billingRepository.save(bill);
            }

            // The consultation is the source of truth for what this visit is charged, so rebuild
            // the fee items from it. This used to be gated on the bill still being PENDING, which
            // silently skipped a bill pre-paid at OPD entry ("bill before OPD") — the doctor's
            // added procedure fees were then never billed at all. Rebuilding is safe: the charges
            // list already carries the consultation + case-paper fees, so they are re-created once
            // (not doubled), and recalculateTotal() below re-derives the status from the ledger —
            // a pre-paid bill that gains extras correctly becomes PARTIAL with a balance due.
            if (bill != null && request.getCharges() != null) {
                java.util.List<com.hms.entity.BillingItem> existingItems = billingItemRepository.findByBillingId(bill.getId());
                billingItemRepository.deleteAll(existingItems);

                for (com.hms.dto.ConsultationRequest.ChargeItem charge : request.getCharges()) {
                    if (charge.getDescription() == null || charge.getDescription().trim().isEmpty()) {
                        continue;
                    }
                    com.hms.entity.BillingItem item = new com.hms.entity.BillingItem();
                    item.setBillingId(bill.getId());
                    item.setHospitalId(hospitalId);
                    item.setDescription(charge.getDescription().trim());
                    item.setAmount(charge.getAmount() != null ? charge.getAmount() : java.math.BigDecimal.ZERO);
                    billingItemRepository.save(item);
                }
                billingService.recalculateTotal(bill.getId());
            }
            
            // --- Process Administered Items (Stock Billing & Deductions) ---
            if (request.getAdministeredItems() != null && !request.getAdministeredItems().isEmpty()) {
                try {
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    savedRecord.setAdministeredItemsJson(mapper.writeValueAsString(request.getAdministeredItems()));
                    medicalRecordRepository.save(savedRecord);
                } catch (Exception e) {
                    logger.error("Failed to serialize administeredItemsJson for OPD medical record", e);
                }
            }

            if (bill != null && request.getAdministeredItems() != null && !request.getAdministeredItems().isEmpty()) {
                for (com.hms.dto.ConsultationRequest.AdministeredItem item : request.getAdministeredItems()) {
                    if (item.getMedicineId() != null) {
                        // Secure lookup enforcing hospitalId to prevent cross-tenant writes
                        com.hms.entity.Medicine med = medicineRepository.findByIdAndHospitalId(item.getMedicineId(), hospitalId)
                            .orElseThrow(() -> new IllegalArgumentException("Medicine not found in active inventory: ID " + item.getMedicineId()));
                        
                        // Strict active state check
                        if (med.getIsActive() == null || !med.getIsActive()) {
                            throw new IllegalArgumentException("Medicine is currently inactive and cannot be administered: " + med.getName());
                        }

                        // Strict tenant match check for safety
                        if (med.getHospitalId() == null || !med.getHospitalId().equals(hospitalId)) {
                            throw new IllegalArgumentException("Security violation: Tenant boundary mismatch for medicine ID " + item.getMedicineId());
                        }

                        // Robust validation to prevent NullPointerException and ensure positive unit prices
                        if (med.getUnitPrice() == null || med.getUnitPrice() <= 0.0) {
                            throw new IllegalArgumentException("Medicine unit price is missing or invalid for: " + med.getName() + ". Please configure a valid unit price in inventory.");
                        }

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
                            logger.warn("Failed to write audit log for OPD medicine deduction", e);
                        }
                        
                        // Create BillingMedicine charge
                        com.hms.entity.BillingMedicine bm = new com.hms.entity.BillingMedicine();
                        bm.setBillingId(bill.getId());
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
            
            // --- Process Services Used (charge + relevant-item stock deduction) ---
            if (bill != null && request.getHospitalInventoryItems() != null && !request.getHospitalInventoryItems().isEmpty()) {
                for (com.hms.dto.ConsultationRequest.HospitalInventoryItem item : request.getHospitalInventoryItems()) {
                    java.math.BigDecimal serviceCharge = hospitalInventoryService.consumeService(
                            item.getServiceId(), item.getQuantity(), hospitalId);

                    com.hms.entity.HospitalServiceEntity svc = hospitalServiceRepository.findByIdAndHospitalId(item.getServiceId(), hospitalId).orElse(null);
                    String svcName = svc != null ? svc.getName() : ("Service #" + item.getServiceId());

                    com.hms.entity.BillingItem bi = new com.hms.entity.BillingItem();
                    bi.setBillingId(bill.getId());
                    bi.setHospitalId(hospitalId);
                    bi.setDescription(svcName + " (Qty: " + item.getQuantity() + ")");
                    bi.setAmount(serviceCharge);
                    billingItemRepository.save(bi);
                }
            }

            if (bill != null) {
                // Recalculate bill total (incorporates medicines + service charges)
                billingService.recalculateTotal(bill.getId());
            }
        } catch (Exception e) {
            logger.error("Failed to create OPD bill or administer stocks", e);
            // Classified by type, not by message text: getMessage() is null often enough that
            // matching on it turned a business refusal into a NullPointerException, and a 400
            // into a 500.
            //
            // Every refusal above -- inactive medicine, a medicine from another facility, a
            // missing unit price, not enough stock -- is an answer to the doctor, not an
            // infrastructure hiccup. Swallowing one completes a consultation that says a
            // medicine was given while nothing was deducted and nothing was billed, which is
            // worse than failing. Anything else (a bill that could not be written, a total that
            // could not be recalculated) is still logged and left alone, as before.
            if (e instanceof IllegalArgumentException) {
                throw (IllegalArgumentException) e;
            }
        }

        // 6. Audit Log
        try {
            String description = appointment != null
                    ? "Consultation completed for Appointment ID: " + appointment.getCustomId()
                    : "Consultation completed for Patient: " + patient.getName();
            auditLogService.logAction(
                    "CONSULTATION_COMPLETED",
                    description,
                    securityHelper.getCurrentUserEmail(),
                    hospitalId,
                    "APPOINTMENT",
                    appointment != null ? appointment.getPublicId() : "N/A",
                    null);
        } catch (Exception e) {
            logger.warn("Failed to create audit log", e);
        }

        // Broadcast WebSocket sync update to all clients in the hospital
        try {
            webSocketHandler.broadcast(hospitalId, "{\"type\":\"REFRESH_DATA\"}");
        } catch (Exception e) {
            logger.warn("Failed to broadcast WebSocket refresh data from submitConsultation", e);
        }

        return opd;
    }
}
