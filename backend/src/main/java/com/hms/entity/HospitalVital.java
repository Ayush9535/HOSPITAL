package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * HospitalVital - a hospital's OPD vitals configuration. Rows exist either as an
 * enable/disable override for a built-in vital (see FormRegistry-style
 * VitalRegistry) or as a hospital-defined custom vital (isCustom = true).
 * A built-in with no row is enabled by default. Only custom vitals are deletable.
 */
@Entity
@Table(name = "hospital_vitals",
        uniqueConstraints = @UniqueConstraint(columnNames = {"hospital_id", "vital_key"}))
@Data @NoArgsConstructor @AllArgsConstructor
public class HospitalVital {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "public_id", nullable = false, unique = true)
    private String publicId;
    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;
    @Column(name = "vital_key", nullable = false, length = 60)
    private String vitalKey;
    @Column(nullable = false, length = 60)
    private String label;
    @Column(length = 20)
    private String unit;
    @Column(nullable = false)
    private Boolean enabled = true;
    @Column(name = "is_custom", nullable = false)
    private Boolean isCustom = false;
    @Column(name = "sort_order")
    private Integer sortOrder = 0;
    @CreationTimestamp @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist public void pre() { if (publicId == null) publicId = java.util.UUID.randomUUID().toString(); }
}
