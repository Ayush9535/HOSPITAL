package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.time.LocalTime;

/** A reusable nurse shift definition (e.g. Morning 08:00-16:00). end<=start crosses midnight. */
@Entity
@Table(name = "shift_templates")
@Data @NoArgsConstructor @AllArgsConstructor
public class ShiftTemplate {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true)
    private String publicId;
    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;
    @Column(nullable = false, length = 60)
    private String name;
    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;
    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    @PrePersist public void pre() { if (publicId == null) publicId = java.util.UUID.randomUUID().toString(); }
}
