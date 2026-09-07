package com.hms.service.hospital;

import com.hms.entity.Bed;
import com.hms.entity.BedStatus;
import com.hms.entity.BedStatusAudit;
import com.hms.repository.BedRepository;
import com.hms.repository.BedStatusAuditRepository;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks; import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BedStatusServiceTest {
    @Mock BedRepository bedRepository;
    @Mock BedStatusAuditRepository auditRepository;
    @Mock SecurityContextHelper securityHelper;
    @Mock AuditLogService auditLogService;
    @Mock com.hms.service.RealtimeNotifier notifier;
    @InjectMocks BedStatusService service;

    private Bed bed(String status) {
        Bed b = new Bed(); b.setBedId(50L); b.setHospitalId(7L); b.setWardId(3L); b.setStatus(status);
        return b;
    }

    @Test void change_recordsPreviousAndNew_andSavesAudit() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(securityHelper.getCurrentUserId()).thenReturn(20L);
        Bed b = bed(BedStatus.OCCUPIED);
        when(bedRepository.findById(50L)).thenReturn(Optional.of(b));
        when(bedRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(auditRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.change(50L, BedStatus.CLEANING, "IPD discharge");

        assertThat(b.getStatus()).isEqualTo(BedStatus.CLEANING);
        ArgumentCaptor<BedStatusAudit> cap = ArgumentCaptor.forClass(BedStatusAudit.class);
        verify(auditRepository).save(cap.capture());
        assertThat(cap.getValue().getPreviousStatus()).isEqualTo(BedStatus.OCCUPIED);
        assertThat(cap.getValue().getNewStatus()).isEqualTo(BedStatus.CLEANING);
        assertThat(cap.getValue().getChangedByUserId()).isEqualTo(20L);
        assertThat(cap.getValue().getRemarks()).isEqualTo("IPD discharge");
    }

    @Test void change_rejectsUnknownStatus() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(bedRepository.findById(50L)).thenReturn(Optional.of(bed(BedStatus.AVAILABLE)));
        assertThatThrownBy(() -> service.change(50L, "sparkly", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void change_rejectsCrossTenantBed() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        Bed b = bed(BedStatus.AVAILABLE); b.setHospitalId(999L);
        when(bedRepository.findById(50L)).thenReturn(Optional.of(b));
        assertThatThrownBy(() -> service.change(50L, BedStatus.MAINTENANCE, null))
                .isInstanceOf(com.hms.exception.ResourceNotFoundException.class);
    }
}
