package com.hms.repository.pharmacy;

import com.hms.entity.pharmacy.MedicineBatch;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;

/**
 * All reads/lookups are branch-scoped: when :branchId is null (admin / single-shop /
 * hospital / clinic) the whole hospital is returned; when set (Multi Pharmacy branch
 * login) only that branch's rows are returned.
 */
@Repository
public interface MedicineBatchRepository extends JpaRepository<MedicineBatch, Long> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM MedicineBatch b WHERE b.id = :id AND b.hospitalId = :hospitalId " +
           "AND (:branchId IS NULL OR b.branchId = :branchId)")
    java.util.Optional<MedicineBatch> findByIdAndHospitalIdForUpdate(@Param("id") Long id,
            @Param("hospitalId") Long hospitalId, @Param("branchId") Long branchId);

    @Query("SELECT b FROM MedicineBatch b WHERE b.hospitalId = :hospitalId " +
           "AND (:branchId IS NULL OR b.branchId = :branchId)")
    Page<MedicineBatch> findScopedInventory(@Param("hospitalId") Long hospitalId,
            @Param("branchId") Long branchId, Pageable pageable);

    @Query("SELECT b FROM MedicineBatch b JOIN b.medicine m WHERE b.hospitalId = :hospitalId AND m.categoryId = :categoryId " +
           "AND (:branchId IS NULL OR b.branchId = :branchId)")
    Page<MedicineBatch> findScopedByCategory(@Param("hospitalId") Long hospitalId,
            @Param("branchId") Long branchId, @Param("categoryId") Long categoryId, Pageable pageable);

    @Query("SELECT b FROM MedicineBatch b JOIN b.medicine m WHERE b.hospitalId = :hospitalId " +
           "AND (:branchId IS NULL OR b.branchId = :branchId) AND (" +
           "LOWER(m.medicineName) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "LOWER(b.batchNumber) LIKE LOWER(CONCAT('%', :q, '%')))")
    Page<MedicineBatch> searchInventory(@Param("hospitalId") Long hospitalId,
            @Param("branchId") Long branchId, @Param("q") String query, Pageable pageable);

    @Query("SELECT b FROM MedicineBatch b JOIN b.medicine m WHERE b.hospitalId = :hospitalId AND m.categoryId = :catId " +
           "AND (:branchId IS NULL OR b.branchId = :branchId) AND (" +
           "LOWER(m.medicineName) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "LOWER(b.batchNumber) LIKE LOWER(CONCAT('%', :q, '%')))")
    Page<MedicineBatch> searchInventoryWithCategory(@Param("hospitalId") Long hospitalId,
            @Param("branchId") Long branchId, @Param("q") String query, @Param("catId") Long categoryId, Pageable pageable);

    @Query("SELECT b FROM MedicineBatch b WHERE b.hospitalId = :hospitalId AND b.expiryDate <= :dateThreshold AND b.currentQuantity > 0 " +
           "AND (:branchId IS NULL OR b.branchId = :branchId)")
    Page<MedicineBatch> findExpiringSoon(@Param("hospitalId") Long hospitalId,
            @Param("branchId") Long branchId, @Param("dateThreshold") LocalDate dateThreshold, Pageable pageable);

    @Query("SELECT SUM(b.currentQuantity) FROM MedicineBatch b WHERE b.hospitalId = :hospitalId AND b.medicineId = :medicineId " +
           "AND (:branchId IS NULL OR b.branchId = :branchId)")
    java.math.BigDecimal sumCurrentQuantityByMedicineId(@Param("hospitalId") Long hospitalId,
            @Param("branchId") Long branchId, @Param("medicineId") Long medicineId);

    @Query("SELECT b FROM MedicineBatch b JOIN b.medicine m WHERE b.hospitalId = :hospitalId " +
           "AND (:branchId IS NULL OR b.branchId = :branchId) AND b.id IN (" +
           "  SELECT MIN(sub.id) FROM MedicineBatch sub " +
           "  JOIN sub.medicine subm " +
           "  WHERE sub.hospitalId = :hospitalId AND (:branchId IS NULL OR sub.branchId = :branchId) AND subm.minStockLevel > 0 " +
           "  GROUP BY sub.medicineId, subm.minStockLevel " +
           "  HAVING SUM(sub.currentQuantity) < subm.minStockLevel" +
           ")")
    Page<MedicineBatch> findLowStock(@Param("hospitalId") Long hospitalId,
            @Param("branchId") Long branchId, Pageable pageable);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM MedicineBatch b WHERE b.hospitalId = :hospitalId AND b.medicineId = :medicineId AND b.batchNumber = :batchNumber " +
           "AND (:branchId IS NULL OR b.branchId = :branchId)")
    java.util.Optional<MedicineBatch> findByHospitalIdAndMedicineIdAndBatchNumberForUpdate(
            @Param("hospitalId") Long hospitalId,
            @Param("branchId") Long branchId,
            @Param("medicineId") Long medicineId,
            @Param("batchNumber") String batchNumber);

    @Query("SELECT b FROM MedicineBatch b JOIN b.medicine m WHERE b.hospitalId = :hospitalId AND b.currentQuantity > 0 " +
           "AND (:branchId IS NULL OR b.branchId = :branchId) " +
           "AND b.expiryDate > CURRENT_DATE " +
           "AND (b.status IS NULL OR b.status = 'ACTIVE') AND (" +
           "LOWER(m.medicineName) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "LOWER(b.batchNumber) LIKE LOWER(CONCAT('%', :q, '%'))) " +
           "ORDER BY b.expiryDate ASC")
    java.util.List<MedicineBatch> searchAvailableBatchesFEFO(@Param("hospitalId") Long hospitalId,
            @Param("branchId") Long branchId, @Param("q") String query);

    @Query("SELECT b FROM MedicineBatch b WHERE b.hospitalId = :hospitalId AND b.medicineId = :medicineId AND b.batchNumber = :batchNumber " +
           "AND (:branchId IS NULL OR b.branchId = :branchId)")
    java.util.Optional<MedicineBatch> findByHospitalIdAndMedicineIdAndBatchNumber(
            @Param("hospitalId") Long hospitalId, @Param("branchId") Long branchId,
            @Param("medicineId") Long medicineId, @Param("batchNumber") String batchNumber);
}
