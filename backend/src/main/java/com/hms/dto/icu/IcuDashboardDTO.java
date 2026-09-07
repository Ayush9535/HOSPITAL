package com.hms.dto.icu;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * The whole ICU board in one payload — ICU Phase 2.
 *
 * <p>The dashboard's headline numbers, the per-unit rows and the bed grid are served by a single
 * request read inside one transaction, so they are one snapshot of one set of records. Two
 * endpoints reading independently could disagree the moment a bed changed between them; this
 * shape makes that impossible rather than unlikely.
 */
@Data
public class IcuDashboardDTO {

    /** Sum across every critical-care unit the caller may see. */
    private IcuBedCountsDTO totals = new IcuBedCountsDTO();

    private List<IcuUnitSummaryDTO> units = new ArrayList<>();

    /**
     * Every bed of those units. Empty when the caller asked for the summary-only endpoint.
     */
    private List<IcuBedRowDTO> beds = new ArrayList<>();

    /**
     * False when the tenant has no ward classified as critical care — the UI shows a setup
     * prompt rather than an empty board that looks broken.
     */
    private boolean hasCriticalCareUnits;
}
