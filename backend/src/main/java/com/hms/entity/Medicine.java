package com.hms.entity;

import com.hms.validation.NoEmoji;
import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "medicines")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Medicine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    @NotBlank(message = "Medicine name is required")
    @Size(max = 200, message = "Medicine name cannot exceed 200 characters")
    @NoEmoji
    private String name;

    @Column(nullable = false)
    @NotNull(message = "Stock quantity is required")
    @Min(value = 0, message = "Stock quantity cannot be negative")
    private Integer stockQuantity = 0; // Current available stock (Default: 0)

    @PositiveOrZero(message = "Unit price cannot be negative")
    private Double unitPrice; // Price per unit

    private java.time.LocalDate expiryDate; // Expiry date

    @Column(name = "min_stock_level")
    @Min(value = 0, message = "Minimum stock level cannot be negative")
    private Integer minStockLevel = 10; // Low stock alert threshold (Default: 10)

    @Size(max = 100, message = "Type cannot exceed 100 characters")
    @NoEmoji
    private String type; // e.g., Tablet, Syrup, Injection, Cream

    @Size(max = 100, message = "Default dosage cannot exceed 100 characters")
    @NoEmoji
    private String defaultDosage; // e.g., 500mg

    @Size(max = 100, message = "Default frequency cannot exceed 100 characters")
    @NoEmoji
    private String defaultFrequency; // e.g., 1-0-1

    @Size(max = 100, message = "Default duration cannot exceed 100 characters")
    @NoEmoji
    private String defaultDuration; // e.g., 5 Days

    @Size(max = 200, message = "Manufacturer cannot exceed 200 characters")
    @NoEmoji
    private String manufacturer;

    @Column(name = "hospital_id")
    private Long hospitalId; // Null for global master list, set for hospital-specific

    @Column(name = "is_active")
    private Boolean isActive = true;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
