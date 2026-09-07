package com.hms.service.hospital;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * What "today" means to this deployment.
 *
 * <p>Follow-ups are compared against a calendar date, so the answer decides whether a patient is
 * due, overdue, or not yet expected — and a bare {@code LocalDate.now()} answers it with whatever
 * zone the server happens to be running in. That was already inconsistent: the daily billing
 * scheduler pins {@code Asia/Kolkata} in its cron while every other date call took the JVM
 * default, so the two could disagree about which day it was for several hours.
 *
 * <p>There is no per-facility timezone anywhere in this system — {@code Hospital} and
 * {@code HospitalSetting} have no such column — so this is a single deployment-wide setting
 * rather than a facility one. It is stated here, in one place, instead of being implied in
 * several. If facilities in different zones are ever onboarded this is the seam that has to
 * change, and it is deliberately small.
 *
 * <p>The clock is injectable so tests can hold a date still and prove yesterday, today and
 * tomorrow without sleeping or depending on when they run.
 */
@Component
public class BusinessClock {

    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public BusinessClock(@Value("${hms.business-timezone:Asia/Kolkata}") String zone) {
        this.clock = Clock.system(ZoneId.of(zone));
    }

    /** For tests: a clock fixed wherever the caller needs it. */
    public BusinessClock(Clock clock) {
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /**
     * Business wall-clock instant. Used where a reading is a snapshot rather than a day — bed
     * occupancy is true at a moment, not across a range, so it timestamps itself.
     */
    public java.time.LocalDateTime now() {
        return java.time.LocalDateTime.now(clock);
    }

    /** The business zone, so a response can state which day boundary produced its numbers. */
    public ZoneId zoneId() {
        return clock.getZone();
    }
}
