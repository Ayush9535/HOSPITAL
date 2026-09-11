package com.hms.security;

import com.hms.repository.PatientNurseAssignmentRepository;
import com.hms.repository.IpdAdmissionRepository;
import com.hms.entity.IpdAdmission;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * NurseAccessGuard - central check that the current nurse is actively assigned
 * to a given IPD admission (Phase 1 Nurse module). Reused by the workspace,
 * vitals, notes, and medication-administration services so the "only your
 * assigned patients" rule lives in one place.
 */
@Component
public class NurseAccessGuard {

    @Autowired
    private PatientNurseAssignmentRepository assignmentRepository;
    @Autowired private IpdAdmissionRepository ipdAdmissionRepository;
    @Autowired private NurseInchargeGuard nurseInchargeGuard;

    @Autowired
    private SecurityContextHelper securityHelper;

    @Autowired
    private com.hms.service.hospital.NurseCoverageService coverageService;

    /** users.id of the current nurse. */
    public Long currentNurseId() {
        return securityHelper.getCurrentUserId();
    }

    /**
     * Throws AccessDeniedException (403) unless the current nurse has an active
     * assignment to the admission, or is currently substituting for a nurse who
     * does (Nursing Mgmt Phase F).
     */
    public void assertAssigned(Long ipdAdmissionId) {
        String role = securityHelper.getCurrentUserRole();
        // Doctors/admins have broad IPD access. Reception too when it edits IPD forms in a
        // non-nursing hospital — the per-form Files & Access check (assertCanEdit) already ran
        // before this, so reaching here means reception is an allowed editor of this form.
        if ("DOCTOR".equals(role) || "HOSPITAL_ADMIN".equals(role) || "RECEPTIONIST".equals(role)) return;
        IpdAdmission admission = ipdAdmissionRepository.findByIdAndHospitalId(ipdAdmissionId, securityHelper.getCurrentHospitalId())
                .orElseThrow(() -> new AccessDeniedException("IPD admission not found"));
        if (admission.getWardId() != null && nurseInchargeGuard.myWardIds().contains(admission.getWardId())) return;
        Long nurseId = securityHelper.getCurrentUserId();
        boolean assigned = assignmentRepository
                .existsByIpdAdmissionIdAndNurseUserIdAndIsActiveTrue(ipdAdmissionId, nurseId);
        if (assigned) return;
        if (coverageService.coversAdmission(nurseId, ipdAdmissionId, java.time.LocalDate.now())) return;
        throw new AccessDeniedException("You are not assigned to this patient");
    }
}
