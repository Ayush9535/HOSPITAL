package com.hms.repository;

import com.hms.entity.HospitalVital;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HospitalVitalRepository extends JpaRepository<HospitalVital, Long> {
    List<HospitalVital> findByHospitalId(Long hospitalId);
    Optional<HospitalVital> findByHospitalIdAndVitalKey(Long hospitalId, String vitalKey);
    Optional<HospitalVital> findByPublicId(String publicId);
}
