package com.hms.dto.pharmacy;

import com.hms.validation.PersonName;
import lombok.Data;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

@Data
public class PharmacySaleRequest {
    private Long patientId;

    @PersonName(message = "Patient name contains invalid characters")
    private String patientName;

    @Size(max = 50, message = "Payment method cannot exceed 50 characters")
    @Pattern(regexp = "^[A-Za-z_ ]*$", message = "Payment method contains invalid characters")
    private String paymentMethod;

    private Boolean isIpdBill;
    private Long ipdAdmissionId;
    private Long prescriptionId;
    private Long doctorId;

    @DecimalMin(value = "0.0", message = "Subtotal cannot be negative")
    private BigDecimal subtotal;

    @DecimalMin(value = "0.0", message = "Tax amount cannot be negative")
    private BigDecimal taxAmount;

    @DecimalMin(value = "0.0", message = "Discount amount cannot be negative")
    private BigDecimal discountAmount;

    @DecimalMin(value = "0.0", message = "Net amount cannot be negative")
    private BigDecimal netAmount;

    @Valid
    @NotNull(message = "Sale items list is required")
    private List<SaleItemRequest> items;

    @Data
    public static class SaleItemRequest {
        private Long medicineId;
        private Long medicineBatchId;

        @NotNull(message = "Quantity is required")
        @DecimalMin(value = "0.01", message = "Sale quantity must be positive")
        private BigDecimal quantity;

        @DecimalMin(value = "0.0", message = "Unit price cannot be negative")
        private BigDecimal unitPrice;

        @DecimalMin(value = "0.0", message = "Tax percentage cannot be negative")
        @DecimalMax(value = "100.0", message = "Tax percentage cannot exceed 100%")
        private BigDecimal taxPercentage;

        @DecimalMin(value = "0.0", message = "Tax amount cannot be negative")
        private BigDecimal taxAmount;

        @DecimalMin(value = "0.0", message = "Discount percentage cannot be negative")
        @DecimalMax(value = "100.0", message = "Discount percentage cannot exceed 100%")
        private BigDecimal discountPercentage;

        @DecimalMin(value = "0.0", message = "Discount amount cannot be negative")
        private BigDecimal discountAmount;

        @DecimalMin(value = "0.0", message = "Total amount cannot be negative")
        private BigDecimal totalAmount;
    }
}
