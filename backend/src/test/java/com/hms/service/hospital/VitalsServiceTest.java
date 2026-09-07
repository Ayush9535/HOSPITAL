package com.hms.service.hospital;

import com.hms.dto.VitalsRequest;
import com.hms.entity.IpdAdmission;
import com.hms.entity.VitalsRecord;
import com.hms.repository.IpdAdmissionRepository;
import com.hms.repository.VitalsRecordRepository;
import com.hms.security.NurseAccessGuard;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VitalsServiceTest {

    @Mock VitalsRecordRepository vitalsRepository;
    @Mock IpdAdmissionRepository ipdAdmissionRepository;
    @Mock SecurityContextHelper securityHelper;
    @Mock NurseAccessGuard nurseAccessGuard;
    @Mock com.hms.security.NurseWriteAccess nurseWriteAccess;
    @Mock com.hms.security.PerformingNurseResolver performingNurseResolver;
    @Mock AuditLogService auditLogService;
    @Mock FormAccessService formAccessService;

    @Mock com.hms.service.RealtimeNotifier notifier;

    @Mock com.hms.service.hospital.icu.IcuAlertEvaluator alertEvaluator;

    @InjectMocks VitalsService service;

    private IpdAdmission admission() {
        IpdAdmission a = new IpdAdmission();
        a.setId(1L);
        a.setHospitalId(7L);
        a.setPatientId(500L);
        a.setIpdNumber("IPD-1");
        return a;
    }

    private VitalsRequest validReq() {
        VitalsRequest r = new VitalsRequest();
        r.setIpdAdmissionId(1L);
        r.setPulse(80);
        r.setBpSystolic(120);
        r.setBpDiastolic(80);
        r.setTemperature(new BigDecimal("98.6"));
        return r;
    }

    @Test
    void create_savesVitals_whenAssignedAndValid() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(securityHelper.getCurrentUserId()).thenReturn(20L);
        when(ipdAdmissionRepository.findById(1L)).thenReturn(Optional.of(admission()));
        when(vitalsRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(performingNurseResolver.resolve(any())).thenReturn(20L);

        VitalsRecord saved = service.create(validReq());

        verify(nurseWriteAccess).assertCanWriteFor(1L);
        assertThat(saved.getPatientId()).isEqualTo(500L);
        assertThat(saved.getRecordedByUserId()).isEqualTo(20L);
        assertThat(saved.getPulse()).isEqualTo(80);
        assertThat(saved.getRecordedAt()).isNotNull();
    }

    @Test
    void create_deniedWhenNotAssigned() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(ipdAdmissionRepository.findById(1L)).thenReturn(Optional.of(admission()));
        doThrow(new AccessDeniedException("not assigned")).when(nurseWriteAccess).assertCanWriteFor(1L);

        assertThatThrownBy(() -> service.create(validReq()))
                .isInstanceOf(AccessDeniedException.class);
        verify(vitalsRepository, never()).save(any());
    }

    @Test
    void create_rejectsEmptyMeasurements() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(ipdAdmissionRepository.findById(1L)).thenReturn(Optional.of(admission()));
        VitalsRequest empty = new VitalsRequest();
        empty.setIpdAdmissionId(1L);

        assertThatThrownBy(() -> service.create(empty))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("At least one");
    }

    @Test
    void create_rejectsNegativePulse() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(ipdAdmissionRepository.findById(1L)).thenReturn(Optional.of(admission()));
        VitalsRequest r = new VitalsRequest();
        r.setIpdAdmissionId(1L);
        r.setPulse(-5); // below the only remaining bound (0)

        assertThatThrownBy(() -> service.create(r))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Pulse");
    }

    @Test
    void create_acceptsHighPulse_noUpperLimit() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(ipdAdmissionRepository.findById(1L)).thenReturn(Optional.of(admission()));
        when(vitalsRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        VitalsRequest r = new VitalsRequest();
        r.setIpdAdmissionId(1L);
        r.setPulse(999); // was rejected as > 250; now allowed — no upper limit

        VitalsRecord saved = service.create(r);
        assertThat(saved.getPulse()).isEqualTo(999);
    }

    @Test
    void create_rejectsFutureRecordedAt() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(ipdAdmissionRepository.findById(1L)).thenReturn(Optional.of(admission()));
        VitalsRequest r = validReq();
        r.setRecordedAt(LocalDateTime.now().plusHours(2));

        assertThatThrownBy(() -> service.create(r))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("future");
    }

    @Test
    void update_rejectsNonAuthor() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(securityHelper.getCurrentUserId()).thenReturn(999L); // different nurse
        VitalsRecord existing = new VitalsRecord();
        existing.setHospitalId(7L);
        existing.setRecordedByUserId(20L);
        existing.setCreatedAt(LocalDateTime.now());
        when(vitalsRepository.findByPublicId("pub-1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.update("pub-1", validReq()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void update_rejectsAfterEditWindow() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(securityHelper.getCurrentUserId()).thenReturn(20L);
        VitalsRecord existing = new VitalsRecord();
        existing.setHospitalId(7L);
        existing.setRecordedByUserId(20L);
        existing.setCreatedAt(LocalDateTime.now().minusHours(13)); // window is 12h
        when(vitalsRepository.findByPublicId("pub-1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.update("pub-1", validReq()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Edit window");
    }
}
