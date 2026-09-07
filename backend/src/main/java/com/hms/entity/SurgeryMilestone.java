package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * SurgeryMilestone - an append-only clinical fact with a time.
 *
 * These are NOT case states. A case is turned over (COMPLETED, theatre freed) while the
 * patient is still in recovery; anaesthesia start, incision, closure and anaesthesia end
 * are four distinct events that are not "started" or "finished". Conflating any of them
 * with the status would corrupt the timings the theatre is measured on.
 */
@Entity
@Table(name = "surgery_milestones", indexes = @Index(name = "idx_milestone_surgery", columnList = "surgery_id,occurred_at"))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SurgeryMilestone {

    // The clinically distinct events. Extend by adding a constant; no schema change.
    public static final String PATIENT_ENTERED_OT = "PATIENT_ENTERED_OT";
    public static final String ANAESTHESIA_START = "ANAESTHESIA_START";
    public static final String INCISION = "INCISION";
    public static final String CLOSURE = "CLOSURE";
    public static final String ANAESTHESIA_END = "ANAESTHESIA_END";
    public static final String LEFT_THEATRE = "LEFT_THEATRE";
    public static final String ARRIVED_RECOVERY = "ARRIVED_RECOVERY";
    public static final String LEFT_RECOVERY = "LEFT_RECOVERY";
    public static final String TRANSFERRED = "TRANSFERRED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    @Column(name = "surgery_id", nullable = false)
    private Long surgeryId;

    @Column(nullable = false, length = 30)
    private String milestone;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "recorded_by_user_id")
    private Long recordedByUserId;

    @Column(name = "performed_by_nurse_id")
    private Long performedByNurseId;

    @Column(length = 255)
    private String note;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
