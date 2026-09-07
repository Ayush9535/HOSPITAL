package com.hms.dto;

import com.hms.validation.NoEmoji;
import com.hms.validation.PersonName;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * CreateHospitalRequest - DTO for creating a new hospital
 * 
 * This DTO is used by Super Admin to create a new hospital.
 * It includes hospital details and the initial hospital admin credentials.
 * 
 * @author HMS Team
 * @version Phase-1
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateHospitalRequest {

    /**
     * Name of the hospital
     */
    @NotBlank(message = "Hospital name is required")
    @Size(max = 150, message = "Hospital name is too long")
    @NoEmoji
    private String hospitalName;

    /**
     * Email for the hospital admin user
     */
    @NotBlank(message = "Admin email is required")
    @Email(message = "Invalid email format")
    @Size(max = 100, message = "Email is too long")
    private String adminEmail;

    /**
     * Password for the hospital admin user
     */
    @NotBlank(message = "Admin password is required")
    @Size(max = 200, message = "Password is too long")
    private String adminPassword;

    /**
     * Name of the hospital admin user
     */
    /**
     * Name of the hospital admin user
     */
    @NotBlank(message = "Admin name is required")
    @PersonName
    private String adminName;

    /**
     * Optional: List of modules to enable.
     * If null/empty, defaults to CMS mode (OPD, BILLING).
     */
    private java.util.List<String> modules;

    /**
     * Whether the hospital is a single doctor hospital.
     */
    private Boolean isSingleDoctor = false;

    @NotBlank(message = "Entity type is required")
    @Pattern(regexp = "^(HOSPITAL|CLINIC|PHARMACY)$", message = "Invalid entity type")
    private String type; // HOSPITAL | CLINIC | PHARMACY

    @NotBlank(message = "A plan must be assigned when creating a hospital")
    @Size(max = 64, message = "Invalid plan reference")
    private String planPublicId;

    @NotBlank(message = "Billing period is required (MONTHLY or YEARLY)")
    @Pattern(regexp = "^(MONTHLY|YEARLY)$", message = "Billing period must be MONTHLY or YEARLY")
    private String billingPeriod;
}
