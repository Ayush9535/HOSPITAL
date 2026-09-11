package com.hms.dto;

import com.hms.entity.Patient;

/**
 * One existing patient offered back to reception when a registration hits an already-registered
 * phone number.
 *
 * <p>Deliberately a hand-built projection rather than the {@link Patient} entity. The conflict
 * body answers exactly one question — "is the person in front of me one of these?" — and must
 * carry nothing beyond what answering it needs. {@code name} and {@code age} identify; {@code id}
 * and {@code publicId} let the caller continue its workflow with the chosen patient; {@code
 * customId} is the PAT-number staff recognise.
 *
 * <p>Excluded on purpose: the phone (the caller just typed it, and echoing it puts a contact
 * number into error logs), the full date of birth (a strong identifier where the age alone
 * settles parent-versus-child), and the address, email and medical history (none of which help
 * make the decision).
 */
public record DuplicatePatientMatch(
        Long id,
        String publicId,
        String customId,
        String name,
        Integer age) {

    public static DuplicatePatientMatch from(Patient patient) {
        return new DuplicatePatientMatch(
                patient.getId(),
                patient.getPublicId(),
                patient.getCustomId(),
                patient.getName(),
                patient.getAge());
    }
}
