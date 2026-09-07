package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * IcuVentilatorParameter - a hospital's ventilator parameter configuration (ICU Phase 7, D-5).
 *
 * <p>Stores <b>overrides and custom definitions only</b>, exactly as {@code hospital_vitals} does:
 * a built-in from {@link com.hms.service.hospital.icu.VentilatorParameterRegistry} with no row is
 * enabled. Lazy defaults mean no seeding and no migration when the registry grows.
 *
 * <p><b>{@code paramKey} is the identity and never changes.</b> {@code displayName} is a label and
 * carries none. Clinical rows store the key, so renaming a parameter cannot orphan a recorded
 * value — which is precisely what the vitals implementation cannot do today, since it derives a
 * custom vital's key from its name and offers no rename at all.
 *
 * <p>This is configuration, not a clinical observation. Nothing here is ever written into
 * {@code icu_ventilator_setting}, and changing a row never rewrites one.
 */
@Entity
@Table(name = "icu_ventilator_parameter",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_icu_vent_param_key", columnNames = {"hospital_id", "param_key"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IcuVentilatorParameter {

    /** What the parameter is: dialled into the machine, or read off it. */
    public static final String SETTING = "SETTING";
    public static final String OBSERVATION = "OBSERVATION";

    /** How a value is validated. MODE is reserved for the built-in mode parameter. */
    public static final String NUMBER = "NUMBER";
    public static final String TEXT = "TEXT";
    public static final String MODE = "MODE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true)
    private String publicId;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    /** Stable identity. Assigned once, never re-derived, never edited. */
    @Column(name = "param_key", nullable = false, length = 60)
    private String paramKey;

    /** Editable label. Carries no identity. */
    @Column(name = "display_name", nullable = false, length = 60)
    private String displayName;

    /** Display only. Never used in arithmetic, never converted. */
    @Column(length = 20)
    private String unit;

    @Column(nullable = false, length = 20)
    private String category = SETTING;

    @Column(name = "value_type", nullable = false, length = 20)
    private String valueType = NUMBER;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(name = "is_custom", nullable = false)
    private Boolean isCustom = false;

    @Column(name = "sort_order")
    private Integer sortOrder = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (publicId == null) publicId = java.util.UUID.randomUUID().toString();
        if (enabled == null) enabled = true;
        if (isCustom == null) isCustom = false;
    }
}
