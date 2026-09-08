package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * SurgeryTeamMember - one person on a surgery's team, in one role.
 *
 * Replaces the free-text surgeon_name / anaesthetist_name columns, which made cardiac and
 * transplant cases impossible to represent and the audit trail unattributable. Those
 * columns survive on Surgery as a nullable fallback for an EXTERNAL operator who has no
 * user row -- which is legitimate denormalisation, mirrored here by external_name.
 */
@Entity
@Table(name = "surgery_team_members", indexes = @Index(name = "idx_team_surgery", columnList = "surgery_id"))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SurgeryTeamMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    @Column(name = "surgery_id", nullable = false)
    private Long surgeryId;

    /** A built-in code (CaseRoles) or a hospital custom code (case_roles). */
    @Column(name = "case_role_code", nullable = false, length = 40)
    private String caseRoleCode;

    /** Internal staff member (a user id). Null for an external operator. */
    @Column(name = "user_id")
    private Long userId;

    /** External operator with no login. Exactly one of userId / externalName is set. */
    @Column(name = "external_name", length = 255)
    private String externalName;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
