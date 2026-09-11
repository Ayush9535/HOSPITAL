package com.hms.service.hospital;

import com.hms.exception.ResourceNotFoundException;

import com.hms.dto.NurseAssignmentDTO;
import com.hms.entity.IpdAdmission;
import com.hms.entity.PatientNurseAssignment;
import com.hms.entity.User;
import com.hms.exception.UnauthorizedException;
import com.hms.repository.DoctorRepository;
import com.hms.repository.IpdAdmissionRepository;
import com.hms.repository.PatientNurseAssignmentRepository;
import com.hms.repository.PatientRepository;
import com.hms.repository.UserRepository;
import com.hms.security.HospitalWebSocketHandler;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * NurseAssignmentService - Hospital Admin assigns nurses to IPD admissions
 * (Phase 1 Nurse module). Enforces a single active assignment per admission;
 * reassignment closes the prior active row and opens a new one (history kept).
 */
@Service
public class NurseAssignmentService {

    private static final Logger logger = LoggerFactory.getLogger(NurseAssignmentService.class);

    // An admission is "active" (assignable) while not discharged.
    private static final List<String> ACTIVE_STATUSES = List.of("ADMITTED", "DISCHARGE_PLANNED");

    @Autowired private PatientNurseAssignmentRepository assignmentRepository;
    @Autowired private IpdAdmissionRepository ipdAdmissionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private DoctorRepository doctorRepository;
    @Autowired private SecurityContextHelper securityHelper;
    @Autowired private AuditLogService auditLogService;
    @Autowired private HospitalWebSocketHandler webSocketHandler;
    @Autowired private NotificationService notificationService;
    @Autowired private com.hms.repository.NurseProfileRepository nurseProfileRepository;

    /**
     * Assign a nurse to an admission. Closes any existing active assignment for
     * that admission first (enforcing the single-active invariant).
     */
    @Transactional
    public PatientNurseAssignment assignNurse(Long ipdAdmissionId, Long nurseUserId, String notes) {
        Long hospitalId = requireHospitalId();
        IpdAdmission admission = requireActiveAdmission(ipdAdmissionId, hospitalId);
        requireNurse(nurseUserId, hospitalId);

        // Close current active assignment (if any) to preserve the single-active invariant.
        closeActiveAssignment(ipdAdmissionId);

        PatientNurseAssignment a = new PatientNurseAssignment();
        a.setHospitalId(hospitalId);
        a.setIpdAdmissionId(ipdAdmissionId);
        a.setPatientId(admission.getPatientId());
        a.setNurseUserId(nurseUserId);
        a.setAssignedByUserId(securityHelper.getCurrentUserId());
        a.setAssignedAt(LocalDateTime.now());
        a.setNotes(notes);
        a.setIsActive(true);
        PatientNurseAssignment saved = assignmentRepository.save(a);

        try {
            com.hms.entity.Patient patient = patientRepository.findById(admission.getPatientId()).orElse(null);
            String patientName = patient != null ? patient.getName() : "Unknown Patient";
            notificationService.create(
                nurseUserId,
                hospitalId,
                "ASSIGNMENT",
                "New Patient Assignment",
                "You have been assigned to patient " + patientName + " (IPD No: " + admission.getIpdNumber() + ")",
                "PATIENT_NURSE_ASSIGNMENT",
                saved.getId()
            );
        } catch (Exception e) {
            logger.error("Failed to trigger assignment notification: {}", e.getMessage(), e);
        }

        audit("NURSE_ASSIGNED", "Assigned nurse " + nurseUserId + " to IPD " + admission.getIpdNumber(),
                hospitalId, ipdAdmissionId);
        broadcastRefresh(hospitalId);
        return saved;
    }

    /**
     * Auto-assign a newly admitted patient to the least-loaded available on-shift
     * nurse in the admission's ward. Picks the nurse with the fewest active
     * patients; ties are broken arbitrarily. No-op (returns null) if the ward has
     * no available nurse. Best-effort — callers wrap this so a failure never
     * blocks the admission itself.
     */
    @Transactional
    public PatientNurseAssignment autoAssignForAdmission(Long ipdAdmissionId, Long wardId,
            Long patientId, Long hospitalId, Long assignedByUserId) {
        if (wardId == null) {
            logger.info("Auto-assign skipped: admission {} has no ward", ipdAdmissionId);
            return null;
        }
        List<com.hms.entity.NurseProfile> candidates =
                nurseProfileRepository.findByWardIdAndIsActiveTrueAndOnShiftTrue(wardId).stream()
                        .filter(p -> hospitalId.equals(p.getHospitalId()) && p.getUserId() != null)
                        .collect(java.util.stream.Collectors.toList());
        if (candidates.isEmpty()) {
            logger.info("Auto-assign skipped: no available on-shift nurse in ward {} for admission {}",
                    wardId, ipdAdmissionId);
            return null;
        }

        // Least-loaded: fewest active assignments wins; ties → first encountered.
        com.hms.entity.NurseProfile chosen = candidates.stream()
                .min(java.util.Comparator.comparingLong(
                        p -> assignmentRepository.countByNurseUserIdAndIsActiveTrue(p.getUserId())))
                .orElse(candidates.get(0));

        // Preserve the single-active invariant (defensive; a fresh admission has none).
        closeActiveAssignment(ipdAdmissionId);

        PatientNurseAssignment a = new PatientNurseAssignment();
        a.setHospitalId(hospitalId);
        a.setIpdAdmissionId(ipdAdmissionId);
        a.setPatientId(patientId);
        a.setNurseUserId(chosen.getUserId());
        a.setAssignedByUserId(assignedByUserId);
        a.setAssignedAt(LocalDateTime.now());
        a.setNotes("Auto-assigned on admission (least-loaded nurse in ward)");
        a.setIsActive(true);
        PatientNurseAssignment saved = assignmentRepository.save(a);

        try {
            com.hms.entity.Patient patient = patientRepository.findById(patientId).orElse(null);
            String patientName = patient != null ? patient.getName() : "a new patient";
            notificationService.create(
                    chosen.getUserId(), hospitalId, "ASSIGNMENT",
                    "New Patient Assignment",
                    "You have been auto-assigned to patient " + patientName + " for admission.",
                    "PATIENT_NURSE_ASSIGNMENT", saved.getId());
        } catch (Exception e) {
            logger.error("Failed to trigger auto-assignment notification: {}", e.getMessage(), e);
        }

        audit("NURSE_AUTO_ASSIGNED", "Auto-assigned nurse " + chosen.getUserId()
                + " to IPD admission " + ipdAdmissionId, hospitalId, ipdAdmissionId);
        broadcastRefresh(hospitalId);
        return saved;
    }

    /**
     * Reassign the nurse for the admission behind an existing assignment.
     */
    @Transactional
    public PatientNurseAssignment reassignNurse(String assignmentPublicId, Long nurseUserId, String notes) {
        Long hospitalId = requireHospitalId();
        PatientNurseAssignment existing = assignmentRepository.findByPublicId(assignmentPublicId)
                .orElseThrow(() -> new ResourceNotFoundException("Assignment not found"));
        requireSameHospital(existing.getHospitalId(), hospitalId);
        return assignNurse(existing.getIpdAdmissionId(), nurseUserId, notes);
    }

    /**
     * Unassign (close) an assignment without opening a new one.
     */
    @Transactional
    public void unassign(String assignmentPublicId) {
        Long hospitalId = requireHospitalId();
        PatientNurseAssignment existing = assignmentRepository.findByPublicId(assignmentPublicId)
                .orElseThrow(() -> new ResourceNotFoundException("Assignment not found"));
        requireSameHospital(existing.getHospitalId(), hospitalId);

        existing.setIsActive(false);
        existing.setUnassignedAt(LocalDateTime.now());
        assignmentRepository.save(existing);

        audit("NURSE_UNASSIGNED", "Unassigned nurse from IPD admission " + existing.getIpdAdmissionId(),
                hospitalId, existing.getIpdAdmissionId());
        broadcastRefresh(hospitalId);
    }

    /**
     * Full assignment history for an admission (newest first).
     */
    public List<PatientNurseAssignment> getHistoryForAdmission(Long ipdAdmissionId) {
        Long hospitalId = requireHospitalId();
        List<PatientNurseAssignment> list = assignmentRepository.findByIpdAdmissionIdOrderByAssignedAtDesc(ipdAdmissionId);
        for (PatientNurseAssignment a : list) {
            requireSameHospital(a.getHospitalId(), hospitalId);
        }
        return list;
    }

    /**
     * Admin overview: every active admission with its current nurse (or none).
     */
    public List<NurseAssignmentDTO> getAssignmentOverview() {
        Long hospitalId = requireHospitalId();
        List<IpdAdmission> admissions = ipdAdmissionRepository.findByHospitalIdAndStatusIn(hospitalId, ACTIVE_STATUSES);
        List<NurseAssignmentDTO> result = new ArrayList<>();
        for (IpdAdmission ipd : admissions) {
            NurseAssignmentDTO dto = new NurseAssignmentDTO();
            dto.setIpdAdmissionId(ipd.getId());
            dto.setIpdNumber(ipd.getIpdNumber());
            dto.setAdmissionDateTime(ipd.getAdmissionDatetime());
            dto.setStatus(ipd.getStatus());
            patientRepository.findById(ipd.getPatientId()).ifPresent(p -> dto.setPatientName(p.getName()));
            doctorRepository.findById(ipd.getDoctorId()).ifPresent(d -> dto.setDoctorName(d.getName()));

            Optional<PatientNurseAssignment> active = assignmentRepository.findByIpdAdmissionIdAndIsActiveTrue(ipd.getId());
            active.ifPresent(a -> {
                dto.setAssignmentPublicId(a.getPublicId());
                dto.setNurseUserId(a.getNurseUserId());
                dto.setAssignedAt(a.getAssignedAt());
                userRepository.findById(a.getNurseUserId()).ifPresent(u -> dto.setNurseName(u.getName()));
            });
            result.add(dto);
        }
        return result;
    }

    /**
     * Close all active assignments for an admission. Called by the discharge
     * flow; safe to call when there are none.
     */
    @Transactional
    public void closeActiveAssignmentsForAdmission(Long ipdAdmissionId) {
        closeActiveAssignment(ipdAdmissionId);
    }

    // --- helpers ---

    private void closeActiveAssignment(Long ipdAdmissionId) {
        assignmentRepository.findByIpdAdmissionIdAndIsActiveTrue(ipdAdmissionId).ifPresent(a -> {
            a.setIsActive(false);
            a.setUnassignedAt(LocalDateTime.now());
            assignmentRepository.save(a);
        });
    }

    private Long requireHospitalId() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            throw new UnauthorizedException("Hospital ID not found in context");
        }
        return hospitalId;
    }

    private IpdAdmission requireActiveAdmission(Long ipdAdmissionId, Long hospitalId) {
        IpdAdmission admission = ipdAdmissionRepository.findById(ipdAdmissionId)
                .orElseThrow(() -> new IllegalArgumentException("IPD admission not found"));
        requireSameHospital(admission.getHospitalId(), hospitalId);
        if (admission.getStatus() == null || !ACTIVE_STATUSES.contains(admission.getStatus().toUpperCase())) {
            throw new IllegalArgumentException("Cannot assign a nurse to a discharged admission");
        }
        return admission;
    }

    private void requireNurse(Long nurseUserId, Long hospitalId) {
        User nurse = userRepository.findById(nurseUserId)
                .orElseThrow(() -> new IllegalArgumentException("Nurse not found"));
        if (!hospitalId.equals(nurse.getHospitalId())) {
            throw new UnauthorizedException("Nurse belongs to another hospital");
        }
        if (!"NURSE".equals(nurse.getRole()) || Boolean.FALSE.equals(nurse.getIsActive())) {
            throw new IllegalArgumentException("Target user is not an active nurse");
        }
    }

    private void requireSameHospital(Long entityHospitalId, Long hospitalId) {
        if (entityHospitalId == null || !entityHospitalId.equals(hospitalId)) {
            throw new UnauthorizedException("Access denied: resource belongs to another hospital");
        }
    }

    private void broadcastRefresh(Long hospitalId) {
        try {
            webSocketHandler.broadcast(hospitalId, "{\"type\":\"REFRESH_DATA\"}");
        } catch (Exception e) {
            logger.warn("Failed to broadcast WebSocket refresh after nurse assignment change", e);
        }
    }

    private void audit(String action, String details, Long hospitalId, Long admissionId) {
        try {
            auditLogService.logAction(action, details, securityHelper.getCurrentUserEmail(), hospitalId,
                    "IPD", admissionId != null ? admissionId.toString() : null, null);
        } catch (Exception e) {
            logger.warn("Failed to write audit log for {}", action, e);
        }
    }
}
