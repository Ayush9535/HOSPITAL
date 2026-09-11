package com.hms.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class WardResponse {
    private Long wardId;
    private String wardName;
    private BigDecimal bedPrice;
    private Integer totalBeds;
    private Integer floorNumber;
    private Long inchargeNurseId;
    private Boolean staffed;
    /** CareUnitRegistry key — GENERAL by default (ICU Phase 2). */
    private String unitType;
    /** Display label for {@link #unitType}. */
    private String unitTypeLabel;
}
