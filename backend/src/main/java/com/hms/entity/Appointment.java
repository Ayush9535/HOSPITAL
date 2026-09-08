package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * Appointment - Entity representing an appointment between a patient and doctor
 * 
 * This entity manages OPD appointments.
 * Each appointment belongs to exactly one hospital (multi-tenant isolation via
 * hospital_id).
 * Hospital Admin creates appointments.
 * Doctors can view their own appointments.
 * 
 * @author HMS Team
 * @version Phase-1
 */
@Entity
@Table(name = "appointments")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Appointment {

    /**
     * Unique identifier for the appointment
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

    public String getPublicId() {
        return publicId;
    }

    public void setPublicId(String publicId) {
        this.publicId = publicId;
    }

    /**
     * Custom readable ID for UI display (e.g., APT1234)
     */
    @Column(name = "custom_id")
    private String customId;

    // Reused across inserts — creating a new Random (or seeding one) per call is
    // both wasteful and a reliability smell. SecureRandom avoids predictable IDs.
    private static final java.security.SecureRandom ID_RANDOM = new java.security.SecureRandom();

    @PrePersist
    public void generateIds() {
        if (this.publicId == null) {
            this.publicId = java.util.UUID.randomUUID().toString();
        }
        if (this.customId == null) {
            // Generate simple random readable ID: APT + 4 random digits
            this.customId = "APT" + (1000 + ID_RANDOM.nextInt(9000));
        }
    }

    /**
     * Hospital ID for multi-tenant isolation
     * Every appointment belongs to exactly one hospital
     */
    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    /**
     * Patient ID for this appointment
     */
    @Column(name = "patient_id", nullable = false)
    private Long patientId;

    /**
     * Doctor ID for this appointment
     */
    @Column(name = "doctor_id", nullable = false)
    private Long doctorId;

    /**
     * Date of the appointment
     */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Column(name = "appointment_date", nullable = false)
    private LocalDate appointmentDate;

    /**
     * Time of the appointment
     */
    @JsonFormat(pattern = "HH:mm")
    @Column(name = "appointment_time", nullable = false)
    private java.time.LocalTime appointmentTime;

    /**
     * Status of the appointment (SCHEDULED, COMPLETED, CANCELLED)
     */
    @Column(nullable = false, length = 20)
    private String status = "SCHEDULED";

    /**
     * Additional notes or reason for visit
     */
    @Column(length = 500)
    @jakarta.validation.constraints.Size(max = 500, message = "Notes is too long")
    @com.hms.validation.NoEmoji
    private String notes;

    /**
     * Soft delete flag
     */
    @Column(nullable = false)
    private Boolean isActive = true;

    /**
     * Timestamp when the appointment was created
     */
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // Transient fields for creating new patients (not persisted to database)

    /**
     * Patient name for new patient creation (not stored in appointments table)
     */
    @Transient
    @jakarta.validation.constraints.Size(max = 150, message = "Patient name is too long")
    @com.hms.validation.NoEmoji
    private String patientName;

    /**
     * Patient phone for new patient creation (not stored in appointments table)
     */
    @Transient
    @jakarta.validation.constraints.Pattern(regexp = "^(\\d{10})?$", message = "Phone number must be exactly 10 digits")
    private String patientPhone;

    /**
     * Patient email for new patient creation (not stored in appointments table)
     */
    @Transient
    @jakarta.validation.constraints.Email(message = "Invalid email format")
    @jakarta.validation.constraints.Size(max = 100, message = "Email is too long")
    private String patientEmail;

    /**
     * Patient date of birth for new patient creation (not stored in
     * appointments table). See Patient.dateOfBirth for why Patient itself
     * computes age instead of storing it.
     */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Transient
    private LocalDate patientDateOfBirth;

    /**
     * Patient gender for new patient creation (not stored in appointments table)
     */
    @Transient
    @jakarta.validation.constraints.Size(max = 20, message = "Gender is too long")
    @com.hms.validation.NoEmoji
    private String patientGender;

    /**
     * Doctor name for display purposes (populated by service)
     */
    @Transient
    @jakarta.validation.constraints.Size(max = 100, message = "Doctor name is too long")
    @com.hms.validation.NoEmoji
    private String doctorName;
}
