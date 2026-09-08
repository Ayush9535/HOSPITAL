package com.hms.entity.pharmacy;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * PharmacyBranch - a medical shop (outlet) operated under one Multi Pharmacy owner tenant.
 *
 * Branches are sub-entities of a single pharmacy tenant (hospital_id). Each branch has
 * exactly one login (a PHARMACIST User, referenced by login_user_id). Branch-scoped
 * pharmacy data carries branch_id (added in a later phase).
 */
@Entity
@Table(name = "pharmacy_branch")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PharmacyBranch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "address")
    private String address;

    @Column(name = "phone")
    private String phone;

    /** The single PHARMACIST User that logs in for this branch. */
    @Column(name = "login_user_id")
    private Long loginUserId;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
