package com.hms.service.hospital.ot;

import com.hms.dto.RecordAnaesthesiaClearanceRequest;
import com.hms.dto.RecordEmergencyOverrideRequest;
import com.hms.entity.*;
import com.hms.exception.ResourceNotFoundException;
import com.hms.repository.*;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

/** Server-side pre-operative documentation and emergency-bypass gates. */
@Service
public class PreOpSafetyService {
    @Autowired private SurgeryRepository surgeryRepository;
    @Autowired private SurgeryFormRepository formRepository;
    @Autowired private SurgeryAnaesthesiaClearanceRepository clearanceRepository;
    @Autowired private SurgeryEmergencyOverrideRepository overrideRepository;
    @Autowired private SurgeryStateTransitionRepository transitionRepository;
    @Autowired private SurgeryStateMachine stateMachine;
    @Autowired private OtPolicyService policyService;
    @Autowired private SecurityContextHelper securityHelper;
    @Autowired private AuditLogService auditLogService;

    @Transactional
    public Surgery enterPreOp(String publicId) {
        Long hospitalId = requireHospitalId();
        Surgery surgery = requireSurgeryForUpdate(publicId, hospitalId);
        return stateMachine.transition(surgery, SurgeryStatus.PRE_OP, null, null, null);
    }

    @Transactional
    public SurgeryAnaesthesiaClearance recordClearance(String publicId, RecordAnaesthesiaClearanceRequest request) {
        Long hospitalId = requireHospitalId();
        Surgery surgery = requireSurgery(publicId, hospitalId);
        if (request == null || request.getOutcome() == null) {
            throw new IllegalArgumentException("Anaesthesia clearance outcome is required");
        }
        String comments = trim(request.getConditionsComments());
        if (request.getOutcome() == AnaesthesiaClearanceOutcome.CLEARED_WITH_CONDITIONS && comments == null) {
            throw new IllegalArgumentException("Conditions/comments are required for conditional clearance");
        }
        SurgeryAnaesthesiaClearance clearance = new SurgeryAnaesthesiaClearance();
        clearance.setSurgeryId(surgery.getId());
        clearance.setHospitalId(hospitalId);
        clearance.setOutcome(request.getOutcome());
        clearance.setConditionsComments(comments);
        clearance.setRecordedByUserId(securityHelper.getCurrentUserId());
        clearance.setRecordedAt(LocalDateTime.now());
        SurgeryAnaesthesiaClearance saved = clearanceRepository.save(clearance);
        audit("SURGERY_ANAESTHESIA_CLEARANCE_RECORDED", "Outcome: " + saved.getOutcome(), hospitalId, surgery.getId());
        return saved;
    }

    @Transactional
    public SurgeryEmergencyOverride recordEmergencyOverride(String publicId, RecordEmergencyOverrideRequest request) {
        Long hospitalId = requireHospitalId();
        Surgery surgery = requireSurgery(publicId, hospitalId);
        if (!"EMERGENCY".equalsIgnoreCase(surgery.getPriority())) {
            throw new IllegalArgumentException("Emergency override is allowed only for an emergency surgery");
        }
        String reason = request == null ? null : trim(request.getReason());
        Set<PreOpGate> gates = request == null ? Set.of() : request.getBypassedGates();
        if (reason == null) throw new IllegalArgumentException("Emergency override reason is required");
        if (gates == null || gates.isEmpty()) throw new IllegalArgumentException("At least one bypassed gate is required");
        if (!EnumSet.allOf(PreOpGate.class).containsAll(gates)) {
            throw new IllegalArgumentException("Unknown pre-operative gate");
        }
        SurgeryEmergencyOverride override = new SurgeryEmergencyOverride();
        override.setSurgeryId(surgery.getId());
        override.setHospitalId(hospitalId);
        override.setReason(reason);
        override.setBypassedGates(gates.stream().map(Enum::name).sorted().reduce((a, b) -> a + "," + b).orElseThrow());
        override.setRecordedByUserId(securityHelper.getCurrentUserId());
        override.setRecordedAt(LocalDateTime.now());
        SurgeryEmergencyOverride saved = overrideRepository.save(override);
        audit("SURGERY_EMERGENCY_OVERRIDE_RECORDED", "Bypassed: " + saved.getBypassedGates() + "; reason: " + reason,
                hospitalId, surgery.getId());
        return saved;
    }

    /** Called inside SurgeryService.start's transaction, after the current surgery has been loaded. */
    public void assertStartAllowed(Surgery surgery, Long hospitalId) {
        boolean checklistRequired = "REQUIRED".equals(policyService.resolve(hospitalId, OtPolicies.PRE_OP_CHECKLIST, surgery.getPriority()));
        boolean clearanceRequired = "REQUIRED".equals(policyService.resolve(hospitalId, OtPolicies.ANAESTHESIA_CLEARANCE, surgery.getPriority()));
        if (!checklistRequired && !clearanceRequired) return;
        if (SurgeryStatus.of(surgery.getStatus()) != SurgeryStatus.PRE_OP) {
            throw new IllegalArgumentException("The surgery must enter PRE_OP before it can start");
        }
        if (checklistRequired && !hasSignedChecklist(surgery, hospitalId) && !hasValidOverride(surgery, hospitalId, PreOpGate.PRE_OP_CHECKLIST)) {
            throw new IllegalArgumentException("A signed PRE-OP checklist is required before the surgery can start");
        }
        if (clearanceRequired && !hasValidClearance(surgery, hospitalId) && !hasValidOverride(surgery, hospitalId, PreOpGate.ANAESTHESIA_CLEARANCE)) {
            throw new IllegalArgumentException("A valid anaesthesia clearance is required before the surgery can start");
        }
    }

    private boolean hasSignedChecklist(Surgery surgery, Long hospitalId) {
        // TODO(OT 4.6B): enforce registry-backed mandatory-field metadata when forms expose it.
        return formRepository.findBySurgeryIdAndFormTypeAndIsCurrentTrue(surgery.getId(), "PRE_OP_CHECKLIST")
                .filter(form -> hospitalId.equals(form.getHospitalId()) && form.getSignedAt() != null)
                .isPresent();
    }

    private boolean hasValidClearance(Surgery surgery, Long hospitalId) {
        return clearanceRepository.findTopByHospitalIdAndSurgeryIdOrderByRecordedAtDescIdDesc(hospitalId, surgery.getId())
                .map(c -> c.getOutcome() == AnaesthesiaClearanceOutcome.CLEARED
                        || (c.getOutcome() == AnaesthesiaClearanceOutcome.CLEARED_WITH_CONDITIONS
                        && trim(c.getConditionsComments()) != null))
                .orElse(false);
    }

    private boolean hasValidOverride(Surgery surgery, Long hospitalId, PreOpGate gate) {
        if (!"EMERGENCY".equalsIgnoreCase(surgery.getPriority())) return false;
        return overrideRepository.findTopByHospitalIdAndSurgeryIdOrderByRecordedAtDescIdDesc(hospitalId, surgery.getId())
                .filter(o -> trim(o.getReason()) != null)
                .filter(o -> hasGate(o, gate))
                // Rescheduling/postponement creates a new SCHEDULED transition, invalidating prior bypasses.
                .filter(o -> transitionRepository.findTopBySurgeryIdAndToStatusOrderByCreatedAtDescIdDesc(
                        surgery.getId(), SurgeryStatus.SCHEDULED.name())
                        .map(t -> !o.getRecordedAt().isBefore(t.getCreatedAt())).orElse(false))
                .isPresent();
    }

    private boolean hasGate(SurgeryEmergencyOverride override, PreOpGate gate) {
        for (String value : override.getBypassedGates().split(",")) {
            if (gate.name().equals(value)) return true;
        }
        return false;
    }

    private Surgery requireSurgery(String publicId, Long hospitalId) {
        return surgeryRepository.findByPublicIdAndHospitalId(publicId, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Surgery not found"));
    }

    private Surgery requireSurgeryForUpdate(String publicId, Long hospitalId) {
        return surgeryRepository.findByPublicIdAndHospitalIdForUpdate(publicId, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Surgery not found"));
    }

    private Long requireHospitalId() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) throw new com.hms.exception.UnauthorizedException("Hospital ID not found in context");
        return hospitalId;
    }

    private String trim(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private void audit(String action, String detail, Long hospitalId, Long surgeryId) {
        try {
            auditLogService.logAction(action, detail, securityHelper.getCurrentUserEmail(), hospitalId, "SURGERY", String.valueOf(surgeryId), null);
        } catch (Exception ignored) {
            // Audit logging follows the existing best-effort convention; the clinical decision itself is durable.
        }
    }
}
