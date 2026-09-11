package com.hms.entity.pharmacy;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "purchase_invoice_items")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseInvoiceItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "purchase_invoice_id", insertable = false, updatable = false)
    private Long purchaseInvoiceId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_invoice_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonBackReference
    private PurchaseInvoice purchaseInvoice;

    @Column(name = "medicine_id", nullable = false)
    private Long medicineId;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "medicine_id", insertable = false, updatable = false)
    private MedicineMaster medicine;

    @Column(name = "batch_number")
    private String batchNumber;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    private BigDecimal quantity;

    @Column(name = "free_quantity")
    private BigDecimal freeQuantity;

    @Column(name = "purchase_rate")
    private BigDecimal purchaseRate;

    private BigDecimal mrp;

    @Column(name = "selling_price")
    private BigDecimal sellingPrice;

    @Column(name = "gst_percentage")
    private BigDecimal gstPercentage;

    @Column(name = "line_total")
    private BigDecimal lineTotal;
}
