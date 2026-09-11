package com.hms.service.hospital;

import com.hms.dto.NursingNoteRequest;
import com.hms.entity.IpdAdmission;
import com.hms.entity.NursingNote;
import com.hms.repository.IpdAdmissionRepository;
import com.hms.repository.NursingNoteRepository;
import com.hms.security.NurseAccessGuard;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NursingNoteServiceTest {

    @Mock NursingNoteRepository noteRepository;
    @Mock IpdAdmissionRepository ipdAdmissionRepository;
    @Mock SecurityContextHelper securityHelper;
    @Mock NurseAccessGuard nurseAccessGuard;
    @Mock com.hms.security.NurseWriteAccess nurseWriteAccess;
    @Mock com.hms.security.PerformingNurseResolver performingNurseResolver;
    @Mock AuditLogService auditLogService;
    @Mock FormAccessService formAccessService;

    @Mock com.hms.service.RealtimeNotifier notifier;

    @InjectMocks NursingNoteService service;

    private IpdAdmission admission() {
        IpdAdmission a = new IpdAdmission();
        a.setId(1L);
        a.setHospitalId(7L);
        a.setPatientId(500L);
        a.setIpdNumber("IPD-1");
        return a;
    }

    private NursingNoteRequest req(String text) {
        NursingNoteRequest r = new NursingNoteRequest();
        r.setIpdAdmissionId(1L);
        r.setNoteText(text);
        return r;
    }

    @Test
    void create_savesNote_whenAssignedAndValid() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(securityHelper.getCurrentUserId()).thenReturn(20L);
        when(ipdAdmissionRepository.findById(1L)).thenReturn(Optional.of(admission()));
        when(noteRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(performingNurseResolver.resolve(any())).thenReturn(20L);

        NursingNote saved = service.create(req("  Patient resting comfortably  "));

        verify(nurseWriteAccess).assertCanWriteFor(1L);
        assertThat(saved.getNoteText()).isEqualTo("Patient resting comfortably"); // trimmed
        assertThat(saved.getNurseUserId()).isEqualTo(20L);
        assertThat(saved.getPatientId()).isEqualTo(500L);
    }

    @Test
    void create_rejectsEmptyText() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(ipdAdmissionRepository.findById(1L)).thenReturn(Optional.of(admission()));

        assertThatThrownBy(() -> service.create(req("   ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required");
    }

    @Test
    void create_rejectsTooLongText() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(ipdAdmissionRepository.findById(1L)).thenReturn(Optional.of(admission()));

        assertThatThrownBy(() -> service.create(req("x".repeat(2001))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most");
    }

    @Test
    void create_deniedWhenNotAssigned() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(ipdAdmissionRepository.findById(1L)).thenReturn(Optional.of(admission()));
        doThrow(new AccessDeniedException("no")).when(nurseWriteAccess).assertCanWriteFor(1L);

        assertThatThrownBy(() -> service.create(req("hello")))
                .isInstanceOf(AccessDeniedException.class);
        verify(noteRepository, never()).save(any());
    }

    @Test
    void update_rejectsNonAuthor() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(securityHelper.getCurrentUserId()).thenReturn(999L);
        NursingNote n = new NursingNote();
        n.setHospitalId(7L);
        n.setNurseUserId(20L);
        n.setCreatedAt(LocalDateTime.now());
        when(noteRepository.findByPublicId("pub-1")).thenReturn(Optional.of(n));

        assertThatThrownBy(() -> service.update("pub-1", req("edit")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void softDelete_rejectsAfterEditWindow() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(securityHelper.getCurrentUserId()).thenReturn(20L);
        NursingNote n = new NursingNote();
        n.setHospitalId(7L);
        n.setNurseUserId(20L);
        n.setCreatedAt(LocalDateTime.now().minusHours(13));
        when(noteRepository.findByPublicId("pub-1")).thenReturn(Optional.of(n));

        assertThatThrownBy(() -> service.softDelete("pub-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Edit window");
    }

    @Test
    void softDelete_marksInactiveForAuthorInWindow() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(securityHelper.getCurrentUserId()).thenReturn(20L);
        NursingNote n = new NursingNote();
        n.setHospitalId(7L);
        n.setNurseUserId(20L);
        n.setIsActive(true);
        n.setCreatedAt(LocalDateTime.now());
        when(noteRepository.findByPublicId("pub-1")).thenReturn(Optional.of(n));
        when(noteRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.softDelete("pub-1");

        assertThat(n.getIsActive()).isFalse();
    }
}
