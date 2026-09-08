package com.hms.dto;

import com.hms.validation.NoEmoji;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Create/update payload for a sugar-chart entry.
 */
@Data
public class SugarChartRequest {
    @Positive
    private Long ipdAdmissionId; // required on create; ignored on update

    @Size(max = 50)
    @NoEmoji
    private String bloodSugar;

    @Size(max = 1000)
    @NoEmoji
    private String treatment;

    @Positive
    private Long performedByNurseId; // optional; required when Separate Nurse Login is OFF
}
