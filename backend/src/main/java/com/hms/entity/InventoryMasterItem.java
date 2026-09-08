package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/**
 * Global (platform-wide) inventory item name dictionary. Curated by the
 * Super Admin; every hospital picks from this list for purchases and for a
 * service's relevant items. No hospital_id -- these are shared names only.
 */
@Entity
@Table(name = "inventory_master_items")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InventoryMasterItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
