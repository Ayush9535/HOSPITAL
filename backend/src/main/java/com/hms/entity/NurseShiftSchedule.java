package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor; import lombok.Data; import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDate; import java.time.LocalDateTime; import java.time.LocalTime;

/** One nurse's shift on one date. start/end are SNAPSHOTS of the template at assign time. */
@Entity
@Table(name = "nurse_shift_schedules",
       uniqueConstraints = @UniqueConstraint(name = "UK_nss_nurse_date", columnNames = {"nurse_profile_id","shift_date"}))
@Data @NoArgsConstructor @AllArgsConstructor
public class NurseShiftSchedule {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true) private String publicId;
    @Column(name = "hospital_id", nullable = false) private Long hospitalId;
    @Column(name = "nurse_profile_id", nullable = false) private Long nurseProfileId;
    @Column(name = "ward_id") private Long wardId;
    @Column(name = "shift_date", nullable = false) private LocalDate shiftDate;
    @Column(name = "shift_template_id", nullable = false) private Long shiftTemplateId;
    @Column(name = "start_time", nullable = false) private LocalTime startTime;
    @Column(name = "end_time", nullable = false) private LocalTime endTime;
    @Column(name = "created_by_user_id") private Long createdByUserId;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
    @PrePersist public void pre() { if (publicId == null) publicId = java.util.UUID.randomUUID().toString(); }
}
