package com.hms.dto;

import java.time.LocalDateTime;

/**
 * Read model for the Hospital Admin "Nurse Assignments" tab: one row per active
 * IPD admission with its currently assigned nurse (or null if unassigned).
 */
public class NurseAssignmentDTO {
    private Long ipdAdmissionId;
    private String ipdNumber;
    private String patientName;
    private String doctorName;
    private LocalDateTime admissionDateTime;
    private String status;

    // Current active assignment (null when unassigned)
    private String assignmentPublicId;
    private Long nurseUserId;
    private String nurseName;
    private LocalDateTime assignedAt;

    public Long getIpdAdmissionId() { return ipdAdmissionId; }
    public void setIpdAdmissionId(Long ipdAdmissionId) { this.ipdAdmissionId = ipdAdmissionId; }

    public String getIpdNumber() { return ipdNumber; }
    public void setIpdNumber(String ipdNumber) { this.ipdNumber = ipdNumber; }

    public String getPatientName() { return patientName; }
    public void setPatientName(String patientName) { this.patientName = patientName; }

    public String getDoctorName() { return doctorName; }
    public void setDoctorName(String doctorName) { this.doctorName = doctorName; }

    public LocalDateTime getAdmissionDateTime() { return admissionDateTime; }
    public void setAdmissionDateTime(LocalDateTime admissionDateTime) { this.admissionDateTime = admissionDateTime; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getAssignmentPublicId() { return assignmentPublicId; }
    public void setAssignmentPublicId(String assignmentPublicId) { this.assignmentPublicId = assignmentPublicId; }

    public Long getNurseUserId() { return nurseUserId; }
    public void setNurseUserId(Long nurseUserId) { this.nurseUserId = nurseUserId; }

    public String getNurseName() { return nurseName; }
    public void setNurseName(String nurseName) { this.nurseName = nurseName; }

    public LocalDateTime getAssignedAt() { return assignedAt; }
    public void setAssignedAt(LocalDateTime assignedAt) { this.assignedAt = assignedAt; }
}
