package com.hms.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Composite, read-only bedside view for a nurse: demographics, admission,
 * diagnosis, current (ACTIVE) prescriptions, latest doctor notes, and a
 * read-only billing summary. Nurse-writable data (vitals, notes, MAR) is served
 * by their own endpoints (M4-M6).
 */
@Data
public class NursePatientDetailDTO {

    // Demographics
    // The numeric id, needed by anything scoped to the patient rather than to this admission --
    // patient documents ask for it. The publicId stays the one shown to a person.
    private Long patientId;
    private String patientPublicId;
    private String patientName;
    private Integer age;
    private String gender;
    private String phone;

    // Admission
    private Long ipdAdmissionId;
    private String ipdNumber;
    private String admissionType;
    private String status;
    private LocalDateTime admissionDateTime;
    private String wardName;
    private String bedCode;
    private String doctorName;
    private String primaryDiagnosis;

    // Latest medical record (doctor's clinical notes)
    private String diagnosis;
    private String treatmentNotes;
    private LocalDate followUpDate;

    // Current medicines (doctor-authored, ACTIVE)
    private List<PrescriptionLite> prescriptions;

    // Read-only billing summary
    private BillingSummary billing;

    @Data
    public static class PrescriptionLite {
        private String medicineName;
        private String dosage;
        private String frequency;
        private String duration;
        private String route;
        private String type;
        private String status;
    }

    @Data
    public static class BillingSummary {
        private BigDecimal total = BigDecimal.ZERO;
        private BigDecimal paid = BigDecimal.ZERO;
        private BigDecimal balance = BigDecimal.ZERO;
    }
}
