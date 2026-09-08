package com.hms.dto;

import lombok.Data;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import com.hms.validation.NoEmoji;

@Data
public class CreateIpdAdmissionRequest {
    private Long opdId; // source OPD id
    private Long wardId;
    private Long bedId;

    @Pattern(regexp = "^(EMERGENCY|ELECTIVE)?$", message = "Admission type must be EMERGENCY or ELECTIVE")
    private String admissionType; // EMERGENCY / ELECTIVE

    @Size(max = 2000, message = "Primary diagnosis is too long")
    @NoEmoji
    private String primaryDiagnosis;
}
