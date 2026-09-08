package com.hms.entity;

/** Bed lifecycle states (stored lowercase in bed.status). */
public final class BedStatus {
    public static final String AVAILABLE = "available";
    public static final String OCCUPIED = "occupied";
    public static final String CLEANING = "cleaning";      // vacated, awaiting cleaning
    public static final String MAINTENANCE = "maintenance";
    private BedStatus() {}

    public static boolean isValid(String s) {
        return AVAILABLE.equals(s) || OCCUPIED.equals(s) || CLEANING.equals(s) || MAINTENANCE.equals(s);
    }
}
