package com.hms.dto;

import com.hms.validation.NoEmoji;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public class CalendarEventRequest {
    @NotBlank(message = "Title is required")
    @Size(max = 150, message = "Title is too long")
    @NoEmoji
    private String title;

    @Size(max = 50, message = "Event type is too long")
    @NoEmoji
    private String eventType;

    private LocalDate fromDate;
    private LocalDate toDate;

    @Size(max = 1000, message = "Description is too long")
    @NoEmoji
    private String description;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public LocalDate getFromDate() { return fromDate; }
    public void setFromDate(LocalDate fromDate) { this.fromDate = fromDate; }
    public LocalDate getToDate() { return toDate; }
    public void setToDate(LocalDate toDate) { this.toDate = toDate; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
