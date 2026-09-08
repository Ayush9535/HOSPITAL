package com.hms.repository;

import com.hms.entity.IcuAlertThreshold;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** ICU Phase 9 configuration. Every finder is tenant-scoped; nothing resolves a bare id. */
public interface IcuAlertThresholdRepository extends JpaRepository<IcuAlertThreshold, Long> {

    List<IcuAlertThreshold> findByHospitalIdAndIsActiveTrue(Long hospitalId);

    Optional<IcuAlertThreshold> findByHospitalIdAndSourceAndMetricKey(
            Long hospitalId, String source, String metricKey);

    /** Only what may actually fire: enabled rows for one source. */
    List<IcuAlertThreshold> findByHospitalIdAndSourceAndEnabledTrueAndIsActiveTrue(
            Long hospitalId, String source);
}
