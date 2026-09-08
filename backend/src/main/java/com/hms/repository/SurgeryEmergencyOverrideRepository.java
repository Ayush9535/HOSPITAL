package com.hms.repository;

import com.hms.entity.SurgeryEmergencyOverride;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SurgeryEmergencyOverrideRepository extends JpaRepository<SurgeryEmergencyOverride, Long> {
    Optional<SurgeryEmergencyOverride> findTopByHospitalIdAndSurgeryIdOrderByRecordedAtDescIdDesc(Long hospitalId, Long surgeryId);
}
