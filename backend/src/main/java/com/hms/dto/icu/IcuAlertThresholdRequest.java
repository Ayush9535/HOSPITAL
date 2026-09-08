package com.hms.dto.icu;

import lombok.Data;

import java.math.BigDecimal;

/** Set or toggle one alert threshold (ICU Phase 9). */
@Data
public class IcuAlertThresholdRequest {

    /** Fires below this. Null clears the lower bound. */
    private BigDecimal minValue;

    /** Fires above this. Null clears the upper bound. */
    private BigDecimal maxValue;

    private Boolean enabled;
}
