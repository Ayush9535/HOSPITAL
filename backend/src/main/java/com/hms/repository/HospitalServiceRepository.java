package com.hms.repository;

import com.hms.entity.HospitalServiceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface HospitalServiceRepository extends JpaRepository<HospitalServiceEntity, Long> {
    List<HospitalServiceEntity> findByHospitalIdAndIsActiveTrueOrderByNameAsc(Long hospitalId);
    Optional<HospitalServiceEntity> findByIdAndHospitalId(Long id, Long hospitalId);
}
