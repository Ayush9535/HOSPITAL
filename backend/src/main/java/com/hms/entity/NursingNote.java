package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * NursingNote - a running observation note for an IPD admission (Phase 1 Nurse
 * module). Soft-deletable; editable by the author within a short window.
 * Category is reserved for future structured handover (free-form in Phase 1).
 */
@Entity
@Table(name = "nursing_notes")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NursingNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String publicId;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    @Column(name = "ipd_admission_id", nullable = false)
    private Long ipdAdmissionId;

    @Column(name = "patient_id", nullable = false)
    private Long patientId;

    @Column(name = "nurse_user_id", nullable = false)
    private Long nurseUserId;

    @Column(name = "performed_by_nurse_id")
    private Long performedByNurseId;

    @Column(name = "note_text", columnDefinition = "text", nullable = false)
    private String noteText;

    /** Orders — drugs / IV fluids accompanying the note; printed in its own column. */
    @Column(name = "orders", columnDefinition = "text")
    private String orders;

    @Column(name = "category", length = 40)
    private String category;

    @Column(name = "surgery_id")
    private Long surgeryId;

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;

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
        if (this.recordedAt == null) {
            this.recordedAt = LocalDateTime.now();
        }
    }
}
