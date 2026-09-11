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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * ManualTaskService - manages bedside tasks assigned to nurses (Phase 1 Nurse module, M7).
 */
@Service
public class ManualTaskService {

    private static final Logger logger = LoggerFactory.getLogger(ManualTaskService.class);
    private static final Set<String> PRIORITIES = Set.of("LOW", "MEDIUM", "HIGH");

    @Autowired private ManualTaskRepository manualTaskRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private IpdAdmissionRepository ipdAdmissionRepository;
    @Autowired private SecurityContextHelper securityHelper;
    @Autowired private AuditLogService auditLogService;
    @Autowired private HospitalWebSocketHandler webSocketHandler;

    // Optional NotificationService placeholder - will be wired in M8
    @Autowired(required = false)
    private NotificationService notificationService;

    @Transactional
    public ManualTask createTask(CreateTaskRequest req) {
        Long hospitalId = requireHospitalId();
        if (!StringUtils.hasText(req.getTitle())) {
            throw new IllegalArgumentException("Title is required");
        }

        // Find assignee nurse
        if (req.getAssignedToNurseUserId() == null) {
            throw new IllegalArgumentException("Assigned nurse user ID is required");
        }
        User nurse = userRepository.findById(req.getAssignedToNurseUserId())
                .orElseThrow(() -> new IllegalArgumentException("Nurse not found"));
        if (!nurse.getIsActive() || !"NURSE".equals(nurse.getRole()) || !hospitalId.equals(nurse.getHospitalId())) {
            throw new IllegalArgumentException("Invalid assignee: must be an active nurse in the same hospital");
        }

        // Optional IPD Admission verify
        if (req.getIpdAdmissionId() != null) {
            IpdAdmission admission = ipdAdmissionRepository.findById(req.getIpdAdmissionId())
                    .orElseThrow(() -> new IllegalArgumentException("Admission not found"));
            if (!hospitalId.equals(admission.getHospitalId())) {
                throw new UnauthorizedException("Access denied: admission belongs to another hospital");
            }
        }

        String priority = req.getPriority() == null ? "MEDIUM" : req.getPriority().toUpperCase();
        if (!PRIORITIES.contains(priority)) {
            throw new IllegalArgumentException("Invalid priority. Allowed values: LOW, MEDIUM, HIGH");
        }

        ManualTask task = new ManualTask();
        task.setHospitalId(hospitalId);
        task.setTitle(req.getTitle().trim());
        task.setDescription(req.getDescription());
        task.setAssignedToNurseUserId(nurse.getId());
        task.setAssignedByUserId(securityHelper.getCurrentUserId());
        task.setIpdAdmissionId(req.getIpdAdmissionId());
        task.setPriority(priority);
        task.setStatus("PENDING");
        task.setDueDate(req.getDueDate());
        task.setIsActive(true);

        ManualTask saved = manualTaskRepository.save(task);

        audit("TASK_CREATED", "Created task: " + saved.getTitle() + " assigned to nurse " + nurse.getName(),
                hospitalId, saved.getId());

        // Emit notification (best-effort) for M8
        notifyAssignee(saved, "New task assigned: " + saved.getTitle());

        broadcastRefresh(hospitalId, "task creation");
        return saved;
    }

    public List<ManualTask> listForHospital() {
        Long hospitalId = requireHospitalId();
        return manualTaskRepository.findByHospitalIdAndIsActiveTrueOrderByCreatedAtDesc(hospitalId);
    }

    public List<ManualTask> listMyTasks() {
        requireHospitalId();
        Long nurseUserId = securityHelper.getCurrentUserId();
        return manualTaskRepository.findByAssignedToNurseUserIdAndIsActiveTrueOrderByCreatedAtDesc(nurseUserId);
    }

    @Transactional
    public ManualTask updateStatus(String publicId, UpdateTaskStatusRequest req) {
        Long hospitalId = requireHospitalId();
        ManualTask task = manualTaskRepository.findByPublicId(publicId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found"));
        if (!hospitalId.equals(task.getHospitalId())) {
            throw new UnauthorizedException("Access denied: task belongs to another hospital");
        }

        String newStatus = req.getStatus() == null ? null : req.getStatus().toUpperCase();
        String role = securityHelper.getCurrentUserRole();

        if ("CANCELLED".equals(newStatus)) {
            if (!"HOSPITAL_ADMIN".equals(role)) {
                throw new AccessDeniedException("Only admins can cancel tasks");
            }
            task.setStatus("CANCELLED");
        } else if ("IN_PROGRESS".equals(newStatus) || "COMPLETED".equals(newStatus)) {
            applyNurseTransition(task, newStatus, req);
        } else {
            throw new IllegalArgumentException("Invalid status: " + newStatus);
        }

        ManualTask saved = manualTaskRepository.save(task);

        audit("TASK_STATUS_UPDATED", "Updated task '" + saved.getTitle() + "' status to " + saved.getStatus(),
                hospitalId, saved.getId());

        broadcastRefresh(hospitalId, "task status update");
        return saved;
    }

    // --- helpers ---

    /** Applies an IN_PROGRESS/COMPLETED transition for the assigned nurse, enforcing the state machine. */
    private void applyNurseTransition(ManualTask task, String newStatus, UpdateTaskStatusRequest req) {
        Long currentUserId = securityHelper.getCurrentUserId();
        String currentStatus = task.getStatus();
        if (!task.getAssignedToNurseUserId().equals(currentUserId)) {
            throw new AccessDeniedException("Access denied: task assigned to another nurse");
        }
        if ("PENDING".equals(currentStatus)) {
            if (!"IN_PROGRESS".equals(newStatus) && !"COMPLETED".equals(newStatus)) {
                throw new IllegalArgumentException("Illegal status transition from PENDING to " + newStatus);
            }
        } else if ("IN_PROGRESS".equals(currentStatus)) {
            if (!"COMPLETED".equals(newStatus)) {
                throw new IllegalArgumentException("Illegal status transition from IN_PROGRESS to " + newStatus);
            }
        } else {
            throw new IllegalArgumentException("Task is already in a terminal state: " + currentStatus);
        }
        task.setStatus(newStatus);
        if ("COMPLETED".equals(newStatus)) {
            task.setCompletedAt(LocalDateTime.now());
            task.setCompletionRemarks(req.getCompletionRemarks());
        }
    }

    private Long requireHospitalId() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            throw new UnauthorizedException("Hospital ID not found in context");
        }
        return hospitalId;
    }

    private void audit(String action, String details, Long hospitalId, Long taskId) {
        try {
            auditLogService.logAction(action, details, securityHelper.getCurrentUserEmail(), hospitalId,
                    "TASK", taskId != null ? taskId.toString() : null, null);
        } catch (Exception e) {
            logger.warn("Failed to write audit log for {}", action, e);
        }
    }

    private void broadcastRefresh(Long hospitalId, String context) {
        try {
            webSocketHandler.broadcast(hospitalId, "{\"type\":\"REFRESH_DATA\"}");
        } catch (Exception e) {
            logger.warn("Failed to broadcast WebSocket refresh after {}", context, e);
        }
    }

    private void notifyAssignee(ManualTask task, String message) {
        if (notificationService != null) {
            try {
                notificationService.create(
                    task.getAssignedToNurseUserId(),
                    task.getHospitalId(),
                    "TASK_ASSIGNED",
                    "New Task Assigned",
                    message,
                    "MANUAL_TASK",
                    task.getId()
                );
            } catch (Exception e) {
                logger.warn("M8 notification hook failed (ignored): {}", e.getMessage());
            }
        }
    }
}
