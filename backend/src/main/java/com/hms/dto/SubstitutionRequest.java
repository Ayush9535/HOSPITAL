package com.hms.dto;

import java.time.LocalDate;

import com.hms.validation.NoEmoji;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public class SubstitutionRequest {
    @NotNull(message = "primaryNurseProfileId is required")
    @Positive
    private Long primaryNurseProfileId;

    @NotNull(message = "replacementNurseProfileId is required")
    @Positive
    private Long replacementNurseProfileId;

    @NotNull(message = "fromDate is required")
    private LocalDate fromDate;

    @NotNull(message = "toDate is required")
    private LocalDate toDate;

    @Size(max = 1000)
    @NoEmoji
    private String reason;

    public Long getPrimaryNurseProfileId() { return primaryNurseProfileId; }
    public void setPrimaryNurseProfileId(Long primaryNurseProfileId) { this.primaryNurseProfileId = primaryNurseProfileId; }
    public Long getReplacementNurseProfileId() { return replacementNurseProfileId; }
    public void setReplacementNurseProfileId(Long replacementNurseProfileId) { this.replacementNurseProfileId = replacementNurseProfileId; }
    public LocalDate getFromDate() { return fromDate; }
    public void setFromDate(LocalDate fromDate) { this.fromDate = fromDate; }
    public LocalDate getToDate() { return toDate; }
    public void setToDate(LocalDate toDate) { this.toDate = toDate; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
