package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * InventoryItem - Entity representing the catalog list of non-medicine hospital inventory items
 * 
 * Maps to 'inventory_items' table.
 */
@Entity
@Table(name = "inventory_items")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InventoryItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(length = 50)
    private String type; // e.g. Syringe, Fluid, Consumable, Surgical, Gloves

    @Column(length = 100)
    private String manufacturer;

    @Column(name = "hospital_id")
    private Long hospitalId;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    /**
     * The ID of the linked HospitalFee row.
     * When this catalog item is used in a consultation/IPD, the fee identified
     * by this ID is auto-applied to the patient's bill.
     */
    @Column(name = "linked_fee_id")
    private Long linkedFeeId;

    @Column(name = "relative_item_ids", length = 1000)
    private String relativeItemIds; // JSON array of numbers, e.g. "[12, 15]"

    /**
     * true (default) = this item has its own physical stock, tracked in
     * HospitalInventory and checked/decremented when used (e.g. an
     * injection ampule). false = this is a pure service/procedure with no
     * stock of its own (e.g. "Dressing") -- its availability is determined
     * entirely by its related items (see relativeItemIds); its own stock
     * is never checked or decremented. Defaults to true so every existing
     * catalog item keeps behaving exactly as it does today.
     *
     * columnDefinition carries the "DEFAULT 1" so that when Hibernate's
     * ddl-auto=update adds this column to an already-populated table, MySQL
     * backfills existing rows to 1 (true / stocked) -- without it, Hibernate
     * adds the column with no DB default, silently defaulting existing rows
     * to 0 (false), which would wrongly flip every pre-existing item to
     * service-type. This matches the migration and setup/schema-full.sql.
     */
    @Column(name = "has_own_stock", nullable = false, columnDefinition = "tinyint(1) not null default 1")
    private Boolean hasOwnStock = true;

    @Column(name = "hospital_type")
    private String hospitalType; // HOSPITAL, CLINIC, or PHARMACY - for tenant isolation
}
