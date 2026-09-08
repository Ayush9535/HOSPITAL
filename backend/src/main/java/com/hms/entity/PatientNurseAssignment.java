package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * PatientNurseAssignment - manual assignment of a nurse to an IPD admission
 * (Phase 1 Nurse module).
 *
 * Exactly one active assignment may exist per admission at a time; reassignment
 * closes the previous active row (isActive=false, unassignedAt set) and opens a
 * new one, so the full assignment history is preserved. Auto-closed when the
 * admission is discharged. Hospital-scoped.
 */
@Entity
@Table(name = "patient_nurse_assignments")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PatientNurseAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String publicId;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    @Column(name = "ipd_admission_id", nullable = false)
    private Long ipdAdmissionId;

    /** Denormalized for fast "my patients" lookups without joining admissions. */
    @Column(name = "patient_id", nullable = false)
    private Long patientId;

    /** users.id of the assigned nurse (role = NURSE). */
    @Column(name = "nurse_user_id", nullable = false)
    private Long nurseUserId;

    @Column(name = "assigned_by_user_id", nullable = false)
    private Long assignedByUserId;

    @Column(name = "assigned_at", nullable = false)
    private LocalDateTime assignedAt;

    @Column(name = "unassigned_at")
    private LocalDateTime unassignedAt;

    @Column(name = "notes", length = 255)
    private String notes;

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
        if (this.assignedAt == null) {
            this.assignedAt = LocalDateTime.now();
        }
    }
}
