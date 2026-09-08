package com.hms.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** Immutable anaesthesia-clearance decision history for one surgery. */
@Entity
@Table(name = "surgery_anaesthesia_clearances", indexes = {
        @Index(name = "idx_sac_hospital_surgery_time", columnList = "hospital_id,surgery_id,recorded_at"),
        @Index(name = "idx_sac_surgery", columnList = "surgery_id")
})
@Data
public class SurgeryAnaesthesiaClearance {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "surgery_id", nullable = false)
    private Long surgeryId;
    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private AnaesthesiaClearanceOutcome outcome;
    @Column(name = "conditions_comments", columnDefinition = "text")
    private String conditionsComments;
    @Column(name = "recorded_by_user_id", nullable = false)
    private Long recordedByUserId;
    @Column(name = "recorded_at", nullable = false, updatable = false)
    private LocalDateTime recordedAt;
}
