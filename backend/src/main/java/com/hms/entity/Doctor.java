package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Doctor - Entity representing a doctor in a hospital
 * 
 * This entity stores doctor information.
 * Each doctor belongs to exactly one hospital (multi-tenant isolation via
 * hospital_id).
 * Only Hospital Admin can add doctors.
 * Doctors can log in and view their appointments and patients.
 * 
 * @author HMS Team
 * @version Phase-1
 */
@Entity
@Table(name = "doctors")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Doctor {

    /**
     * Unique identifier for the doctor
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
     * Custom readable ID for UI display (e.g., DOC1234)
     */
    @Column(name = "custom_id")
    private String customId;

    @PrePersist
    public void generateIds() {
        if (this.publicId == null) {
            this.publicId = java.util.UUID.randomUUID().toString();
        }
        // customId is set by DoctorService after save using the auto-increment id
    }

    /**
     * Hospital ID for multi-tenant isolation
     * Every doctor belongs to exactly one hospital
     */
    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    /**
     * Doctor's full name
     */
    @Column(nullable = false, length = 100)
    @jakarta.validation.constraints.NotBlank(message = "Name is required")
    @com.hms.validation.PersonName
    private String name;

    /**
     * Doctor's specialization (e.g., General Physician, Cardiologist)
     */
    @Column(nullable = false, length = 100)
    @jakarta.validation.constraints.NotBlank(message = "Specialization is required")
    @jakarta.validation.constraints.Size(max = 100, message = "Specialization is too long")
    @com.hms.validation.NoEmoji
    private String specialization;

    /**
     * Doctor's contact phone number
     */
    @Column(nullable = false, length = 15)
    @jakarta.validation.constraints.NotBlank(message = "Phone number is required")
    @jakarta.validation.constraints.Pattern(regexp = "^\\d{10}$", message = "Phone number must be exactly 10 digits")
    private String phone;

    /**
     * Doctor's email address
     */
    @Column(nullable = false, length = 100)
    @jakarta.validation.constraints.NotBlank(message = "Email is required")
    @jakarta.validation.constraints.Email(message = "Invalid email format")
    @jakarta.validation.constraints.Size(max = 100, message = "Email is too long")
    private String email;

    /**
     * Soft delete flag
     */
    @Column(nullable = false)
    private Boolean isActive = true;

    /**
     * Timestamp when the doctor record was created
     */
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }
}
