package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * HospitalInventoryPurchase - Entity representing chronological purchase records of hospital inventory items.
 */
@Entity
@Table(name = "hospital_inventory_purchase")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HospitalInventoryPurchase {

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

    @Column(name = "min_stock_level")
    private Integer minStockLevel = 10;

    @Column(length = 50)
    private String type; // e.g. Syringe, Fluid, Consumable, Surgical, Gloves

    @Column(length = 100)
    private String manufacturer;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    @Column(name = "purchase_date", nullable = false)
    private LocalDateTime purchaseDate;

    @PrePersist
    protected void onCreate() {
        purchaseDate = LocalDateTime.now();
    }
}
