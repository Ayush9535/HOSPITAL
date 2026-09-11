package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * CaseRole - a hospital's CUSTOM surgical-team role (e.g. HARVEST_SURGEON, PERFUSIONIST).
 *
 * Built-in roles live in CaseRoles; this table holds only what a hospital adds, so a new
 * specialty capability is a row, not a migration (Principle 3).
 */
@Entity
@Table(name = "case_roles",
        uniqueConstraints = @UniqueConstraint(name = "uk_case_role", columnNames = {"hospital_id", "code"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CaseRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    @Column(nullable = false, length = 40)
    private String code;

    @Column(nullable = false, length = 100)
    private String label;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;
}
