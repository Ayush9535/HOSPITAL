package com.hms.service.hospital.ot;

import com.hms.entity.Surgery;
import com.hms.entity.SurgeryMilestone;
import com.hms.entity.WhoChecklist;
import com.hms.exception.UnauthorizedException;
import com.hms.repository.SurgeryMilestoneRepository;
import com.hms.repository.SurgeryRepository;
import com.hms.repository.WhoChecklistRepository;
import com.hms.security.PerformingNurseResolver;
import com.hms.security.SecurityContextHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * SurgeryExecutionService - the theatre-side of a case: WHO checklist, clinical milestones
 * and the operative note.
 *
 * The WHO checklist's phases are real, signed timestamps, and the state machine consults
 * {@link #timeOutSigned} before allowing a start when policy is BLOCKING -- a UI that
 * merely hides the Start button is not access control.
 */
@Service
public class SurgeryExecutionService {

    private static final Set<String> MILESTONES = Set.of(
            SurgeryMilestone.PATIENT_ENTERED_OT, SurgeryMilestone.ANAESTHESIA_START,
            SurgeryMilestone.INCISION, SurgeryMilestone.CLOSURE, SurgeryMilestone.ANAESTHESIA_END,
            SurgeryMilestone.LEFT_THEATRE, SurgeryMilestone.ARRIVED_RECOVERY,
            SurgeryMilestone.LEFT_RECOVERY, SurgeryMilestone.TRANSFERRED);

    @Autowired private SurgeryRepository surgeryRepository;
    @Autowired private SurgeryMilestoneRepository milestoneRepository;
    @Autowired private WhoChecklistRepository whoRepository;
    @Autowired private SecurityContextHelper securityHelper;
    @Autowired private PerformingNurseResolver performingNurseResolver;

    // --- milestones ---

    public List<SurgeryMilestone> milestones(Long surgeryId) {
        Long hospitalId = requireHospitalId();
        requireSurgery(surgeryId, hospitalId);
        return milestoneRepository.findBySurgeryIdOrderByOccurredAtAsc(surgeryId);
    }

    @Transactional
    public SurgeryMilestone recordMilestone(Long surgeryId, String milestone, LocalDateTime occurredAt,
            Long performedByNurseId, String note) {
        Long hospitalId = requireHospitalId();
        Surgery surgery = requireSurgery(surgeryId, hospitalId);
        if (!MILESTONES.contains(milestone)) throw new IllegalArgumentException("Unknown milestone: " + milestone);

        SurgeryMilestone m = new SurgeryMilestone();
        m.setHospitalId(hospitalId);
        m.setSurgeryId(surgery.getId());
        m.setMilestone(milestone);
        m.setOccurredAt(occurredAt == null ? LocalDateTime.now() : occurredAt);
        m.setRecordedByUserId(securityHelper.getCurrentUserId());
        m.setPerformedByNurseId(performingNurseResolver.resolve(performedByNurseId));
        m.setNote(note);
        return milestoneRepository.save(m);
    }

    // --- WHO checklist ---

    public WhoChecklist checklist(Long surgeryId) {
        Long hospitalId = requireHospitalId();
        requireSurgery(surgeryId, hospitalId);
        return whoRepository.findBySurgeryId(surgeryId).orElse(null);
    }

    /**
     * Sign one WHO phase. Each phase is a one-way commitment: signing a phase that is
     * already signed is rejected rather than silently re-stamped.
     */
    @Transactional
    public WhoChecklist signPhase(Long surgeryId, String phase, Boolean siteMarked, Boolean countsCorrect) {
        Long hospitalId = requireHospitalId();
        Surgery surgery = requireSurgery(surgeryId, hospitalId);
        WhoChecklist c = whoRepository.findBySurgeryId(surgery.getId()).orElseGet(() -> {
            WhoChecklist n = new WhoChecklist();
            n.setHospitalId(hospitalId);
            n.setSurgeryId(surgery.getId());
            return n;
        });
        Long userId = securityHelper.getCurrentUserId();
        LocalDateTime now = LocalDateTime.now();

        switch (phase) {
            case "SIGN_IN" -> {
                if (c.getSignInAt() != null) throw new IllegalArgumentException("Sign-In is already signed");
                c.setSignInAt(now);
                c.setSignInByUserId(userId);
                if (siteMarked != null) c.setSiteMarked(siteMarked);
            }
            case "TIME_OUT" -> {
                if (c.getSignInAt() == null) throw new IllegalArgumentException("Complete Sign-In before Time-Out");
                if (c.getTimeOutAt() != null) throw new IllegalArgumentException("Time-Out is already signed");
                c.setTimeOutAt(now);
                c.setTimeOutByUserId(userId);
            }
            case "SIGN_OUT" -> {
                if (c.getTimeOutAt() == null) throw new IllegalArgumentException("Complete Time-Out before Sign-Out");
                if (c.getSignOutAt() != null) throw new IllegalArgumentException("Sign-Out is already signed");
                // Sign-Out is where the counts are confirmed; refusing to record an
                // incorrect count is a patient-safety fact, not a preference.
                if (Boolean.FALSE.equals(countsCorrect)) {
                    throw new IllegalArgumentException("Instrument and sponge counts are not correct — resolve before Sign-Out");
                }
                c.setSignOutAt(now);
                c.setSignOutByUserId(userId);
                if (countsCorrect != null) c.setCountsCorrect(countsCorrect);
            }
            default -> throw new IllegalArgumentException("Unknown WHO phase: " + phase);
        }
        return whoRepository.save(c);
    }

    /** The state machine's blocking gate: has the Time-Out been signed for this case? */
    public boolean timeOutSigned(Long surgeryId) {
        return whoRepository.findBySurgeryId(surgeryId).map(c -> c.getTimeOutAt() != null).orElse(false);
    }

    public boolean signOutSigned(Long surgeryId) {
        return whoRepository.findBySurgeryId(surgeryId).map(c -> c.getSignOutAt() != null).orElse(false);
    }

    // --- operative note ---

    @Transactional
    public Surgery saveOperativeNote(Long surgeryId, String note) {
        Long hospitalId = requireHospitalId();
        Surgery surgery = requireSurgery(surgeryId, hospitalId);
        if (note == null || note.trim().isEmpty()) throw new IllegalArgumentException("Operative note is required");
        surgery.setOperativeNote(note.trim());
        surgery.setOperativeNoteByUserId(securityHelper.getCurrentUserId());
        surgery.setOperativeNoteAt(LocalDateTime.now());
        return surgeryRepository.save(surgery);
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
