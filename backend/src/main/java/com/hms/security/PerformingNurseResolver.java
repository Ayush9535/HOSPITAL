package com.hms.security;

import com.hms.entity.NurseProfile;
import com.hms.exception.UnauthorizedException;
import com.hms.repository.HospitalSettingRepository;
import com.hms.repository.NurseProfileRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * PerformingNurseResolver - which NurseProfile is credited as the caregiver
 * for a nursing record (Nursing Mgmt Phase A, "Performed By").
 * Separate Nurse Login ON  -> the logged-in nurse (requested id ignored).
 * Separate Nurse Login OFF -> the "Performed By" selection (required + validated).
 * Returns the NurseProfile id for performed_by_nurse_id (may be null if ON and
 * the actor is not a nurse, e.g. an incharge entering their own record).
 */
@Component
public class PerformingNurseResolver {

    @Autowired private HospitalSettingRepository hospitalSettingRepository;
    @Autowired private NurseProfileRepository nurseProfileRepository;
    @Autowired private SecurityContextHelper securityHelper;

    public Long resolve(Long requestedNurseProfileId) {
        String role = securityHelper.getCurrentUserRole();
        // A doctor — and, in a non-nursing hospital, reception — records as themselves, with no
        // "performing nurse" (there are no nurses to attribute the care to).
        if ("DOCTOR".equals(role) || "RECEPTIONIST".equals(role)) return null;
        Long hospitalId = securityHelper.getCurrentHospitalId();
        boolean separateLogin = hospitalSettingRepository.findByHospital_Id(hospitalId)
                .map(s -> Boolean.TRUE.equals(s.getSeparateNurseLogin())).orElse(false);

        if (separateLogin) {
            return nurseProfileRepository.findByUserId(securityHelper.getCurrentUserId())
                    .map(NurseProfile::getId).orElse(null);
        }
        if (requestedNurseProfileId == null) {
            throw new IllegalArgumentException("Performed By nurse is required");
        }
        NurseProfile p = nurseProfileRepository.findById(requestedNurseProfileId)
                .orElseThrow(() -> new IllegalArgumentException("Performed By nurse not found"));
        if (!hospitalId.equals(p.getHospitalId()) || !Boolean.TRUE.equals(p.getIsActive())) {
            throw new UnauthorizedException("Performed By nurse is invalid for this hospital");
        }
        return p.getId();
    }
}
