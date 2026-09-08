package com.hms.repository;

import com.hms.entity.IcuInfusionRate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** ICU Phase 6 - the rate history. Append-only: a titration or correction adds a row. */
public interface IcuInfusionRateRepository extends JpaRepository<IcuInfusionRate, Long> {

    Optional<IcuInfusionRate> findByPublicIdAndHospitalId(String publicId, Long hospitalId);

    /** Newest first. Includes corrected rows so the chart can show them struck through. */
    List<IcuInfusionRate> findByIcuInfusionIdAndHospitalIdAndIsActiveTrueOrderByEffectiveFromDescIdDesc(
            Long icuInfusionId, Long hospitalId);

    List<IcuInfusionRate> findByIcuInfusionIdInAndHospitalIdAndIsActiveTrueOrderByEffectiveFromDescIdDesc(
            Collection<Long> icuInfusionIds, Long hospitalId);

    /** Rate rows replaced by a correction, so history and "current" can exclude them. */
    @Query("SELECT r.supersedesRateId FROM IcuInfusionRate r "
         + "WHERE r.icuInfusionId = :infusionId AND r.hospitalId = :hospitalId "
         + "AND r.supersedesRateId IS NOT NULL")
    List<Long> findSupersededRateIds(@Param("infusionId") Long infusionId,
                                     @Param("hospitalId") Long hospitalId);
}
