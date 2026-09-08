package com.hms.service.hospital;

import com.hms.dto.CreateTaskRequest;
import com.hms.dto.UpdateTaskStatusRequest;
import com.hms.entity.IpdAdmission;
import com.hms.entity.ManualTask;
import com.hms.entity.User;
import com.hms.exception.UnauthorizedException;
import com.hms.repository.IpdAdmissionRepository;
import com.hms.repository.ManualTaskRepository;
import com.hms.repository.UserRepository;
import com.hms.security.SecurityContextHelper;
import com.hms.security.HospitalWebSocketHandler;
import com.hms.service.AuditLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ManualTaskServiceTest {

    @Mock ManualTaskRepository manualTaskRepository;
    @Mock UserRepository userRepository;
    @Mock IpdAdmissionRepository ipdAdmissionRepository;
    @Mock SecurityContextHelper securityHelper;
    @Mock AuditLogService auditLogService;
    @Mock HospitalWebSocketHandler webSocketHandler;

    @InjectMocks ManualTaskService service;

    private User nurseUser() {
        User u = new User();
        u.setId(10L);
        u.setHospitalId(7L);
        u.setRole("NURSE");
        u.setIsActive(true);
        u.setName("Nurse Joy");
        return u;
    }

    private IpdAdmission admission() {
        IpdAdmission a = new IpdAdmission();
        a.setId(50L);
        a.setHospitalId(7L);
        return a;
    }

    @Test
    void createTask_savesSuccessfully() {
        CreateTaskRequest req = new CreateTaskRequest();
        req.setTitle("Dressing Change");
        req.setDescription("Change dressing on wound");
        req.setAssignedToNurseUserId(10L);
        req.setIpdAdmissionId(50L);

        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(securityHelper.getCurrentUserId()).thenReturn(2L);
        when(userRepository.findById(10L)).thenReturn(Optional.of(nurseUser()));
        when(ipdAdmissionRepository.findById(50L)).thenReturn(Optional.of(admission()));
        when(manualTaskRepository.save(any(ManualTask.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ManualTask saved = service.createTask(req);

        assertThat(saved.getTitle()).isEqualTo("Dressing Change");
        assertThat(saved.getPriority()).isEqualTo("MEDIUM");
        assertThat(saved.getStatus()).isEqualTo("PENDING");
        assertThat(saved.getAssignedToNurseUserId()).isEqualTo(10L);
        assertThat(saved.getAssignedByUserId()).isEqualTo(2L);
        assertThat(saved.getHospitalId()).isEqualTo(7L);
    }

    @Test
    void createTask_rejectsNonNurseAssignee() {
        CreateTaskRequest req = new CreateTaskRequest();
        req.setTitle("Triage Task");
        req.setAssignedToNurseUserId(10L);

        User doctor = nurseUser();
        doctor.setRole("DOCTOR");

        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(userRepository.findById(10L)).thenReturn(Optional.of(doctor));

        assertThatThrownBy(() -> service.createTask(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid assignee");
    }

    @Test
    void createTask_rejectsDifferentHospitalAdmission() {
        CreateTaskRequest req = new CreateTaskRequest();
        req.setTitle("Dressing Change");
        req.setAssignedToNurseUserId(10L);
        req.setIpdAdmissionId(50L);

        IpdAdmission otherAdmission = admission();
        otherAdmission.setHospitalId(99L); // different hospital

        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(userRepository.findById(10L)).thenReturn(Optional.of(nurseUser()));
        when(ipdAdmissionRepository.findById(50L)).thenReturn(Optional.of(otherAdmission));

        assertThatThrownBy(() -> service.createTask(req))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void updateStatus_nurseCompletesOwnTask() {
        ManualTask task = new ManualTask();
        task.setId(100L);
        task.setHospitalId(7L);
        task.setAssignedToNurseUserId(10L);
        task.setStatus("IN_PROGRESS");
        task.setTitle("Dressing Change");

        UpdateTaskStatusRequest req = new UpdateTaskStatusRequest();
        req.setStatus("COMPLETED");
        req.setCompletionRemarks("Done beautifully");

        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(securityHelper.getCurrentUserId()).thenReturn(10L);
        when(securityHelper.getCurrentUserRole()).thenReturn("NURSE");
        when(manualTaskRepository.findByPublicId("task-id")).thenReturn(Optional.of(task));
        when(manualTaskRepository.save(any(ManualTask.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ManualTask updated = service.updateStatus("task-id", req);

        assertThat(updated.getStatus()).isEqualTo("COMPLETED");
        assertThat(updated.getCompletedAt()).isNotNull();
        assertThat(updated.getCompletionRemarks()).isEqualTo("Done beautifully");
    }

    @Test
    void updateStatus_adminCancelsTask() {
        ManualTask task = new ManualTask();
        task.setId(100L);
        task.setHospitalId(7L);
        task.setAssignedToNurseUserId(10L);
        task.setStatus("PENDING");
        task.setTitle("Dressing Change");

        UpdateTaskStatusRequest req = new UpdateTaskStatusRequest();
        req.setStatus("CANCELLED");

        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(securityHelper.getCurrentUserRole()).thenReturn("HOSPITAL_ADMIN");
        when(manualTaskRepository.findByPublicId("task-id")).thenReturn(Optional.of(task));
        when(manualTaskRepository.save(any(ManualTask.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ManualTask updated = service.updateStatus("task-id", req);

        assertThat(updated.getStatus()).isEqualTo("CANCELLED");
    }

    @Test
    void updateStatus_nurseCannotCancel() {
        ManualTask task = new ManualTask();
        task.setHospitalId(7L);
        task.setAssignedToNurseUserId(10L);
        task.setStatus("PENDING");

        UpdateTaskStatusRequest req = new UpdateTaskStatusRequest();
        req.setStatus("CANCELLED");

        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(securityHelper.getCurrentUserRole()).thenReturn("NURSE");
        when(manualTaskRepository.findByPublicId("task-id")).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> service.updateStatus("task-id", req))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("cancel");
    }

    @Test
    void updateStatus_rejectsIllegalTransition() {
        ManualTask task = new ManualTask();
        task.setHospitalId(7L);
        task.setAssignedToNurseUserId(10L);
        task.setStatus("PENDING");

        // Cannot jump straight from PENDING to COMPLETED if sequentially strict,
        // or let's try something clearly illegal like PENDING to CANCELLED as a nurse:
        UpdateTaskStatusRequest req = new UpdateTaskStatusRequest();
        req.setStatus("COMPLETED"); // Wait, let's see: PENDING to COMPLETED is allowed in my service transition guard,
        // but let's test a transition like COMPLETED to PENDING.
        task.setStatus("COMPLETED");

        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(securityHelper.getCurrentUserId()).thenReturn(10L);
        when(securityHelper.getCurrentUserRole()).thenReturn("NURSE");
        when(manualTaskRepository.findByPublicId("task-id")).thenReturn(Optional.of(task));

        UpdateTaskStatusRequest invalidReq = new UpdateTaskStatusRequest();
        invalidReq.setStatus("IN_PROGRESS");

        assertThatThrownBy(() -> service.updateStatus("task-id", invalidReq))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
