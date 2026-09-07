package com.hms.dto.icu;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * One bed on the ICU bed board (ICU Phase 2).
 *
 * <p>Read-only. Occupancy comes from the EXISTING records — {@code beds.status} for the bed's
 * own state and {@code ipd_admission} for who is in it. ICU stores no bed state of its own.
 */
@Data
public class IcuBedRowDTO {

    // ── the bed ───────────────────────────────────────────────────────────────
    private Long bedId;
    private String bedCode;
    private Long wardId;
    private String wardName;
    /** CareUnitRegistry key. */
    private String unitType;
    private String unitTypeLabel;

    /** One of BedStatus: available / occupied / cleaning / maintenance. */
    private String status;

    // ── the patient, present only for a bed with an active admission ──────────
    private Long ipdAdmissionId;
    private String ipdNumber;
    private String patientName;
    private Integer age;
    private String gender;
    private LocalDateTime admittedAt;

    /** Role-gated: omitted for roles without patient-detail scope. */
    private String primaryDiagnosis;

    /**
     * The admission's treating doctor. ICU Phase 2 has no {@code icu_stay}, so there is no
     * separate intensivist yet — that arrives with the stay record in a later phase.
     */
    private String consultantName;

    /** False while the nurse admission form is still outstanding. */
    private Boolean admissionConfirmed;

    // ── latest recorded respiratory observation, role-gated ───────────────────
    /**
     * The most recent SpO2 / respiratory rate actually recorded for this admission, with the
     * time it was taken. Recorded values only — the board never derives a severity, risk score
     * or clinical judgement from them.
     */
    private Integer latestSpo2;
    private Integer latestRespiratoryRate;
    private LocalDateTime vitalsRecordedAt;

    /**
     * The patient's ICU stay, when one is open. Null until ICU-3 populated it, and still null
     * for a bed with no active admission — the field itself has existed since ICU-2 so that
     * filling it needed no DTO or frontend change.
     */
    private IcuStayDTO icuStay;

    /**
     * False when the bed's own status and the admission records disagree — an occupied bed with
     * no active admission, or an active admission on a bed not marked occupied.
     *
     * <p>Surfaced rather than silently reconciled: the board shows what the records actually
     * say, so a real inconsistency reaches the ward instead of being hidden behind a tidy view.
     */
    private Boolean occupancyConsistent = true;

    /** Short human-readable reason when {@link #occupancyConsistent} is false; null otherwise. */
    private String occupancyNote;
}
