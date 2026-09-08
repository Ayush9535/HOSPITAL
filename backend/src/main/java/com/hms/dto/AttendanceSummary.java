package com.hms.dto;

import lombok.Data;

@Data
public class AttendanceSummary {
    private int present;
    private int absent;
    private int halfDay;
    private int leave;
    private int holiday;
    private int late;
    private int unmarked;
}
