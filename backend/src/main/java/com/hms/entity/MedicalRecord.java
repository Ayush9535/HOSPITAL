package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * MedicalRecord - Entity to store clinical details of a patient visit (OPD)
 * 
 * Captures symptoms, diagnosis, and treatment notes.
 * Linked to an Appointment (usually) and a Patient.
 * 
 * @author HMS Team
 * @version Phase-3
 */
@Entity
@Table(name = "medical_records")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MedicalRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String publicId;

    @PrePersist
    public void generateIds() {
        if (this.publicId == null) {
            this.publicId = java.util.UUID.randomUUID().toString();
        }
    }

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    @Column(name = "patient_id", nullable = false)
    private Long patientId;

    @Column(name = "doctor_id", nullable = false)
    private Long doctorId;

    @Column(name = "appointment_id", unique = true)
    private Long appointmentId;

    @Column(name = "opd_id", unique = true)
    private Long opdId;

    @Column(name = "ipd_admission_id")
    private Long ipdAdmissionId;

    @Column(name = "visit_type", nullable = false)
    private String visitType = "OPD"; // OPD or IPD

    @Column(length = 1000)
    private String symptoms;

    @Column(length = 1000)
    private String diagnosis;

    @Column(length = 2000)
    private String treatmentNotes;

    private LocalDate followUpDate;

    /**
     * What the doctor asked the patient to come back for. Kept separate from diagnosis and
     * treatment notes deliberately: those describe the visit that happened, this describes the
     * one that has not.
     */
    @Column(name = "follow_up_instructions", length = 1000)
    private String followUpInstructions;

    /**
     * Where this follow-up has got to. NULL means OPEN — every consultation written before this
     * column existed is therefore still actionable, which is the honest reading: nobody recorded
     * that those patients came back, so nothing may claim they did.
     *
     * <p>Deliberately absent: RESCHEDULED. Moving a follow-up changes {@link #followUpDate} and
     * the record stays open; a terminal state for it would end an appointment that is still
     * outstanding. DUE_TODAY, OVERDUE and UPCOMING are absent for the opposite reason — they are
     * functions of the date and would otherwise need something to rewrite them every midnight.
     */
    @Column(name = "follow_up_status", length = 20)
    private String followUpStatus;

    /**
     * The OPD created when the patient actually returned for this follow-up.
     *
     * <p>This column IS the invariant. A follow-up is one row, so it can hold one actioned OPD
     * and no more; the claim is taken with a conditional UPDATE that only succeeds while this is
     * still NULL, so two receptionists pressing "Patient Arrived" together cannot both win. A
     * unique index closes the other direction — one OPD cannot be claimed by two follow-ups.
     */
    @Column(name = "actioned_opd_id")
    private Long actionedOpdId;

    /** Who said the patient had arrived, and when. The follow-up date itself is never rewritten. */
    @Column(name = "actioned_by_user_id")
    private Long actionedByUserId;

    @Column(name = "actioned_at")
    private LocalDateTime actionedAt;

    /** Open, whether that is recorded explicitly or by the absence of any later decision. */
    public static final String FOLLOW_UP_OPEN = "OPEN";
    /** The patient returned and an encounter was created from this follow-up. */
    public static final String FOLLOW_UP_ACTIONED = "ACTIONED";
    /** Closed without a return visit being needed. */
    public static final String FOLLOW_UP_COMPLETED = "COMPLETED";
    /** Called off — the patient is not expected back for this. */
    public static final String FOLLOW_UP_CANCELLED = "CANCELLED";

    /** True while this follow-up can still be acted on. */
    public boolean isFollowUpOpen() {
        return followUpDate != null
                && (followUpStatus == null || FOLLOW_UP_OPEN.equals(followUpStatus));
    }

    @Column(name = "administered_items_json", length = 3000)
    private String administeredItemsJson;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
