package com.hms.dto;

import lombok.Data;
import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import com.hms.validation.NoEmoji;

/**
 * "Create Surgery Request" payload. Either ipdAdmissionId (inpatient, from the IPD
 * case view) or patientId (day-care procedure with no admission) must be supplied.
 */
@Data
public class CreateSurgeryRequest {
    private Long ipdAdmissionId;  // inpatient procedures
    private Long patientId;       // day-care procedures (no admission)

    @NotBlank(message = "Procedure name is required")
    @Size(max = 200, message = "Procedure name is too long")
    @NoEmoji
    private String procedureName;

    @Size(max = 2000, message = "Clinical notes is too long")
    @NoEmoji
    private String clinicalNotes;

    @Pattern(regexp = "^(ELECTIVE|EMERGENCY)?$", message = "Priority must be ELECTIVE or EMERGENCY")
    private String priority;      // ELECTIVE | EMERGENCY
    private LocalDate preferredDate;
}
