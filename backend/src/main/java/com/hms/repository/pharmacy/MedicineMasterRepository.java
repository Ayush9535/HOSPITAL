package com.hms.repository.pharmacy;

import com.hms.entity.pharmacy.MedicineMaster;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MedicineMasterRepository extends JpaRepository<MedicineMaster, Long> {
    
    Optional<MedicineMaster> findByIdAndHospitalId(Long id, Long hospitalId);

    // Branch-scoped find-or-create lookup (null branchId => hospital scope).
    @Query("SELECT m FROM MedicineMaster m WHERE m.hospitalId = :hospitalId AND LOWER(m.medicineName) = LOWER(:medicineName) " +
           "AND (:branchId IS NULL OR m.branchId = :branchId)")
    Optional<MedicineMaster> findFirstScopedByName(@Param("hospitalId") Long hospitalId, @Param("branchId") Long branchId, @Param("medicineName") String medicineName);

    @Query("SELECT m FROM MedicineMaster m WHERE m.hospitalId = :hospitalId AND (:branchId IS NULL OR m.branchId = :branchId)")
    Page<MedicineMaster> findScoped(@Param("hospitalId") Long hospitalId, @Param("branchId") Long branchId, Pageable pageable);

    @Query("SELECT m FROM MedicineMaster m WHERE m.hospitalId = :hospitalId AND (:branchId IS NULL OR m.branchId = :branchId) AND (" +
           "LOWER(m.medicineName) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "LOWER(m.genericName) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "LOWER(m.medicineCode) LIKE LOWER(CONCAT('%', :q, '%')))")
    Page<MedicineMaster> searchMedicines(@Param("hospitalId") Long hospitalId, @Param("branchId") Long branchId, @Param("q") String query, Pageable pageable);

    @Query("SELECT m FROM MedicineMaster m WHERE m.hospitalId = :hospitalId AND (:branchId IS NULL OR m.branchId = :branchId) AND (" +
           "LOWER(m.medicineName) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "LOWER(m.genericName) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "LOWER(m.medicineCode) LIKE LOWER(CONCAT('%', :q, '%'))) AND m.isActive = true")
    List<MedicineMaster> findTop10ByHospitalIdAndMedicineNameContainingIgnoreCase(@Param("hospitalId") Long hospitalId, @Param("branchId") Long branchId, @Param("q") String query);
}
