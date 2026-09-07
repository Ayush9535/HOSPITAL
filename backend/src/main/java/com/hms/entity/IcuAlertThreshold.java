package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * IcuAlertThreshold - one hospital's alert threshold for one metric (ICU Phase 9).
 *
 * <p><b>No row means no alert.</b> Unlike every other config table in the module there is no lazy
 * default here, because a default threshold would be the system stating a clinical norm. The
 * table ships empty and stays empty until a hospital types a number into it.
 *
 * <p>Per hospital only (D-3): no ward-level and no patient-level rows. A per-patient limit is a
 * clinical order, not a setting.
 *
 * <p>Configuration only. Nothing here is written into a clinical record, and changing a row never
 * rewrites one.
 */
@Entity
@Table(name = "icu_alert_threshold",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_icu_alert_metric", columnNames = {"hospital_id", "source", "metric_key"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IcuAlertThreshold {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true)
    private String publicId;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    /** An AlertMetricRegistry source. ICU-9 only ever stores VITALS (D-1). */
    @Column(nullable = false, length = 20)
    private String source;

    @Column(name = "metric_key", nullable = false, length = 60)
    private String metricKey;

    /** Fires when the recorded value is BELOW this. Null means no lower bound. */
    @Column(name = "min_value", precision = 12, scale = 3)
    private BigDecimal minValue;

    /** Fires when the recorded value is ABOVE this. Null means no upper bound. */
    @Column(name = "max_value", precision = 12, scale = 3)
    private BigDecimal maxValue;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(name = "updated_by_user_id")
    private Long updatedByUserId;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (publicId == null) publicId = java.util.UUID.randomUUID().toString();
        if (enabled == null) enabled = true;
        if (isActive == null) isActive = true;
    }
}
