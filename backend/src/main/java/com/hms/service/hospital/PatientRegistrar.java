package com.hms.service.hospital;

import com.hms.entity.Patient;
import com.hms.repository.PatientRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one place a patient row comes into existence.
 *
 * <p>The registration number is derived from the auto-increment id, so it cannot be assigned until
 * the row exists: insert, read the id back, then write the number onto the same managed instance.
 * Both steps have to be one unit of work — a patient committed without a number is a patient whose
 * prescription shows a raw UUID, and nothing later repairs it.
 *
 * <p>This lives in its own component rather than as a method on PatientService for a specific
 * reason. PatientService.addPatient must NOT be transactional: it audits afterwards, and
 * AuditLogService.logAction is itself {@code @Transactional}, so an audit failure inside a caller's
 * transaction marks that transaction rollback-only and destroys the registration even though the
 * caller catches the exception. Keeping the transaction here, reached cross-bean, gives the insert
 * its own committed boundary while leaving the audit outside it — which is the behaviour direct
 * registration had before the number was introduced, and the behaviour it keeps now.
 *
 * <p>Cross-bean also makes the annotation effective at all: Spring ignores {@code @Transactional}
 * on non-public methods under CGLIB proxying, and a self-invocation from a non-transactional
 * caller would silently run with no transaction.
 *
 * <p>Propagation is the default REQUIRED, so the appointment path — already transactional at its
 * entry point — joins its caller and keeps appointment, patient and number in one transaction.
 *
 * <p>Deliberately narrow: validation, tenant resolution, duplicate policy, auditing and DOB rules
 * stay with the callers, which is why two callers with different rules can share this.
 */
@Component
public class PatientRegistrar {

    @Autowired
    private PatientRepository patientRepository;

    /**
     * Insert a new patient and give it its registration number, atomically.
     *
     * <p>The caller is responsible for having set the tenant: this method does not resolve or
     * assign {@code hospitalId}.
     *
     * @param patient a new, unsaved patient with its tenant already set
     * @return the managed instance, carrying its generated id and customId
     */
    @Transactional
    public Patient persistNewPatient(Patient patient) {
        // saveAndFlush, not save: the INSERT has to reach the database before getId() can answer.
        Patient saved = patientRepository.saveAndFlush(patient);
        saved.setCustomId("PAT" + saved.getId());
        // Dirty-checked; the UPDATE is written when this transaction flushes before commit.
        return saved;
    }
}
