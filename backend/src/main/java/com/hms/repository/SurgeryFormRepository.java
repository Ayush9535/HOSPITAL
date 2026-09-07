package com.hms.repository;

import com.hms.entity.SurgeryForm;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SurgeryFormRepository extends JpaRepository<SurgeryForm, Long> {

    /** The live row for a procedure's form. Superseded versions carry isCurrent = null. */
    Optional<SurgeryForm> findBySurgeryIdAndFormTypeAndIsCurrentTrue(Long surgeryId, String formType);

    /** Live forms for a procedure (drives the "Saved" badges). */
    List<SurgeryForm> findBySurgeryIdAndIsCurrentTrue(Long surgeryId);

    /** Full version history for one form, newest first. */
    List<SurgeryForm> findBySurgeryIdAndFormTypeOrderByVersionDesc(Long surgeryId, String formType);

    /** Legacy: forms saved against an admission before Phase 1 re-keyed them to the surgery. */
    List<SurgeryForm> findByIpdAdmissionId(Long ipdAdmissionId);
}
