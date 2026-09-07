package com.hms.service.hospital;
import com.hms.util.LogSanitizer;

import com.hms.exception.ResourceNotFoundException;

import com.hms.dto.CreateOpdRequest;
import com.hms.entity.*;
import com.hms.repository.*;
import com.hms.security.SecurityContextHelper;
import com.hms.security.HospitalWebSocketHandler;
import com.hms.service.AuditLogService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

@Service
public class OpdService {

    private static final Logger logger = LoggerFactory.getLogger(OpdService.class);

    private final OpdRepository opdRepository;
    private final QueueEntryRepository queueEntryRepository;
    private final PatientRepository patientRepository;
    private final DoctorRepository doctorRepository;
    private final UserRepository userRepository;
    private final MedicalRecordRepository medicalRecordRepository;

    private final SecurityContextHelper securityHelper;
    private final AuditLogService auditLogService;
    private final HospitalWebSocketHandler webSocketHandler;
    private final VitalSettingsService vitalSettingsService;

    // Field-injected (not in the constructor) to avoid changing every OpdService construction
    // site. Used only by the "payment first" flow. @Lazy guards against any init-order cycle.
    @org.springframework.beans.factory.annotation.Autowired @org.springframework.context.annotation.Lazy
    private BillingService billingService;
    @org.springframework.beans.factory.annotation.Autowired
    private com.hms.repository.HospitalSettingRepository hospitalSettingRepository;

    private static final com.fasterxml.jackson.databind.ObjectMapper VITALS_JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    public OpdService(OpdRepository opdRepository,
                      QueueEntryRepository queueEntryRepository,
                      PatientRepository patientRepository,
                      DoctorRepository doctorRepository,
                      UserRepository userRepository,
                      MedicalRecordRepository medicalRecordRepository,
                      SecurityContextHelper securityHelper,
                      AuditLogService auditLogService,
                      HospitalWebSocketHandler webSocketHandler,
                      VitalSettingsService vitalSettingsService) {
        this.opdRepository = opdRepository;
        this.queueEntryRepository = queueEntryRepository;
        this.patientRepository = patientRepository;
        this.doctorRepository = doctorRepository;
        this.userRepository = userRepository;
        this.medicalRecordRepository = medicalRecordRepository;
        this.securityHelper = securityHelper;
        this.auditLogService = auditLogService;
        this.webSocketHandler = webSocketHandler;
        this.vitalSettingsService = vitalSettingsService;
    }

    /** Keep only the hospital's enabled custom vitals, stored as a JSON object. */
    private String serializeCustomVitals(java.util.Map<String, String> submitted) {
        if (submitted == null || submitted.isEmpty()) return null;
        java.util.Set<String> allowed = vitalSettingsService.enabledCustomKeys();
        java.util.Map<String, String> kept = new java.util.LinkedHashMap<>();
        submitted.forEach((k, v) -> {
            if (allowed.contains(k) && v != null && !v.trim().isEmpty()) kept.put(k, v.trim());
        });
        if (kept.isEmpty()) return null;
        try {
            return VITALS_JSON.writeValueAsString(kept);
        } catch (Exception e) {
            logger.warn("Could not serialize custom vitals; storing none", e);
            return null;
        }
    }

    @Transactional
    public Opd createOpd(CreateOpdRequest req) {
        // Validate Vitals
        if (req.getBp() != null && !req.getBp().trim().isEmpty()) {
            String bp = req.getBp().trim();
            if (!bp.matches("^\\d{2,3}\\s*/\\s*\\d{2,3}$")) {
                throw new IllegalArgumentException("Blood pressure must be in format Systolic/Diastolic, e.g., 120/80");
            }
            String[] parts = bp.split("/");
            int systolic = Integer.parseInt(parts[0].trim());
            int diastolic = Integer.parseInt(parts[1].trim());
            if (systolic <= diastolic) {
                throw new IllegalArgumentException("Systolic blood pressure must be greater than diastolic blood pressure");
            }
        }
        if (req.getTemperature() != null && req.getTemperature() < 0) {
            throw new IllegalArgumentException("Temperature cannot be negative");
        }
        if (req.getPulse() != null && req.getPulse() < 0) {
            throw new IllegalArgumentException("Pulse cannot be negative");
        }
        if (req.getWeight() != null && req.getWeight() < 0) {
            throw new IllegalArgumentException("Weight cannot be negative");
        }
        if (req.getSpo2() != null && req.getSpo2() < 0) {
            throw new IllegalArgumentException("SpO2 cannot be negative");
        }

        Opd opd = new Opd();
        Patient patient;
        if (req.getPatientId() != null && !req.getPatientId().trim().isEmpty()) {
            String pid = req.getPatientId().trim();
            Long hospitalId = securityHelper.getCurrentHospitalId();
            if (pid.matches("^\\d+$")) {
                Long numericId = Long.parseLong(pid);
                patient = patientRepository.findByIdAndHospitalIdAndIsActiveTrue(numericId, hospitalId)
                        .orElseThrow(() -> new IllegalArgumentException("Invalid patient id"));
            } else {
                patient = patientRepository.findByPublicIdAndHospitalIdAndIsActiveTrue(pid, hospitalId)
                        .orElseThrow(() -> new IllegalArgumentException("Invalid patient id"));
            }
        } else {
            throw new IllegalArgumentException("Patient ID is required");
        }
        opd.setPatient(patient);

        // Set receptionist from authenticated user (do not trust client-supplied receptionistId)
        try {
            Long receptionistId = securityHelper.getCurrentUserId();
            if (receptionistId != null) {
                userRepository.findById(receptionistId).ifPresent(opd::setReceptionist);
            }
        } catch (Exception e) {
            logger.warn("Could not resolve current user as receptionist; continuing without receptionist assignment", e);
        }

        if (req.getDoctorId() != null && !req.getDoctorId().trim().isEmpty()) {
            String docIdStr = req.getDoctorId().trim();
            java.util.Optional<Doctor> docOpt = java.util.Optional.empty();
            Long hospitalId = securityHelper.getCurrentHospitalId();
            if (docIdStr.matches("^\\d+$")) {
                Long numericId = Long.parseLong(docIdStr);
                docOpt = doctorRepository.findByIdOrUserId(numericId, userRepository);
            } else {
                docOpt = doctorRepository.findByPublicIdAndHospitalIdAndIsActiveTrue(docIdStr, hospitalId);
            }
            // findByIdOrUserId is not tenant-scoped: without this filter a numeric doctorId
            // would attach another hospital's doctor to this hospital's OPD (and print that
            // doctor on the case paper). Drop any doctor that is not in the caller's hospital.
            docOpt.filter(d -> hospitalId != null && hospitalId.equals(d.getHospitalId()))
                    .ifPresent(opd::setDoctor);
        }

        // Only vitals the hospital has switched ON are captured. Disabled built-ins are
        // dropped server-side, and submitted custom vitals are filtered to enabled keys.
        java.util.Set<String> onBuiltIns = vitalSettingsService.enabledBuiltInKeys();
        opd.setBp(onBuiltIns.contains("BP") ? req.getBp() : null);
        opd.setTemperature(onBuiltIns.contains("TEMPERATURE") ? req.getTemperature() : null);
        opd.setPulse(onBuiltIns.contains("PULSE") ? req.getPulse() : null);
        opd.setWeight(onBuiltIns.contains("WEIGHT") ? req.getWeight() : null);
        opd.setHeight(onBuiltIns.contains("HEIGHT") ? req.getHeight() : null);
        opd.setSpo2(onBuiltIns.contains("SPO2") ? req.getSpo2() : null);
        opd.setCustomVitals(serializeCustomVitals(req.getCustomVitals()));
        opd.setProblem(req.getProblem());
        if (req.getVisitType() != null) {
            try {
                opd.setVisitType(Opd.VisitType.valueOf(req.getVisitType().toUpperCase()));
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid visit type '{}'; defaulting to NEW", LogSanitizer.clean(req.getVisitType()));
                opd.setVisitType(Opd.VisitType.NEW);
            }
        }

        Opd saved = opdRepository.save(opd);
        // Set sequential caseId using auto-increment id: OPD-1, OPD-2, OPD-3...
        saved.setCaseId("OPD-" + saved.getId());
        saved = opdRepository.save(saved);

        // Create queue entry
        if (saved.getDoctor() != null) {
            QueueEntry entry = new QueueEntry();
            entry.setOpd(saved);
            entry.setDoctor(saved.getDoctor());
            queueEntryRepository.save(entry);
        }

        // "Payment first": bill the consultation + case-paper fee and mark it paid right now,
        // at OPD entry. Best-effort — a billing hiccup must not block creating the OPD case.
        try {
            Long billHospitalId = saved.getPatient() != null ? saved.getPatient().getHospitalId() : null;
            boolean payFirst = billHospitalId != null && hospitalSettingRepository.findByHospital_Id(billHospitalId)
                    .map(s -> "FIRST".equalsIgnoreCase(s.getBillPaymentTiming()))
                    .orElse(false);
            if (payFirst) {
                Long docId = saved.getDoctor() != null ? saved.getDoctor().getId() : null;
                billingService.createPaidOpdBillAtEntry(saved.getId(), saved.getPatient().getId(), docId,
                        req.getPaymentMethod(), req.getPaymentReference());
            }
        } catch (Exception e) {
            logger.warn("Payment-first OPD billing failed for OPD {}", saved.getId(), e);
        }

        // Audit log for OPD creation
        try {
            String performedBy = resolveCurrentEmailQuietly();
            Long auditHospitalId = resolveCurrentHospitalIdQuietly();

            String details = "OPD " + (saved.getCaseId() != null ? saved.getCaseId() : saved.getId())
                    + " created for patient " + (saved.getPatient() != null ? saved.getPatient().getId() : "-");

            auditLogService.logAction(
                    "OPD_CREATED",
                    details,
                    performedBy,
                    auditHospitalId,
                    "OPD",
                    saved.getCaseId() != null ? saved.getCaseId() : (saved.getId() != null ? saved.getId().toString() : null),
                    null
            );
        } catch (Exception e) {
            logger.warn("Failed to write audit log for OPD creation", e);
        }

        // Broadcast real-time update to all connected clients in this hospital
        try {
            Long broadcastHospitalId = securityHelper.getCurrentHospitalId();
            if (broadcastHospitalId != null) {
                webSocketHandler.broadcast(broadcastHospitalId, "{\"type\":\"REFRESH_DATA\"}");
            }
        } catch (Exception e) {
            logger.warn("Failed to broadcast WebSocket refresh after OPD creation", e);
        }

        return saved;
    }

    public java.util.List<QueueEntry> getQueueForDoctor(Long doctorId) {
        // findQueueForDoctorToday is keyed only on doctorId. Doctor ids are sequential and
        // global, so without this ownership check one hospital could read another hospital's
        // live OPD queue (its waiting patients) by enumerating doctor ids.
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null
                || doctorRepository.findByIdAndHospitalIdAndIsActiveTrue(doctorId, hospitalId).isEmpty()) {
            throw new ResourceNotFoundException("Doctor not found");
        }
        // Reading the queue used to create today's follow-up encounters as a side effect. It no
        // longer does: a follow-up falling due is not evidence the patient came in, and two
        // people opening this screen at once produced two encounters for the same patient.
        // Outstanding follow-ups are read from /hospital/follow-ups, which writes nothing.
        return queueEntryRepository.findQueueForDoctorToday(doctorId);
    }

    public Opd getOpdById(Long id) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            return null;
        }
        return opdRepository.findByIdAndHospitalIdWithPatientAndDoctor(id, hospitalId).orElse(null);
    }

    public org.springframework.data.domain.Page<Opd> getOpds(String search, String dateStr, com.hms.entity.Opd.Status status, org.springframework.data.domain.Pageable pageable) {
        Long hospitalId = null;
        try {
            hospitalId = securityHelper.getCurrentHospitalId();
        } catch (Exception e) {
            logger.warn("Could not resolve hospital ID for OPD listing", e);
        }

        if (hospitalId == null) {
            // Fallback: return empty page
            return new org.springframework.data.domain.PageImpl<>(java.util.List.of());
        }

        java.time.LocalDateTime startDate = null;
        java.time.LocalDateTime endDate = null;
        if (dateStr != null && !dateStr.trim().isEmpty()) {
            try {
                java.time.LocalDate date = java.time.LocalDate.parse(dateStr.trim());
                startDate = date.atStartOfDay();
                endDate = date.atTime(23, 59, 59, 999999999);
            } catch (Exception e) {
                logger.warn("Invalid date filter '{}' ignored for OPD listing", LogSanitizer.clean(dateStr));
            }
        }

        String searchVal = (search != null && !search.trim().isEmpty()) ? search.trim() : null;

        return opdRepository.searchByHospitalAndDateRange(hospitalId, searchVal, startDate, endDate, status, pageable);
    }

    /**
     * Pending IPD requests for the caller's hospital: the doctor recommended admission and
     * reception has not yet converted the OPD (status still != IN_IPD).
     *
     * <p>Unlike {@link #getOpds}, a missing hospital is an error rather than an empty result.
     * These feed a count badge, and silently reporting zero pending admissions is indistinguishable
     * from genuinely having none -- the failure has to reach the caller.
     */
    public long getPendingIpdRequestCount() {
        return opdRepository.countPendingIpdRequests(requireHospitalId(), com.hms.entity.Opd.Status.IN_IPD);
    }

    public org.springframework.data.domain.Page<Opd> getPendingIpdRequests(
            org.springframework.data.domain.Pageable pageable) {
        return opdRepository.findPendingIpdRequests(requireHospitalId(), com.hms.entity.Opd.Status.IN_IPD, pageable);
    }

    private Long requireHospitalId() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            throw new com.hms.exception.UnauthorizedException("Hospital ID not found in context");
        }
        return hospitalId;
    }

    public org.springframework.data.domain.Page<Opd> getOpds(String search, String dateStr, org.springframework.data.domain.Pageable pageable) {
        return getOpds(search, dateStr, null, pageable);
    }

    public java.util.List<QueueEntry> getHospitalQueue() {
        Long hospitalId = null;
        try {
            hospitalId = securityHelper.getCurrentHospitalId();
        } catch (Exception e) {
            logger.warn("Could not resolve hospital ID for queue listing", e);
        }
        if (hospitalId == null) return java.util.List.of();
        // See getQueueForDoctorToday: this read no longer creates follow-up encounters.
        return queueEntryRepository.findQueueForHospitalToday(hospitalId);
    }

@org.springframework.transaction.annotation.Transactional
public java.util.Optional<java.util.Map<String, String>> getPatientNameAndCustomIdAndPublicId(Long patientId) {
        return patientRepository.findById(patientId).map(p -> {
            java.util.Map<String, String> map = new java.util.HashMap<>();
            map.put("name", p.getName());
            map.put("customId", p.getCustomId());
            map.put("publicId", p.getPublicId());
            return map;
        });
    }

    public java.util.Optional<String> getDoctorName(Long doctorId) {
        return doctorRepository.findById(doctorId).map(com.hms.entity.Doctor::getName);
    }

    /** Current user's email for audit, or null if it can't be resolved (best-effort). */
    private String resolveCurrentEmailQuietly() {
        try {
            return securityHelper.getCurrentUserEmail();
        } catch (Exception e) {
            logger.debug("Could not resolve current user email for audit log", e);
            return null;
        }
    }

    /** Current hospital id for audit, or null if it can't be resolved (best-effort). */
    private Long resolveCurrentHospitalIdQuietly() {
        try {
            return securityHelper.getCurrentHospitalId();
        } catch (Exception e) {
            logger.debug("Could not resolve hospital ID for audit log", e);
            return null;
        }
    }
}
