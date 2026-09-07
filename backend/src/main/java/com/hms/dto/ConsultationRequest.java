package com.hms.dto;

import lombok.Data;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.util.List;

@Data
public class ConsultationRequest {
    private Long appointmentId; // Optional - for appointment-based consultations
    private Long opdId; // Optional - for OPD-based consultations

    @NotBlank(message = "Patient ID is required")
    private String patientId; // Required - patient public ID or numeric id

    private String symptoms;
    private String diagnosis;
    private String treatmentNotes;
    private LocalDate followUpDate;

    /** Optional. Absent on every consultation recorded before this field existed. */
    private String followUpInstructions;
    private Boolean ipdAdmitRecommended;

    @Valid
    private List<PrescriptionItem> prescription;
    
    private List<String> labTests;

    @Valid
    private List<AdministeredItem> administeredItems;

    @Valid
    private List<HospitalInventoryItem> hospitalInventoryItems;

    @Data
    public static class HospitalInventoryItem {
        @NotNull(message = "Service ID is required")
        private Long serviceId;

        @NotNull(message = "Quantity is required")
        @Min(value = 1, message = "Quantity must be positive")
        private Integer quantity;
    }

    @Data
    public static class PrescriptionItem {
        @NotBlank(message = "Prescription medicine name is required")
        private String medicineName;

        @NotBlank(message = "Prescription dosage is required")
        private String dosage;

        @NotBlank(message = "Prescription frequency is required")
        private String frequency;

        @NotBlank(message = "Prescription duration is required")
        private String duration;

        private String instructions;
    }

    @Data
    public static class AdministeredItem {
        @NotNull(message = "Medicine ID is required")
        private Long medicineId;

        @NotBlank(message = "Medicine name is required")
        private String medicineName;

        @NotNull(message = "Quantity is required")
        @Min(value = 1, message = "Quantity must be positive")
        private Integer quantity;

        private String dosage;
        private String frequency;
        private String duration;
        private String instructions;
    }

    @Valid
    private List<ChargeItem> charges;

    @Data
    public static class ChargeItem {
        @NotBlank(message = "Charge description is required")
        private String description;

        @NotNull(message = "Charge amount is required")
        @Min(value = 0, message = "Charge amount must be non-negative")
        private java.math.BigDecimal amount;
    }
}
