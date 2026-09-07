package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Notification - stores in-app notifications for users, specifically nurses (Phase 1 Nurse module, M8).
 */
@Entity
@Table(name = "notifications")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String publicId;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    @Column(name = "recipient_user_id", nullable = false)
    private Long recipientUserId;

    @Column(nullable = false, length = 30)
    private String type; // ASSIGNMENT, TASK_ASSIGNED, PRESCRIPTION_ACTIVE, PRESCRIPTION_STOPPED

    @Column(nullable = false, length = 150)
    private String title;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String message;

    @Column(name = "reference_type", length = 50)
    private String referenceType; // PATIENT_NURSE_ASSIGNMENT, MANUAL_TASK, PRESCRIPTION

    @Column(name = "reference_id")
    private Long referenceId;

    @Column(name = "is_read", nullable = false)
    private Boolean isRead = false;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (this.publicId == null) {
            this.publicId = "ntf-" + java.util.UUID.randomUUID().toString();
        }
        if (this.isRead == null) {
            this.isRead = false;
        }
    }
}
