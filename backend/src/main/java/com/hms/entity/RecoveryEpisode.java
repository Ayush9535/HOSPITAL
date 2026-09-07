package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * RecoveryEpisode - a patient's stay in PACU after a procedure.
 *
 * A recovery episode is NOT a case state. The theatre is turned over (the case is
 * COMPLETED, the room freed) while the patient is still here; holding the case "busy" until
 * the patient reached a ward would destroy the utilisation figure the module exists to
 * produce. Recovery is therefore its own record, created only when the hospital's
 * RECOVERY_TRACKING policy asks for it.
 *
 * Aldrete scores are recorded over time as RecoveryObservation rows; a patient is fit to
 * leave PACU when the score qualifies.
 */
@Entity
@Table(name = "ot_recovery_episodes",
        uniqueConstraints = @UniqueConstraint(name = "uk_recovery_surgery", columnNames = {"surgery_id"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecoveryEpisode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    @Column(name = "surgery_id", nullable = false)
    private Long surgeryId;

    @Column(name = "patient_id", nullable = false)
    private Long patientId;

    @Column(name = "arrived_at", nullable = false)
    private LocalDateTime arrivedAt;

    @Column(name = "discharged_at")
    private LocalDateTime dischargedAt;

    /** Where the patient went from PACU. */
    @Column(name = "transfer_destination", length = 20)
    private String transferDestination; // WARD | ICU | HDU | HOME | MORTUARY

    @Column(name = "arrived_by_user_id")
    private Long arrivedByUserId;

    @Column(name = "discharged_by_user_id")
    private Long dischargedByUserId;
    /** OT-P0B: the tenant-owned recovery bay this patient occupies. Required at admit time. */
    @Column(name = "recovery_bay_id")
    private Long recoveryBayId;
}
