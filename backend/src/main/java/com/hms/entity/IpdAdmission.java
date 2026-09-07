package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "ipd_admission")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IpdAdmission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ipd_number", nullable = false, unique = true)
    private String ipdNumber;

    @Column(name = "patient_id", nullable = false)
    private Long patientId;

    @Column(name = "doctor_id", nullable = false)
    private Long doctorId;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    @Column(name = "source_opd_id")
    private Long sourceOpdId;

    @Column(name = "admission_type", nullable = false)
    private String admissionType; // EMERGENCY / ELECTIVE

    @Column(name = "status", nullable = false)
    private String status; // ADMITTED / DISCHARGED

    @Column(name = "admission_datetime", nullable = false)
    private LocalDateTime admissionDatetime;

    @Column(name = "discharge_datetime")
    private LocalDateTime dischargeDatetime;

    @Column(name = "ward_id", nullable = false)
    private Long wardId;

    @Column(name = "bed_id", nullable = false)
    private Long bedId;

    @Column(name = "primary_diagnosis", columnDefinition = "text")
    private String primaryDiagnosis;

    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    /**
     * Whether the nurse has completed the admission form and confirmed the
     * patient as admitted (after collecting the signed printed form). Until then
     * the admission is "pending" even though a bed is reserved.
     */
    @Column(name = "admission_confirmed", nullable = false)
    private Boolean admissionConfirmed = false;

    @Column(name = "admission_confirmed_at")
    private LocalDateTime admissionConfirmedAt;

    /** users.id of the receptionist/admin who admitted the patient (assigned ward/bed). */
    @Column(name = "admitted_by_user_id")
    private Long admittedByUserId;
}
