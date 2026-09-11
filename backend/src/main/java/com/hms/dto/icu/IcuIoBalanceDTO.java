package com.hms.dto.icu;

import lombok.Data;

/**
 * Fluid balance over a range (ICU Phase 5).
 *
 * <p>Always computed from {@code icu_io_entry}, never stored, so it cannot drift from the events
 * it summarises. Arithmetic only: totals and their difference. There is deliberately no target,
 * no threshold and no verdict on whether a balance is acceptable — that is clinical
 * interpretation, and ICU records values rather than judging them.
 *
 * <p>{@code VitalsRecord.urine_output_ml} never contributes here (D-2).
 */
@Data
public class IcuIoBalanceDTO {

    /** IV fluids + oral. */
    private int totalIntakeMl;

    /** Ryles tube aspiration + urine + vomit. */
    private int totalOutputMl;

    /** totalIntakeMl - totalOutputMl. May be negative. */
    private int netBalanceMl;

    /** Entries counted, excluding any superseded by a correction. */
    private int entryCount;
}
