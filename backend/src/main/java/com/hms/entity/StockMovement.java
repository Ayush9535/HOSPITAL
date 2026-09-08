package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Append-only record of one physical stock change in Hospital/Clinic inventory.
 *
 * <p>Before this existed, Hospital/Clinic stock was a single mutable integer written from twelve
 * call sites across six services, with nothing but best-effort free-text audit rows behind it.
 * Current stock could not be reconciled or explained. Every physical mutation now writes exactly
 * one row here, in the same transaction as the mutation, so:
 *
 * <pre>
 *   current = SUM(signed quantity of all movements for that item/batch)
 * </pre>
 *
 * <p>Rows are never updated or deleted. A mistake is corrected by appending a compensating
 * movement (an adjustment or a return), never by rewriting history -- the same rule the bed and
 * surgery audit trails already follow.
 *
 * <p>{@code direction} is redundant with {@code movementType} on purpose: it keeps the
 * reconciliation SUM trivial and correct without the query needing to know the semantics of every
 * type, and it makes an incorrectly-signed row visible instead of silently wrong.
 */
@Entity
@Table(name = "stock_movements",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_stock_movement_idempotency",
                columnNames = {"hospital_id", "idempotency_key", "batch_id"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockMovement {

    /** Which inventory this movement belongs to. The two are deliberately never mixed. */
    public static final String DOMAIN_MEDICINE = "MEDICINE";
    public static final String DOMAIN_HOSPITAL_ITEM = "HOSPITAL_ITEM";

    public static final String IN = "IN";
    public static final String OUT = "OUT";

    public static final String OPENING = "OPENING";
    public static final String PURCHASE_RECEIPT = "PURCHASE_RECEIPT";
    public static final String DISPENSE = "DISPENSE";
    public static final String CONSUMPTION = "CONSUMPTION";
    public static final String RETURN_IN = "RETURN_IN";
    public static final String RETURN_OUT = "RETURN_OUT";
    public static final String POSITIVE_ADJUSTMENT = "POSITIVE_ADJUSTMENT";
    public static final String NEGATIVE_ADJUSTMENT = "NEGATIVE_ADJUSTMENT";
    public static final String DISPOSAL = "DISPOSAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String publicId;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    /** MEDICINE | HOSPITAL_ITEM */
    @Column(name = "inventory_domain", nullable = false, length = 20)
    private String inventoryDomain;

    /** medicines.id when MEDICINE; hospital_inventory.id when HOSPITAL_ITEM. */
    @Column(name = "item_id", nullable = false)
    private Long itemId;

    /** medicine_stock_batches.id. Null for HOSPITAL_ITEM, which is not batch-tracked. */
    @Column(name = "batch_id")
    private Long batchId;

    @Column(name = "movement_type", nullable = false, length = 30)
    private String movementType;

    /** IN | OUT -- the sign to apply when reconciling. */
    @Column(nullable = false, length = 3)
    private String direction;

    /** Always positive; {@link #direction} carries the sign. */
    @Column(nullable = false)
    private Integer quantity;

    /** Stock in that item/batch after this movement, for point-in-time reconstruction. */
    @Column(name = "balance_after")
    private Integer balanceAfter;

    /** What caused this: PRESCRIPTION, MEDICINE_PURCHASE, IPD_ADMISSION, HOSPITAL_SERVICE, ... */
    @Column(name = "reference_type", length = 40)
    private String referenceType;

    @Column(name = "reference_id")
    private Long referenceId;

    /**
     * Caller-supplied key making a stock-posting command replay-safe: a double-clicked or retried
     * request that reaches the server twice posts stock once.
     *
     * <p>Unique per (hospital, key, batch) rather than per (hospital, key), because one command
     * legitimately writes several rows. A dispense of twelve units against lots of ten and forty
     * empties the first and takes two from the second, and each of those is its own movement --
     * they have different batches and different balances, and collapsing them would lose which
     * lot the patient's medicine actually came from. Keyed on the pair, a replay of that command
     * still collides on every row it would rewrite, which is the property we needed.
     */
    @Column(name = "idempotency_key", length = 100)
    private String idempotencyKey;

    @Column(name = "performed_by_user_id")
    private Long performedByUserId;

    @Column(length = 255)
    private String remarks;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (this.publicId == null) this.publicId = java.util.UUID.randomUUID().toString();
    }

    /** Signed contribution to the running balance. */
    public int signedQuantity() {
        return OUT.equals(direction) ? -quantity : quantity;
    }
}
