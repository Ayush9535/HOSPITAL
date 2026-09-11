package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor; import lombok.Data; import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;

/** One bed status transition: previous -> new, who, when, why. */
@Entity
@Table(name = "bed_status_audits")
@Data @NoArgsConstructor @AllArgsConstructor
public class BedStatusAudit {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true) private String publicId;
    @Column(name = "hospital_id", nullable = false) private Long hospitalId;
    @Column(name = "bed_id", nullable = false) private Long bedId;
    @Column(name = "ward_id") private Long wardId;
    @Column(name = "previous_status", length = 20) private String previousStatus;
    @Column(name = "new_status", nullable = false, length = 20) private String newStatus;
    @Column(name = "changed_by_user_id") private Long changedByUserId;
    @Column(name = "remarks", length = 255) private String remarks;
    @CreationTimestamp @Column(name = "changed_at", nullable = false, updatable = false) private LocalDateTime changedAt;
    @PrePersist public void pre() { if (publicId == null) publicId = java.util.UUID.randomUUID().toString(); }
}
