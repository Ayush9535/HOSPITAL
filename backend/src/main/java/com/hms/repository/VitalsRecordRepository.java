package com.hms.repository;

import com.hms.entity.VitalsRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface VitalsRecordRepository extends JpaRepository<VitalsRecord, Long> {
    Optional<VitalsRecord> findByPublicId(String publicId);

    /** ICU Phase 4 — tenant-scoped resolve, so a foreign record is simply missing. */
    Optional<VitalsRecord> findByPublicIdAndHospitalId(String publicId, Long hospitalId);
    List<VitalsRecord> findByIpdAdmissionIdAndIsActiveTrueOrderByRecordedAtDesc(Long ipdAdmissionId);
    /** CLIN-P1: one query across every admission belonging to a patient, not N. */
    List<VitalsRecord> findByIpdAdmissionIdInAndIsActiveTrueOrderByRecordedAtAsc(java.util.List<Long> ipdAdmissionIds);

    /**
     * ICU Phase 2 — the latest active reading for each of several admissions, in one query.
     *
     * The ICU board shows the last recorded SpO2/respiratory rate per bed; asking per admission
     * would be one query per patient on every poll. Only the newest row per admission is
     * returned, not the history. A tie on recordedAt can yield more than one row for an
     * admission; the caller keeps the first and the values are equivalent by definition.
     */
    @org.springframework.data.jpa.repository.Query(
        "SELECT v FROM VitalsRecord v WHERE v.isActive = true AND v.ipdAdmissionId IN :ids "
      + "AND v.recordedAt = (SELECT MAX(v2.recordedAt) FROM VitalsRecord v2 "
      + "                    WHERE v2.ipdAdmissionId = v.ipdAdmissionId AND v2.isActive = true)")
    List<VitalsRecord> findLatestForAdmissions(
            @org.springframework.data.repository.query.Param("ids") java.util.Collection<Long> ids);
}
