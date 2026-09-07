package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * RecoveryBay - a named PACU/recovery location, as a first-class tenant-owned resource.
 *
 * Deliberately not an OtRoom: a theatre is freed the moment its case COMPLETEs, while the
 * patient may still be recovering, so occupancy in one must never be read from the other.
 * Deliberately not a Ward/Bed either -- recovery has its own tiny lifecycle (named,
 * active/inactive) and does not need bed-status machinery (cleaning, maintenance, ...).
 *
 * Occupancy is derived, not stored: a bay is occupied exactly when an
 * ot_recovery_episodes row with dischargedAt IS NULL references it
 * (RecoveryEpisodeRepository#existsActiveByRecoveryBayId). Admission locks the bay row
 * (PESSIMISTIC_WRITE) before checking this, so two concurrent admissions cannot both succeed
 * against the same bay.
 */
@Entity
@Table(name = "recovery_bays",
        uniqueConstraints = @UniqueConstraint(name = "uk_recovery_bay_name", columnNames = {"hospital_id", "name"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecoveryBay {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true)
    private String publicId;
    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;
    @Column(nullable = false, length = 100)
    private String name;
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (this.publicId == null) this.publicId = java.util.UUID.randomUUID().toString();
    }
}
