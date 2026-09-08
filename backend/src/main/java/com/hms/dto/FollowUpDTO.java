package com.hms.dto;

import java.time.LocalDate;

/**
 * One outstanding follow-up, as the due list needs it.
 *
 * <p>Carries what a receptionist needs to recognise the patient and what a clinician needs to
 * recall the visit — and nothing else. The original consultation's symptoms, prescription and
 * treatment notes are deliberately not here: this list is read at a front desk, and the whole
 * record remains available to anyone entitled to open the patient.
 */
public class FollowUpDTO {

    private Long medicalRecordId;
    private Long originalOpdId;

    private Long patientId;
    private String patientPublicId;
    private String patientCustomId;
    private String patientName;
    private String patientPhone;

    private Long doctorId;
    private String doctorName;

    private LocalDate followUpDate;
    private String followUpInstructions;
    private String diagnosis;

    /** Persisted lifecycle: OPEN (possibly implied by NULL), ACTIONED, COMPLETED, CANCELLED. */
    private String status;

    /** Derived from the date, never stored: OVERDUE, DUE_TODAY or UPCOMING. */
    private String timing;

    /** Signed: negative before the date, 0 on the day, positive once it has passed. */
    private long daysOverdue;

    public FollowUpDTO(Long medicalRecordId, Long originalOpdId, Long patientId,
                       String patientPublicId, String patientCustomId, String patientName,
                       String patientPhone, Long doctorId, String doctorName,
                       LocalDate followUpDate, String followUpInstructions, String diagnosis,
                       String status) {
        this.medicalRecordId = medicalRecordId;
        this.originalOpdId = originalOpdId;
        this.patientId = patientId;
        this.patientPublicId = patientPublicId;
        this.patientCustomId = patientCustomId;
        this.patientName = patientName;
        this.patientPhone = patientPhone;
        this.doctorId = doctorId;
        this.doctorName = doctorName;
        this.followUpDate = followUpDate;
        this.followUpInstructions = followUpInstructions;
        this.diagnosis = diagnosis;
        this.status = status == null ? com.hms.entity.MedicalRecord.FOLLOW_UP_OPEN : status;
    }

    public Long getMedicalRecordId() { return medicalRecordId; }
    public Long getOriginalOpdId() { return originalOpdId; }
    public Long getPatientId() { return patientId; }
    public String getPatientPublicId() { return patientPublicId; }
    public String getPatientCustomId() { return patientCustomId; }
    public String getPatientName() { return patientName; }
    public String getPatientPhone() { return patientPhone; }
    public Long getDoctorId() { return doctorId; }
    public String getDoctorName() { return doctorName; }
    public LocalDate getFollowUpDate() { return followUpDate; }
    public String getFollowUpInstructions() { return followUpInstructions; }
    public String getDiagnosis() { return diagnosis; }
    public String getStatus() { return status; }
    public String getTiming() { return timing; }
    public void setTiming(String timing) { this.timing = timing; }
    public long getDaysOverdue() { return daysOverdue; }
    public void setDaysOverdue(long daysOverdue) { this.daysOverdue = daysOverdue; }
}
