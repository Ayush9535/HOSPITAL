package com.hms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * HospitalSettingDTO - Data Transfer Object for hospital operational settings
 *
 * Used to exchange settings (receptionMode, billingHandler) between backend and client.
 * Field values are constrained to known domain values to prevent DB constraint violations
 * and invalid operational states.
 *
 * @author HMS Team
 * @version Phase-1
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HospitalSettingDTO {

    /**
     * Reception mode for the hospital.
     * Must be one of: HAS_RECEPTIONIST, SOLO
     */
    @NotBlank(message = "receptionMode is required")
    @Pattern(
        regexp = "HAS_RECEPTIONIST|SOLO",
        message = "receptionMode must be HAS_RECEPTIONIST or SOLO"
    )
    private String receptionMode;

    /**
     * Who handles billing in this hospital.
     * Must be one of: RECEPTIONIST, DOCTOR, BOTH
     */
    @NotBlank(message = "billingHandler is required")
    @Pattern(
        regexp = "RECEPTIONIST|DOCTOR|BOTH",
        message = "billingHandler must be RECEPTIONIST, DOCTOR, or BOTH"
    )
    private String billingHandler;

    private Boolean inClinic = true;

    /**
     * Pharmacy barcode workflow toggle. Defaults on.
     */
    private Boolean barcodeEnabled = true;

    /**
     * Nursing Mgmt: when true, nurses/nurse incharges log in through a
     * separate nurse login page instead of the shared hospital login.
     */
    private Boolean separateNurseLogin = false;

    private Boolean otInchargeEnabled = false;

    // Print Settings: pages included in the consultation-complete combined print.
    private Boolean printCasePaper = true;
    private Boolean printBill = true;
    private Boolean printPrescription = true;
    private Boolean printInClinic = true;

    // FIRST or LAST. FIRST charges consultation + case-paper at OPD entry.
    private String billPaymentTiming = "LAST";

    public HospitalSettingDTO(String receptionMode, String billingHandler) {
        this.receptionMode = receptionMode;
        this.billingHandler = billingHandler;
        this.inClinic = true;
    }

    public HospitalSettingDTO(String receptionMode, String billingHandler, Boolean inClinic) {
        this.receptionMode = receptionMode;
        this.billingHandler = billingHandler;
        this.inClinic = inClinic;
        this.barcodeEnabled = true;
    }
}
