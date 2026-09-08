package com.hms.entity.pharmacy;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "medicine_batches")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MedicineBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    /** Multi Pharmacy branch scope; null for non-branch tenants. */
    @Column(name = "branch_id")
    private Long branchId;

    @Column(name = "medicine_id", nullable = false)
    private Long medicineId;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "medicine_id", insertable = false, updatable = false)
    private MedicineMaster medicine;

    @Column(name = "batch_number", nullable = false)
    private String batchNumber;

    @Column(name = "expiry_date", nullable = false)
    private LocalDate expiryDate;

    @Column(name = "manufacturing_date")
    private LocalDate manufacturingDate;

    @Column(nullable = false)
    private BigDecimal mrp;

    @Column(name = "purchase_rate", nullable = false)
    private BigDecimal purchaseRate;

    @Column(name = "selling_price", nullable = false)
    private BigDecimal sellingPrice;

    @Column(name = "current_quantity")
    private BigDecimal currentQuantity = BigDecimal.ZERO;

    @Column(name = "reserved_quantity")
    private BigDecimal reservedQuantity = BigDecimal.ZERO;



    @Column(name = "supplier_id")
    private Long supplierId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", insertable = false, updatable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Supplier supplier;

    @Column(name = "purchase_invoice_item_id")
    private Long purchaseInvoiceItemId;

    @Column(name = "gst_percentage")
    private BigDecimal gstPercentage;

    private String status; // ACTIVE, EXPIRED, QUARANTINED, RECALLED

    @Column(columnDefinition = "TEXT")
    private String remarks;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
