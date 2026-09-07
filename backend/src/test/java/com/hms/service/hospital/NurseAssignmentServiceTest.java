package com.hms.service.hospital;

import com.hms.entity.IpdAdmission;
import com.hms.entity.PatientNurseAssignment;
import com.hms.entity.User;
import com.hms.exception.UnauthorizedException;
import com.hms.repository.DoctorRepository;
import com.hms.repository.IpdAdmissionRepository;
import com.hms.repository.PatientNurseAssignmentRepository;
import com.hms.repository.PatientRepository;
import com.hms.repository.UserRepository;
import com.hms.security.HospitalWebSocketHandler;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NurseAssignmentServiceTest {

    @Mock PatientNurseAssignmentRepository assignmentRepository;
    @Mock IpdAdmissionRepository ipdAdmissionRepository;
    @Mock UserRepository userRepository;
    @Mock PatientRepository patientRepository;
    @Mock DoctorRepository doctorRepository;
    @Mock SecurityContextHelper securityHelper;
    @Mock AuditLogService auditLogService;
    @Mock HospitalWebSocketHandler webSocketHandler;

    @InjectMocks NurseAssignmentService service;

    private IpdAdmission admission(Long id, Long hospitalId, String status) {
        IpdAdmission a = new IpdAdmission();
        a.setId(id);
        a.setHospitalId(hospitalId);
        a.setStatus(status);
        a.setPatientId(500L);
        a.setDoctorId(600L);
        a.setIpdNumber("IPD-1");
        return a;
    }

    private User nurse(Long id, Long hospitalId) {
        User u = new User();
        u.setId(id);
        u.setHospitalId(hospitalId);
        u.setRole("NURSE");
        u.setIsActive(true);
        return u;
    }

    @Test
    void assignNurse_createsActiveAssignment_andClosesPriorActive() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(securityHelper.getCurrentUserId()).thenReturn(9L);
        when(ipdAdmissionRepository.findById(1L)).thenReturn(Optional.of(admission(1L, 7L, "ADMITTED")));
        when(userRepository.findById(50L)).thenReturn(Optional.of(nurse(50L, 7L)));

        PatientNurseAssignment prior = new PatientNurseAssignment();
        prior.setIsActive(true);
        when(assignmentRepository.findByIpdAdmissionIdAndIsActiveTrue(1L)).thenReturn(Optional.of(prior));
        when(assignmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PatientNurseAssignment result = service.assignNurse(1L, 50L, "beds 1-8");

        assertThat(prior.getIsActive()).isFalse();       // prior closed
        assertThat(prior.getUnassignedAt()).isNotNull();
        assertThat(result.getIsActive()).isTrue();
        assertThat(result.getNurseUserId()).isEqualTo(50L);
        assertThat(result.getIpdAdmissionId()).isEqualTo(1L);
        assertThat(result.getPatientId()).isEqualTo(500L);
        assertThat(result.getAssignedByUserId()).isEqualTo(9L);
        verify(assignmentRepository, times(2)).save(any()); // close prior + save new
    }

    @Test
    void assignNurse_rejectsDischargedAdmission() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(ipdAdmissionRepository.findById(1L)).thenReturn(Optional.of(admission(1L, 7L, "DISCHARGED")));

        assertThatThrownBy(() -> service.assignNurse(1L, 50L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("discharged");
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    void assignNurse_rejectsCrossHospitalAdmission() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(ipdAdmissionRepository.findById(1L)).thenReturn(Optional.of(admission(1L, 99L, "ADMITTED")));

        assertThatThrownBy(() -> service.assignNurse(1L, 50L, null))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void assignNurse_rejectsNonNurseTarget() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(ipdAdmissionRepository.findById(1L)).thenReturn(Optional.of(admission(1L, 7L, "ADMITTED")));
        User doctor = new User();
        doctor.setId(50L);
        doctor.setHospitalId(7L);
        doctor.setRole("DOCTOR");
        doctor.setIsActive(true);
        when(userRepository.findById(50L)).thenReturn(Optional.of(doctor));

        assertThatThrownBy(() -> service.assignNurse(1L, 50L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not an active nurse");
    }

    @Test
    void unassign_closesAssignment() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        PatientNurseAssignment a = new PatientNurseAssignment();
        a.setHospitalId(7L);
        a.setIsActive(true);
        when(assignmentRepository.findByPublicId("pub-1")).thenReturn(Optional.of(a));
        when(assignmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.unassign("pub-1");

        assertThat(a.getIsActive()).isFalse();
        assertThat(a.getUnassignedAt()).isNotNull();
    }

    @Test
    void unassign_rejectsCrossHospital() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        PatientNurseAssignment a = new PatientNurseAssignment();
        a.setHospitalId(99L);
        a.setIsActive(true);
        when(assignmentRepository.findByPublicId("pub-1")).thenReturn(Optional.of(a));

        assertThatThrownBy(() -> service.unassign("pub-1"))
                .isInstanceOf(UnauthorizedException.class);
    }
}
