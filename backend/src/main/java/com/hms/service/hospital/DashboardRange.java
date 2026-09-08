package com.hms.service.hospital;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The dashboard's business-day windows, in one place.
 *
 * <p>The client sends only the enum name. It never sends dates, and that is deliberate: a browser
 * asked for "today" answers in its own timezone, and several existing screens get this wrong by
 * formatting {@code new Date().toISOString()}, which yields the UTC date and is therefore yesterday
 * between midnight and 05:30 IST. The business day is a property of the hospital, not of whoever
 * happens to be looking, so the server owns it.
 *
 * <p>Every window is half-open, {@code >= from} and {@code < toExclusive}. The alternative —
 * an inclusive bound at {@code LocalTime.MAX} — is what produced the defect fixed in
 * {@code fix/ist-date-boundaries}: 23:59:59.999999999 carries nanosecond precision that a
 * {@code DATETIME(6)} column cannot hold, so the last fraction of a day fell through the gap.
 *
 * <p>The bounds are business wall-clock {@code LocalDateTime} and are compared directly against
 * DATETIME columns. They are NOT converted to UTC. The deployed chain — JVM, MySQL session and
 * JDBC {@code serverTimezone} — is Asia/Kolkata throughout, so those columns already hold IST wall
 * clock; shifting the bounds to UTC while the data stayed put is precisely the bug that made
 * "today" run from half past six the previous evening.
 */
public enum DashboardRange {

    TODAY(0),
    LAST_7_DAYS(6),
    LAST_30_DAYS(29);

    private final int daysBack;

    DashboardRange(int daysBack) {
        this.daysBack = daysBack;
    }

    /** Inclusive start of the window. */
    public LocalDateTime from(LocalDate today) {
        return today.minusDays(daysBack).atStartOfDay();
    }

    /** Exclusive end: the start of tomorrow, so the whole of today counts. */
    public LocalDateTime toExclusive(LocalDate today) {
        return today.plusDays(1).atStartOfDay();
    }

    /** Number of daily buckets a trend for this range must contain, silent days included. */
    public int bucketCount() {
        return daysBack + 1;
    }

    /** The first day a trend bucket covers. */
    public LocalDate firstDay(LocalDate today) {
        return today.minusDays(daysBack);
    }

    /**
     * Parse the query parameter. Unknown values are rejected rather than defaulted: silently
     * showing "today" to someone who asked for thirty days is worse than an error, because the
     * numbers look plausible.
     */
    public static DashboardRange parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return TODAY;
        }
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException notARange) {
            throw new IllegalArgumentException(
                    "Unsupported range '" + raw + "'. Use TODAY, LAST_7_DAYS or LAST_30_DAYS.");
        }
    }
}
