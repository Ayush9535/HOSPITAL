package com.hms.dto;

import com.hms.validation.NoEmoji;
import jakarta.validation.constraints.Size;

/** Toggle a vital on/off, or define a new custom vital (name + unit). */
public class VitalSettingRequest {
    private Boolean enabled;

    @Size(max = 100, message = "Vital name is too long")
    @NoEmoji
    private String name;

    @Size(max = 20, message = "Unit is too long")
    @NoEmoji
    private String unit;

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
}
