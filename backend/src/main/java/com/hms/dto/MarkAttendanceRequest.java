package com.hms.dto;

import lombok.Data;
import com.hms.validation.NoEmoji;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;

@Data
public class MarkAttendanceRequest {
    @NotNull(message = "nurseProfileId is required")
    @Positive
    private Long nurseProfileId;   // required

    @NotNull(message = "date is required")
    private LocalDate date;        // required

    @NotBlank(message = "status is required")
    @Pattern(regexp = "(?i)^(PRESENT|ABSENT|HALF_DAY|LEAVE|HOLIDAY|LATE)$",
            message = "status must be one of: PRESENT, ABSENT, HALF_DAY, LEAVE, HOLIDAY, LATE")
    private String status;         // required: PRESENT/ABSENT/HALF_DAY/LEAVE/HOLIDAY/LATE
    private LocalTime checkInTime; // optional
    private LocalTime checkOutTime;// optional

    @Size(max = 1000)
    @NoEmoji
    private String remarks;        // optional
}
