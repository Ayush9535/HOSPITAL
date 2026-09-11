package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A physically received lot of one Hospital/Clinic medicine: batch number, expiry, and the
 * quantity still on the shelf.
 *
 * <p><b>Why this is not pharmacy's MedicineBatch.</b> That entity's {@code medicine_id} is a hard
 * FK to {@code medicine_master} -- it maps the association eagerly
 * ({@code @ManyToOne @JoinColumn(name = "medicine_id", insertable = false, updatable = false)})
 * and its repository joins through it for category and manufacturer filters. Hospital and Clinic
 * tenants keep their medicines in {@code medicines} ({@link Medicine}) and no hospital-tenant code
 * references MedicineMaster anywhere. Reusing it would mean writing hospital medicines into the
 * pharmacy tenant's master to satisfy the FK, which pollutes that tenant's catalogue and its
 * category joins. So Hospital/Clinic get their own batch table pointing at {@code medicines}, and
 * the pharmacy bounded context is left untouched.
 *
 * <p><b>Source of truth.</b> Available stock for a medicine is SUM(currentQuantity) over its
 * active, unexpired batches -- never a hand-maintained aggregate. {@link Medicine#getStockQuantity()}
 * is retained only as a legacy read cache for screens not yet migrated; nothing consumes it to
 * decide whether stock exists.
 *
 * <p>Quantities are integers in whatever unit the facility already counts that medicine in --
 * {@link Medicine} carries no unit of measure, so there is nothing here to convert between.
 * Pack-to-unit conversion (box -> strip -> tablet) needs that field first and is out of scope.
 */
@Entity
@Table(name = "medicine_stock_batches",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_medicine_batch",
                columnNames = {"hospital_id", "medicine_id", "batch_number"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MedicineStockBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String publicId;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    /** FK to medicines.id -- the Hospital/Clinic tenant medicine master. */
    @Column(name = "medicine_id", nullable = false)
    private Long medicineId;

    /**
     * Supplier's batch/lot number. Free text: it is the supplier's identifier, not ours. Unique
     * per (hospital, medicine) so re-receiving the same lot tops it up instead of silently
     * creating a second row with a different expiry.
     */
    @Column(name = "batch_number", nullable = false, length = 100)
    private String batchNumber;

    @Column(name = "expiry_date", nullable = false)
    private LocalDate expiryDate;

    /** As-received quantity; never decremented. Kept so the ledger can be reconciled against it. */
    @Column(name = "received_quantity", nullable = false)
    private Integer receivedQuantity = 0;

    /** What is actually left. Only ever moved by an atomic conditional UPDATE. */
    @Column(name = "current_quantity", nullable = false)
    private Integer currentQuantity = 0;

    @Column(name = "unit_price")
    private Double unitPrice;

    /** Soft-disable a lot (recall, quarantine) without deleting its history. */
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (this.publicId == null) this.publicId = java.util.UUID.randomUUID().toString();
        if (this.receivedAt == null) this.receivedAt = LocalDateTime.now();
    }

    /** Usable today: active, in date, and actually holding something. */
    public boolean isDispensable(LocalDate asOf) {
        return Boolean.TRUE.equals(isActive)
                && currentQuantity != null && currentQuantity > 0
                && expiryDate != null && !expiryDate.isBefore(asOf);
    }
}
