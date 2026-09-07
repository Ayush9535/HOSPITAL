package com.hms.dto;

import lombok.Data;
import com.hms.validation.NoEmoji;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Data
public class UpdateTaskStatusRequest {
    @NotBlank(message = "status is required")
    @Pattern(regexp = "(?i)^(PENDING|IN_PROGRESS|COMPLETED|CANCELLED)$",
            message = "status must be one of: PENDING, IN_PROGRESS, COMPLETED, CANCELLED")
    private String status;

    @Size(max = 1000)
    @NoEmoji
    private String completionRemarks;
}
