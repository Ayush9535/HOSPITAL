package com.hms.dto;

import lombok.Data;
import java.time.LocalTime;
import com.hms.validation.NoEmoji;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Data
public class ShiftTemplateRequest {
    @NotBlank(message = "name is required")
    @Size(max = 100)
    @NoEmoji
    private String name;

    @NotNull(message = "startTime is required")
    private LocalTime startTime;

    @NotNull(message = "endTime is required")
    private LocalTime endTime;
}
