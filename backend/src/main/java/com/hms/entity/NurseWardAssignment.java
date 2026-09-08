package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A temporary ward assignment: a nurse works {@code tempWardId} for [from,to].
 * The nurse's primary ward (NurseProfile.wardId) is never modified; coverage
 * reverts automatically once the date falls outside the window.
 */
@Entity
@Table(name = "nurse_ward_assignments")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NurseWardAssignment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true)
    private String publicId;
    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;
    @Column(name = "nurse_profile_id", nullable = false)
    private Long nurseProfileId;
    @Column(name = "temp_ward_id", nullable = false)
    private Long tempWardId;
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
