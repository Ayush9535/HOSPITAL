package com.hms.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** Immutable emergency decision. Bypassed gates use the closed PreOpGate vocabulary. */
@Entity
@Table(name = "surgery_emergency_overrides", indexes =
        @Index(name = "idx_seo_hospital_surgery_time", columnList = "hospital_id,surgery_id,recorded_at"))
@Data
public class SurgeryEmergencyOverride {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "surgery_id", nullable = false)
    private Long surgeryId;
    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;
    @Column(nullable = false, columnDefinition = "text")
    private String reason;
    /** Comma-delimited PreOpGate names, validated before persistence. */
    @Column(name = "bypassed_gates", nullable = false, length = 100)
    private String bypassedGates;
    @Column(name = "recorded_by_user_id", nullable = false)
    private Long recordedByUserId;
    @Column(name = "recorded_at", nullable = false, updatable = false)
    private LocalDateTime recordedAt;
}
