package com.hms.repository;

import com.hms.entity.SugarChartEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SugarChartEntryRepository extends JpaRepository<SugarChartEntry, Long> {
    Optional<SugarChartEntry> findByPublicId(String publicId);
    List<SugarChartEntry> findByIpdAdmissionIdAndIsActiveTrueOrderByRecordedAtDesc(Long ipdAdmissionId);
    /** CLIN-P1: one query across every admission belonging to a patient, not N. */
    List<SugarChartEntry> findByIpdAdmissionIdInAndIsActiveTrueOrderByRecordedAtAsc(java.util.List<Long> ipdAdmissionIds);
}
