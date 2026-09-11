package com.hms.entity;

/** Nurse attendance statuses (stored uppercase). */
public final class AttendanceStatus {
    public static final String PRESENT = "PRESENT";
    public static final String ABSENT = "ABSENT";
    public static final String HALF_DAY = "HALF_DAY";
    public static final String LEAVE = "LEAVE";
    public static final String HOLIDAY = "HOLIDAY";
    public static final String LATE = "LATE";
    private AttendanceStatus() {}

    public static boolean isValid(String s) {
        return PRESENT.equals(s) || ABSENT.equals(s) || HALF_DAY.equals(s)
                || LEAVE.equals(s) || HOLIDAY.equals(s) || LATE.equals(s);
    }
}
