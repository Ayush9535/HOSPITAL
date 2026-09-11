package com.hms.repository;

import com.hms.entity.MedicationAdministration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MedicationAdministrationRepository extends JpaRepository<MedicationAdministration, Long> {
    List<MedicationAdministration> findByIpdAdmissionIdAndIsActiveTrueOrderByCreatedAtDesc(Long ipdAdmissionId);
    /** CLIN-P1: one query across every admission belonging to a patient, not N. */
    List<MedicationAdministration> findByIpdAdmissionIdInAndIsActiveTrueOrderByCreatedAtAsc(java.util.List<Long> ipdAdmissionIds);
    List<MedicationAdministration> findByPrescriptionIdAndIsActiveTrueOrderByCreatedAtDesc(Long prescriptionId);

    /**
     * Records already written for this order with this outcome since a cutoff.
     *
     * <p>Used to recognise the same administration arriving twice. Recording a dose is not
     * naturally idempotent -- a nurse may legitimately give the same drug twice in a day -- so
     * the test is deliberately narrow and time-bounded rather than a unique constraint, which
     * would make the legitimate second dose impossible to record.
     */
    List<MedicationAdministration> findByPrescriptionIdAndStatusAndIsActiveTrueAndCreatedAtAfter(
            Long prescriptionId, String status, java.time.LocalDateTime after);
}
