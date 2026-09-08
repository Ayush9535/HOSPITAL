package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * ManualTask - admin-created, nurse-completed task (Phase 1 Nurse module, M7).
 * Allows assigning arbitrary nursing to-dos (e.g. dressing change, check lab report)
 * to a specific nurse.
 */
@Entity
@Table(name = "manual_tasks")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ManualTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String publicId;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    @Column(nullable = false, length = 150)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "assigned_to_nurse_user_id", nullable = false)
    private Long assignedToNurseUserId;

    @Column(name = "assigned_by_user_id", nullable = false)
    private Long assignedByUserId;

    @Column(name = "ipd_admission_id")
    private Long ipdAdmissionId;

    @Column(nullable = false, length = 10)
    private String priority = "MEDIUM"; // LOW / MEDIUM / HIGH

    @Column(nullable = false, length = 15)
    private String status = "PENDING"; // PENDING / IN_PROGRESS / COMPLETED / CANCELLED

    @Column(name = "due_date")
    private LocalDateTime dueDate;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "completion_remarks", length = 500)
    private String completionRemarks;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (this.publicId == null) {
            this.publicId = java.util.UUID.randomUUID().toString();
        }
        if (this.priority == null) {
            this.priority = "MEDIUM";
        }
        if (this.status == null) {
            this.status = "PENDING";
        }
    }
}
