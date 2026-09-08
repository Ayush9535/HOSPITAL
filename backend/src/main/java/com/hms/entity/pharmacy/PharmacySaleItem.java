package com.hms.entity.pharmacy;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(name = "pharmacy_sale_items")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PharmacySaleItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sale_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonBackReference
    private PharmacySale pharmacySale;

    @Column(name = "medicine_id", nullable = false)
    private Long medicineId;

    @Column(name = "medicine_batch_id", nullable = false)
    private Long medicineBatchId;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "medicine_batch_id", insertable = false, updatable = false)
    private MedicineBatch medicineBatch;

    @Column(name = "prescribed_quantity", precision = 10, scale = 2)
    private BigDecimal prescribedQuantity;

    @Column(name = "sold_quantity", nullable = false, precision = 10, scale = 2)
    private BigDecimal quantity; // Mapped to sold_quantity

    /**
     * How much of this line has already been returned and refunded.
     *
     * <p>Without it the return check compared each request against the originally sold quantity,
     * so the same units could be refunded again and again. This is the authoritative record of
     * what is still returnable; it is incremented by a conditional UPDATE that refuses to take
     * the total past sold_quantity, so two simultaneous returns cannot both win.
     */
    @Column(name = "returned_quantity", precision = 10, scale = 2)
    private BigDecimal returnedQuantity = BigDecimal.ZERO;

    @Column(name = "unit_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "discount_percentage", precision = 5, scale = 2)
    private BigDecimal discountPercentage;

    @Column(name = "gst_percentage", precision = 5, scale = 2)
    private BigDecimal taxPercentage; // Mapped to gst_percentage

    @Column(name = "tax_percentage")
    private BigDecimal taxPercentageRaw;

    @Column(name = "tax_amount")
    private BigDecimal taxAmount;

    @Column(name = "discount_amount", precision = 12, scale = 2)
    private BigDecimal discountAmount;

    @Column(name = "line_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount; // Mapped to line_total

    @Column(name = "total_amount")
    private BigDecimal totalAmountRaw;

    @Column(name = "quantity")
    private BigDecimal quantityRaw;
}
