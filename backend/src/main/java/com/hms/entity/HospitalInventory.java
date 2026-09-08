package com.hms.entity;

import com.hms.validation.NoEmoji;
import jakarta.persistence.*;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/**
 * HospitalInventory - Entity representing physical active stock levels of non-medicine hospital inventory
 * 
 * Maps to 'hospital_inventory' table.
 */
@Entity
@Table(name = "hospital_inventory")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HospitalInventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Name is required")
    @Size(max = 150, message = "Name is too long")
    @NoEmoji
    @Column(nullable = false)
    private String name;

    @Min(value = 0, message = "Stock quantity cannot be negative")
    @Column(name = "stock_quantity", nullable = false)
    private Integer stockQuantity = 0;

    @DecimalMin(value = "0.0", message = "Unit price cannot be negative")
    @Column(name = "unit_price")
    private Double unitPrice;

    @Column(name = "expiry_date")
    private java.time.LocalDate expiryDate;

    @Min(value = 0, message = "Minimum stock level cannot be negative")
    @Column(name = "min_stock_level")
    private Integer minStockLevel = 10;

    @Size(max = 50, message = "Type is too long")
    @NoEmoji
    @Column(length = 50)
    private String type; // e.g. Syringe, Fluid, Consumable, Surgical, Gloves

    @Size(max = 100, message = "Manufacturer is too long")
    @NoEmoji
    @Column(length = 100)
    private String manufacturer;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
