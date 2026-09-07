package com.hms.dto;

import lombok.Data;
import java.time.LocalDateTime;
import com.hms.validation.NoEmoji;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@Data
public class CreateTaskRequest {
    @NotBlank(message = "title is required")
    @Size(max = 200)
    @NoEmoji
    private String title;

    @Size(max = 2000)
    @NoEmoji
    private String description;

    @NotNull(message = "assignedToNurseUserId is required")
    @Positive
    private Long assignedToNurseUserId;

    @Positive
    private Long ipdAdmissionId; // nullable

    @Pattern(regexp = "(?i)^(LOW|MEDIUM|HIGH)$", message = "priority must be one of: LOW, MEDIUM, HIGH")
    private String priority = "MEDIUM"; // LOW / MEDIUM / HIGH
    private LocalDateTime dueDate;
}
