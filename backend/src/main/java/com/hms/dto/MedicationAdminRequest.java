package com.hms.dto;

import com.hms.validation.NoEmoji;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Payload to record administration of a prescribed dose.
 */
@Data
public class MedicationAdminRequest {
    @Positive
    private Long ipdAdmissionId;

    @Positive
    private Long prescriptionId;

    @NotBlank
    @Pattern(regexp = "^(GIVEN|SKIPPED|DELAYED|REFUSED|NOT_AVAILABLE)$",
            message = "status must be one of GIVEN, SKIPPED, DELAYED, REFUSED, NOT_AVAILABLE")
    private String status;              // GIVEN / SKIPPED / DELAYED / REFUSED / NOT_AVAILABLE

    private LocalDateTime scheduledTime;   // optional
    private LocalDateTime administeredTime; // required for GIVEN / DELAYED

    @Size(max = 1000)
    @NoEmoji
    private String remarks;

    @Positive
    private Long performedByNurseId; // optional; required when Separate Nurse Login is OFF
}
