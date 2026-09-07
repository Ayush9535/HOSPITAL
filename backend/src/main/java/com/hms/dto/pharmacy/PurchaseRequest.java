package com.hms.dto.pharmacy;

import com.hms.validation.NoEmoji;
import lombok.Data;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
public class PurchaseRequest {
    private Long supplierId;

    @Size(max = 50, message = "Invoice number cannot exceed 50 characters")
    @Pattern(regexp = "^[A-Za-z0-9 /_\\-]*$", message = "Invoice number contains invalid characters")
    private String invoiceNumber;

    private LocalDate invoiceDate;

    @DecimalMin(value = "0.0", message = "Subtotal cannot be negative")
    private BigDecimal subtotal;

    @DecimalMin(value = "0.0", message = "Discount amount cannot be negative")
    private BigDecimal discountAmount;

    @DecimalMin(value = "0.0", message = "GST amount cannot be negative")
    private BigDecimal gstAmount;

    @DecimalMin(value = "0.0", message = "Total amount cannot be negative")
    private BigDecimal totalAmount;

    @Pattern(regexp = "^(DRAFT|POSTED)?$", message = "Posting status must be DRAFT or POSTED")
    private String postingStatus; // DRAFT or POSTED
    
    @Valid
    @NotNull(message = "Purchase items list is required")
    private List<PurchaseItemRequest> items;

    @Data
    public static class PurchaseItemRequest {
        private Long medicineId;
        // For standalone pharmacy: medicine sourced from the platform catalog by name.
        // When medicineId is absent, the purchase find-or-creates the local medicine
        // from these fields.
        @Size(max = 200, message = "Medicine name cannot exceed 200 characters")
        @NoEmoji
        private String medicineName;

        @Size(max = 100, message = "Medicine type cannot exceed 100 characters")
        @NoEmoji
        private String medicineType;

        @Size(max = 200, message = "Manufacturer name cannot exceed 200 characters")
        @NoEmoji
        private String manufacturerName;

        @Size(max = 50, message = "Batch number cannot exceed 50 characters")
        @Pattern(regexp = "^[A-Za-z0-9 /_\\-]*$", message = "Batch number contains invalid characters")
        private String batchNumber;

        private LocalDate expiryDate;

        @NotNull(message = "Quantity is required")
        @DecimalMin(value = "0.01", message = "Purchase quantity must be positive")
        private BigDecimal quantity;

        @NotNull(message = "Free quantity is required")
        @DecimalMin(value = "0.0", message = "Free quantity cannot be negative")
        private BigDecimal freeQuantity;

        @DecimalMin(value = "0.0", message = "Purchase rate cannot be negative")
        private BigDecimal purchaseRate;

        @DecimalMin(value = "0.0", message = "MRP cannot be negative")
        private BigDecimal mrp;

        @DecimalMin(value = "0.0", message = "Selling price cannot be negative")
        private BigDecimal sellingPrice;

        @DecimalMin(value = "0.0", message = "GST percentage cannot be negative")
        @DecimalMax(value = "100.0", message = "GST percentage cannot exceed 100%")
        private BigDecimal gstPercentage;

        @DecimalMin(value = "0.0", message = "Line total cannot be negative")
        private BigDecimal lineTotal;
    }
}
