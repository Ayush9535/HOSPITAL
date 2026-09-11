package com.hms.repository.pharmacy;

import com.hms.entity.pharmacy.Manufacturer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ManufacturerRepository extends JpaRepository<Manufacturer, Long> {
    Page<Manufacturer> findByHospitalIdAndManufacturerNameContainingIgnoreCase(Long hospitalId, String name, Pageable pageable);
    Page<Manufacturer> findByHospitalId(Long hospitalId, Pageable pageable);
    Optional<Manufacturer> findByIdAndHospitalId(Long id, Long hospitalId);
}
