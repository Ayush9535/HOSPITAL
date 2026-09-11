package com.hms.dto;

import com.hms.validation.NoEmoji;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class CreatePlanRequest {

    @NotBlank(message = "Plan name is required")
    @Size(max = 150, message = "Plan name is too long")
    @NoEmoji
    private String name;

    @NotNull(message = "Plan type is required")
    @Pattern(regexp = "^(HOSPITAL|CLINIC|PHARMACY)$", message = "Plan type must be HOSPITAL, CLINIC or PHARMACY")
    private String type; // HOSPITAL | CLINIC | PHARMACY

    @NotNull(message = "Monthly price is required")
    @DecimalMin(value = "0.0", message = "Monthly price cannot be negative")
    private BigDecimal monthlyPrice;

    @NotNull(message = "Yearly price is required")
    @DecimalMin(value = "0.0", message = "Yearly price cannot be negative")
    private BigDecimal yearlyPrice;

    private List<String> modules;

    private List<String> features;

    private Boolean inClinic = false;

    // Pharmacy chain support: enable multiple outlets (medical shops) under one owner.
    private Boolean multiOutlet = false;

    @Min(value = 0, message = "Max outlets cannot be negative")
    private Integer maxOutlets;
}
