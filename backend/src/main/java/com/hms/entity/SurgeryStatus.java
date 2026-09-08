package com.hms.entity;

/**
 * SurgeryStatus - the surgical case lifecycle.
 *
 * Nine states. Deliberately absent:
 *  - IN_THEATRE   a state whose only content was a timestamp; it is the
 *                 PATIENT_ENTERED_OT milestone instead.
 *  - WAITLISTED   the waiting list IS "APPROVED with no schedule"; a state that
 *                 exists only to be left immediately is not a state.
 *  - RESCHEDULED  a rescheduled case is still SCHEDULED; the move is a
 *                 SCHEDULED -> SCHEDULED transition carrying both slots.
 *  - IN_RECOVERY  the theatre is turned over while the patient is still in PACU.
 *                 Case status and patient location are different lifecycles, and
 *                 conflating them destroys theatre utilisation.
 *
 * The test for a state: does it gate which actions are legal? If a query or a
 * timestamp answers it, it is not a state.
 */
public enum SurgeryStatus {

    /** A clinician has asked for the procedure. */
    REQUESTED,

    /** Cleared to be scheduled. With APPROVAL_MODE=NONE the system grants this itself. */
    APPROVED,

    /** Has a theatre and a time. */
    SCHEDULED,

    /** Patient is being prepared; pre-op checks and clearances apply here. */
    PRE_OP,

    /** Knife to skin through closure. */
    IN_PROGRESS,

    /** The procedure is finished. The theatre is released at this point. */
    COMPLETED,

    /** Clinical documentation complete and patient dispositioned. Never billing, never discharge. */
    CLOSED,

    /** Terminal. Requires a reason. */
    CANCELLED,

    /** Not terminal: returns to APPROVED and re-enters the waiting list. */
    POSTPONED;

    public boolean isTerminal() {
        return this == CANCELLED || this == CLOSED;
    }

    public static SurgeryStatus of(String value) {
        if (value == null) throw new IllegalArgumentException("Surgery status is required");
        try {
            return valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown surgery status: " + value);
        }
    }
}
