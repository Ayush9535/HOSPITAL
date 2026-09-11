package com.hms.repository;

import com.hms.entity.SurgeryAnaesthesiaClearance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SurgeryAnaesthesiaClearanceRepository extends JpaRepository<SurgeryAnaesthesiaClearance, Long> {
    Optional<SurgeryAnaesthesiaClearance> findTopByHospitalIdAndSurgeryIdOrderByRecordedAtDescIdDesc(Long hospitalId, Long surgeryId);
}
