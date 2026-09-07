package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * RecoveryObservation - one timed Aldrete score within a recovery episode.
 *
 * A small time-series, not a status: the patient is scored at intervals until fit to leave
 * PACU. The five Aldrete components each score 0-2 (total 0-10).
 */
@Entity
@Table(name = "ot_recovery_observations",
        indexes = @Index(name = "idx_recovery_obs_episode", columnList = "episode_id,observed_at"))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecoveryObservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    @Column(name = "episode_id", nullable = false)
    private Long episodeId;

    @Column(name = "observed_at", nullable = false)
    private LocalDateTime observedAt;

    /** Aldrete total 0-10, derived from the five components when they are given. */
    @Column(name = "aldrete_score")
    private Integer aldreteScore;

    @Column(name = "recorded_by_user_id")
    private Long recordedByUserId;

    @Column(name = "performed_by_nurse_id")
    private Long performedByNurseId;

    @Column(length = 255)
    private String note;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
