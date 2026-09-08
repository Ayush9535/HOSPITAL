package com.hms.entity;

/** A clinician-recorded decision; the HMS never infers clinical fitness. */
public enum AnaesthesiaClearanceOutcome {
    CLEARED,
    CLEARED_WITH_CONDITIONS,
    DEFERRED,
    NOT_CLEARED
}
