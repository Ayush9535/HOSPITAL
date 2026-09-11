package com.hms.entity.pharmacy;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "inventory_transactions")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InventoryTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    /** Multi Pharmacy branch scope; null for non-branch tenants. */
    @Column(name = "branch_id")
    private Long branchId;

    @Column(name = "medicine_batch_id", nullable = false)
    private Long medicineBatchId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "medicine_batch_id", insertable = false, updatable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private MedicineBatch medicineBatch;

    @Column(name = "transaction_type")
    private String transactionType; // PURCHASE, SALE, RETURN, ADJUSTMENT

    @Column(nullable = false)
    private BigDecimal quantity;

    @Column(name = "quantity_before")
    private BigDecimal quantityBefore;

    @Column(name = "quantity_after")
    private BigDecimal quantityAfter;

    @Column(name = "reference_type")
    private String referenceType; // PURCHASE_INVOICE, SALE_INVOICE

    @Column(name = "reference_id")
    private Long referenceId;

    @Column(columnDefinition = "TEXT")
    private String remarks;

    @Column(name = "created_by")
    private Long createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
