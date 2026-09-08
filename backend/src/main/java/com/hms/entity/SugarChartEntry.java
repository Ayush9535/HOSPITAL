package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * SugarChartEntry - one blood-sugar reading + treatment for an IPD admission
 * (Phase 1 Nurse module), for diabetic patients. Timestamped at entry. Mirrors
 * the nursing-note lifecycle (author edit/soft-delete within a window).
 */
@Entity
@Table(name = "sugar_chart_entries")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SugarChartEntry {

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

    @Column(name = "blood_sugar", length = 60)
    private String bloodSugar;

    @Column(name = "treatment", columnDefinition = "text")
    private String treatment;

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
