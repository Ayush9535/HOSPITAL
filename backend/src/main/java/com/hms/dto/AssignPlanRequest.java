package com.hms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AssignPlanRequest {

    @NotBlank(message = "Hospital public ID is required")
    @Size(max = 100, message = "Hospital public ID is too long")
    private String hospitalPublicId;

    @NotNull(message = "Billing period is required (MONTHLY or YEARLY)")
    @Pattern(regexp = "^(MONTHLY|YEARLY)$", message = "Billing period must be MONTHLY or YEARLY")
    private String billingPeriod; // MONTHLY | YEARLY
}
