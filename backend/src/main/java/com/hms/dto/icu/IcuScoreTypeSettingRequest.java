package com.hms.dto.icu;

import lombok.Data;

/** Enable or disable a severity score type for this hospital (ICU Phase 8). */
@Data
public class IcuScoreTypeSettingRequest {

    /** The only thing an administrator may change: whether the hospital uses this score. */
    private Boolean enabled;
}
