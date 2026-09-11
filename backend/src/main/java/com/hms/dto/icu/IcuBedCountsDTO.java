package com.hms.dto.icu;

import lombok.Data;

/**
 * The count block shared by the hospital-wide totals and each unit summary (ICU Phase 2).
 *
 * <p>Deliberately the same shape in both places so the dashboard's headline numbers and the
 * per-unit rows are provably the same arithmetic: the totals are the sum of the units, and a
 * test can assert exactly that.
 *
 * <p>Bed states mirror {@code BedStatus} one-for-one — ICU reads the existing four states and
 * introduces none of its own.
 */
@Data
public class IcuBedCountsDTO {

    /** Every bed in the unit(s), whatever its state. */
    private int totalBeds;

    private int occupied;
    private int available;
    private int cleaning;
    private int maintenance;

    /** Active admissions resolved to a bed in the unit(s). */
    private int patients;

    /** Admissions that started today (admission_datetime is today). */
    private int newAdmissionsToday;

    /** Admitted patients whose nurse admission form is not yet confirmed — outstanding work. */
    private int pendingConfirmation;

    /** Beds vacated and awaiting cleaning — outstanding work. */
    private int awaitingCleaning;

    /**
     * Rows where the bed's own status and the admission records disagree. Always zero on a
     * healthy tenant; non-zero is a real data-integrity signal, not a display artefact.
     */
    private int occupancyMismatches;
}
