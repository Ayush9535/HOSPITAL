package com.hms.dto;

import lombok.Data;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Data
public class AssignPatientNurseRequest {
    @NotNull(message = "ipdAdmissionId is required")
    @Positive
    private Long ipdAdmissionId;   // required

    @NotNull(message = "nurseProfileId is required")
    @Positive
    private Long nurseProfileId;   // required — the staff nurse to assign
}
