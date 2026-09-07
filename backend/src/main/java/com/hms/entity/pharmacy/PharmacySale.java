package com.hms.entity.pharmacy;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "pharmacy_sales")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PharmacySale {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    /** Multi Pharmacy branch scope; null for non-branch tenants. */
    @Column(name = "branch_id")
    private Long branchId;

    @Column(name = "patient_id")
    private Long patientId;

    @Column(name = "prescription_id")
    private Long prescriptionId;

    @Column(name = "doctor_id")
    private Long doctorId;

    @Column(name = "pharmacist_id")
    private Long pharmacistId;

    @Column(name = "sale_type", length = 30)
    private String saleType; // WALK-IN, PRESCRIPTION, IPD

    @Column(name = "invoice_number", unique = true)
    private String billNumber; // Mapped to invoice_number column

    @Column(name = "subtotal", precision = 12, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "gst_amount", precision = 12, scale = 2)
    private BigDecimal taxAmount; // Mapped to gst_amount column

    @Column(name = "tax_amount")
    private BigDecimal taxAmountRaw;

    @Column(name = "discount_amount", precision = 12, scale = 2)
    private BigDecimal discountAmount;

    @Column(name = "total_amount", precision = 12, scale = 2)
    private BigDecimal netAmount; // Mapped to total_amount column

    @Column(name = "net_amount")
    private BigDecimal netAmountRaw;

    @Column(name = "payment_status", length = 50)
    private String paymentStatus; // PAID, PENDING, CANCELLED

    @Column(name = "payment_method", length = 50)
    private String paymentMethod; // CASH, CARD, UPI, IPD_BILL

    @Column(name = "posting_status", length = 50)
    private String postingStatus; // DRAFT, POSTED

    @Column(name = "is_ipd_bill")
    private Boolean isIpdBill;

    @Column(name = "ipd_admission_id")
    private Long ipdAdmissionId;

    @Column(name = "patient_name")
    private String patientName;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @com.fasterxml.jackson.annotation.JsonManagedReference
    @OneToMany(mappedBy = "pharmacySale", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<PharmacySaleItem> items;

    @PrePersist
    public void generateBillNumber() {
        if (this.billNumber == null) {
            this.billNumber = "PHB-" + System.currentTimeMillis();
        }
        if (this.postingStatus == null) {
            this.postingStatus = "POSTED";
        }
    }
}
