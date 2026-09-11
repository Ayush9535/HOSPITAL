package com.hms.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * A patient (IPD admission) currently assigned to the logged-in nurse.
 */
@Data
public class MyPatientDTO {
    private Long ipdAdmissionId;
    private String ipdNumber;
    private String patientName;
    private Integer age;
    private String gender;
    private Long wardId;
    private String wardName;
    private String bedCode;
    private String primaryDiagnosis;
    private String doctorName;
    private LocalDateTime admissionDateTime;
    private String status;
    private Boolean admissionConfirmed;
    // Status of an active surgery for this patient (REQUESTED / SCHEDULED /
    // IN_PROGRESS), or null when there is no active surgery.
    private String surgeryStatus;
    // When this patient appears because the nurse is substituting for someone,
    // the primary nurse's name; null for the nurse's own assignments.
    private String coveredFor;
}
