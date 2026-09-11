package com.hms.service.hospital;

import com.hms.dto.MyPatientDTO;
import com.hms.dto.NursePatientDetailDTO;
import com.hms.entity.*;
import com.hms.repository.*;
import com.hms.security.NurseAccessGuard;
import com.hms.security.SecurityContextHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NurseWorkspaceServiceTest {

    @Mock PatientNurseAssignmentRepository assignmentRepository;
    @Mock IpdAdmissionRepository ipdAdmissionRepository;
    @Mock PatientRepository patientRepository;
    @Mock DoctorRepository doctorRepository;
    @Mock WardRepository wardRepository;
    @Mock BedRepository bedRepository;
    @Mock PrescriptionRepository prescriptionRepository;
    @Mock MedicalRecordRepository medicalRecordRepository;
    @Mock BillingRepository billingRepository;
    @Mock BillingPaymentRepository billingPaymentRepository;
    @Mock SecurityContextHelper securityHelper;
    @Mock NurseAccessGuard nurseAccessGuard;
    @Mock SurgeryRepository surgeryRepository;
    @Mock com.hms.security.NurseInchargeGuard nurseInchargeGuard;
    @Mock com.hms.repository.NurseProfileRepository nurseProfileRepository;
    @Mock com.hms.repository.NurseAttendanceRepository nurseAttendanceRepository;
    @Mock NurseCoverageService coverageService;
    @Mock com.hms.service.RealtimeNotifier notifier;
    @Mock NurseAssignmentService nurseAssignmentService;

    @InjectMocks NurseWorkspaceService service;

    @org.junit.jupiter.api.Test
    void inchargeDashboard_aggregatesAcrossWards() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(nurseInchargeGuard.myWardIds()).thenReturn(java.util.List.of(3L));

        com.hms.entity.IpdAdmission admitted = new com.hms.entity.IpdAdmission();
        admitted.setWardId(3L); admitted.setStatus("ADMITTED");
        admitted.setAdmissionDatetime(java.time.LocalDateTime.now());
        com.hms.entity.IpdAdmission dischargedToday = new com.hms.entity.IpdAdmission();
        dischargedToday.setWardId(3L); dischargedToday.setStatus("DISCHARGED");
        dischargedToday.setDischargeDatetime(java.time.LocalDateTime.now());
        when(ipdAdmissionRepository.findByHospitalIdAndStatusIn(eq(7L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of(admitted, dischargedToday));

        when(coverageService.effectiveWardNurses(eq(3L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of(new com.hms.entity.NurseProfile(), new com.hms.entity.NurseProfile()));
        com.hms.entity.NurseAttendance p = new com.hms.entity.NurseAttendance(); p.setStatus("PRESENT");
        com.hms.entity.NurseAttendance lv = new com.hms.entity.NurseAttendance(); lv.setStatus("LEAVE");
        when(nurseAttendanceRepository.findByWardIdAndAttendanceDate(eq(3L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of(p, lv));

        com.hms.entity.Bed avail = new com.hms.entity.Bed(); avail.setStatus("available");
        com.hms.entity.Bed clean = new com.hms.entity.Bed(); clean.setStatus("cleaning");
        when(bedRepository.findByWardIdAndHospitalId(3L, 7L)).thenReturn(java.util.List.of(avail, clean));

        com.hms.dto.NurseInchargeDashboardDTO dto = service.getInchargeDashboard();

        org.assertj.core.api.Assertions.assertThat(dto.getPatients().getTotal()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(dto.getPatients().getNewAdmissionsToday()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(dto.getPatients().getDischargesToday()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(dto.getNurses().getTotal()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(dto.getNurses().getPresent()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(dto.getNurses().getOnLeave()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(dto.getBeds().getTotal()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(dto.getBeds().getAvailable()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(dto.getBeds().getCleaningRequired()).isEqualTo(1);
    }

    private PatientNurseAssignment assignment(Long admissionId, Long hospitalId) {
        PatientNurseAssignment a = new PatientNurseAssignment();
        a.setIpdAdmissionId(admissionId);
        a.setHospitalId(hospitalId);
        a.setNurseUserId(20L);
        a.setIsActive(true);
        return a;
    }

    private IpdAdmission admission(Long id, Long hospitalId, String status) {
        IpdAdmission ipd = new IpdAdmission();
        ipd.setId(id);
        ipd.setHospitalId(hospitalId);
        ipd.setStatus(status);
        ipd.setIpdNumber("IPD-" + id);
        ipd.setPatientId(100L + id);
        ipd.setDoctorId(300L);
        return ipd;
    }

    @Test
    void getMyPatients_excludesDischargedAdmissions() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(securityHelper.getCurrentUserId()).thenReturn(20L);
        when(assignmentRepository.findByNurseUserIdAndIsActiveTrue(20L))
                .thenReturn(List.of(assignment(1L, 7L), assignment(2L, 7L)));
        when(ipdAdmissionRepository.findByIdAndHospitalId(1L, 7L)).thenReturn(Optional.of(admission(1L, 7L, "ADMITTED")));
        when(ipdAdmissionRepository.findByIdAndHospitalId(2L, 7L)).thenReturn(Optional.of(admission(2L, 7L, "DISCHARGED")));
        when(patientRepository.findById(anyLong())).thenReturn(Optional.empty());
        when(doctorRepository.findById(anyLong())).thenReturn(Optional.empty());

        List<MyPatientDTO> result = service.getMyPatients();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getIpdNumber()).isEqualTo("IPD-1");
    }

    @Test
    void getMyPatients_excludesOtherHospitalAssignments() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(securityHelper.getCurrentUserId()).thenReturn(20L);
        when(assignmentRepository.findByNurseUserIdAndIsActiveTrue(20L))
                .thenReturn(List.of(assignment(9L, 99L))); // different hospital

        List<MyPatientDTO> result = service.getMyPatients();

        assertThat(result).isEmpty();
        verify(ipdAdmissionRepository, never()).findByIdAndHospitalId(9L, 7L);
    }

    @Test
    void getPatientDetail_deniedWhenNotAssigned() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        doThrow(new AccessDeniedException("not assigned")).when(nurseAccessGuard).assertAssigned(5L);

        assertThatThrownBy(() -> service.getPatientDetail(5L))
                .isInstanceOf(AccessDeniedException.class);
        verify(ipdAdmissionRepository, never()).findById(5L);
    }

    @Test
    void getPatientDetail_buildsCompositeWhenAssigned() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        doNothing().when(nurseAccessGuard).assertAssigned(1L);
        when(ipdAdmissionRepository.findById(1L)).thenReturn(Optional.of(admission(1L, 7L, "ADMITTED")));
        when(patientRepository.findById(101L)).thenReturn(Optional.empty());
        when(doctorRepository.findById(300L)).thenReturn(Optional.empty());
        when(medicalRecordRepository.findByIpdAdmissionIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());

        Prescription pr = new Prescription();
        pr.setMedicineName("Paracetamol");
        pr.setDosage("500mg");
        pr.setStatus("ACTIVE");
        when(prescriptionRepository.findByIpdAdmissionIdAndStatus(1L, "ACTIVE")).thenReturn(List.of(pr));
        when(billingRepository.findByIpdAdmissionId(1L)).thenReturn(List.of());

        NursePatientDetailDTO dto = service.getPatientDetail(1L);

        assertThat(dto.getIpdNumber()).isEqualTo("IPD-1");
        assertThat(dto.getPrescriptions()).hasSize(1);
        assertThat(dto.getPrescriptions().get(0).getMedicineName()).isEqualTo("Paracetamol");
        assertThat(dto.getBilling().getBalance()).isEqualByComparingTo("0");
    }

    // ── incharge bedside view: ward-scoped, not assignment-scoped ────────────

    @org.junit.jupiter.api.Test
    void inchargeOpensAWardPatientsChartWithoutBeingAssignedToThem() {
        // The point of the separate entry point: an incharge supervises a ward and is never
        // assigned to a patient, so the staff-nurse rule would refuse every chart they open.
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        IpdAdmission ipd = new IpdAdmission();
        ipd.setId(11L);
        ipd.setHospitalId(7L);
        ipd.setPatientId(5L);
        ipd.setDoctorId(2L);
        ipd.setIpdNumber("IPD-11");
        ipd.setStatus("ADMITTED");
        when(ipdAdmissionRepository.findById(11L)).thenReturn(Optional.of(ipd));

        NursePatientDetailDTO dto = service.getWardPatientDetail(11L);

        assertThat(dto.getIpdAdmissionId()).isEqualTo(11L);
        assertThat(dto.getIpdNumber()).isEqualTo("IPD-11");
        verify(nurseInchargeGuard).assertAdmissionInMyWard(11L);
        verifyNoInteractions(nurseAccessGuard);
    }

    @org.junit.jupiter.api.Test
    void inchargeCannotOpenAChartOutsideTheirWards() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        doThrow(new AccessDeniedException("not your ward"))
                .when(nurseInchargeGuard).assertAdmissionInMyWard(11L);

        assertThatThrownBy(() -> service.getWardPatientDetail(11L))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(ipdAdmissionRepository);
    }

    @org.junit.jupiter.api.Test
    void anotherHospitalsAdmissionIsRefusedEvenInsideTheWardCheck() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        IpdAdmission foreign = new IpdAdmission();
        foreign.setId(11L);
        foreign.setHospitalId(99L);
        when(ipdAdmissionRepository.findById(11L)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.getWardPatientDetail(11L))
                .isInstanceOf(com.hms.exception.UnauthorizedException.class);
    }

    @org.junit.jupiter.api.Test
    void theStaffNurseRuleIsUntouchedByTheInchargeEntryPoint() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        // Widening getPatientDetail would have loosened "only your own patients" for staff
        // nurses too. It still asks the assignment guard, and still only that one.
        doThrow(new AccessDeniedException("not assigned"))
                .when(nurseAccessGuard).assertAssigned(11L);

        assertThatThrownBy(() -> service.getPatientDetail(11L))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(nurseInchargeGuard);
    }

    @org.junit.jupiter.api.Test
    void assigningANursePushesARefreshSoBothScreensCatchUp() {
        // The incharge's ward list and the assigned nurse's "My Patients" both change.
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        com.hms.entity.NurseProfile p = new com.hms.entity.NurseProfile();
        p.setId(4L);
        p.setHospitalId(7L);
        p.setIsActive(true);
        p.setIsIncharge(false);
        p.setUserId(9L);
        p.setWardId(3L);
        when(nurseProfileRepository.findById(4L)).thenReturn(Optional.of(p));

        service.assignPatientNurse(11L, 4L);

        verify(notifier).refresh(7L);
    }
}
