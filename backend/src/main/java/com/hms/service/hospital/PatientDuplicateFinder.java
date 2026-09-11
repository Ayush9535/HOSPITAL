package com.hms.service.hospital;

import com.hms.dto.DuplicatePatientMatch;
import com.hms.entity.Patient;
import com.hms.repository.PatientRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The one place the question "does this hospital already have an active patient on this number?"
 * is asked.
 *
 * <p>Scoped to the tenant and to active patients, in that order of importance. The same phone
 * number at two different hospitals is normal and must never collide — families move between
 * providers, and a shared number is not a shared identity. An <b>inactive</b> patient does not
 * reserve a number either: soft delete is an undo for mis-registration, and letting a deleted row
 * hold a real person's phone hostage would deny that person registration with no way back, since
 * this system has no reactivation flow.
 *
 * <p>Its own component rather than a private method so it is reachable through the Spring proxy
 * from a caller that is not itself transactional, and so a caller that needs a <em>fresh</em>
 * read transaction actually gets one.
 */
@Component
public class PatientDuplicateFinder {

    @Autowired
    private PatientRepository patientRepository;

    /**
     * Active patients of this hospital already holding {@code phone}, in registration order.
     *
     * @param excludePatientId a patient to leave out — the one being edited, which is not its own
     *                         duplicate. Null on registration.
     */
    @Transactional(readOnly = true)
    public List<DuplicatePatientMatch> findActiveByPhone(Long hospitalId, String phone,
                                                         Long excludePatientId) {
        if (hospitalId == null || phone == null || phone.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<Patient> matches = patientRepository.findActiveByPhoneOrdered(phone, hospitalId);
        return matches.stream()
                .filter(p -> excludePatientId == null || !excludePatientId.equals(p.getId()))
                .map(DuplicatePatientMatch::from)
                .toList();
    }

    /**
     * Whether {@code patient} currently carries a live acknowledgement for the number it is about
     * to hold.
     *
     * <p>Value-bound on purpose: an acknowledgement records that this patient is a different
     * person sharing <em>that specific number</em>. It is not a per-patient opt-out, so an
     * acknowledgement for X grants nothing at all for Y. See {@code Patient.duplicatePhoneAckFor}.
     */
    public boolean hasLiveAcknowledgementFor(Patient patient, String phone) {
        return patient != null
                && patient.getDuplicatePhoneAckFor() != null
                && Objects.equals(patient.getDuplicatePhoneAckFor(), phone);
    }

    /**
     * Masks a phone number for anything that gets written down — audit details, logs.
     *
     * <p>Enough to reconcile a record against a number someone is holding, not enough to be a
     * contact list. Same shape the Checkpoint 2C identity audit used.
     */
    public static String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) {
            return "****";
        }
        return phone.substring(0, 2) + "******" + phone.substring(phone.length() - 2);
    }
}
