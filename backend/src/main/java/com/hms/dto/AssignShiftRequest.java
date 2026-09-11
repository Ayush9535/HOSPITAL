package com.hms.dto;

import lombok.Data;
import java.time.LocalDate;
import com.hms.validation.NoEmoji;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@Data
public class AssignShiftRequest {
    @NotNull(message = "nurseProfileId is required")
    @Positive
    private Long nurseProfileId;

    @NotNull(message = "date is required")
    private LocalDate date;

    @NotBlank(message = "shiftTemplatePublicId is required")
    @Size(max = 100)
    @NoEmoji
    private String shiftTemplatePublicId;
}
