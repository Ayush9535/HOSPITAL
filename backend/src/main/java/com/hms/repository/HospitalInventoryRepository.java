package com.hms.repository;

import com.hms.entity.HospitalInventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface HospitalInventoryRepository extends JpaRepository<HospitalInventory, Long> {
    List<HospitalInventory> findByHospitalId(Long hospitalId);

    List<HospitalInventory> findByHospitalIdAndIsActiveTrue(Long hospitalId);

    Optional<HospitalInventory> findByIdAndHospitalId(Long id, Long hospitalId);

    boolean existsByNameAndHospitalId(String name, Long hospitalId);

    List<HospitalInventory> findByNameAndHospitalIdAndIsActiveTrue(String name, Long hospitalId);
}
