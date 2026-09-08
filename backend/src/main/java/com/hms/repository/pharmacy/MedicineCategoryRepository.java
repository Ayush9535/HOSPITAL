package com.hms.repository.pharmacy;

import com.hms.entity.pharmacy.MedicineCategory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MedicineCategoryRepository extends JpaRepository<MedicineCategory, Long> {
    
    Page<MedicineCategory> findByHospitalIdAndCategoryNameContainingIgnoreCase(Long hospitalId, String name, Pageable pageable);
    
    Page<MedicineCategory> findByHospitalId(Long hospitalId, Pageable pageable);
    
    Optional<MedicineCategory> findByIdAndHospitalId(Long id, Long hospitalId);
}
