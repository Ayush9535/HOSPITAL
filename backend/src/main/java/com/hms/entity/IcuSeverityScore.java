package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * IcuSeverityScore - one timed severity scoring (ICU Phase 8).
 *
 * <p>Shaped after {@code RecoveryObservation} per the design: a timed row per scoring, never
 * updated, because what the ward round discusses is the trend. "SOFA was 9 on Monday, 6 today"
 * needs both rows; a single current number answers nothing.
 *
 * <p><b>Components live in {@code components_json}, keyed by component key.</b> No column per
 * component: SOFA has six and APACHE II has none (D-4), and one table holding both would carry a
 * row of mostly-null columns. Same reasoning as ICU-7's {@code values_json}.
 *
 * <p><b>{@code total_score} is stored, not recomputed</b> (D-6). Unlike ICU-5's fluid balance —
 * a derived view of rows that can each be corrected — a total is part of what was charted at that
 * moment: the number that went in the notes and onto the ward round. It is summed on write and
 * read back verbatim.
 *
 * <p>Keyed on the <b>admission</b>, not the ICU stay: a patient scored in MICU and transferred to
 * SICU has two stays and one continuous series. {@code icuStayId} is provenance only.
 */
@Entity
@Table(name = "icu_severity_score", indexes = {
        @Index(name = "idx_icu_score_admission",
                columnList = "ipd_admission_id,score_type,scored_at"),
        @Index(name = "idx_icu_score_hospital", columnList = "hospital_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IcuSeverityScore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true)
    private String publicId;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    /** The admission is the key, so a bed or ward move carries the series with it. */
    @Column(name = "ipd_admission_id", nullable = false)
    private Long ipdAdmissionId;

    @Column(name = "patient_id", nullable = false)
    private Long patientId;

    /** Which ICU stay this fell inside, when it fell inside one. Provenance only. */
    @Column(name = "icu_stay_id")
    private Long icuStayId;

    /** A {@code SeverityScoreRegistry} key. */
    @Column(name = "score_type", nullable = false, length = 20)
    private String scoreType;

    /** {@code {"respiratory":2,"renal":1}} keyed by component key. Null for a total-only score. */
    @Column(name = "components_json", columnDefinition = "text")
    private String componentsJson;

    /** The sum of the entered components, or the total the clinician entered directly. */
    @Column(name = "total_score")
    private Integer totalScore;

    @Column(name = "scored_at", nullable = false)
    private LocalDateTime scoredAt;

    @Column(name = "recorded_by_user_id")
    private Long recordedByUserId;

    @Column(name = "performed_by_nurse_id")
    private Long performedByNurseId;

    /** Set on a correction; points at the row this one replaces, which stays readable. */
    @Column(name = "supersedes_score_id")
    private Long supersedesScoreId;

    @Column(length = 255)
    private String note;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (this.publicId == null) this.publicId = java.util.UUID.randomUUID().toString();
        if (this.isActive == null) this.isActive = true;
    }
}
