package com.hms.repository;

import com.hms.entity.IcuSeverityScore;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** ICU Phase 8. Every finder is tenant-scoped; nothing here resolves a bare id. */
public interface IcuSeverityScoreRepository extends JpaRepository<IcuSeverityScore, Long> {

    Optional<IcuSeverityScore> findByPublicIdAndHospitalId(String publicId, Long hospitalId);

    /** Newest first. Includes corrected rows so the chart can show them struck through. */
    List<IcuSeverityScore>
    findByIpdAdmissionIdAndHospitalIdAndIsActiveTrueOrderByScoredAtDescIdDesc(
            Long ipdAdmissionId, Long hospitalId);

    List<IcuSeverityScore>
    findByIpdAdmissionIdAndHospitalIdAndScoreTypeAndIsActiveTrueOrderByScoredAtDescIdDesc(
            Long ipdAdmissionId, Long hospitalId, String scoreType);

    /** Rows replaced by a correction, so history and "latest" can exclude them. */
    @Query("SELECT s.supersedesScoreId FROM IcuSeverityScore s "
         + "WHERE s.ipdAdmissionId = :admissionId AND s.hospitalId = :hospitalId "
         + "AND s.supersedesScoreId IS NOT NULL")
    List<Long> findSupersededIds(@Param("admissionId") Long admissionId,
                                 @Param("hospitalId") Long hospitalId);
}
