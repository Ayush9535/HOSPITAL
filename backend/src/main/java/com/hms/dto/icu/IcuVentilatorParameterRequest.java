package com.hms.dto.icu;

import com.hms.validation.NoEmoji;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Toggle, rename or define a ventilator parameter (ICU Phase 7). */
@Data
public class IcuVentilatorParameterRequest {

    private Boolean enabled;

    /** The display name. On add it also seeds the key, ONCE; on edit it changes the label only. */
    @Size(max = 60, message = "Parameter name is too long")
    @NoEmoji
    private String displayName;

    @Size(max = 20, message = "Unit is too long")
    @NoEmoji
    private String unit;

    /** SETTING or OBSERVATION. */
    private String category;

    /** Custom parameters only, and only NUMBER or TEXT — MODE is reserved. */
    private String valueType;
}
