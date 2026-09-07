package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * IcuInfusionRate - one rate, in force from a moment (ICU Phase 6).
 *
 * <p>This is the "rate over time" the design asks for. A titration APPENDS a row; it never edits
 * one. The rate in force at any instant is the latest row whose {@code effectiveFrom} is at or
 * before it, so the history stays answerable long after the infusion has stopped.
 *
 * <p>The unit is stored exactly as entered, from a fixed registry. <b>No conversion is ever
 * performed</b> — turning MCG_KG_MIN into ML_HR needs a drug concentration and a body weight the
 * system does not hold, and computing it would be clinical dose calculation rather than recording.
 */
@Entity
@Table(name = "icu_infusion_rate", indexes = {
        @Index(name = "idx_icu_inf_rate_infusion", columnList = "icu_infusion_id"),
        @Index(name = "idx_icu_inf_rate_effective", columnList = "icu_infusion_id,effective_from")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IcuInfusionRate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true)
    private String publicId;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    @Column(name = "icu_infusion_id", nullable = false)
    private Long icuInfusionId;

    /** Fractional by necessity: 0.05 mcg/kg/min is a real rate. */
    @Column(name = "rate_value", nullable = false, precision = 12, scale = 3)
    private BigDecimal rateValue;

    /** A key from {@code InfusionRateUnitRegistry}; stored as entered, never converted. */
    @Column(name = "rate_unit", nullable = false, length = 20)
    private String rateUnit;

    @Column(name = "effective_from", nullable = false)
    private LocalDateTime effectiveFrom;

    @Column(name = "recorded_by_user_id")
    private Long recordedByUserId;

    @Column(name = "performed_by_nurse_id")
    private Long performedByNurseId;

    /**
     * The rate row this one corrects, when it is a correction rather than a titration.
     *
     * <p>A titration is a NEW clinical fact — the rate genuinely changed. A correction says the
     * recorded rate was wrong. Both append, and in both cases the earlier row stays readable; only
     * a corrected row is excluded from the history the chart treats as what actually happened.
     */
    @Column(name = "supersedes_rate_id")
    private Long supersedesRateId;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (this.publicId == null) this.publicId = java.util.UUID.randomUUID().toString();
        if (this.isActive == null) this.isActive = true;
    }
}
