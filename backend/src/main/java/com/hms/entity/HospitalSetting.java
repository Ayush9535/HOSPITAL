package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * HospitalSetting - Entity representing the operational configurations for a hospital
 * 
 * Maps to 'hospital_settings' table. Handles toggles like receptionist mode
 * and billing ownership.
 * 
 * @author HMS Team
 * @version Phase-1
 */
@Entity
@Table(name = "hospital_settings")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HospitalSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "hospital_id", nullable = false, unique = true)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Hospital hospital;

    @Column(name = "reception_mode", nullable = false, length = 20)
    private String receptionMode = "HAS_RECEPTIONIST"; // HAS_RECEPTIONIST or SOLO

    @Column(name = "billing_handler", nullable = false, length = 20)
    private String billingHandler = "RECEPTIONIST"; // RECEPTIONIST or DOCTOR

    @Column(name = "in_clinic", nullable = false)
    private Boolean inClinic = true;

    // Pharmacy: enables the barcode scan/print workflow (billing barcode mode,
    // inventory label printing). Defaults on; toggled from the pharmacy Settings tab.
    @Column(name = "barcode_enabled", nullable = false)
    private Boolean barcodeEnabled = true;

    @Column(name = "separate_nurse_login", nullable = false)
    private Boolean separateNurseLogin = false;

    @Column(name = "ot_incharge_enabled", nullable = false)
    private Boolean otInchargeEnabled = false;

    // Print Settings: which pages the consultation-complete combined print includes. All default
    // on (today's behaviour). An "off" page is left out of the merged PDF at consultation.
    @Column(name = "print_case_paper", nullable = false)
    private Boolean printCasePaper = true;

    @Column(name = "print_bill", nullable = false)
    private Boolean printBill = true;

    @Column(name = "print_prescription", nullable = false)
    private Boolean printPrescription = true;

    @Column(name = "print_in_clinic", nullable = false)
    private Boolean printInClinic = true;

    // Bill payment timing: FIRST = charge consultation + case-paper fee at OPD entry (billed &
    // paid there); LAST = current flow (payment at/after consultation). Defaults LAST.
    @Column(name = "bill_payment_timing", nullable = false, length = 10)
    private String billPaymentTiming = "LAST";

    public Boolean getOtInchargeEnabled() {
        return otInchargeEnabled;
    }

    public void setOtInchargeEnabled(Boolean otInchargeEnabled) {
        this.otInchargeEnabled = otInchargeEnabled;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Hospital getHospital() {
        return hospital;
    }

    public void setHospital(Hospital hospital) {
        this.hospital = hospital;
    }

    public String getReceptionMode() {
        return receptionMode;
    }

    public void setReceptionMode(String receptionMode) {
        this.receptionMode = receptionMode;
    }

    public String getBillingHandler() {
        return billingHandler;
    }

    public void setBillingHandler(String billingHandler) {
        this.billingHandler = billingHandler;
    }

    public Boolean getInClinic() {
        return inClinic;
    }

    public void setInClinic(Boolean inClinic) {
        this.inClinic = inClinic;
    }

    public Boolean getBarcodeEnabled() {
        return barcodeEnabled;
    }

    public void setBarcodeEnabled(Boolean barcodeEnabled) {
        this.barcodeEnabled = barcodeEnabled;
    }

    public Boolean getSeparateNurseLogin() {
        return separateNurseLogin;
    }

    public void setSeparateNurseLogin(Boolean separateNurseLogin) {
        this.separateNurseLogin = separateNurseLogin;
    }
}
