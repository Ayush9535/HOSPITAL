package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A relevant item consumed by a HospitalServiceEntity, referencing a global
 * InventoryMasterItem. One row per (service, item) pair.
 */
@Entity
@Table(name = "hospital_service_items")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HospitalServiceItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "service_id", nullable = false)
    private Long serviceId;

    @Column(name = "master_item_id", nullable = false)
    private Long masterItemId;
}
