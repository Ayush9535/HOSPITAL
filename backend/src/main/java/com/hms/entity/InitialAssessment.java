package com.hms.entity;

import com.hms.validation.NoEmoji;
import jakarta.persistence.*;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * InitialAssessment - the nurse-captured clinical fields of the NABH "Admission
 * History & Initial Assessment" form (Phase 1 Nurse module). These fields are
 * not collected in the admission or consent forms, so they are entered here.
 * One per IPD admission. Medicines come live from prescriptions (not stored).
 */
@Entity
@Table(name = "initial_assessments")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InitialAssessment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String publicId;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    @Column(name = "ipd_admission_id", nullable = false, unique = true)
    private Long ipdAdmissionId;

    @Size(max = 2000)
    @NoEmoji
    @Column(name = "chief_complaints", columnDefinition = "text")
    private String chiefComplaints;
    @Size(max = 2000)
    @NoEmoji
    @Column(name = "associated_illness", columnDefinition = "text")
    private String associatedIllness;
    @Size(max = 2000)
    @NoEmoji
    @Column(name = "relevant_investigations", columnDefinition = "text")
    private String relevantInvestigations;
    @Size(max = 2000)
    @NoEmoji
    @Column(name = "allergies", columnDefinition = "text")
    private String allergies;
    @Size(max = 2000)
    @NoEmoji
    @Column(name = "vaccination_history", columnDefinition = "text")
    private String vaccinationHistory;
    @Size(max = 2000)
    @NoEmoji
    @Column(name = "others", columnDefinition = "text")
    private String others;
    @Size(max = 2000)
    @NoEmoji
    @Column(name = "past_history", columnDefinition = "text")
    private String pastHistory;
    @Size(max = 2000)
    @NoEmoji
    @Column(name = "family_history", columnDefinition = "text")
    private String familyHistory;
    @Size(max = 2000)
    @NoEmoji
    @Column(name = "personal_history", columnDefinition = "text")
    private String personalHistory;
    @Size(max = 2000)
    @NoEmoji
    @Column(name = "provisional_diagnosis", columnDefinition = "text")
    private String provisionalDiagnosis;
    @Size(max = 2000)
    @NoEmoji
    @Column(name = "care_plan", columnDefinition = "text")
    private String carePlan;

    /** Selected pain-assessment score 0–10 (null if not assessed). */
    @Min(value = 0, message = "painScore must be between 0 and 10")
    @Max(value = 10, message = "painScore must be between 0 and 10")
    @Column(name = "pain_score")
    private Integer painScore;

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
