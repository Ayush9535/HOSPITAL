package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A nurse substitution: {@code replacementNurseProfileId} covers
 * {@code primaryNurseProfileId}'s patients for [from,to]. The primary's
 * assignment rows are never modified; coverage reverts automatically after the
 * window.
 */
@Entity
@Table(name = "nurse_substitutions")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NurseSubstitution {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true)
    private String publicId;
    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;
    @Column(name = "primary_nurse_profile_id", nullable = false)
    private Long primaryNurseProfileId;
    @Column(name = "replacement_nurse_profile_id", nullable = false)
    private Long replacementNurseProfileId;
    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;
    @Column(name = "to_date", nullable = false)
    private LocalDate toDate;
    @Column(length = 255)
    private String reason;
    @Column(name = "created_by_user_id")
    private Long createdByUserId;
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void pre() {
        if (publicId == null) publicId = java.util.UUID.randomUUID().toString();
    }
}
