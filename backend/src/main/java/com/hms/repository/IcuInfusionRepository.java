package com.hms.repository;

import com.hms.entity.IcuInfusion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** ICU Phase 6. Every finder is tenant-scoped; nothing here resolves a bare id. */
public interface IcuInfusionRepository extends JpaRepository<IcuInfusion, Long> {

    Optional<IcuInfusion> findByPublicIdAndHospitalId(String publicId, Long hospitalId);

    List<IcuInfusion> findByIpdAdmissionIdAndHospitalIdAndIsActiveTrueOrderByStartedAtDesc(
            Long ipdAdmissionId, Long hospitalId);

    /** Currently running: started and not yet stopped. */
    List<IcuInfusion> findByIpdAdmissionIdAndHospitalIdAndIsActiveTrueAndStoppedAtIsNullOrderByStartedAtDesc(
            Long ipdAdmissionId, Long hospitalId);
}
