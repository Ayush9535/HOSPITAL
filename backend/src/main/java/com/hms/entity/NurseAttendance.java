package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * One nurse's attendance for one date. Shift fields are SNAPSHOTS of the
 * nurse's schedule for that date, so later roster edits never rewrite history.
 */
@Entity
@Table(name = "nurse_attendance",
       uniqueConstraints = @UniqueConstraint(name = "UK_na_nurse_date", columnNames = {"nurse_profile_id", "attendance_date"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NurseAttendance {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true)
    private String publicId;
    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;
    @Column(name = "nurse_profile_id", nullable = false)
    private Long nurseProfileId;
    @Column(name = "ward_id")
    private Long wardId;
    @Column(name = "attendance_date", nullable = false)
    private LocalDate attendanceDate;
    @Column(nullable = false, length = 20)
    private String status;
    @Column(name = "shift_template_id")
    private Long shiftTemplateId;
    @Column(name = "shift_start_time")
    private LocalTime shiftStartTime;
    @Column(name = "shift_end_time")
    private LocalTime shiftEndTime;
    @Column(name = "check_in_time")
    private LocalTime checkInTime;
    @Column(name = "check_out_time")
    private LocalTime checkOutTime;
    @Column(length = 255)
    private String remarks;
    @Column(name = "marked_by_user_id")
    private Long markedByUserId;
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void pre() {
        if (publicId == null) publicId = java.util.UUID.randomUUID().toString();
    }
}
