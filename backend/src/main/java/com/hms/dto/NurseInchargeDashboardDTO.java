package com.hms.dto;

import lombok.Data;

/** Aggregated counts for the Nurse Incharge dashboard (across their wards). */
@Data
public class NurseInchargeDashboardDTO {
    private Patients patients = new Patients();
    private Nurses nurses = new Nurses();
    private Beds beds = new Beds();

    @Data
    public static class Patients {
        private int total;
        private int newAdmissionsToday;
        private int dischargesToday;
    }

    @Data
    public static class Nurses {
        private int total;
        private int present;
        private int absent;
        private int onLeave;
    }

    @Data
    public static class Beds {
        private int total;
        private int available;
        private int occupied;
        private int cleaningRequired;
        private int underMaintenance;
    }
}
