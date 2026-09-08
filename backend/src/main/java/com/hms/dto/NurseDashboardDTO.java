package com.hms.dto;

import lombok.Data;

import java.util.List;

/**
 * Aggregates for the nurse dashboard landing screen (Phase 1).
 * Counts for tasks / vitals-today / unread notifications are added as those
 * milestones (M4, M7, M8) come online — this DTO grows additively.
 */
@Data
public class NurseDashboardDTO {
    private long assignedPatientCount;
    private List<MyPatientDTO> recentPatients;
}
