package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * IcuStay - one period of critical care inside an existing IPD admission (ICU Phase 3).
 *
 * <p>Not a patient, not an admission, and not a status on one. The patient is admitted exactly as
 * before; this records that part of that admission was spent in a critical-care ward, and carries
 * the facts a bed cannot: when critical care began, why, under which intensivist, and how it
 * ended. Same shape as {@code RecoveryEpisode} one level up — a phase of an episode, not a state
 * of it.
 *
 * <p>An admission may have MANY stays, at most one ACTIVE. A patient who goes ICU → ward → ICU has
 * two rows; that is ICU readmission, a real quality indicator, and it falls out for free.
 *
 * <p>A closed stay is immutable. There is deliberately no {@code is_active} soft delete: a
 * hideable critical-care episode would falsify the length-of-stay and readmission figures the
 * module exists to produce.
 */
@Entity
@Table(name = "icu_stay",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_icu_stay_active", columnNames = {"hospital_id", "active_marker"}),
        indexes = {
                @Index(name = "idx_icu_stay_admission", columnList = "ipd_admission_id"),
                @Index(name = "idx_icu_stay_hospital", columnList = "hospital_id")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IcuStay {

    public static final String ACTIVE = "ACTIVE";
    public static final String CLOSED = "CLOSED";

    /** How the patient reached critical care. */
    public static final String SRC_EMERGENCY = "EMERGENCY";
    public static final String SRC_OPD = "OPD";
    public static final String SRC_WARD = "WARD";
    public static final String SRC_OT_RECOVERY = "OT_RECOVERY";
    public static final String SRC_ICU_TRANSFER = "ICU_TRANSFER";
    public static final String SRC_EXTERNAL_REFERRAL = "EXTERNAL_REFERRAL";

    /** How the stay ended. */
    public static final String DISP_WARD = "WARD";
    public static final String DISP_HOME = "HOME";
    public static final String DISP_LAMA = "LAMA";
    public static final String DISP_REFERRED_OUT = "REFERRED_OUT";
    public static final String DISP_EXPIRED = "EXPIRED";
    public static final String DISP_ANOTHER_ICU = "ANOTHER_ICU";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true)
    private String publicId;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    /** Immutable after insert: a stay cannot be moved between admissions. */
    @Column(name = "ipd_admission_id", nullable = false)
    private Long ipdAdmissionId;

    /** Denormalised for reads; always equals the admission's patient. */
    @Column(name = "patient_id", nullable = false)
    private Long patientId;

    /** The critical-care ward. Equals the admission's ward while ACTIVE. */
    @Column(name = "ward_id", nullable = false)
    private Long wardId;

    @Column(name = "status", nullable = false, length = 10,
            columnDefinition = "VARCHAR(10) NOT NULL DEFAULT 'ACTIVE'")
    private String status = ACTIVE;

    @Column(name = "source", nullable = false, length = 20)
    private String source;

    /**
     * Discriminated by {@link #source}: an OPD id, the ward stepped up from, a recovery episode,
     * or the stay just closed. Deliberately not a foreign key — the referent type varies. Reads
     * must tolerate an unresolvable value rather than fail.
     */
    @Column(name = "source_ref_id")
    private Long sourceRefId;

    @Column(name = "admitted_at", nullable = false)
    private LocalDateTime admittedAt;

    @Column(name = "admission_reason", length = 255)
    private String admissionReason;

    /** Optional. When null the admission's treating doctor remains responsible. */
    @Column(name = "intensivist_doctor_id")
    private Long intensivistDoctorId;

    @Column(name = "admitted_by_user_id")
    private Long admittedByUserId;

    @Column(name = "disposition", length = 20)
    private String disposition;

    @Column(name = "discharged_at")
    private LocalDateTime dischargedAt;

    @Column(name = "discharged_by_user_id")
    private Long dischargedByUserId;

    /**
     * Holds {@code ipd_admission_id} while ACTIVE and NULL once closed.
     *
     * <p>This is what makes "at most one ACTIVE stay per admission" a database guarantee rather
     * than a service check a race can slip past. MySQL has no partial index, and
     * {@code UNIQUE(ipd_admission_id, status)} cannot work because CLOSED repeats — but MySQL
     * treats NULLs as distinct in a unique index, so closed rows never collide.
     */
    @Column(name = "active_marker")
    private Long activeMarker;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (this.publicId == null) this.publicId = java.util.UUID.randomUUID().toString();
        if (this.status == null) this.status = ACTIVE;
    }

    public boolean isActive() {
        return ACTIVE.equals(status);
    }
}
