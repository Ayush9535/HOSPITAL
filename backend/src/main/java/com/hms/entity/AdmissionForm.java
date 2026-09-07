package com.hms.entity;

import com.hms.validation.NoEmoji;
import com.hms.validation.PersonName;
import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * AdmissionForm - the IPD admission form a nurse fills to complete a patient's
 * admission (Phase 1 Nurse module). One form per IPD admission. Known values
 * (patient/admission/doctor/ward/bed) are pre-filled when the draft is created;
 * the rest (relative, insurance, category, etc.) are entered by the nurse.
 */
@Entity
@Table(name = "admission_forms")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdmissionForm {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String publicId;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    @Column(name = "ipd_admission_id", nullable = false, unique = true)
    private Long ipdAdmissionId;

    // Header
    @Size(max = 60)
    @NoEmoji
    @Column(name = "prn_no", length = 60)
    private String prnNo;
    @Size(max = 60)
    @NoEmoji
    @Column(name = "bed_no", length = 60)
    private String bedNo;
    @Size(max = 40)
    @NoEmoji
    @Column(name = "category", length = 40) // free text; pre-filled with the ward name
    private String category;

    // Patient
    @PersonName
    @Column(name = "patient_surname", length = 100)
    private String patientSurname;
    @PersonName
    @Column(name = "patient_first_name", length = 100)
    private String patientFirstName;
    @PersonName
    @Column(name = "husband_father_name", length = 150)
    private String husbandFatherName;
    @Size(max = 500)
    @NoEmoji
    @Column(name = "patient_address", columnDefinition = "text")
    private String patientAddress;
    @Size(max = 20)
    @NoEmoji
    @Column(name = "age", length = 20)
    private String age;
    @Size(max = 20)
    @NoEmoji
    @Column(name = "sex", length = 20)
    private String sex;
    @Size(max = 100)
    @NoEmoji
    @Column(name = "occupation", length = 100)
    private String occupation;

    // Category / payer
    @Size(max = 40)
    @NoEmoji
    @Column(name = "patient_category", length = 40) // free text label, e.g. Hospital Patient / Private Patient / PMC / PMT
    private String patientCategory;
    @Size(max = 20)
    @NoEmoji
    @Column(name = "mediclaim", length = 20) // free text label, e.g. Cashless / Reimburse
    private String mediclaim;
    @Size(max = 200)
    @NoEmoji
    @Column(name = "tpa_name", length = 200)
    private String tpaName;

    // Contacts
    @PersonName
    @Column(name = "relative_name", length = 150)
    private String relativeName;
    @Email
    @Size(max = 120)
    @Column(name = "email", length = 120)
    private String email;
    @Size(max = 40)
    @Pattern(regexp = "^[0-9+()\\-\\s]{6,40}$", message = "Invalid telephone number")
    @Column(name = "telephone", length = 40)
    private String telephone;
    @PersonName
    @Column(name = "receptionist_name", length = 120)
    private String receptionistName;
    @PersonName
    @Column(name = "ref_dr", length = 150)
    private String refDr;

    // Admission clinical
    @Size(max = 60)
    @NoEmoji
    @Column(name = "ipd_registration_no", length = 60)
    private String ipdRegistrationNo;
    @Size(max = 120)
    @NoEmoji
    @Column(name = "department", length = 120)
    private String department;
    @PersonName
    @Column(name = "under_care_of_dr", length = 150)
    private String underCareOfDr;
    @Size(max = 40)
    @NoEmoji
    @Column(name = "admitted_date", length = 40)
    private String admittedDate;
    @Size(max = 40)
    @NoEmoji
    @Column(name = "admitted_time", length = 40)
    private String admittedTime;
    @Size(max = 1000)
    @NoEmoji
    @Column(name = "prov_diagnosis1", columnDefinition = "text")
    private String provDiagnosis1;
    @Size(max = 1000)
    @NoEmoji
    @Column(name = "prov_diagnosis2", columnDefinition = "text")
    private String provDiagnosis2;

    @Size(max = 1000)
    @NoEmoji
    @Column(name = "hypersensitivity_history", columnDefinition = "text")
    private String hypersensitivityHistory;

    // Relatives block (for signature)
    @Size(max = 500)
    @NoEmoji
    @Column(name = "relative_address", columnDefinition = "text")
    private String relativeAddress;
    @Size(max = 40)
    @Pattern(regexp = "^[0-9+()\\-\\s]{6,40}$", message = "Invalid phone number")
    @Column(name = "relative_phone", length = 40)
    private String relativePhone;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (this.publicId == null) {
            this.publicId = java.util.UUID.randomUUID().toString();
        }
    }
}
