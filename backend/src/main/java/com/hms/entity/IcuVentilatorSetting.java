package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * IcuVentilatorSetting - one timed ventilator snapshot (ICU Phase 7).
 *
 * <p>Shaped after {@code RecoveryObservation} per the design: a timed row per change, never
 * updated, so "what was the vent set to at 4 a.m.?" stays answerable the morning after.
 *
 * <p><b>Values live in {@code values_json}, keyed by {@code param_key}</b> (D-5). There is no
 * column per parameter, deliberately: the catalogue is configurable, and a column per parameter
 * would mean a migration every time a hospital wanted one of its own. Same shape as
 * {@code opd.custom_vitals}, applied to every parameter rather than only the custom ones, because
 * here even the built-ins can be renamed or switched off.
 *
 * <p>{@code ventilationStatus} stays a typed NOT NULL column (D-1): it is what distinguishes a
 * ventilated row from an extubation row, it is structural rather than configurable, and it must be
 * queryable without parsing JSON.
 *
 * <p>Keyed on the <b>admission</b>, not the ICU stay: a patient ventilated in MICU and transferred
 * to SICU has two stays and one continuous course of ventilation. {@code icuStayId} is provenance
 * only (D-2).
 */
@Entity
@Table(name = "icu_ventilator_setting", indexes = {
        @Index(name = "idx_icu_vent_admission", columnList = "ipd_admission_id,observed_at"),
        @Index(name = "idx_icu_vent_hospital", columnList = "hospital_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IcuVentilatorSetting {

    /** Invasive ventilation via an endotracheal or tracheostomy tube. */
    public static final String INVASIVE = "INVASIVE";
    /** Non-invasive ventilation via a mask. */
    public static final String NIV = "NIV";
    /** Not ventilated — how an extubation is recorded, rather than inferred from a gap. */
    public static final String OFF = "OFF";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true)
    private String publicId;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    /** The admission is the key, so a bed or ward move carries the ventilation course with it. */
    @Column(name = "ipd_admission_id", nullable = false)
    private Long ipdAdmissionId;

    @Column(name = "patient_id", nullable = false)
    private Long patientId;

    /** Which ICU stay this fell inside, when it fell inside one. Provenance only (D-2). */
    @Column(name = "icu_stay_id")
    private Long icuStayId;

    @Column(name = "ventilation_status", nullable = false, length = 20)
    private String ventilationStatus;

    /** {@code {"fio2":60,"peep":8}} keyed by param_key. Never holds a display name. */
    @Column(name = "values_json", columnDefinition = "text")
    private String valuesJson;

    @Column(name = "observed_at", nullable = false)
    private LocalDateTime observedAt;

    @Column(name = "recorded_by_user_id")
    private Long recordedByUserId;

    @Column(name = "performed_by_nurse_id")
    private Long performedByNurseId;

    /** Set on a correction; points at the row this one replaces, which stays readable. */
    @Column(name = "supersedes_setting_id")
    private Long supersedesSettingId;

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

    public boolean isVentilated() {
        return !OFF.equals(ventilationStatus);
    }

    public static boolean isValidStatus(String status) {
        return INVASIVE.equals(status) || NIV.equals(status) || OFF.equals(status);
    }
}
