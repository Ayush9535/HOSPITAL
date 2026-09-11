package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * IcuScoreTypeSetting - whether a hospital uses a given severity score (ICU Phase 8).
 *
 * <p>Overrides only, with a lazy default: a {@code SeverityScoreRegistry} type with no row is
 * <b>enabled</b>. No seeding, and no migration when the registry grows — the
 * {@code hospital_vitals} / {@code icu_ventilator_parameter} pattern.
 *
 * <p><b>Deliberately thinner than ICU-7's parameter catalogue</b> (D-2). There is no display name,
 * no unit, no category, no custom type and no delete, because a hospital may choose whether it
 * runs SOFA — not what SOFA is. Renaming a component would leave a score that is no longer
 * comparable to anyone else's, which defeats the purpose of a standardised score.
 *
 * <p>Configuration only. Nothing here is ever written into {@code icu_severity_score}, and
 * toggling a row never rewrites one.
 */
@Entity
@Table(name = "icu_score_type_setting",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_icu_score_type", columnNames = {"hospital_id", "score_type"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IcuScoreTypeSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true)
    private String publicId;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    /** A {@code SeverityScoreRegistry} key. Identity; never edited. */
    @Column(name = "score_type", nullable = false, length = 20)
    private String scoreType;

    @Column(nullable = false)
    private Boolean enabled = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (publicId == null) publicId = java.util.UUID.randomUUID().toString();
        if (enabled == null) enabled = true;
    }
}
