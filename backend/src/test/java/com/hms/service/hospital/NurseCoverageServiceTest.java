package com.hms.service.hospital;

import com.hms.entity.NurseProfile;
import com.hms.entity.NurseSubstitution;
import com.hms.entity.NurseWardAssignment;
import com.hms.repository.NurseProfileRepository;
import com.hms.repository.NurseSubstitutionRepository;
import com.hms.repository.NurseWardAssignmentRepository;
import com.hms.repository.PatientNurseAssignmentRepository;
import com.hms.security.NurseInchargeGuard;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NurseCoverageServiceTest {
    @Mock NurseWardAssignmentRepository wardAssignmentRepository;
    @Mock NurseSubstitutionRepository substitutionRepository;
    @Mock NurseProfileRepository nurseProfileRepository;
    @Mock PatientNurseAssignmentRepository patientAssignmentRepository;
    @Mock NurseInchargeGuard nurseInchargeGuard;
    @Mock SecurityContextHelper securityHelper;
    @Mock AuditLogService auditLogService;
    @InjectMocks NurseCoverageService service;

    private NurseProfile nurse(Long id, Long userId, Long wardId) {
        NurseProfile p = new NurseProfile();
        p.setId(id); p.setUserId(userId); p.setWardId(wardId);
        p.setHospitalId(7L); p.setIsActive(true); p.setIsIncharge(false);
        p.setName("Nurse " + id);
        return p;
    }

    private NurseWardAssignment tempAssign(Long nurseProfileId, Long tempWardId) {
        NurseWardAssignment w = new NurseWardAssignment();
        w.setNurseProfileId(nurseProfileId); w.setTempWardId(tempWardId);
        return w;
    }

    @Test void effectiveWardNurses_excludesTempOut_includesTempIn() {
        LocalDate today = LocalDate.now();
        NurseProfile home = nurse(1L, 101L, 3L);   // stays in ward 3
        NurseProfile leaving = nurse(2L, 102L, 3L); // temp-assigned OUT to ward 9
        NurseProfile visiting = nurse(3L, 103L, 5L);// temp-assigned IN to ward 3

        when(nurseProfileRepository.findByWardIdAndIsInchargeFalseAndIsActiveTrue(3L))
                .thenReturn(List.of(home, leaving));
        when(wardAssignmentRepository
                .findByNurseProfileIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(eq(1L), any(), any()))
                .thenReturn(List.of());
        when(wardAssignmentRepository
                .findByNurseProfileIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(eq(2L), any(), any()))
                .thenReturn(List.of(tempAssign(2L, 9L)));
        when(wardAssignmentRepository
                .findByTempWardIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(eq(3L), any(), any()))
                .thenReturn(List.of(tempAssign(3L, 3L)));
        when(nurseProfileRepository.findById(3L)).thenReturn(Optional.of(visiting));

        List<NurseProfile> result = service.effectiveWardNurses(3L, today);

        assertThat(result).extracting(NurseProfile::getId).containsExactlyInAnyOrder(1L, 3L);
    }

    @Test void coversAdmission_trueWhenSubstitutingAssignedPrimary() {
        LocalDate today = LocalDate.now();
        NurseProfile replacement = nurse(10L, 200L, 3L); // logged-in replacement
        NurseSubstitution sub = new NurseSubstitution();
        sub.setPrimaryNurseProfileId(20L);
        sub.setReplacementNurseProfileId(10L);
        NurseProfile primary = nurse(20L, 300L, 3L);

        when(nurseProfileRepository.findByUserId(200L)).thenReturn(Optional.of(replacement));
        when(substitutionRepository
                .findByReplacementNurseProfileIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(eq(10L), any(), any()))
                .thenReturn(List.of(sub));
        when(nurseProfileRepository.findById(20L)).thenReturn(Optional.of(primary));
        when(patientAssignmentRepository
                .existsByIpdAdmissionIdAndNurseUserIdAndIsActiveTrue(555L, 300L)).thenReturn(true);

        assertThat(service.coversAdmission(200L, 555L, today)).isTrue();
    }
}
