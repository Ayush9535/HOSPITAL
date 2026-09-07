package com.hms.service.hospital;

import com.hms.dto.MedicationAdminRequest;
import com.hms.entity.IpdAdmission;
import com.hms.entity.MedicationAdministration;
import com.hms.entity.Prescription;
import com.hms.repository.IpdAdmissionRepository;
import com.hms.repository.MedicationAdministrationRepository;
import com.hms.repository.PrescriptionRepository;
import com.hms.security.NurseAccessGuard;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MedicationAdministrationServiceTest {

    @Mock MedicationAdministrationRepository marRepository;
    @Mock PrescriptionRepository prescriptionRepository;
    @Mock IpdAdmissionRepository ipdAdmissionRepository;
    @Mock SecurityContextHelper securityHelper;
    @Mock NurseAccessGuard nurseAccessGuard;
    @Mock com.hms.security.NurseWriteAccess nurseWriteAccess;
    @Mock com.hms.security.PerformingNurseResolver performingNurseResolver;
    @Mock AuditLogService auditLogService;

    @Mock com.hms.service.RealtimeNotifier notifier;

    @InjectMocks MedicationAdministrationService service;

    private IpdAdmission admission() {
        IpdAdmission a = new IpdAdmission();
        a.setId(1L);
        a.setHospitalId(7L);
        a.setPatientId(500L);
        a.setIpdNumber("IPD-1");
        return a;
    }

    private Prescription prescription(Long id) {
        Prescription p = new Prescription();
        p.setId(id);
        p.setHospitalId(7L);
        p.setStatus("ACTIVE");
        p.setMedicineName("Paracetamol");
        return p;
    }

    private MedicationAdminRequest req(Long presId, String status) {
        MedicationAdminRequest r = new MedicationAdminRequest();
        r.setIpdAdmissionId(1L);
        r.setPrescriptionId(presId);
        r.setStatus(status);
        return r;
    }

    @Test
    void record_savesGiven_andDefaultsAdministeredTime() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(securityHelper.getCurrentUserId()).thenReturn(20L);
        when(ipdAdmissionRepository.findById(1L)).thenReturn(Optional.of(admission()));
        when(prescriptionRepository.findByIpdAdmissionIdAndStatus(1L, "ACTIVE"))
                .thenReturn(List.of(prescription(10L)));
        when(marRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(performingNurseResolver.resolve(any())).thenReturn(20L);

        MedicationAdministration saved = service.record(req(10L, "GIVEN"));

        verify(nurseWriteAccess).assertCanWriteFor(1L);
        assertThat(saved.getStatus()).isEqualTo("GIVEN");
        assertThat(saved.getAdministeredTime()).isNotNull(); // defaulted
        assertThat(saved.getPrescriptionId()).isEqualTo(10L);
        assertThat(saved.getPatientId()).isEqualTo(500L);
        assertThat(saved.getNurseUserId()).isEqualTo(20L);
    }

    @Test
    void record_savesRefused_withoutRequiringTime() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(securityHelper.getCurrentUserId()).thenReturn(20L);
        when(ipdAdmissionRepository.findById(1L)).thenReturn(Optional.of(admission()));
        when(prescriptionRepository.findByIpdAdmissionIdAndStatus(1L, "ACTIVE"))
                .thenReturn(List.of(prescription(10L)));
        when(marRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(performingNurseResolver.resolve(any())).thenReturn(20L);

        MedicationAdministration saved = service.record(req(10L, "REFUSED"));

        assertThat(saved.getStatus()).isEqualTo("REFUSED");
        assertThat(saved.getAdministeredTime()).isNull();
    }

    @Test
    void record_rejectsInvalidStatus() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(ipdAdmissionRepository.findById(1L)).thenReturn(Optional.of(admission()));

        assertThatThrownBy(() -> service.record(req(10L, "MAYBE")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid administration status");
    }

    @Test
    void record_rejectsPrescriptionNotActiveForAdmission() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(ipdAdmissionRepository.findById(1L)).thenReturn(Optional.of(admission()));
        when(prescriptionRepository.findByIpdAdmissionIdAndStatus(1L, "ACTIVE"))
                .thenReturn(List.of(prescription(99L))); // different id

        assertThatThrownBy(() -> service.record(req(10L, "GIVEN")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not an active order");
        verify(marRepository, never()).save(any());
    }

    @Test
    void record_deniedWhenNotAssigned() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(ipdAdmissionRepository.findById(1L)).thenReturn(Optional.of(admission()));
        doThrow(new AccessDeniedException("no")).when(nurseWriteAccess).assertCanWriteFor(1L);

        assertThatThrownBy(() -> service.record(req(10L, "GIVEN")))
                .isInstanceOf(AccessDeniedException.class);
        verify(marRepository, never()).save(any());
    }
}
