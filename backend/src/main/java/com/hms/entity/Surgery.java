package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Surgery - an Operation Theatre case (OT module).
 *
 * The surgery is its own aggregate, anchored on the PATIENT. An IPD admission is
 * optional: a day-care procedure (cataract, endoscopy, minor orthopaedics) has
 * {@code ipdAdmissionId == null} and {@code encounterType == DAY_CARE}. Any query
 * that joins through the admission must tolerate a null.
 *
 * Own status lifecycle; IpdAdmission.status is untouched.
 */
@Entity
@Table(name = "surgeries")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Surgery {

    public static final String REQUESTED = "REQUESTED";
    public static final String SCHEDULED = "SCHEDULED";
    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String COMPLETED = "COMPLETED";
    public static final String CANCELLED = "CANCELLED";

    /** Inpatient procedure hanging off an IPD admission. */
    public static final String ENCOUNTER_IPD = "IPD";
    /** Same-day procedure with no admission. */
    public static final String ENCOUNTER_DAY_CARE = "DAY_CARE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String publicId;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    /** Null for a DAY_CARE procedure. */
    @Column(name = "ipd_admission_id")
    private Long ipdAdmissionId;

    @Column(name = "encounter_type", nullable = false, length = 20)
    private String encounterType = ENCOUNTER_IPD;

    // Waiting-list ordering. The waiting list itself is a query (APPROVED with no slot),
    // so these are attributes of the case, not a status.
    @Column(name = "waitlist_priority", nullable = false)
    private Integer waitlistPriority = 0;

    @Column(name = "target_date")
    private LocalDate targetDate;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "patient_id", nullable = false)
    private Long patientId;

    @Column(name = "procedure_name", length = 255)
    private String procedureName;

    @Column(name = "clinical_notes", columnDefinition = "text")
    private String clinicalNotes;

    @Column(name = "priority", length = 20)
    private String priority; // ELECTIVE | EMERGENCY

    @Column(name = "preferred_date")
    private LocalDate preferredDate;

    @Column(name = "requested_by_doctor_id")
    private Long requestedByDoctorId;

    @Column(name = "requested_by_user_id")
    private Long requestedByUserId;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "status", nullable = false, length = 20)
    private String status = REQUESTED;

    /** Optimistic revision exposed to schedule/reschedule clients. */
    @Version
    @Column(name = "lifecycle_version", nullable = false)
    private Long lifecycleVersion;

    @Column(name = "surgeon_doctor_id")
    private Long surgeonDoctorId;

    // Display name of the operator: usually the assigned doctor's name, but may be
    // a free-text "Other" operator (e.g. external / anaesthetist-led) when no listed
    // doctor is assigned (surgeonDoctorId is null).
    @Column(name = "surgeon_name", length = 255)
    private String surgeonName;

    // Optional anaesthetist present for the surgery (free text).
    @Column(name = "anaesthetist_name", length = 255)
    private String anaesthetistName;

    @Column(name = "scheduled_at")
    private LocalDateTime scheduledAt;

    /**
     * Legacy. An OT used to be a ward whose name contained "OT". Retained and dual-read
     * for one release so existing rows resolve; ot_room_id is the real link.
     */
    @Column(name = "ot_ward_id")
    private Long otWardId;

    @Column(name = "ot_room_id")
    private Long otRoomId;

    /** Drives interval booking. Absent means the 60-minute default used by the clash query. */
    @Column(name = "estimated_duration_minutes")
    private Integer estimatedDurationMinutes;

    @Column(name = "ot_bed_id")
    private Long otBedId;

    @Column(name = "scheduled_by_user_id")
    private Long scheduledByUserId;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    // The operative note, authored by the surgeon immediately post-op (NABH COP).
    @Column(name = "operative_note", columnDefinition = "text")
    private String operativeNote;

    @Column(name = "operative_note_by_user_id")
    private Long operativeNoteByUserId;

    @Column(name = "operative_note_at")
    private LocalDateTime operativeNoteAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (this.publicId == null) this.publicId = java.util.UUID.randomUUID().toString();
        if (this.requestedAt == null) this.requestedAt = LocalDateTime.now();
        if (this.status == null) this.status = REQUESTED;
        if (this.encounterType == null) {
            this.encounterType = this.ipdAdmissionId != null ? ENCOUNTER_IPD : ENCOUNTER_DAY_CARE;
        }
    }
}
