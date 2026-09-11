package com.hms.dto;

import jakarta.validation.constraints.Pattern;

public class FormAccessRequest {
    private Boolean enabled;

    @Pattern(regexp = "^(DOCTOR|NURSE|BOTH)$", message = "accessRole must be one of DOCTOR, NURSE, BOTH")
    private String accessRole;

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getAccessRole() { return accessRole; }
    public void setAccessRole(String accessRole) { this.accessRole = accessRole; }
}
