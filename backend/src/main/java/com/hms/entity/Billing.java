package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Billing - Entity representing a billing record for consultation
 * 
 * This entity stores simple billing information for OPD consultations.
 * Phase-1: Only consultation fees, no GST, no PDF generation.
 * Each billing record belongs to exactly one hospital (multi-tenant isolation
 * via hospital_id).
 * 
 * @author HMS Team
 * @version Phase-1
 */
@Entity
@Table(name = "billing")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Billing {

    /**
     * Unique identifier for the billing record
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Public unique identifier (UUID) for security
     */
    /**
     * Public unique identifier (UUID) for security
     */
    @Column(nullable = false, unique = true)
    private String publicId;

    /**
     * Custom readable ID for UI display (e.g., BIL1234)
     */
    @Column(name = "custom_id")
    private String customId;

    // Reused SecureRandom for the numeric suffix of custom IDs (a single shared, non-predictable
    // instance rather than a new java.util.Random() per call).
    private static final java.security.SecureRandom CUSTOM_ID_RANDOM = new java.security.SecureRandom();

    @PrePersist
    public void generateIds() {
        if (this.publicId == null) {
            this.publicId = java.util.UUID.randomUUID().toString();
        }
        if (this.customId == null) {
            // Generate simple random readable ID: BIL + 4 random digits
            this.customId = "BIL" + (1000 + CUSTOM_ID_RANDOM.nextInt(9000));
        }
    }

    /**
     * Hospital ID for multi-tenant isolation
     * Every billing record belongs to exactly one hospital
     */
    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    /**
     * Patient ID for this billing record
     */
    @Column(name = "patient_id", nullable = false)
    private Long patientId;

    /**
     * Doctor ID for this billing record
     */
    @Column(name = "doctor_id", nullable = false)
    private Long doctorId;

    /**
     * Appointment ID for this billing record (optional)
     */
    @Column(name = "appointment_id")
    private Long appointmentId;

    /**
     * OPD id (if this bill is for an OPD case)
     */
    @Column(name = "opd_id")
    private Long opdId;

    @Column(name = "ipd_admission_id")
    private Long ipdAdmissionId;

    @Column(name = "billing_type", nullable = false)
    private String billingType = "OPD"; // OPD or IPD

    /**
     * Consultation fee amount
     * No GST or additional charges in Phase-1
     */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    /**
     * Payment status: PENDING (nothing collected), PARTIAL (a balance is owed), PAID (ledger
     * covers the total), CLOSED (written off / cancelled).
     *
     * Defaults to PENDING, never PAID: a bill must be paid for, not assumed paid. The old
     * default of "PAID" meant any creator that forgot to set a status produced a silently free
     * bill. It is normally derived from the payments ledger — see BillingService.recalculateTotal.
     */
    @Column(nullable = false, length = 20)
    private String paymentStatus = "PENDING";

    /**
     * Payment Method (Cash, Online, Card, etc.)
     */
    @Column(name = "payment_method", length = 50)
    private String paymentMethod;

    /**
     * Payment reference / UTR number when paid via UPI or online
     */
    @Column(name = "payment_reference", length = 100)
    private String paymentReference;

    /**
     * Additional notes or description
     */
    @Column(length = 500)
    private String description;

    @Column(name = "marked_paid_by", length = 100)
    private String markedPaidBy;

    /**
     * Timestamp when the billing record was created
     */
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
