package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/** Per-hospital override for a clinical form's availability + who may edit it
 *  (Files & Access, Phase 1). A missing row means enabled + BOTH. */
@Entity
@Table(name = "hospital_form_access",
        uniqueConstraints = @UniqueConstraint(columnNames = {"hospital_id", "form_key"}))
@Data @NoArgsConstructor @AllArgsConstructor
public class HospitalFormAccess {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;
    @Column(name = "form_key", nullable = false, length = 60)
    private String formKey;
    @Column(nullable = false)
    private Boolean enabled = true;
    @Column(name = "access_role", nullable = false, length = 10)
    private String accessRole = "BOTH"; // DOCTOR | NURSE | BOTH
    @UpdateTimestamp @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
