package com.hms.security;

import com.hms.entity.IpdAdmission;
import com.hms.entity.NurseProfile;
import com.hms.entity.Ward;
import com.hms.repository.IpdAdmissionRepository;
import com.hms.repository.NurseProfileRepository;
import com.hms.repository.WardRepository;
import com.hms.repository.NurseWardAssignmentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.LinkedHashSet;
import java.time.LocalDate;
import java.util.stream.Collectors;

/**
 * NurseInchargeGuard - restricts a Nurse Incharge to their assigned wards.
 * HOSPITAL_ADMIN has access to all wards. The "only your wards" rule lives here.
 */
@Component
public class NurseInchargeGuard {

    @Autowired private NurseProfileRepository nurseProfileRepository;
    @Autowired private WardRepository wardRepository;
    @Autowired private IpdAdmissionRepository ipdAdmissionRepository;
    @Autowired private NurseWardAssignmentRepository wardAssignmentRepository;
    @Autowired private SecurityContextHelper securityHelper;

    private boolean isAdmin() {
        return "HOSPITAL_ADMIN".equals(securityHelper.getCurrentUserRole());
    }

    /** The NurseProfile id of the current incharge, or null. */
    private Long currentInchargeProfileId() {
        return nurseProfileRepository.findByUserId(securityHelper.getCurrentUserId())
                .map(NurseProfile::getId).orElse(null);
    }

    public void assertWardAccess(Long wardId) {
        // Load the ward and confirm it belongs to the caller's hospital FIRST — for admins
        // too. The admin bypass below is only a bypass of the "your wards" incharge rule,
        // not of tenant isolation: an admin (or a controller that loads a bed by raw id and
        // delegates here, e.g. BedController.requireBedForWardAccess) must never reach
        // another hospital's ward.
        Long hospitalId = securityHelper.getCurrentHospitalId();
        Ward ward = wardRepository.findById(wardId)
                .orElseThrow(() -> new IllegalArgumentException("Ward not found"));
        if (hospitalId == null || !hospitalId.equals(ward.getHospitalId())) {
            throw new AccessDeniedException("Ward not in your hospital");
        }
        if (!myWardIds().contains(ward.getWardId())) throw new AccessDeniedException("You cannot access this ward");
    }

    public void assertAdmissionInMyWard(Long ipdAdmissionId) {
        IpdAdmission a = ipdAdmissionRepository.findByIdAndHospitalId(ipdAdmissionId, securityHelper.getCurrentHospitalId())
                .orElseThrow(() -> new IllegalArgumentException("IPD admission not found"));
        assertWardAccess(a.getWardId());
    }

    /** Role-resolved effective ward scope. Temporary assignment replaces primary ward. */
    public List<Long> myWardIds() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) return List.of();
        if (isAdmin()) {
            return wardRepository.findByHospitalId(hospitalId).stream()
                    .map(Ward::getWardId).collect(Collectors.toList());
        }
        NurseProfile profile = nurseProfileRepository.findByUserId(securityHelper.getCurrentUserId()).orElse(null);
        if (profile == null || !hospitalId.equals(profile.getHospitalId())) return List.of();
        LocalDate today = LocalDate.now();
        LinkedHashSet<Long> wards = new LinkedHashSet<>();
        wardAssignmentRepository.findByNurseProfileIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(profile.getId(), today, today)
                .stream()
                .filter(a -> hospitalId.equals(a.getHospitalId()))
                .map(a -> a.getTempWardId())
                .filter(wardId -> wardRepository.findByWardIdAndHospitalId(wardId, hospitalId).isPresent())
                .forEach(wards::add);
        if ("NURSE".equals(securityHelper.getCurrentUserRole())) {
            if (wards.isEmpty() && profile.getWardId() != null) wards.add(profile.getWardId());
        } else {
            wardRepository.findByHospitalIdAndInchargeNurseId(hospitalId, profile.getId()).stream()
                    .map(Ward::getWardId).forEach(wards::add);
        }
        return List.copyOf(wards);
    }
}
