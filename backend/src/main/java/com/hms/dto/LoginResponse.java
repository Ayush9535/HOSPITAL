package com.hms.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * LoginResponse - DTO for login responses
 * 
 * This DTO contains the JWT token and user information after successful login.
 * Used for both Super Admin and Hospital user login responses.
 * 
 * @author HMS Team
 * @version Phase-1
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {

    /**
     * JWT authentication token
     */
    private String token;

    /**
     * User ID
     */
    private Long userId;

    /**
     * User's name
     */
    private String name;

    /**
     * User's email
     */
    private String email;

    /**
     * User's role (SUPER_ADMIN, HOSPITAL_ADMIN, DOCTOR)
     */
    private String role;

    /**
     * Hospital ID (null for Super Admin)
     */
    private Long hospitalId;

    /**
     * Hospital name (null for Super Admin)
     */
    /**
     * Hospital name (null for Super Admin)
     */
    private String hospitalName;

    /**
     * Enabled modules for the hospital (e.g., ["OPD", "BILLING", "IPD"])
     */
    private java.util.List<String> modules;

    /**
     * Operational mode for hospital (receptionMode enums HAS_RECEPTIONIST or SOLO)
     */
    private String receptionMode;

    /**
     * Billing responsibility (billingHandler enums RECEPTIONIST or DOCTOR)
     */
    private String billingHandler;

    /**
     * Fresh user profile fields
     */
    private String phone;
    private Integer age;
    private String gender;
    private String specialization;
    private Boolean isSingleDoctor;
    private Boolean inClinic;
    private Boolean barcodeEnabled;
    private Boolean otInchargeEnabled;
    /** FIRST or LAST — lets the OPD entry form know whether to collect payment up front. */
    private String billPaymentTiming;
    private String logoUrl;
    private String parentOrganization;
    private String hospitalAddress;
    private String hospitalPhone;

    /** HOSPITAL | CLINIC | PHARMACY — entity type this user belongs to */
    private String hospitalType;
}
