package com.hms.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * NurseWriteAccess - authorizes a nursing write for an admission based on role:
 *  - NURSE (staff nurse)  -> must be actively assigned to the patient.
 *  - NURSE_INCHARGE / HOSPITAL_ADMIN -> must be the incharge of the patient's ward.
 * Centralizes the "who may record for this patient" rule across the write services.
 */
@Component
public class NurseWriteAccess {

    @Autowired private NurseAccessGuard nurseAccessGuard;
    @Autowired private NurseInchargeGuard nurseInchargeGuard;
    @Autowired private SecurityContextHelper securityHelper;

    public void assertCanWriteFor(Long ipdAdmissionId) {
        String role = securityHelper.getCurrentUserRole();
        if ("NURSE_INCHARGE".equals(role) || "HOSPITAL_ADMIN".equals(role)) {
            nurseInchargeGuard.assertAdmissionInMyWard(ipdAdmissionId);
        } else {
            nurseAccessGuard.assertAssigned(ipdAdmissionId);
        }
    }
}
