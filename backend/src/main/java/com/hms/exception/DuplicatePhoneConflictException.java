package com.hms.exception;

import com.hms.dto.DuplicatePatientMatch;

import java.util.List;

/**
 * A registration (or a phone change) names a contact number that one or more <b>active</b>
 * patients of the same hospital already hold.
 *
 * <p>This is not an error in the usual sense and it is not a hard block: a parent and a child
 * legitimately share one mobile number. It is a question that only a human can answer — is this
 * the same person, or a different person on the same number? — so the matches travel with the
 * exception and reach the caller in the 409 body, where reception picks one or declares the new
 * registration a different person.
 *
 * <p>Thrown <b>before</b> anything is written. That matters: on the appointment path the check
 * runs inside {@code AppointmentService.createAppointment}'s transaction, and throwing before the
 * first write leaves a clean rollback rather than a rollback-only transaction that can no longer
 * record anything.
 */
public class DuplicatePhoneConflictException extends RuntimeException {

    private final transient List<DuplicatePatientMatch> conflicts;

    public DuplicatePhoneConflictException(String message, List<DuplicatePatientMatch> conflicts) {
        super(message);
        this.conflicts = List.copyOf(conflicts);
    }

    public List<DuplicatePatientMatch> getConflicts() {
        return conflicts;
    }
}
