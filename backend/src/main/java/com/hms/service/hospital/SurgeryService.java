package com.hms.service.hospital;

import com.hms.dto.CreateSurgeryRequest;
import com.hms.dto.ScheduleSurgeryRequest;
import com.hms.dto.SurgeryView;
import com.hms.entity.*;
import com.hms.exception.ConflictException;
import com.hms.exception.ResourceNotFoundException;
import com.hms.exception.UnauthorizedException;
import com.hms.repository.*;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * SurgeryService - OT case lifecycle (OT module, Phase 2). Doctors create
 * requests; reception schedules/starts/completes. HOSPITAL tenant only, OT-gated
 * at the controller. One active (non-terminal) surgery per IPD admission.
 */
@Service
public class SurgeryService {
    private static final String SURGERY_ENTITY = "SURGERY";


    private static final Logger logger = LoggerFactory.getLogger(SurgeryService.class);
    private static final Set<String> ACTIVE_STATUSES =
            Set.of(Surgery.REQUESTED, Surgery.SCHEDULED, Surgery.IN_PROGRESS);

    @Autowired private SurgeryRepository surgeryRepository;
    @Autowired private IpdAdmissionRepository ipdAdmissionRepository;
    @Autowired private DoctorRepository doctorRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private WardRepository wardRepository;
    @Autowired private BedRepository bedRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private PatientNurseAssignmentRepository assignmentRepository;
    @Autowired private NotificationService notificationService;
    @Autowired private SecurityContextHelper securityHelper;
    @Autowired private AuditLogService auditLogService;
    @Autowired private com.hms.service.RealtimeNotifier notifier;
    // Nursing Mgmt Phase C2: all bed status writes go through the audited service.
    @Autowired private BedStatusService bedStatusService;

    // Nothing in this class writes surgeries.status directly: every move goes through the
    // state machine, which validates it against the transition table and records an
    // append-only audit row.
    @Autowired private com.hms.service.hospital.ot.SurgeryStateMachine stateMachine;
    @Autowired private com.hms.service.hospital.ot.OtRoomService otRoomService;
    @Autowired private com.hms.service.hospital.ot.OtSchedulingService otSchedulingService;
    @Autowired private com.hms.repository.OtRoomRepository otRoomRepository;
    @Autowired private com.hms.service.hospital.ot.OtPolicyService otPolicyService;
    @Autowired private com.hms.service.hospital.ot.SurgeryExecutionService surgeryExecutionService;
    @Autowired private com.hms.service.hospital.ot.PreOpSafetyService preOpSafetyService;
    @Autowired private com.hms.repository.OtRoomOccupancyRepository occupancyRepository;

    // ---------- Doctor: create request ----------

    @Transactional
    public Surgery createRequest(CreateSurgeryRequest req) {
        Long hospitalId = requireHospitalId();
        if (req.getProcedureName() == null || req.getProcedureName().trim().isEmpty()) {
            throw new IllegalArgumentException("Procedure name is required");
        }
        if (req.getIpdAdmissionId() == null && req.getPatientId() == null) {
            throw new IllegalArgumentException("ipdAdmissionId or patientId is required");
        }

        // An inpatient procedure hangs off an admission; a day-care procedure (cataract,
        // endoscopy, minor orthopaedics) is anchored on the patient alone.
        IpdAdmission admission = req.getIpdAdmissionId() != null
                ? requireAdmission(req.getIpdAdmissionId(), hospitalId) : null;
        Long patientId = admission != null ? admission.getPatientId() : req.getPatientId();

        // Scope the "already has an active surgery" check to the PATIENT so it holds for
        // day-care too; admission-scoped, it would silently pass for a second day-care case.
        if (!surgeryRepository.findByPatientIdAndStatusIn(patientId, ACTIVE_STATUSES).isEmpty()) {
            throw new IllegalArgumentException("This patient already has an active surgery");
        }

        Long doctorId = doctorRepository
                .findByEmailAndHospitalId(securityHelper.getCurrentUserEmail(), hospitalId)
                .map(Doctor::getId).orElse(null);

        Surgery s = new Surgery();
        s.setHospitalId(hospitalId);
        s.setIpdAdmissionId(admission != null ? admission.getId() : null);
        s.setEncounterType(admission != null ? Surgery.ENCOUNTER_IPD : Surgery.ENCOUNTER_DAY_CARE);
        s.setPatientId(patientId);
        s.setProcedureName(req.getProcedureName().trim());
        s.setClinicalNotes(trim(req.getClinicalNotes()));
        s.setPriority("EMERGENCY".equalsIgnoreCase(req.getPriority()) ? "EMERGENCY" : "ELECTIVE");
        s.setPreferredDate(req.getPreferredDate());
        s.setRequestedByDoctorId(doctorId);
        s.setRequestedByUserId(securityHelper.getCurrentUserId());
        s.setRequestedAt(LocalDateTime.now());
        // Status is not set here: Surgery.prePersist defaults it to REQUESTED, and every
        // subsequent change belongs to the state machine, which is its only writer.
        Surgery saved = surgeryRepository.save(s);

        stateMachine.recordCreation(saved);

        String detail = admission != null
                ? "Surgery requested for IPD " + admission.getIpdNumber()
                : "Day-care surgery requested for patient " + patientId;
        audit("SURGERY_REQUESTED", detail, hospitalId, admission != null ? admission.getId() : saved.getId());
        return saved;
    }

    // ---------- Approve (used when APPROVAL_MODE requires a human) ----------

    /**
     * A human approval, for hospitals whose APPROVAL_MODE is SINGLE or DUAL. When the policy
     * is NONE, scheduling auto-approves and this is never needed.
     */
    @Transactional
    public Surgery approve(String publicId) {
        Long hospitalId = requireHospitalId();
        Surgery s = requireSurgeryForUpdate(publicId, hospitalId);
        Surgery saved = stateMachine.transition(s, com.hms.entity.SurgeryStatus.APPROVED, null, null, null);
        audit("SURGERY_APPROVED", "Surgery approved", hospitalId, saved.getIpdAdmissionId());
        return saved;
    }

    // ---------- Reception: schedule ----------

    @Transactional
    public Surgery schedule(String publicId, ScheduleSurgeryRequest req) {
        Long hospitalId = requireHospitalId();
        Surgery s = requireSurgeryForUpdate(publicId, hospitalId);
        assertExpectedVersion(s, req == null ? null : req.getExpectedVersion());
        com.hms.entity.SurgeryStatus current = com.hms.entity.SurgeryStatus.of(s.getStatus());
        boolean isReschedule = current == com.hms.entity.SurgeryStatus.SCHEDULED;
        if (current != com.hms.entity.SurgeryStatus.REQUESTED
                && current != com.hms.entity.SurgeryStatus.APPROVED
                && current != com.hms.entity.SurgeryStatus.POSTPONED
                && !isReschedule) {
            throw new IllegalArgumentException("Only a requested, approved or scheduled surgery can be scheduled");
        }
        if (req.getScheduledAt() == null || (req.getOtRoomId() == null && req.getOtWardId() == null)) {
            throw new IllegalArgumentException("Date/time and a theatre are required");
        }

        // Any active doctor may be assigned as the operating surgeon; reception decides.
        // If no doctor id is given ("Other"), a free-text operator name is required
        // instead (e.g. external / anaesthetist-led case).
        Doctor surgeon = null;
        String surgeonDisplayName;
        if (req.getSurgeonDoctorId() != null) {
            surgeon = doctorRepository.findByIdAndHospitalIdAndIsActiveTrue(req.getSurgeonDoctorId(), hospitalId)
                    .orElseThrow(() -> new IllegalArgumentException("Doctor not found"));
            surgeonDisplayName = surgeon.getName();
        } else {
            if (req.getSurgeonName() == null || req.getSurgeonName().trim().isEmpty()) {
                throw new IllegalArgumentException("Select a doctor, or choose Other and enter the operator's name");
            }
            surgeonDisplayName = req.getSurgeonName().trim();
        }
        // The theatre. A room is preferred; a legacy ward id resolves to the room migrated
        // from it, and only falls back to the old ward check when no room exists yet.
        com.hms.entity.OtRoom room = null;
        if (req.getOtRoomId() != null) {
            room = otRoomService.requireRoomById(req.getOtRoomId(), hospitalId);
        } else if (req.getOtWardId() != null) {
            room = otRoomService.findBySourceWard(hospitalId, req.getOtWardId());
        }

        Ward ward = null;
        if (req.getOtWardId() != null) {
            ward = wardRepository.findById(req.getOtWardId())
                    .orElseThrow(() -> new IllegalArgumentException("OT ward not found"));
            if (!hospitalId.equals(ward.getHospitalId())) {
                throw new UnauthorizedException("Access denied: ward belongs to another hospital");
            }
        }

        s.setSurgeonDoctorId(surgeon != null ? surgeon.getId() : null);
        if (req.getEstimatedDurationMinutes() != null) {
            s.setEstimatedDurationMinutes(req.getEstimatedDurationMinutes());
        }

        final Long thisSurgeryId = s.getId();
        if (room != null) {
            // Lock the theatre for the rest of the transaction, then check for a clash:
            // interval overlap cannot be a unique index, and a read-then-write check races.
            com.hms.entity.OtRoom locked = otSchedulingService.lockRoom(room.getId());
            otSchedulingService.assertSlotIsFree(hospitalId, locked, s, req.getScheduledAt());
            s.setOtRoomId(locked.getId());
            s.setOtWardId(locked.getSourceWardId() != null ? locked.getSourceWardId() : req.getOtWardId());
        } else {
            if (ward == null) throw new IllegalArgumentException("A theatre is required");
            // Legacy path: the ward holds one case at a time. A reschedule of the case that
            // already holds the theatre is not a clash with itself.
            boolean otBusy = java.util.stream.Stream.concat(
                            surgeryRepository.findByOtWardIdAndStatus(ward.getWardId(), Surgery.SCHEDULED).stream(),
                            surgeryRepository.findByOtWardIdAndStatus(ward.getWardId(), Surgery.IN_PROGRESS).stream())
                    .anyMatch(other -> !other.getId().equals(thisSurgeryId));
            if (otBusy) {
                throw new IllegalArgumentException("That OT ward already has a scheduled or ongoing surgery");
            }
            s.setOtWardId(ward.getWardId());
        }

        java.time.LocalDateTime previousSlot = s.getScheduledAt();

        s.setSurgeonName(surgeonDisplayName);
        s.setAnaesthetistName(trim(req.getAnaesthetistName()));
        s.setScheduledAt(req.getScheduledAt());
        s.setScheduledByUserId(securityHelper.getCurrentUserId());

        // Whether a human must approve before scheduling is a hospital policy, resolved for
        // this case's priority (so an emergency can waive it). APPROVAL_MODE=NONE means the
        // system approves and the SYSTEM row keeps the audit honest; SINGLE/DUAL means a
        // REQUESTED case must already have been approved by someone holding OT_APPROVE.
        String approvalMode = otPolicyService.resolve(
                hospitalId, com.hms.service.hospital.ot.OtPolicies.APPROVAL_MODE, s.getPriority());
        if (current == com.hms.entity.SurgeryStatus.REQUESTED || current == com.hms.entity.SurgeryStatus.POSTPONED) {
            if ("NONE".equals(approvalMode)) {
                s = stateMachine.autoTransition(s, com.hms.entity.SurgeryStatus.APPROVED,
                        com.hms.entity.SurgeryStateTransition.REASON_AUTO_APPROVED);
            } else {
                throw new IllegalArgumentException("This surgery must be approved before it can be scheduled");
            }
        }
        // A rescheduled case is still SCHEDULED: the move is SCHEDULED -> SCHEDULED and the
        // history lives on the transition row, not in a separate state.
        String payload = isReschedule
                ? "{\"oldSlot\":\"" + previousSlot + "\",\"newSlot\":\"" + req.getScheduledAt() + "\"}"
                : null;
        Surgery saved = stateMachine.transition(s, com.hms.entity.SurgeryStatus.SCHEDULED, null, null, payload);

        notifyNurse(saved, hospitalId, surgeonDisplayName);
        if (surgeon != null) notifySurgeon(saved, hospitalId, surgeon);
        audit(isReschedule ? "SURGERY_RESCHEDULED" : "SURGERY_SCHEDULED",
                (isReschedule ? "Surgery rescheduled" : "Surgery scheduled") + " (operator " + surgeonDisplayName + ")",
                hospitalId, saved.getIpdAdmissionId());
        return saved;
    }

    // ---------- Reception: start / complete / cancel ----------

    @Transactional
    public Surgery start(String publicId) {
        Long hospitalId = requireHospitalId();
        Surgery s = requireSurgeryForUpdate(publicId, hospitalId);
        preOpSafetyService.assertStartAllowed(s, hospitalId);
        // WHO checklist gate. With WHO_CHECKLIST_MODE=BLOCKING a case cannot start without a
        // signed Time-Out -- enforced HERE, server-side, not by hiding a button. Emergencies
        // resolve to ADVISORY through the priority scope, so this never blocks a crash case.
        String whoMode = otPolicyService.resolve(
                hospitalId, com.hms.service.hospital.ot.OtPolicies.WHO_CHECKLIST_MODE, s.getPriority());
        if ("BLOCKING".equals(whoMode) && !surgeryExecutionService.timeOutSigned(s.getId())) {
            throw new IllegalArgumentException("The WHO Time-Out must be signed before the surgery can start");
        }
        // A theatre modelled as an OtRoom has no ward and therefore no bed to occupy. The
        // legacy ward-backed OTs still do, and their bed state must keep working.
        if (s.getOtWardId() != null) {
            Bed bed = acquireOtBed(s.getOtWardId(), hospitalId);
            bedStatusService.change(bed.getBedId(), com.hms.entity.BedStatus.OCCUPIED, "Surgery started");
            bed.setCurrentIpdAdmissionId(s.getIpdAdmissionId());
            bedRepository.save(bed);
            s.setOtBedId(bed.getBedId());
        }
        acquireOtRoom(s, hospitalId);
        markRoom(s, com.hms.entity.OtRoom.OCCUPIED, s.getId());
        s.setStartedAt(LocalDateTime.now());
        Surgery saved = stateMachine.transition(s, com.hms.entity.SurgeryStatus.IN_PROGRESS, null, null, null);
        audit("SURGERY_STARTED", "Surgery started", hospitalId, saved.getIpdAdmissionId());
        return saved;
    }

    @Transactional
    public Surgery complete(String publicId) {
        Long hospitalId = requireHospitalId();
        Surgery s = requireSurgeryForUpdate(publicId, hospitalId);
        if (!Surgery.IN_PROGRESS.equals(s.getStatus())) {
            throw new IllegalArgumentException("Only an in-progress surgery can be completed");
        }
        releaseResourcesForCompletion(s, hospitalId);
        s.setCompletedAt(LocalDateTime.now());
        // The theatre is released the moment the procedure ends -- not when the patient
        // finally reaches a ward. Recovery is a milestone, never a case state.
        Surgery saved = stateMachine.transition(s, com.hms.entity.SurgeryStatus.COMPLETED, null, null, null);
        audit("SURGERY_COMPLETED", "Surgery completed", hospitalId, saved.getIpdAdmissionId());
        return saved;
    }

    /**
     * NABH reports elective cancellations BY REASON, so every cancellation carries one.
     * A caller that sends none is recorded as OTHER rather than rejected: making the reason
     * mandatory is a hospital policy (CANCELLATION_REASON), which lands with the policy engine.
     */
    @Transactional
    public Surgery cancel(String publicId, String reasonCode, String reasonText) {
        Long hospitalId = requireHospitalId();
        Surgery s = requireSurgeryForUpdate(publicId, hospitalId);
        if (com.hms.entity.SurgeryStatus.of(s.getStatus()).isTerminal()) {
            throw new IllegalArgumentException("Surgery is already closed");
        }
        // Do not release resources before the state machine rejects an illegal IN_PROGRESS ->
        // CANCELLED transition. The state-machine error contract remains unchanged.
        String code = resolveCancellationReason(hospitalId, s, reasonCode);
        Surgery saved = stateMachine.transition(s, com.hms.entity.SurgeryStatus.CANCELLED, code, reasonText, null);
        audit("SURGERY_CANCELLED", "Surgery cancelled (" + code + ")", hospitalId, saved.getIpdAdmissionId());
        return saved;
    }

    /**
     * Postpone is not cancel: the case returns to APPROVED and re-enters the waiting list.
     * Conflating the two loses the case and corrupts the cancellation-rate indicator.
     */
    @Transactional
    public Surgery postpone(String publicId, String reasonCode, String reasonText) {
        Long hospitalId = requireHospitalId();
        Surgery s = requireSurgeryForUpdate(publicId, hospitalId);
        String code = resolveCancellationReason(hospitalId, s, reasonCode);
        Surgery postponed = stateMachine.transition(s, com.hms.entity.SurgeryStatus.POSTPONED, code, reasonText, null);
        postponed.setScheduledAt(null);
        postponed.setOtWardId(null);
        Surgery saved = stateMachine.autoTransition(postponed, com.hms.entity.SurgeryStatus.APPROVED,
                "RETURNED_TO_WAITING_LIST");
        audit("SURGERY_POSTPONED", "Surgery postponed (" + code + ")", hospitalId, saved.getIpdAdmissionId());
        return saved;
    }

    /**
     * Close a completed case: clinical documentation complete and patient dispositioned.
     * NEVER billing, never discharge -- those are other modules with their own lifecycles.
     * An unpaid bill must not keep an OT case open and corrupt throughput metrics.
     */
    @Transactional
    public Surgery close(String publicId) {
        Long hospitalId = requireHospitalId();
        Surgery s = requireSurgeryForUpdate(publicId, hospitalId);
        Surgery saved = stateMachine.transition(s, com.hms.entity.SurgeryStatus.CLOSED, null, null, null);
        audit("SURGERY_CLOSED", "Surgery closed", hospitalId, saved.getIpdAdmissionId());
        return saved;
    }

    // ---------- Reads ----------

    /**
     * The waiting list is derived, not stored: approved cases with no slot yet. Hospitals
     * approve a hundred and schedule twenty, and that backlog is a query -- not a status.
     */
    public List<SurgeryView> listWaitingList() {
        Long hospitalId = requireHospitalId();
        return decorate(surgeryRepository
                .findByHospitalIdAndStatusAndScheduledAtIsNullOrderByRequestedAtAsc(
                        hospitalId, com.hms.entity.SurgeryStatus.APPROVED.name()));
    }

    /**
     * The OT List: every case scheduled for one day, in theatre order. This is the sheet a
     * hospital prints and pins to the theatre door each morning.
     */
    public List<SurgeryView> listForDate(java.time.LocalDate date) {
        Long hospitalId = requireHospitalId();
        java.time.LocalDate day = date == null ? java.time.LocalDate.now() : date;
        return decorate(surgeryRepository.findScheduledBetween(
                hospitalId, day.atStartOfDay(), day.plusDays(1).atStartOfDay()));
    }

    /** The case timeline: every status change with its actor and reason. */
    public List<com.hms.entity.SurgeryStateTransition> timeline(String publicId) {
        Long hospitalId = requireHospitalId();
        Surgery s = requireSurgery(publicId, hospitalId);
        return stateMachine.timeline(s.getId());
    }

    /** Reception "Requests" filter. */
    public List<SurgeryView> listRequests() {
        Long hospitalId = requireHospitalId();
        return decorate(surgeryRepository.findByHospitalIdAndStatusOrderByRequestedAtDesc(hospitalId, Surgery.REQUESTED));
    }

    /**
     * Reception "Scheduled/Live" filter. Includes COMPLETED so a just-finished case remains
     * on the board for recovery admission and closure -- it leaves only once CLOSED.
     */
    public List<SurgeryView> listBoard() {
        Long hospitalId = requireHospitalId();
        return decorate(surgeryRepository.findByHospitalIdAndStatusInOrderByScheduledAtAsc(
                hospitalId, List.of(Surgery.SCHEDULED, Surgery.IN_PROGRESS, Surgery.COMPLETED)));
    }

    /** Doctor board: scheduled+live surgeries where they are the assigned surgeon or requester. */
    public List<SurgeryView> listMyBoard() {
        Long hospitalId = requireHospitalId();
        Long myDoctorId = doctorRepository
                .findByEmailAndHospitalId(securityHelper.getCurrentUserEmail(), hospitalId)
                .map(Doctor::getId).orElse(null);
        List<Surgery> all = surgeryRepository.findByHospitalIdAndStatusInOrderByScheduledAtAsc(
                hospitalId, List.of(Surgery.SCHEDULED, Surgery.IN_PROGRESS));
        List<Surgery> mine = all.stream()
                .filter(s -> Objects.equals(s.getSurgeonDoctorId(), myDoctorId)
                        || Objects.equals(s.getRequestedByDoctorId(), myDoctorId))
                .collect(Collectors.toList());
        return decorate(mine);
    }

    /** Active surgery for an IPD admission (for the doctor IPD case view); null if none. */
    public SurgeryView getActiveForAdmission(Long ipdAdmissionId) {
        Long hospitalId = requireHospitalId();
        requireAdmission(ipdAdmissionId, hospitalId);
        return surgeryRepository.findByIpdAdmissionIdAndStatusIn(ipdAdmissionId, ACTIVE_STATUSES).stream()
                .findFirst().map(s -> decorate(List.of(s)).get(0)).orElse(null);
    }

    /** Doctor dropdown for reception scheduling — any active doctor can be assigned. */
    public List<Map<String, Object>> listSurgeons() {
        Long hospitalId = requireHospitalId();
        return doctorRepository.findByHospitalIdAndIsActiveTrueOrderByCreatedAtDesc(hospitalId).stream()
                .map(d -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("doctorId", d.getId());
                    m.put("name", d.getName());
                    m.put("specialization", d.getSpecialization());
                    return m;
                }).collect(Collectors.toList());
    }

    // ---------- helpers ----------

    // Nursing Mgmt Phase C2: the theatre bed is marked for cleaning (not
    // immediately available) so the next surgery cannot start until it is cleaned.
    private Bed acquireOtBed(Long otWardId, Long hospitalId) {
        List<Long> candidateIds = bedRepository.findAvailableBedIdsInWard(otWardId, hospitalId);
        for (Long candidateId : candidateIds) {
            Bed locked = bedRepository.findByBedIdAndHospitalIdForUpdate(candidateId, hospitalId).orElse(null);
            if (locked != null && "available".equalsIgnoreCase(locked.getStatus())) {
                return locked;
            }
        }
        throw new ConflictException("OT theatre bed is busy or has no available bed");
    }

    private void freeOtBed(Surgery s, String remark) {
        if (s.getOtBedId() == null) return;
        try {
            bedStatusService.change(s.getOtBedId(), com.hms.entity.BedStatus.CLEANING, remark);
        } catch (Exception e) {
            logger.warn("Failed to mark OT bed for cleaning: {}", e.getMessage());
        }
    }

    /**
     * Completion is all-or-nothing: unlike cancellation's legacy best-effort cleanup, every
     * resource mutation below is required before the surgery may become COMPLETED.
     */
    private void releaseResourcesForCompletion(Surgery surgery, Long hospitalId) {
        releaseOtBedForCompletion(surgery, hospitalId);
        releaseOtRoomForCompletion(surgery, hospitalId);
    }

    private void releaseOtBedForCompletion(Surgery surgery, Long hospitalId) {
        if (surgery.getOtBedId() == null) return;
        Bed bed = bedRepository.findByBedIdAndHospitalIdForUpdate(surgery.getOtBedId(), hospitalId)
                .orElseThrow(() -> new IllegalStateException("Assigned OT bed is no longer available"));
        if (!com.hms.entity.BedStatus.OCCUPIED.equalsIgnoreCase(bed.getStatus())) {
            throw new IllegalStateException("Assigned OT bed is not occupied by the surgery");
        }
        bedStatusService.changeLocked(bed, com.hms.entity.BedStatus.CLEANING, "Surgery completed");
    }

    private void releaseOtRoomForCompletion(Surgery surgery, Long hospitalId) {
        if (surgery.getOtRoomId() == null) return;
        // Keep e435b8b's global OT resource order: bed first, then theatre, then occupancy.
        com.hms.entity.OtRoom room = otSchedulingService.lockRoom(surgery.getOtRoomId());
        if (!hospitalId.equals(room.getHospitalId())) {
            throw new IllegalStateException("Assigned theatre belongs to another hospital");
        }
        if (!com.hms.entity.OtRoom.OCCUPIED.equals(room.getStatus())
                || !Objects.equals(surgery.getId(), room.getCurrentSurgeryId())) {
            throw new IllegalStateException("Assigned theatre is not occupied by the surgery");
        }
        room.setStatus(com.hms.entity.OtRoom.CLEANING);
        room.setCurrentSurgeryId(null);
        otRoomRepository.save(room);
        occupancyRepository.findOpenBySurgeryIdForUpdate(surgery.getId()).ifPresent(occupancy -> {
            occupancy.setOccupiedTo(LocalDateTime.now());
            occupancyRepository.save(occupancy);
        });
    }

    /**
     * A valid reason passes through. An unknown one is rejected when the hospital's
     * CANCELLATION_REASON policy is REQUIRED (NABH needs the indicator), and quietly
     * becomes OTHER when it is OPTIONAL.
     */
    private String resolveCancellationReason(Long hospitalId, Surgery s, String reasonCode) {
        if (com.hms.service.hospital.ot.CancellationReasons.isValid(reasonCode)) return reasonCode;
        String policy = otPolicyService.resolve(
                hospitalId, com.hms.service.hospital.ot.OtPolicies.CANCELLATION_REASON, s.getPriority());
        if ("REQUIRED".equals(policy)) {
            throw new IllegalArgumentException("A cancellation reason is required");
        }
        return "OTHER";
    }

    private void acquireOtRoom(Surgery s, Long hospitalId) {
        if (s.getOtRoomId() == null) return;

        com.hms.entity.OtRoom room = otSchedulingService.lockRoom(s.getOtRoomId());
        if (!hospitalId.equals(room.getHospitalId())) {
            throw new UnauthorizedException("Access denied: theatre belongs to another hospital");
        }
        Long holder = room.getCurrentSurgeryId();
        if (com.hms.entity.OtRoom.OCCUPIED.equals(room.getStatus())
                && holder != null && !holder.equals(s.getId())) {
            throw new ConflictException("That theatre is already in use by another case");
        }
    }

    /** Room state is best-effort: a failed status flip must never fail the clinical action. */
    private void markRoom(Surgery s, String status, Long currentSurgeryId) {
        if (s.getOtRoomId() == null) return;
        try {
            com.hms.entity.OtRoom room = otRoomService.requireRoomById(s.getOtRoomId(), s.getHospitalId());
            room.setStatus(status);
            room.setCurrentSurgeryId(currentSurgeryId);
            otRoomRepository.save(room);
            // Occupancy timeline: OCCUPIED opens a span, anything else closes it. This is
            // what utilisation and turnover are computed from, and it is best-effort too.
            if (com.hms.entity.OtRoom.OCCUPIED.equals(status)) {
                openOccupancy(s);
            } else {
                closeOccupancy(s);
            }
        } catch (Exception e) {
            logger.warn("Failed to update theatre status: {}", e.getMessage());
        }
    }

    private void openOccupancy(Surgery s) {
        if (occupancyRepository.findBySurgeryIdAndOccupiedToIsNull(s.getId()).isPresent()) return;
        com.hms.entity.OtRoomOccupancy o = new com.hms.entity.OtRoomOccupancy();
        o.setHospitalId(s.getHospitalId());
        o.setOtRoomId(s.getOtRoomId());
        o.setSurgeryId(s.getId());
        o.setOccupiedFrom(LocalDateTime.now());
        occupancyRepository.save(o);
    }

    private void closeOccupancy(Surgery s) {
        occupancyRepository.findBySurgeryIdAndOccupiedToIsNull(s.getId()).ifPresent(o -> {
            o.setOccupiedTo(LocalDateTime.now());
            occupancyRepository.save(o);
        });
    }

    private void notifyNurse(Surgery s, Long hospitalId, String operatorName) {
        try {
            // A day-care procedure has no admission, so no ward nurse is assigned to notify.
            if (s.getIpdAdmissionId() == null) return;
            assignmentRepository.findByIpdAdmissionIdAndIsActiveTrue(s.getIpdAdmissionId()).ifPresent(asg -> {
                Long patientId = ipdAdmissionRepository.findById(s.getIpdAdmissionId())
                        .map(IpdAdmission::getPatientId).orElse(s.getPatientId());
                String patientName = patientRepository.findById(patientId)
                        .map(Patient::getName).orElse("your patient");
                notificationService.create(
                        asg.getNurseUserId(), hospitalId, "OT_SCHEDULED",
                        "Surgery scheduled",
                        "Surgery (" + safe(s.getProcedureName()) + ") scheduled for " + patientName
                                + " on " + s.getScheduledAt() + " with " + safe(operatorName),
                        SURGERY_ENTITY, s.getId());
            });
        } catch (Exception e) {
            logger.warn("Failed to notify nurse of OT schedule: {}", e.getMessage());
        }
    }

    private void notifySurgeon(Surgery s, Long hospitalId, Doctor surgeon) {
        try {
            if (surgeon.getEmail() == null) return;
            userRepository.findByEmail(surgeon.getEmail()).ifPresent(u ->
                    notificationService.create(
                            u.getId(), hospitalId, "OT_ASSIGNED",
                            "You are assigned a surgery",
                            "You are scheduled to operate (" + safe(s.getProcedureName()) + ") on " + s.getScheduledAt(),
                            SURGERY_ENTITY, s.getId()));
        } catch (Exception e) {
            logger.warn("Failed to notify surgeon of OT assignment: {}", e.getMessage());
        }
    }

    private List<SurgeryView> decorate(List<Surgery> list) {
        Map<Long, String> ipdNumbers = new HashMap<>();
        Map<Long, Patient> patients = new HashMap<>();
        Map<Long, String> doctorNames = new HashMap<>();
        Map<Long, String> wardNames = new HashMap<>();
        Map<Long, String> roomNames = new HashMap<>();
        return list.stream().map(s -> {
            SurgeryView v = SurgeryView.of(s);
            // Day-care procedures have no admission, so no IPD number to show.
            if (s.getIpdAdmissionId() != null) {
                v.setIpdNumber(ipdNumbers.computeIfAbsent(s.getIpdAdmissionId(), id ->
                        ipdAdmissionRepository.findById(id).map(IpdAdmission::getIpdNumber).orElse(null)));
            }
            Patient p = patients.computeIfAbsent(s.getPatientId(), id ->
                    patientRepository.findById(id).orElse(null));
            if (p != null) {
                v.setPatientName(p.getName());
                v.setPatientAge(p.getAge());
                v.setPatientSex(p.getGender());
            }
            // Prefer the stored operator name (covers the "Other" case); fall back to
            // the assigned doctor's current name for older records.
            if (s.getSurgeonName() != null && !s.getSurgeonName().isBlank()) {
                v.setSurgeonName(s.getSurgeonName());
            } else if (s.getSurgeonDoctorId() != null) {
                v.setSurgeonName(doctorNames.computeIfAbsent(s.getSurgeonDoctorId(), id ->
                        doctorRepository.findById(id).map(Doctor::getName).orElse(null)));
            }
            if (s.getRequestedByDoctorId() != null) {
                v.setRequestedByName(doctorNames.computeIfAbsent(s.getRequestedByDoctorId(), id ->
                        doctorRepository.findById(id).map(Doctor::getName).orElse(null)));
            }
            if (s.getOtWardId() != null) {
                v.setOtWardName(wardNames.computeIfAbsent(s.getOtWardId(), id ->
                        wardRepository.findById(id).map(Ward::getWardName).orElse(null)));
            }
            if (s.getOtRoomId() != null) {
                v.setOtRoomName(roomNames.computeIfAbsent(s.getOtRoomId(), id ->
                        otRoomRepository.findById(id).map(com.hms.entity.OtRoom::getName).orElse(null)));
            }
            return v;
        }).collect(Collectors.toList());
    }

    private Surgery requireSurgery(String publicId, Long hospitalId) {
        Surgery s = surgeryRepository.findByPublicId(publicId)
                .orElseThrow(() -> new IllegalArgumentException("Surgery not found"));
        if (!hospitalId.equals(s.getHospitalId())) {
            throw new UnauthorizedException("Access denied: surgery belongs to another hospital");
        }
        return s;
    }

    /** Lifecycle commands must serialize on the tenant-scoped aggregate before taking OT resources. */
    private Surgery requireSurgeryForUpdate(String publicId, Long hospitalId) {
        return surgeryRepository.findByPublicIdAndHospitalIdForUpdate(publicId, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Surgery not found"));
    }

    private void assertExpectedVersion(Surgery surgery, Long expectedVersion) {
        if (expectedVersion == null) {
            throw new IllegalArgumentException("Surgery version is required");
        }
        long currentVersion = surgery.getLifecycleVersion() == null ? 0L : surgery.getLifecycleVersion();
        if (expectedVersion.longValue() != currentVersion) {
            throw new ConflictException("Surgery was modified by another request. Refresh and retry.");
        }
    }

    private IpdAdmission requireAdmission(Long ipdAdmissionId, Long hospitalId) {
        IpdAdmission a = ipdAdmissionRepository.findById(ipdAdmissionId)
                .orElseThrow(() -> new IllegalArgumentException("IPD admission not found"));
        if (!hospitalId.equals(a.getHospitalId())) {
            throw new UnauthorizedException("Access denied: admission belongs to another hospital");
        }
        return a;
    }

    private Long requireHospitalId() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) throw new UnauthorizedException("Hospital ID not found in context");
        return hospitalId;
    }

    private String trim(String s) { return s == null ? null : s.trim(); }
    private String safe(String s) { return s == null ? "" : s; }

    /**
     * Audits the transition AND pushes it. Every OT lifecycle write (request, approve,
     * schedule, start, complete, cancel, postpone, close) ends here, so the OT board, the
     * reception schedule and the nurse's list all move the moment a surgery changes state.
     */
    private void audit(String action, String details, Long hospitalId, Long admissionId) {
        notifier.refresh(hospitalId);
        try {
            auditLogService.logAction(action, details, securityHelper.getCurrentUserEmail(), hospitalId,
                    SURGERY_ENTITY, admissionId != null ? admissionId.toString() : null, null);
        } catch (Exception e) {
            logger.warn("Failed to write audit log for {}", action, e);
        }
    }
}
