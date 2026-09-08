package com.hms.dto;

import lombok.Data;

import java.time.LocalTime;

/** One row of the incharge's daily attendance sheet (marked or not). */
@Data
public class AttendanceSheetRow {
    private Long nurseProfileId;
    private String nurseName;
    private Long shiftTemplateId;
    private LocalTime shiftStartTime;
    private LocalTime shiftEndTime;
    private String attendancePublicId;
    private String status;
    private LocalTime checkInTime;
    private LocalTime checkOutTime;
    private String remarks;
}
