package com.hms.service.hospital.ot;

import com.hms.entity.Surgery;
import com.hms.entity.SurgeryStateTransition;
import com.hms.entity.SurgeryStatus;
import com.hms.repository.SurgeryRepository;
import com.hms.repository.SurgeryStateTransitionRepository;
import com.hms.security.SecurityContextHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.hms.entity.SurgeryStatus.*;

/**
 * SurgeryStateMachine - the ONLY writer of surgeries.status.
 *
 * Replaces a String status guarded by ad-hoc `if` chains. Every move is checked against
 * a declarative table and recorded in surgery_state_transitions, so the case timeline
 * and every board metric derive from one append-only source.
 *
 * A workflow engine was considered and rejected: nine states do not justify a second
 * runtime and data model.
 */
@Service
public class SurgeryStateMachine {

    /**
     * Legal moves. A transition absent from this table cannot happen, whatever the
     * caller believes.
     */
    private static final Map<SurgeryStatus, Set<SurgeryStatus>> ALLOWED = new EnumMap<>(SurgeryStatus.class);
    static {
        ALLOWED.put(REQUESTED, EnumSet.of(APPROVED, CANCELLED));
        ALLOWED.put(APPROVED, EnumSet.of(SCHEDULED, CANCELLED));
        // SCHEDULED -> SCHEDULED is a reschedule: a rescheduled case is still scheduled.
        // SCHEDULED -> POSTPONED sends it back to the waiting list rather than losing it.
        ALLOWED.put(SCHEDULED, EnumSet.of(SCHEDULED, PRE_OP, IN_PROGRESS, POSTPONED, CANCELLED));
        ALLOWED.put(PRE_OP, EnumSet.of(IN_PROGRESS, POSTPONED, CANCELLED));
        ALLOWED.put(IN_PROGRESS, EnumSet.of(COMPLETED));
        ALLOWED.put(COMPLETED, EnumSet.of(CLOSED));
        ALLOWED.put(POSTPONED, EnumSet.of(APPROVED, CANCELLED));
        ALLOWED.put(CLOSED, EnumSet.noneOf(SurgeryStatus.class));
        ALLOWED.put(CANCELLED, EnumSet.noneOf(SurgeryStatus.class));
    }

    /** Leaving these requires a reason from the taxonomy, or the NABH indicator is wrong. */
    private static final Set<SurgeryStatus> REASON_REQUIRED = EnumSet.of(CANCELLED, POSTPONED);

    @Autowired private SurgeryRepository surgeryRepository;
    @Autowired private SurgeryStateTransitionRepository transitionRepository;
    @Autowired private SecurityContextHelper securityHelper;

    public Set<SurgeryStatus> allowedFrom(SurgeryStatus from) {
        return ALLOWED.getOrDefault(from, EnumSet.noneOf(SurgeryStatus.class));
    }

    public Set<SurgeryStatus> allowedFor(Surgery surgery) {
        return allowedFrom(SurgeryStatus.of(surgery.getStatus()));
    }

    public boolean canMove(SurgeryStatus from, SurgeryStatus to) {
        return allowedFrom(from).contains(to);
    }

    /** A user-initiated move. */
    @Transactional
    public Surgery transition(Surgery surgery, SurgeryStatus to, String reasonCode, String reasonText,
            String payloadJson) {
        return apply(surgery, to, SurgeryStateTransition.ACTOR_USER, securityHelper.getCurrentUserId(),
                reasonCode, reasonText, payloadJson);
    }

    /**
     * A move the hospital's policy does not require a human to perform. It is still
     * recorded, attributed to SYSTEM: an approval nobody made must never look like one
     * somebody made.
     */
    @Transactional
    public Surgery autoTransition(Surgery surgery, SurgeryStatus to, String reasonCode) {
        return apply(surgery, to, SurgeryStateTransition.ACTOR_SYSTEM, null, reasonCode, null, null);
    }

    /** Records the creation of a case: there is no prior status to move from. */
    @Transactional
    public void recordCreation(Surgery surgery) {
        writeTransition(surgery, null, SurgeryStatus.of(surgery.getStatus()),
                SurgeryStateTransition.ACTOR_USER, securityHelper.getCurrentUserId(), null, null, null);
    }

    public List<SurgeryStateTransition> timeline(Long surgeryId) {
        return transitionRepository.findBySurgeryIdOrderByCreatedAtAsc(surgeryId);
    }

    private Surgery apply(Surgery surgery, SurgeryStatus to, String actorKind, Long actorUserId,
            String reasonCode, String reasonText, String payloadJson) {
        SurgeryStatus from = SurgeryStatus.of(surgery.getStatus());

        if (!canMove(from, to)) {
            throw new IllegalArgumentException(
                    "A " + human(from) + " surgery cannot move to " + human(to));
        }
        if (REASON_REQUIRED.contains(to)) {
            if (!CancellationReasons.isValid(reasonCode)) {
                throw new IllegalArgumentException("A reason is required to " + human(to).toLowerCase() + " a surgery");
            }
        }

        surgery.setStatus(to.name());
        // Stamped here, not by callers: the waiting list orders by it, and a case can be
        // approved by a human or by policy.
        if (to == APPROVED) surgery.setApprovedAt(java.time.LocalDateTime.now());
        Surgery saved = surgeryRepository.save(surgery);
        writeTransition(saved, from, to, actorKind, actorUserId, reasonCode, reasonText, payloadJson);
        return saved;
    }

    private void writeTransition(Surgery surgery, SurgeryStatus from, SurgeryStatus to, String actorKind,
            Long actorUserId, String reasonCode, String reasonText, String payloadJson) {
        SurgeryStateTransition t = new SurgeryStateTransition();
        t.setHospitalId(surgery.getHospitalId());
        t.setSurgeryId(surgery.getId());
        t.setFromStatus(from == null ? null : from.name());
        t.setToStatus(to.name());
        t.setActorKind(actorKind);
        t.setActorUserId(actorUserId);
        t.setReasonCode(reasonCode);
        t.setReasonText(reasonText);
        t.setPayloadJson(payloadJson);
        transitionRepository.save(t);
    }

    private String human(SurgeryStatus s) {
        return s.name().toLowerCase().replace('_', ' ');
    }
}
