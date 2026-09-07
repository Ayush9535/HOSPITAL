package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * MedicationAdministration - a record of what happened to a prescribed dose
 * (Phase 1 Nurse module). References the doctor-authored {@code prescriptions}
 * row (never duplicating medicine details). Corrections are made by appending a
 * new record, so history is immutable/audit-friendly.
 */
@Entity
@Table(name = "medication_administrations")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MedicationAdministration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String publicId;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    @Column(name = "ipd_admission_id", nullable = false)
    private Long ipdAdmissionId;

    @Column(name = "prescription_id", nullable = false)
    private Long prescriptionId;

    @Column(name = "patient_id", nullable = false)
    private Long patientId;

    @Column(name = "nurse_user_id", nullable = false)
    private Long nurseUserId;

    @Column(name = "performed_by_nurse_id")
    private Long performedByNurseId;

    @Column(name = "scheduled_time")
    private LocalDateTime scheduledTime;

    @Column(name = "administered_time")
    private LocalDateTime administeredTime;

    // GIVEN / SKIPPED / DELAYED / REFUSED / NOT_AVAILABLE
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "remarks", length = 500)
    private String remarks;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (this.publicId == null) {
            this.publicId = java.util.UUID.randomUUID().toString();
        }
    }
}
