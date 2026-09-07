package com.hms.service.hospital;

import com.hms.entity.InitialAssessment;
import com.hms.entity.IpdAdmission;
import com.hms.exception.UnauthorizedException;
import com.hms.repository.InitialAssessmentRepository;
import com.hms.repository.IpdAdmissionRepository;
import com.hms.security.NurseAccessGuard;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * InitialAssessmentService - stores the nurse-captured clinical fields of the
 * Admission History & Initial Assessment form (Phase 1 Nurse module).
 */
@Service
public class InitialAssessmentService {

    private static final Logger logger = LoggerFactory.getLogger(InitialAssessmentService.class);

    @Autowired private InitialAssessmentRepository repository;
    @Autowired private IpdAdmissionRepository ipdAdmissionRepository;
    @Autowired private SecurityContextHelper securityHelper;
    @Autowired private NurseAccessGuard nurseAccessGuard;
    @Autowired private AuditLogService auditLogService;
    @Autowired private FormAccessService formAccessService;
    @Autowired private com.hms.service.RealtimeNotifier notifier;

    public InitialAssessment getOrDraft(Long ipdAdmissionId) {
        Long hospitalId = requireHospitalId();
        IpdAdmission ipd = requireAdmission(ipdAdmissionId, hospitalId);
        nurseAccessGuard.assertAssigned(ipdAdmissionId);
        return repository.findByIpdAdmissionId(ipdAdmissionId).orElseGet(() -> {
            InitialAssessment a = new InitialAssessment();
            a.setHospitalId(hospitalId);
            a.setIpdAdmissionId(ipd.getId());
            return a;
        });
    }

    @Transactional
    public InitialAssessment save(Long ipdAdmissionId, InitialAssessment incoming) {
        Long hospitalId = requireHospitalId();
        formAccessService.assertCanEdit("INITIAL_ASSESSMENT");
        requireAdmission(ipdAdmissionId, hospitalId);
        nurseAccessGuard.assertAssigned(ipdAdmissionId);

        InitialAssessment a = repository.findByIpdAdmissionId(ipdAdmissionId).orElseGet(InitialAssessment::new);
        a.setHospitalId(hospitalId);
        a.setIpdAdmissionId(ipdAdmissionId);
        a.setChiefComplaints(incoming.getChiefComplaints());
        a.setAssociatedIllness(incoming.getAssociatedIllness());
        a.setRelevantInvestigations(incoming.getRelevantInvestigations());
        a.setAllergies(incoming.getAllergies());
        a.setVaccinationHistory(incoming.getVaccinationHistory());
        a.setOthers(incoming.getOthers());
        a.setPastHistory(incoming.getPastHistory());
        a.setFamilyHistory(incoming.getFamilyHistory());
        a.setPersonalHistory(incoming.getPersonalHistory());
        a.setProvisionalDiagnosis(incoming.getProvisionalDiagnosis());
        a.setCarePlan(incoming.getCarePlan());
        a.setPainScore(incoming.getPainScore());
        InitialAssessment saved = repository.save(a);

        try {
            auditLogService.logAction("INITIAL_ASSESSMENT_SAVED",
                    "Saved initial assessment for IPD admission " + ipdAdmissionId,
                    securityHelper.getCurrentUserEmail(), hospitalId, "IPD",
                    String.valueOf(ipdAdmissionId), null);
        } catch (Exception e) {
            logger.warn("Failed to write audit log for INITIAL_ASSESSMENT_SAVED", e);
        }
        // The doctor's IPD case mirrors this sheet -- push it so their copy is not stale.
        notifier.refresh(hospitalId);
        return saved;
    }

    private IpdAdmission requireAdmission(Long ipdAdmissionId, Long hospitalId) {
        IpdAdmission ipd = ipdAdmissionRepository.findById(ipdAdmissionId)
                .orElseThrow(() -> new IllegalArgumentException("IPD admission not found"));
        if (!hospitalId.equals(ipd.getHospitalId())) {
            throw new UnauthorizedException("Access denied: admission belongs to another hospital");
        }
        return ipd;
    }

    private Long requireHospitalId() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) throw new UnauthorizedException("Hospital ID not found in context");
        return hospitalId;
    }
}
