package com.hms.repository;

import com.hms.entity.IcuVentilatorSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** ICU Phase 7. Every finder is tenant-scoped; nothing here resolves a bare id. */
public interface IcuVentilatorSettingRepository extends JpaRepository<IcuVentilatorSetting, Long> {

    Optional<IcuVentilatorSetting> findByPublicIdAndHospitalId(String publicId, Long hospitalId);

    /** Newest first. Includes corrected rows so the chart can show them struck through. */
    List<IcuVentilatorSetting>
    findByIpdAdmissionIdAndHospitalIdAndIsActiveTrueOrderByObservedAtDescIdDesc(
            Long ipdAdmissionId, Long hospitalId);

    /** Rows replaced by a correction, so history and "current" can exclude them. */
    @Query("SELECT s.supersedesSettingId FROM IcuVentilatorSetting s "
         + "WHERE s.ipdAdmissionId = :admissionId AND s.hospitalId = :hospitalId "
         + "AND s.supersedesSettingId IS NOT NULL")
    List<Long> findSupersededIds(@Param("admissionId") Long admissionId,
                                 @Param("hospitalId") Long hospitalId);
}
