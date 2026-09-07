package com.hms.dto.icu;

import lombok.Data;

/**
 * One critical-care unit (a ward typed as critical care) with its live counts — ICU Phase 2.
 */
@Data
public class IcuUnitSummaryDTO {
    private Long wardId;
    private String wardName;
    /** CareUnitRegistry key. */
    private String unitType;
    private String unitTypeLabel;
    private Long inchargeNurseId;
    private IcuBedCountsDTO counts = new IcuBedCountsDTO();
}
