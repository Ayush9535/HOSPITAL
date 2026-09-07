package com.hms.service.hospital.ot;

import com.hms.entity.RecoveryBay;
import com.hms.entity.RecoveryEpisode;
import com.hms.entity.RecoveryObservation;
import com.hms.entity.Surgery;
import com.hms.entity.SurgeryStatus;
import com.hms.exception.ConflictException;
import com.hms.exception.UnauthorizedException;
import com.hms.repository.PatientRepository;
import com.hms.repository.RecoveryBayRepository;
import com.hms.repository.RecoveryEpisodeRepository;
import com.hms.repository.RecoveryObservationRepository;
import com.hms.repository.SurgeryRepository;
import com.hms.security.PerformingNurseResolver;
import com.hms.security.SecurityContextHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RecoveryService - the PACU episode after a procedure, gated by the hospital's
 * RECOVERY_TRACKING policy.
 *
 * NONE: the hospital records nothing (a 10-bed nursing home). MILESTONE: arrival and
 * departure are milestones only. PACU_EPISODE: a full episode with an Aldrete series.
 * The theatre is never held for any of this -- recovery is a record, not a case state.
 *
 * OT-P0B: a patient must never be COMPLETED with no discoverable location. admit() now
 * requires a tenant-owned RecoveryBay and enforces the surgery is actually COMPLETED before
 * transferring ownership to recovery; board() is the single place both "in recovery" and
 * "completed but not yet admitted" patients are guaranteed to surface.
 */
@Service
public class RecoveryService {
    private static final Set<String> DESTINATIONS = Set.of("WARD", "ICU", "HDU", "HOME", "MORTUARY");
    @Autowired private RecoveryEpisodeRepository episodeRepository;
    @Autowired private RecoveryObservationRepository observationRepository;
    @Autowired private RecoveryBayRepository bayRepository;
    @Autowired private SurgeryRepository surgeryRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private OtPolicyService otPolicyService;
    @Autowired private SecurityContextHelper securityHelper;
    @Autowired private PerformingNurseResolver performingNurseResolver;

    public RecoveryEpisode episode(Long surgeryId) {
        Long hospitalId = requireHospitalId();
        requireSurgery(surgeryId, hospitalId);
        return episodeRepository.findBySurgeryId(surgeryId).orElse(null);
    }

    public List<RecoveryObservation> observations(Long episodeId) {
        return observationRepository.findByEpisodeIdOrderByObservedAtAsc(episodeId);
    }

    /**
     * Admit a patient to recovery. Requires an active, unoccupied, tenant-owned bay: without one
     * the transition must fail with a controlled 409/400, not create a patient with nowhere to
     * be found. The surgery stays COMPLETED either way -- it is only ever moved on by
     * discharge()+close(), never by a failed admit here.
     */
    @Transactional
    public RecoveryEpisode admit(Long surgeryId, String recoveryBayPublicId) {
        Long hospitalId = requireHospitalId();
        Surgery surgery = requireSurgery(surgeryId, hospitalId);
        assertTracked(hospitalId, surgery);
        if (SurgeryStatus.of(surgery.getStatus()) != SurgeryStatus.COMPLETED) {
            throw new IllegalArgumentException("Only a completed surgery may be admitted to recovery");
        }
        if (episodeRepository.findBySurgeryId(surgeryId).isPresent()) {
            throw new IllegalArgumentException("This patient is already in recovery");
        }
        if (recoveryBayPublicId == null || recoveryBayPublicId.isBlank()) {
            throw new IllegalArgumentException("A recovery bay must be selected");
        }
        // Resolve the externally-addressed publicId (tenant-scoped: a foreign hospital's bay
        // resolves to "not found", never leaks) to its internal id, then lock THAT row before
        // checking occupancy -- two concurrent admits targeting the same bay must not both
        // succeed. The publicId lookup itself needs no lock: its identity cannot change.
        Long recoveryBayId = bayRepository.findByPublicIdAndHospitalId(recoveryBayPublicId, hospitalId)
                .map(RecoveryBay::getId)
                .orElseThrow(() -> new IllegalArgumentException("Recovery bay not found"));
        RecoveryBay bay = bayRepository.findByIdAndHospitalIdForUpdate(recoveryBayId, hospitalId)
                .orElseThrow(() -> new IllegalArgumentException("Recovery bay not found"));
        if (!Boolean.TRUE.equals(bay.getIsActive())) {
            throw new ConflictException("This recovery bay is not in service");
        }
        if (episodeRepository.existsActiveByRecoveryBayId(bay.getId())) {
            throw new ConflictException("This recovery bay is occupied. Choose another bay.");
        }
        RecoveryEpisode e = new RecoveryEpisode();
        e.setHospitalId(hospitalId);
        e.setSurgeryId(surgery.getId());
        e.setPatientId(surgery.getPatientId());
        e.setArrivedAt(LocalDateTime.now());
        e.setArrivedByUserId(securityHelper.getCurrentUserId());
        e.setRecoveryBayId(bay.getId());
        return episodeRepository.save(e);
    }

    /** Record an Aldrete observation. Only for PACU_EPISODE tracking. */
    @Transactional
    public RecoveryObservation observe(Long surgeryId, Integer aldreteScore, Long performedByNurseId, String note) {
        Long hospitalId = requireHospitalId();
        requireSurgery(surgeryId, hospitalId);
        RecoveryEpisode e = episodeRepository.findBySurgeryId(surgeryId)
                .orElseThrow(() -> new IllegalArgumentException("Admit the patient to recovery first"));
        if (aldreteScore != null && (aldreteScore < 0 || aldreteScore > 10)) {
            throw new IllegalArgumentException("Aldrete score must be between 0 and 10");
        }
        RecoveryObservation o = new RecoveryObservation();
        o.setHospitalId(hospitalId);
        o.setEpisodeId(e.getId());
        o.setObservedAt(LocalDateTime.now());
        o.setAldreteScore(aldreteScore);
        o.setRecordedByUserId(securityHelper.getCurrentUserId());
        o.setPerformedByNurseId(performingNurseResolver.resolve(performedByNurseId));
        o.setNote(note);
        return observationRepository.save(o);
    }

    /** Discharge from PACU to a destination. */
    @Transactional
    public RecoveryEpisode discharge(Long surgeryId, String destination) {
        Long hospitalId = requireHospitalId();
        requireSurgery(surgeryId, hospitalId);
        RecoveryEpisode e = episodeRepository.findBySurgeryId(surgeryId)
                .orElseThrow(() -> new IllegalArgumentException("This patient is not in recovery"));
        if (destination == null || !DESTINATIONS.contains(destination)) {
            throw new IllegalArgumentException("A valid destination is required (WARD, ICU, HDU, HOME, MORTUARY)");
        }
        e.setDischargedAt(LocalDateTime.now());
        e.setDischargedByUserId(securityHelper.getCurrentUserId());
        e.setTransferDestination(destination);
        return episodeRepository.save(e);
    }

    /**
     * The hospital-wide recovery board: every patient who is either actively in recovery, or
     * COMPLETED and waiting to be admitted. This is the enforcement of "every active patient has
     * exactly one discoverable operational location" -- a patient a failed/omitted admit left
     * without a bay still appears here, in the awaiting section, not nowhere.
     */
    public Map<String, Object> board() {
        Long hospitalId = requireHospitalId();
        List<RecoveryEpisode> active =
                episodeRepository.findByHospitalIdAndDischargedAtIsNullOrderByArrivedAtAsc(hospitalId);
        Set<Long> inRecoverySurgeryIds = new java.util.HashSet<>();
        List<Map<String, Object>> inRecovery = new ArrayList<>();
        for (RecoveryEpisode e : active) {
            inRecoverySurgeryIds.add(e.getSurgeryId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("surgeryId", e.getSurgeryId());
            row.put("patientId", e.getPatientId());
            row.put("patientName", patientName(e.getPatientId(), hospitalId));
            row.put("arrivedAt", e.getArrivedAt());
            row.put("bayId", e.getRecoveryBayId());
            row.put("bayName", e.getRecoveryBayId() == null ? null
                    : bayRepository.findByIdAndHospitalId(e.getRecoveryBayId(), hospitalId)
                            .map(RecoveryBay::getName).orElse(null));
            row.put("status", "IN_RECOVERY");
            inRecovery.add(row);
        }

        // A surgery that has EVER had an episode -- active or already discharged -- has left the
        // "awaiting" bucket for good. Only the active set decides IN_RECOVERY membership above;
        // a discharged episode must still keep the surgery off "awaiting", or a patient properly
        // discharged to a ward reappears looking like they still need admission.
        Set<Long> everHadEpisodeSurgeryIds = new java.util.HashSet<>(inRecoverySurgeryIds);
        for (RecoveryEpisode e : episodeRepository.findByHospitalId(hospitalId)) {
            everHadEpisodeSurgeryIds.add(e.getSurgeryId());
        }

        List<Map<String, Object>> awaitingRecovery = new ArrayList<>();
        for (Surgery s : surgeryRepository.findByHospitalIdAndStatusOrderByRequestedAtDesc(
                hospitalId, SurgeryStatus.COMPLETED.name())) {
            if (everHadEpisodeSurgeryIds.contains(s.getId())) continue; // already admitted at some point
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("surgeryId", s.getId());
            row.put("patientId", s.getPatientId());
            row.put("patientName", patientName(s.getPatientId(), hospitalId));
            row.put("procedureName", s.getProcedureName());
            row.put("completedAt", s.getCompletedAt());
            row.put("status", "AWAITING_RECOVERY");
            awaitingRecovery.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("inRecovery", inRecovery);
        out.put("awaitingRecovery", awaitingRecovery);
        return out;
    }

    private String patientName(Long patientId, Long hospitalId) {
        return patientRepository.findByIdAndHospitalIdAndIsActiveTrue(patientId, hospitalId)
                .map(com.hms.entity.Patient::getName).orElse(null);
    }

    private void assertTracked(Long hospitalId, Surgery surgery) {
        String mode = otPolicyService.resolve(hospitalId, OtPolicies.RECOVERY_TRACKING, surgery.getPriority());
        if ("NONE".equals(mode)) {
            throw new IllegalArgumentException("This hospital does not track recovery. Enable it under OT Policies.");
        }
    }

    private Surgery requireSurgery(Long surgeryId, Long hospitalId) {
        Surgery s = surgeryRepository.findById(surgeryId)
                .orElseThrow(() -> new IllegalArgumentException("Surgery not found"));
        if (!hospitalId.equals(s.getHospitalId())) {
            throw new UnauthorizedException("Access denied: surgery belongs to another hospital");
        }
        return s;
    }

    private Long requireHospitalId() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) throw new UnauthorizedException("Hospital ID not found in context");
        return hospitalId;
    }
}
