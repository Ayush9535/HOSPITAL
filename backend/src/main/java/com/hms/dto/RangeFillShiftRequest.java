package com.hms.dto;

import lombok.Data;
import java.time.LocalDate;
import java.util.List;
import com.hms.validation.NoEmoji;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@Data
public class RangeFillShiftRequest {
    @NotNull(message = "nurseProfileId is required")
    @Positive
    private Long nurseProfileId;

    @NotNull(message = "fromDate is required")
    private LocalDate fromDate;

    @NotNull(message = "toDate is required")
    private LocalDate toDate;

    @NotBlank(message = "shiftTemplatePublicId is required")
    @Size(max = 100)
    @NoEmoji
    private String shiftTemplatePublicId;

    private List<@Min(1) @Max(7) Integer> daysOfWeek; // 1=Mon..7=Sun; null/empty = all days
}
