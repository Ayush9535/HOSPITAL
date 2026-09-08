package com.hms.service.hospital;
import com.hms.util.LogSanitizer;

import com.hms.entity.Appointment;
import com.hms.repository.AppointmentRepository;
import com.hms.repository.DoctorRepository;
import com.hms.repository.PatientRepository;
import com.hms.security.SecurityContextHelper;

import com.hms.exception.ResourceNotFoundException;
import com.hms.exception.UnauthorizedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AppointmentService - Service for managing appointments
 * 
 * This service handles appointment-related operations:
 * - Creating new appointments
 * - Listing appointments for a hospital
 * - Listing appointments for a specific doctor
 * - Getting appointment details
 * 
 * All operations are automatically filtered by hospital_id for multi-tenant
 * isolation.
 * 
 * @author HMS Team
 * @version Phase-1
 */
@Service
public class AppointmentService {

    private static final Logger logger = LoggerFactory.getLogger(AppointmentService.class);

    // Appointment status values, defined once instead of repeating the literals.
    private static final String STATUS_SCHEDULED = "SCHEDULED";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_CANCELLED = "CANCELLED";

    @Autowired
    private AppointmentRepository appointmentRepository;

    @Autowired
    private PatientRepository patientRepository;

    @Autowired
    private DoctorRepository doctorRepository;

    @Autowired
    private SecurityContextHelper securityHelper;

    @Autowired
    private com.hms.service.hospital.BillingService billingService;

    @Autowired
    private com.hms.service.AuditLogService auditLogService;

    @Autowired
    private com.hms.security.HospitalWebSocketHandler webSocketHandler;


    /**
     * Create a new appointment
     * Validates that patient and doctor belong to the same hospital
     * Automatically sets hospital_id from the authenticated user's context
     * If patientId is null but patient details are provided, creates a new patient
     *
     * @param appointment Appointment entity to create
     * @return Created Appointment entity
     */
    @Transactional
    public Appointment createAppointment(Appointment appointment) {
        // Get hospital_id from security context (multi-tenant isolation)
        Long hospitalId = securityHelper.getCurrentHospitalId();

        // AppointmentController enforces APPOINTMENTS. The entitlement model guarantees that
        // APPOINTMENTS includes OPD, avoiding a duplicate service-side module check and its
        // misleading 400 response for a plan authorization failure.

        if (hospitalId == null) {
            throw new UnauthorizedException("Hospital ID not found in context");
        }

        // Handle patient - either find existing or create new
        Long patientId = appointment.getPatientId();

        // If patientId is null, check if we have patient details to create a new
        // patient
        if (patientId == null) {
            // Check if patient details are provided (name, phone)
            String patientName = appointment.getPatientName();
            String patientPhone = appointment.getPatientPhone();
            String patientEmail = appointment.getPatientEmail();
            java.time.LocalDate patientDateOfBirth = appointment.getPatientDateOfBirth();
            String patientGender = appointment.getPatientGender();

            if (patientName == null || patientPhone == null) {
                throw new IllegalArgumentException("Either patientId or patient details (name, phone) must be provided");
            }

            // Check if patient already exists by phone
            List<com.hms.entity.Patient> existingPatients = patientRepository
                    .findByPhoneAndHospitalIdAndIsActiveTrue(patientPhone, hospitalId);

            if (!existingPatients.isEmpty()) {
                // Use existing patient (first one found if duplicates exist)
                com.hms.entity.Patient existingPatient = existingPatients.get(0);
                patientId = existingPatient.getId();
                logger.info("Found existing patient with phone {}, using patient ID {}", LogSanitizer.clean(patientPhone), patientId);
            } else {
                // Create new patient
                com.hms.entity.Patient newPatient = new com.hms.entity.Patient();
                newPatient.setName(patientName);
                newPatient.setPhone(patientPhone);
                newPatient.setEmail(patientEmail != null ? patientEmail : "");
                // Default to today (age 0) if not provided, though frontend should require it
                newPatient.setDateOfBirth(patientDateOfBirth != null ? patientDateOfBirth : java.time.LocalDate.now(java.time.ZoneId.systemDefault()));
                newPatient.setGender(patientGender != null ? patientGender : "Unknown");
                newPatient.setAddress("Walk-in"); // Default address for quick appointments
                newPatient.setHospitalId(hospitalId);
                newPatient.setIsActive(true);

                // Log patient creation audit
                try {
                    auditLogService.logAction(
                            "PATIENT_CREATED",
                            "Patient " + newPatient.getName() + " was created during appointment booking.",
                            securityHelper.getCurrentUserEmail(),
                            hospitalId,
                            "PATIENT",
                            null, // ID not yet available pre-save? No, save first.
                            "Auto-created");
                } catch (Exception e) {
                }

                com.hms.entity.Patient savedPatient = patientRepository.save(newPatient);
                patientId = savedPatient.getId();
                logger.info("Created new patient {} with ID {} for hospital {}", LogSanitizer.clean(patientName), patientId, hospitalId);
            }

            // Set the patientId in the appointment
            appointment.setPatientId(patientId);
        } else {
            // Verify patient belongs to this hospital and is active
            patientRepository
                    .findByIdAndHospitalIdAndIsActiveTrue(patientId, hospitalId)
                    .orElseThrow(() -> new ResourceNotFoundException("Patient not found in your hospital or is inactive"));
        }

        // Verify doctor belongs to this hospital and is active
        doctorRepository
                .findByIdAndHospitalIdAndIsActiveTrue(appointment.getDoctorId(), hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found in your hospital or is inactive"));

        // -----------------------------------------------------------
        // Time Slot Validation (New Feature)
        // -----------------------------------------------------------
        if (appointment.getAppointmentTime() == null) {
            throw new IllegalArgumentException("Appointment time is required");
        }

        // Strict 30-minute slot enforcement logic
        // Fetches all active appointments for this doctor on this date
        List<Appointment> existingAppointments = appointmentRepository
                .findByDoctorIdAndAppointmentDateAndIsActiveTrue(appointment.getDoctorId(),
                        appointment.getAppointmentDate());

        for (Appointment existing : existingAppointments) {
            // Check for exact time match (assuming strict 30 min slots) for non-cancelled appointments
            if (!STATUS_CANCELLED.equals(existing.getStatus()) && existing.getAppointmentTime().equals(appointment.getAppointmentTime())) {
                throw new IllegalArgumentException("Slot " + appointment.getAppointmentTime() + " is already booked.");
            }

            // Optional: Advanced overlap check if we allowed flexible durations later
            // LocalTime start = existing.getAppointmentTime();
            // LocalTime end = start.plusMinutes(30);
            // ... check for overlap ...
        }
        // -----------------------------------------------------------

        // Set hospital_id to ensure multi-tenant isolation
        appointment.setHospitalId(hospitalId);
        appointment.setStatus(STATUS_SCHEDULED); // Default status

        logger.info("Hospital {} scheduling appointment for patient {} with doctor {} at {}", hospitalId,
                appointment.getPatientId(), appointment.getDoctorId(), appointment.getAppointmentTime());

        Appointment savedAppointment = appointmentRepository.save(appointment);

        // Log Appointment Creation Audit
        try {
            String pName = savedAppointment.getPatientName();
            if (pName == null || pName.isBlank()) {
                pName = patientRepository.findById(savedAppointment.getPatientId())
                    .map(com.hms.entity.Patient::getName).orElse("Unknown");
            }
            String dName = savedAppointment.getDoctorName();
            if (dName == null || dName.isBlank()) {
                dName = doctorRepository.findById(savedAppointment.getDoctorId())
                    .map(com.hms.entity.Doctor::getName).orElse("Unknown");
            }
            auditLogService.logAction(
                    "APPOINTMENT_CREATED",
                    "Appointment for patient " + pName + " with doctor " + dName + " was scheduled.",
                    securityHelper.getCurrentUserEmail(),
                    hospitalId,
                    "APPOINTMENT",
                    savedAppointment.getPublicId(),
                    null);
        } catch (Exception e) {
            logger.warn("Failed to create audit log for appointment scheduling", e);
        }

        try {
            webSocketHandler.broadcast(hospitalId, "{\"type\":\"REFRESH_DATA\"}");
        } catch (Exception e) {
            // ignore
        }


        return savedAppointment;
    }

    /**
     * Move an appointment to a new date and/or time.
     *
     * <p>Rescheduling was simply absent: the update endpoint read only status and notes, so a
     * request carrying a new time was accepted with 200 and the appointment stayed exactly where
     * it was. Reception had no way to move a booking and no indication that the change had not
     * happened.
     *
     * <p>The same slot rule that guards booking guards the move -- a reschedule that lands on
     * another patient's slot is refused rather than double-booking the doctor. The appointment's
     * own slot is excluded from that check so re-saving it unchanged is not a clash with itself.
     */
    private void reschedule(Appointment appointment, java.time.LocalDate newDate,
            java.time.LocalTime newTime, Long hospitalId) {
        if (newDate == null && newTime == null) {
            return;
        }
        java.time.LocalDate date = newDate != null ? newDate : appointment.getAppointmentDate();
        java.time.LocalTime time = newTime != null ? newTime : appointment.getAppointmentTime();
        if (date.equals(appointment.getAppointmentDate()) && time.equals(appointment.getAppointmentTime())) {
            return;
        }

        for (Appointment existing : appointmentRepository
                .findByDoctorIdAndAppointmentDateAndIsActiveTrue(appointment.getDoctorId(), date)) {
            if (existing.getId().equals(appointment.getId())) continue;
            if (STATUS_CANCELLED.equals(existing.getStatus())) continue;
            if (time.equals(existing.getAppointmentTime())) {
                throw new IllegalArgumentException("Slot " + time + " is already booked.");
            }
        }

        appointment.setAppointmentDate(date);
        appointment.setAppointmentTime(time);

        try {
            auditLogService.logAction(
                    "APPOINTMENT_RESCHEDULED",
                    "Appointment moved to " + date + " " + time + ".",
                    securityHelper.getCurrentUserEmail(),
                    hospitalId,
                    "APPOINTMENT",
                    appointment.getPublicId(),
                    null);
        } catch (Exception e) {
            logger.warn("Failed to create audit log for appointment reschedule", e);
        }
    }

    /**
     * Helper method to populate transient name fields (Patient Name, Doctor Name)
     * Avoids N+1 problem by fetching in bulk
     */
    private List<Appointment> populateNames(List<Appointment> appointments) {
        if (appointments.isEmpty())
            return appointments;

        // Collect IDs
        java.util.Set<Long> patientIds = appointments.stream().map(Appointment::getPatientId)
                .collect(java.util.stream.Collectors.toSet());
        java.util.Set<Long> doctorIds = appointments.stream().map(Appointment::getDoctorId)
                .collect(java.util.stream.Collectors.toSet());

        // Fetch Maps
        Map<Long, com.hms.entity.Patient> patientsMap = patientRepository.findAllById(patientIds).stream()
                .collect(java.util.stream.Collectors.toMap(com.hms.entity.Patient::getId, p -> p));

        Map<Long, com.hms.entity.Doctor> doctorsMap = doctorRepository.findAllById(doctorIds).stream()
                .collect(java.util.stream.Collectors.toMap(com.hms.entity.Doctor::getId, d -> d));

        // Populate Names and Self-Heal missing Public IDs
        appointments.forEach(appt -> {
            // Self-heal: Check if public ID is missing
            if (appt.getPublicId() == null || appt.getPublicId().trim().isEmpty()) {
                appt.setPublicId(java.util.UUID.randomUUID().toString()); // Force set UUID
                if (appt.getCustomId() == null)
                    appt.generateIds(); // Handle customId via generateIds or manually
                appointmentRepository.save(appt);
                logger.info("Self-healed missing Public ID for appointment ID: {}", appt.getId());
            }

            com.hms.entity.Patient p = patientsMap.get(appt.getPatientId());
            if (p != null)
                appt.setPatientName(p.getName());

            com.hms.entity.Doctor d = doctorsMap.get(appt.getDoctorId());
            if (d != null)
                appt.setDoctorName(d.getName());
        });

        return appointments;
    }

    /**
     * Get all active appointments for the current hospital with pagination,
     * search, and optional view filter
     */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<Appointment> getAllAppointments(String search,
            org.springframework.data.domain.Pageable pageable, String view) {
        Long hospitalId = securityHelper.getCurrentHospitalId();

        if (hospitalId == null) {
            throw new UnauthorizedException("Hospital ID not found in context");
        }

        org.springframework.data.domain.Page<Appointment> page;
        java.time.LocalDate today = java.time.LocalDate.now();

        if (view == null || view.isEmpty()) {
            if (search != null && !search.isEmpty()) {
                page = appointmentRepository.searchAppointments(hospitalId, search, pageable);
            } else {
                page = appointmentRepository
                        .findByHospitalIdAndIsActiveTrueOrderByAppointmentDateDesc(hospitalId, pageable);
            }
        } else {
            switch (view.toLowerCase()) {
                case "today":
                    if (search != null && !search.isEmpty()) {
                        page = appointmentRepository
                                .searchAppointmentsByDate(hospitalId, search, today, pageable);
                    } else {
                        page = appointmentRepository
                                .findByHospitalIdAndAppointmentDateAndIsActiveTrueOrderByAppointmentTimeAsc(hospitalId,
                                        today, pageable);
                    }
                    break;
                case "upcoming":
                    if (search != null && !search.isEmpty()) {
                        page = appointmentRepository
                                .searchAppointmentsByDateAfter(hospitalId, search, today, pageable);
                    } else {
                        page = appointmentRepository
                                .findByHospitalIdAndAppointmentDateGreaterThanEqualAndIsActiveTrueOrderByAppointmentDateAscAppointmentTimeAsc(
                                        hospitalId, today, pageable);
                    }
                    break;
                case "history":
                    if (search != null && !search.isEmpty()) {
                        page = appointmentRepository
                                .searchAppointmentsHistory(hospitalId, search, today, pageable);
                    } else {
                        page = appointmentRepository
                                .findByHospitalIdAndIsActiveTrueAndAppointmentDateBeforeOrHospitalIdAndIsActiveTrueAndStatusInOrderByAppointmentDateDescAppointmentTimeDesc(
                                        hospitalId, today, hospitalId,
                                        java.util.Arrays.asList(STATUS_COMPLETED, STATUS_CANCELLED),
                                        pageable);
                    }
                    break;
                default:
                    if (search != null && !search.isEmpty()) {
                        page = appointmentRepository
                                .searchAppointments(hospitalId, search, pageable);
                    } else {
                        page = appointmentRepository
                                .findByHospitalIdAndIsActiveTrueOrderByAppointmentDateDesc(hospitalId, pageable);
                    }
            }
        }

        // Populate names (using list from page)
        populateNames(page.getContent());

        return page;
    }

    @Transactional(readOnly = true)
    public List<Appointment> getAllAppointments() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null)
            throw new UnauthorizedException("Hospital ID not found in context");
        List<Appointment> list = appointmentRepository
                .findByHospitalIdAndIsActiveTrueOrderByAppointmentDateDesc(hospitalId);
        return populateNames(list);
    }

    /**
     * Get active appointments for a specific doctor with optional view filter
     */
    @Transactional(readOnly = true)
    public List<Appointment> getAppointmentsByDoctor(Long doctorId, String view) {
        Long hospitalId = securityHelper.getCurrentHospitalId();

        if (hospitalId == null) {
            throw new UnauthorizedException("Hospital ID not found in context");
        }

        // Verify doctor belongs to this hospital and is active
        doctorRepository.findByIdAndHospitalIdAndIsActiveTrue(doctorId, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found in your hospital or is inactive"));

        List<Appointment> appointments;
        java.time.LocalDate today = java.time.LocalDate.now();

        if (view == null || view.isEmpty()) {
            // Default: All active appointments sorted by date desc
            appointments = appointmentRepository
                    .findByDoctorIdAndHospitalIdAndIsActiveTrueOrderByAppointmentDateDesc(doctorId, hospitalId);
        } else {
            switch (view.toLowerCase()) {
                case "today":
                    appointments = appointmentRepository
                            .findByDoctorIdAndAppointmentDateAndIsActiveTrueOrderByAppointmentTimeAsc(doctorId, today);
                    break;
                case "upcoming":
                    appointments = appointmentRepository
                            .findByDoctorIdAndAppointmentDateGreaterThanEqualAndIsActiveTrueOrderByAppointmentDateAscAppointmentTimeAsc(
                                    doctorId, today);
                    break;
                case "history":
                    appointments = appointmentRepository
                            .findByDoctorIdAndIsActiveTrueAndAppointmentDateBeforeOrDoctorIdAndIsActiveTrueAndStatusInOrderByAppointmentDateDescAppointmentTimeDesc(
                                    doctorId, today, doctorId, java.util.Arrays.asList(STATUS_COMPLETED, STATUS_CANCELLED));
                    break;
                default:
                    appointments = appointmentRepository
                            .findByDoctorIdAndHospitalIdAndIsActiveTrueOrderByAppointmentDateDesc(doctorId, hospitalId);
            }
        }

        return populateNames(appointments);
    }

    /**
     * Get active appointments for a specific patient (Patient History)
     */
    @Transactional(readOnly = true)
    public List<Appointment> getAppointmentsByPatient(String patientPublicId) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            throw new UnauthorizedException("Hospital ID not found in context");
        }

        // Resolve Patient Public ID to Long ID
        Optional<com.hms.entity.Patient> patientOpt = patientRepository
                .findByPublicIdAndHospitalIdAndIsActiveTrue(patientPublicId, hospitalId);

        if (patientOpt.isEmpty()) {
            try {
                Long id = Long.parseLong(patientPublicId);
                patientOpt = patientRepository.findByIdAndHospitalIdAndIsActiveTrue(id, hospitalId);
            } catch (NumberFormatException e) {
                // ignore
            }
        }

        com.hms.entity.Patient patient = patientOpt.orElseThrow(() -> new ResourceNotFoundException("Patient not found"));

        List<Appointment> appointments = appointmentRepository
                .findByPatientIdAndHospitalIdAndIsActiveTrueOrderByAppointmentDateDesc(patient.getId(), hospitalId);

        return populateNames(appointments);
    }

    /**
     * Get an active appointment by ID
     * Ensures the appointment belongs to the current hospital (multi-tenant
     * isolation)
     * 
     * @param id Appointment ID
     * @return Appointment entity
     * @throws RuntimeException if appointment not found, inactive, or doesn't
     *                          belong to the hospital
     */
    @Transactional(readOnly = true)
    public Appointment getAppointmentByPublicId(String publicId) {
        // Get hospital_id from security context (multi-tenant isolation)
        Long hospitalId = securityHelper.getCurrentHospitalId();

        if (hospitalId == null) {
            throw new UnauthorizedException("Hospital ID not found in context");
        }

        // Find appointment only if it belongs to this hospital and is active
        Optional<Appointment> apptOpt = appointmentRepository.findByPublicIdAndHospitalIdAndIsActiveTrue(publicId,
                hospitalId);

        if (apptOpt.isEmpty()) {
            try {
                Long id = Long.parseLong(publicId);
                apptOpt = appointmentRepository.findByIdAndHospitalIdAndIsActiveTrue(id, hospitalId);
            } catch (NumberFormatException e) {
                // ignore
            }
        }

        Appointment appointment = apptOpt.orElseThrow(() -> new ResourceNotFoundException("Appointment not found"));

        // Populate names for single appointment
        populateNames(java.util.Collections.singletonList(appointment));

        return appointment;
    }

    /**
     * Get today's appointments for the current hospital
     * Used for Overview dashboard - shows only today's appointments
     * 
     * @return List of today's appointments
     */
    @Transactional(readOnly = true)
    public List<Appointment> getTodaysAppointments() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            throw new UnauthorizedException("Hospital ID not found in context");
        }

        LocalDate today = LocalDate.now();
        List<Appointment> appointments = appointmentRepository
                .findByHospitalIdAndAppointmentDateAndIsActiveTrue(hospitalId, today);

        logger.info("Found {} appointments for today for hospital {}", appointments.size(), hospitalId);
        return populateNames(appointments);
    }

    @Transactional(readOnly = true)
    public long getTodaysAppointmentsCount() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            throw new UnauthorizedException("Hospital ID not found in context");
        }
        LocalDate today = LocalDate.now();
        return appointmentRepository.countByHospitalIdAndIsActiveTrueAndAppointmentDate(hospitalId, today);
    }

    // ... keeping the rest (delete, update, stats, myAppointments)

    /**
     * Soft delete an appointment
     * 
     * @param id Appointment ID
     */
    public void deleteAppointment(String publicId, String reason) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null)
            throw new UnauthorizedException("Hospital ID not found in context");

        Optional<Appointment> apptOpt = appointmentRepository.findByPublicIdAndHospitalIdAndIsActiveTrue(publicId,
                hospitalId);

        if (apptOpt.isEmpty()) {
            try {
                Long id = Long.parseLong(publicId);
                apptOpt = appointmentRepository.findByIdAndHospitalIdAndIsActiveTrue(id, hospitalId);
            } catch (NumberFormatException e) {
                // ignore
            }
        }

        Appointment appointment = apptOpt.orElseThrow(() -> new ResourceNotFoundException("Appointment not found"));

        logger.info("Hospital {} soft deleting appointment ID: {}. Reason: {}", hospitalId, LogSanitizer.clean(publicId), LogSanitizer.clean(reason));

        appointment.setIsActive(false);
        appointmentRepository.save(appointment);

        auditLogService.logAction(
                "APPOINTMENT_DELETED",
                "Appointment for " + appointment.getPatientName() + " with " + appointment.getDoctorName()
                        + " was deleted. Reason: " + (reason != null ? reason : "No reason provided"),
                securityHelper.getCurrentUserEmail(),
                hospitalId,
                "APPOINTMENT",
                publicId,
                reason);

        try {
            webSocketHandler.broadcast(hospitalId, "{\"type\":\"REFRESH_DATA\"}");
        } catch (Exception e) {
            // ignore
        }
    }

    /**
     * Update appointment status (SCHEDULED -> COMPLETED / CANCELLED)
     * 
     * @param id     Appointment ID
     * @param status New Status
     * @return Updated Appointment
     */
    public Appointment updateStatus(String publicId, String status, String reason) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null)
            throw new UnauthorizedException("Hospital ID not found");

        Optional<Appointment> apptOpt = appointmentRepository.findByPublicIdAndHospitalIdAndIsActiveTrue(publicId,
                hospitalId);

        if (apptOpt.isEmpty()) {
            // Fallback: Try to find by ID if the publicId string is numeric (legacy
            // support)
            try {
                Long id = Long.parseLong(publicId);
                apptOpt = appointmentRepository.findByIdAndHospitalIdAndIsActiveTrue(id, hospitalId);
            } catch (NumberFormatException e) {
                // Not a number, ignore
            }
        }

        Appointment appointment = apptOpt.orElseThrow(() -> new ResourceNotFoundException("Appointment not found"));

        // Basic validation
        if (!status.equals(STATUS_SCHEDULED) && !status.equals(STATUS_COMPLETED) && !status.equals(STATUS_CANCELLED)) {
            throw new IllegalArgumentException("Invalid status. Allowed: SCHEDULED, COMPLETED, CANCELLED");
        }

        String oldStatus = appointment.getStatus();
        appointment.setStatus(status);
        Appointment saved = appointmentRepository.save(appointment);

        // Trigger Billing if Completed
        if (STATUS_COMPLETED.equals(status) && !oldStatus.equals(STATUS_COMPLETED)) {
            try {
                billingService.autoGenerateOpdBill(saved);
            } catch (Exception e) {
                logger.error("Failed to auto-generate bill for appointment {}", LogSanitizer.clean(publicId), e);
            }
        }

        // Log status change if significant
        // Log status change if significant
        if (!oldStatus.equals(status)) {
            try {
                auditLogService.logAction(
                        "APPOINTMENT_STATUS_CHANGED",
                        "Status changed from " + oldStatus + " to " + status + ". Reason: "
                                + (reason != null ? reason : "No reason provided"),
                        securityHelper.getCurrentUserEmail(),
                        hospitalId,
                        "APPOINTMENT",
                        saved.getPublicId(),
                        reason);
            } catch (Exception e) {
                logger.warn("Failed to log audit for status change", e);
            }
        }

        try {
            webSocketHandler.broadcast(hospitalId, "{\"type\":\"REFRESH_DATA\"}");
        } catch (Exception e) {
            // ignore
        }

        return saved;
    }

    /**
     * Update appointment details (Status & Notes)
     * 
     * @param id     Appointment ID
     * @param status New Status
     * @param notes  New Notes
     * @return Updated Appointment
     */
    public Appointment updateDetails(String publicId, String status, String notes,
            java.time.LocalDate newDate, java.time.LocalTime newTime) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null)
            throw new UnauthorizedException("Hospital ID not found");

        Optional<Appointment> apptOpt = appointmentRepository.findByPublicIdAndHospitalIdAndIsActiveTrue(publicId,
                hospitalId);

        if (apptOpt.isEmpty()) {
            try {
                Long id = Long.parseLong(publicId);
                apptOpt = appointmentRepository.findByIdAndHospitalIdAndIsActiveTrue(id, hospitalId);
            } catch (NumberFormatException e) {
                // ignore
            }
        }

        Appointment appointment = apptOpt.orElseThrow(() -> new ResourceNotFoundException("Appointment not found"));

        String oldStatus = appointment.getStatus();
        if (status != null && !status.isEmpty()) {
            // Basic validation
            if (!status.equals(STATUS_SCHEDULED) && !status.equals(STATUS_COMPLETED) && !status.equals(STATUS_CANCELLED)) {
                throw new IllegalArgumentException("Invalid status. Allowed: SCHEDULED, COMPLETED, CANCELLED");
            }
            appointment.setStatus(status);
        }

        if (notes != null) {
            appointment.setNotes(notes);
        }

        reschedule(appointment, newDate, newTime, hospitalId);

        Appointment saved = appointmentRepository.save(appointment);
        if (saved == null) {
            throw new IllegalArgumentException("Failed to save appointment");
        }

        // Trigger Billing if Completed and previously wasn't
        if (STATUS_COMPLETED.equals(status) && !STATUS_COMPLETED.equals(oldStatus)) {
            try {
                billingService.autoGenerateOpdBill(saved);
            } catch (Exception e) {
                logger.error("Failed to auto-generate bill for appointment {}", LogSanitizer.clean(publicId), e);
            }
        }

        // Log Update
        try {
            auditLogService.logAction(
                    "APPOINTMENT_UPDATED",
                    "Appointment updated. Status: " + (status != null ? status : appointment.getStatus())
                            + (notes != null ? ". Notes updated." : ""),
                    securityHelper.getCurrentUserEmail(),
                    hospitalId,
                    "APPOINTMENT",
                    saved.getPublicId(),
                    null);
        } catch (Exception e) {
            logger.warn("Failed to log appointment update", e);
        }

        try {
            webSocketHandler.broadcast(hospitalId, "{\"type\":\"REFRESH_DATA\"}");
        } catch (Exception e) {
            // ignore
        }

        return saved;
    }

    /**
     * Get dashboard stats (Today, Pending, Total)
     */
    @Transactional(readOnly = true)
    public Map<String, Long> getDashboardStats() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null)
            throw new UnauthorizedException("Hospital ID not found");
        LocalDate today = LocalDate.now();

        long todayCount = appointmentRepository.countByHospitalIdAndIsActiveTrueAndAppointmentDate(hospitalId, today);
        long pendingCount = appointmentRepository.countByHospitalIdAndIsActiveTrueAndStatus(hospitalId, STATUS_SCHEDULED);
        long totalCount = appointmentRepository.countByHospitalIdAndIsActiveTrue(hospitalId);

        Map<String, Long> stats = new HashMap<>();
        stats.put("today", todayCount);
        stats.put("pending", pendingCount);
        stats.put("total", totalCount);

        return stats;
    }

    /**
     * Get appointments for the currently logged-in doctor with pagination, search,
     * and optional view filter
     */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<Appointment> getMyAppointments(String view, String search,
            org.springframework.data.domain.Pageable pageable) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            throw new UnauthorizedException("Hospital ID not found in context");
        }
        String email = securityHelper.getCurrentUserEmail();

        com.hms.entity.Doctor doctor = doctorRepository.findByEmailAndHospitalId(email, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor profile not found for current user"));

        return getAppointmentsByDoctorPaginated(doctor.getId(), view, search, pageable);
    }

    /**
     * Get paginated appointments for a specific doctor with search and view filter
     */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<Appointment> getAppointmentsByDoctorPaginated(Long doctorId,
            String view, String search, org.springframework.data.domain.Pageable pageable) {
        Long hospitalId = securityHelper.getCurrentHospitalId();

        if (hospitalId == null) {
            throw new UnauthorizedException("Hospital ID not found in context");
        }

        // Verify doctor belongs to this hospital and is active
        doctorRepository.findByIdAndHospitalIdAndIsActiveTrue(doctorId, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found in your hospital or is inactive"));

        org.springframework.data.domain.Page<Appointment> page;
        java.time.LocalDate today = java.time.LocalDate.now();

        if (view == null || view.isEmpty()) {
            // Default: All active appointments
            if (search != null && !search.isEmpty()) {
                page = appointmentRepository.searchAppointmentsByDoctor(doctorId, hospitalId, search, pageable);
            } else {
                page = appointmentRepository
                        .findByDoctorIdAndHospitalIdAndIsActiveTrueOrderByAppointmentDateDesc(doctorId, hospitalId,
                                pageable);
            }
        } else {
            switch (view.toLowerCase()) {
                case "today":
                    if (search != null && !search.isEmpty()) {
                        page = appointmentRepository
                                .searchAppointmentsByDoctorAndDate(doctorId, hospitalId, search, today, pageable);
                    } else {
                        page = appointmentRepository
                                .findByDoctorIdAndAppointmentDateAndIsActiveTrueOrderByAppointmentTimeAsc(doctorId,
                                        today, pageable);
                    }
                    break;
                case "upcoming":
                    if (search != null && !search.isEmpty()) {
                        page = appointmentRepository
                                .searchAppointmentsByDoctorAndDateAfter(doctorId, hospitalId, search, today, pageable);
                    } else {
                        page = appointmentRepository
                                .findByDoctorIdAndAppointmentDateGreaterThanEqualAndIsActiveTrueOrderByAppointmentDateAscAppointmentTimeAsc(
                                        doctorId, today, pageable);
                    }
                    break;
                case "history":
                    if (search != null && !search.isEmpty()) {
                        page = appointmentRepository
                                .searchAppointmentsHistoryByDoctor(doctorId, hospitalId, search, today, pageable);
                    } else {
                        page = appointmentRepository
                                .findByDoctorIdAndIsActiveTrueAndAppointmentDateBeforeOrDoctorIdAndIsActiveTrueAndStatusInOrderByAppointmentDateDescAppointmentTimeDesc(
                                        doctorId, today, doctorId, java.util.Arrays.asList(STATUS_COMPLETED, STATUS_CANCELLED),
                                        pageable);
                    }
                    break;
                default:
                    if (search != null && !search.isEmpty()) {
                        page = appointmentRepository.searchAppointmentsByDoctor(doctorId, hospitalId, search, pageable);
                    } else {
                        page = appointmentRepository
                                .findByDoctorIdAndHospitalIdAndIsActiveTrueOrderByAppointmentDateDesc(doctorId,
                                        hospitalId, pageable);
                    }
            }
        }

        // Populate names (using list from page)
        populateNames(page.getContent());

        return page;
    }
}
