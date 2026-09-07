package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * IcuInfusion - one continuous infusion, as a SPAN (ICU Phase 6).
 *
 * <p>A ward drug is given and finished: one event, which is what
 * {@code MedicationAdministration} models. An ICU drug runs — noradrenaline from 14:05, raised at
 * 16:20, stopped at 22:00 — and a single {@code administeredTime} cannot express "started and
 * still running".
 *
 * <p><b>The current rate is deliberately NOT a column here.</b> It lives in
 * {@link IcuInfusionRate}, one row per titration, because the requirement is rate OVER TIME: a
 * mutable field would hold only the latest value and could never answer "what was it running at
 * when the blood pressure dropped?", which is the question the chart exists to answer.
 *
 * <p><b>Separate from fluid balance (ICU-6 D-1).</b> An infusion is a drug-delivery record.
 * {@code icu_io_entry} is the authoritative fluid-balance event stream. Nothing here is
 * synchronised into it, no IV_FLUIDS entry is derived from an infusion, and infusion data never
 * counts towards an I/O balance. Same separation-of-meaning rule as ICU-5 D-2.
 */
@Entity
@Table(name = "icu_infusion", indexes = {
        @Index(name = "idx_icu_inf_admission", columnList = "ipd_admission_id"),
        @Index(name = "idx_icu_inf_hospital", columnList = "hospital_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IcuInfusion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true)
    private String publicId;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    /** The admission is the key, so a bed or ward move carries the infusion automatically. */
    @Column(name = "ipd_admission_id", nullable = false)
    private Long ipdAdmissionId;

    @Column(name = "patient_id", nullable = false)
    private Long patientId;

    /**
     * The order this infusion delivers, when one exists.
     *
     * <p>Nullable by decision (D-2): an ICU drip is often started on a verbal order before the
     * prescription is entered, and requiring it would block recording care that actually happened.
     * When present it REUSES {@code Prescription} rather than duplicating the order.
     */
    @Column(name = "prescription_id")
    private Long prescriptionId;

    /** Denormalised for display, exactly as Prescription already stores the name. */
    @Column(name = "medicine_name", nullable = false, length = 255)
    private String medicineName;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    /** NULL while the infusion is running. */
    @Column(name = "stopped_at")
    private LocalDateTime stoppedAt;

    @Column(name = "stop_reason", length = 255)
    private String stopReason;

    @Column(name = "started_by_user_id")
    private Long startedByUserId;

    @Column(name = "performed_by_nurse_id")
    private Long performedByNurseId;

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

    /** Running means started and not yet stopped. */
    public boolean isRunning() {
        return stoppedAt == null;
    }
}
