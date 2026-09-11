package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "lab_orders")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LabOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String publicId;

    @PrePersist
    public void generateIds() {
        if (this.publicId == null) this.publicId = java.util.UUID.randomUUID().toString();
    }

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    @Column(name = "medical_record_id")
    private Long medicalRecordId;

    /** Required by the table (a pathology module owns it). Must be set on every insert. */
    @Column(name = "patient_id", nullable = false)
    private Long patientId;

    /** Links the order back to the OPD case, so it can print on the case paper. */
    @Column(name = "opd_id")
    private Long opdId;

    @Column(name = "test_name", nullable = false)
    private String testName;

    @Column(name = "status", nullable = false, length = 50)
    private String status = "ORDERED"; // ORDERED, COMPLETED, CANCELLED

    /** NOT NULL in the table; ROUTINE unless a stat order is placed. */
    @Column(name = "priority", nullable = false, length = 10)
    private String priority = "ROUTINE";

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
