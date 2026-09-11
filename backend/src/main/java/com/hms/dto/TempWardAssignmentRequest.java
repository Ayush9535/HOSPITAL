package com.hms.dto;

import java.time.LocalDate;

import com.hms.validation.NoEmoji;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public class TempWardAssignmentRequest {
    @NotNull(message = "nurseProfileId is required")
    @Positive
    private Long nurseProfileId;

    @NotNull(message = "tempWardId is required")
    @Positive
    private Long tempWardId;

    @NotNull(message = "fromDate is required")
    private LocalDate fromDate;

    @NotNull(message = "toDate is required")
    private LocalDate toDate;

    @Size(max = 1000)
    @NoEmoji
    private String reason;

    public Long getNurseProfileId() { return nurseProfileId; }
    public void setNurseProfileId(Long nurseProfileId) { this.nurseProfileId = nurseProfileId; }
    public Long getTempWardId() { return tempWardId; }
    public void setTempWardId(Long tempWardId) { this.tempWardId = tempWardId; }
    public LocalDate getFromDate() { return fromDate; }
    public void setFromDate(LocalDate fromDate) { this.fromDate = fromDate; }
    public LocalDate getToDate() { return toDate; }
    public void setToDate(LocalDate toDate) { this.toDate = toDate; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
