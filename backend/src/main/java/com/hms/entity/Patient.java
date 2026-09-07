package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;

/**
 * Patient - Entity representing a patient in a hospital
 * 
 * This entity stores patient information for OPD management.
 * Each patient belongs to exactly one hospital (multi-tenant isolation via
 * hospital_id).
 * Only Hospital Admin can add patients.
 * 
 * @author HMS Team
 * @version Phase-1
 */
@Entity
@Table(name = "patients")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Patient {

    /**
     * Unique identifier for the patient
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
     * Custom readable ID for UI display (e.g., PAT1234)
     */
    @Column(name = "custom_id")
    private String customId;

    @PrePersist
    public void generateIds() {
        if (this.publicId == null) {
            this.publicId = java.util.UUID.randomUUID().toString();
        }
        // customId is set by PatientService after save using the auto-increment id
    }

    /**
     * Hospital ID for multi-tenant isolation
     * Every patient belongs to exactly one hospital
     */
    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    /**
     * Patient's full name
     */
    @Column(nullable = false, length = 100)
    @jakarta.validation.constraints.NotBlank(message = "Name is required")
    @jakarta.validation.constraints.Size(max = 100, message = "Name is too long")
    @com.hms.validation.NoEmoji
    private String name;

    /**
     * Patient's date of birth. Nullable at the DB/entity level — not because
     * it's optional, but to let Hibernate's ddl-auto=update add this column
     * safely to a table that already has rows (a NOT NULL column with no
     * default fails on populated tables in MySQL strict mode; this project
     * already hit that exact failure twice with orphaned columns). "Always
     * required" is enforced in PatientService.addPatient/updatePatient, the
     * same way phone number already is.
     */
    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    /**
     * Computed, not stored — age changes every year, so storing it meant it
     * silently went stale until someone manually corrected it. This getter
     * makes age always correct with zero maintenance. Because Jackson (JSON)
     * and Thymeleaf (PDF templates) both call getters via normal property
     * access, every existing place that reads patient.getAge() / ${patient.age}
     * keeps working unchanged, now receiving a live value instead of a stored one.
     */
    public Integer getAge() {
        return dateOfBirth != null ? Period.between(dateOfBirth, LocalDate.now()).getYears() : null;
    }

    /**
     * Patient's gender (MALE, FEMALE, OTHER)
     */
    @Column(nullable = false, length = 10)
    @jakarta.validation.constraints.NotBlank(message = "Gender is required")
    @jakarta.validation.constraints.Pattern(regexp = "^[A-Za-z \\-]{1,10}$", message = "Invalid gender")
    private String gender;

    /**
     * Patient's contact phone number
     */
    @Column(nullable = false, length = 15)
    @jakarta.validation.constraints.NotBlank(message = "Phone number is required")
    @jakarta.validation.constraints.Pattern(regexp = "^[0-9]{10}$", message = "Phone number must be exactly 10 digits")
    private String phone;

    /**
     * Patient's email address (optional)
     */
    @Column(length = 100)
    @jakarta.validation.constraints.Email(message = "Invalid email format")
    @jakarta.validation.constraints.Size(max = 100, message = "Email is too long")
    private String email;

    /**
     * Patient's address
     */
    @Column(length = 255)
    @jakarta.validation.constraints.Size(max = 255, message = "Address is too long")
    @com.hms.validation.NoEmoji
    private String address;

    /**
     * Patient consultation status
     * REGISTERED - Initial state when patient is added
     * CONSULTING - Doctor has started consultation
     * COMPLETED - Consultation finished, prescription given
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PatientStatus status = PatientStatus.REGISTERED;

    /**
     * CMS: Medical History / Allergies / Notes
     * Simplied text field for Phase 1
     */
    @Column(name = "medical_history", length = 1000)
    @jakarta.validation.constraints.Size(max = 1000, message = "Medical history is too long")
    @com.hms.validation.NoEmoji
    private String medicalHistory;

    /**
     * Soft delete flag
     */
    @Column(nullable = false)
    private Boolean isActive = true;

    /**
     * Timestamp when the patient record was created
     */
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Transient field to hold the latest bill information for UI display.
     * This is not persisted in the patient table.
     */
    @Transient
    private Billing latestBill;
}
