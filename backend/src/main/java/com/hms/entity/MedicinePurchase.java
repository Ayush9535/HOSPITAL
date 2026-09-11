package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * MedicinePurchase - Entity representing chronological purchase records of in-clinic medicines.
 */
@Entity
@Table(name = "medicine_purchase")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MedicinePurchase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "unit_price")
    private Double unitPrice;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    /**
     * The supplier's batch/lot number for this delivery, when the invoice carries one.
     *
     * <p>Optional, and never invented: stock that arrives without one is filed under a lot named
     * after its expiry instead. What matters for safety is that two deliveries with different
     * expiries stay apart, and expiry alone is enough for that.
     */
    @Column(name = "batch_number", length = 100)
    private String batchNumber;

    @Column(name = "min_stock_level")
    private Integer minStockLevel = 10;

    private String type; // e.g. Tablet, Capsule, Syrup, Injection, Cream

    private String defaultDosage; // e.g. 500mg
    private String defaultFrequency; // e.g. 1-0-1
    private String defaultDuration; // e.g. 5 Days

    private String manufacturer;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    @Column(name = "purchase_date", nullable = false)
    private LocalDateTime purchaseDate;

    @PrePersist
    protected void onCreate() {
        purchaseDate = LocalDateTime.now(java.time.ZoneId.systemDefault());
    }
}
