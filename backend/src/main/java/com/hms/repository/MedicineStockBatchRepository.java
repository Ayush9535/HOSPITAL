package com.hms.repository;

import com.hms.entity.MedicineStockBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface MedicineStockBatchRepository extends JpaRepository<MedicineStockBatch, Long> {

    Optional<MedicineStockBatch> findByIdAndHospitalId(Long id, Long hospitalId);

    Optional<MedicineStockBatch> findByHospitalIdAndMedicineIdAndBatchNumber(
            Long hospitalId, Long medicineId, String batchNumber);

    List<MedicineStockBatch> findByHospitalIdAndMedicineIdOrderByExpiryDateAsc(Long hospitalId, Long medicineId);

    /**
     * Dispensable batches in FEFO order: earliest expiry consumed first.
     *
     * <p>The exclusions are the point -- expired, deactivated (recall/quarantine) and empty lots
     * are not stock and must never be offered or counted. Ordering by expiry then id keeps
     * selection deterministic when two lots share an expiry date, so a retry picks the same lot.
     */
    @Query("SELECT b FROM MedicineStockBatch b "
            + "WHERE b.hospitalId = :hospitalId AND b.medicineId = :medicineId "
            + "AND b.isActive = true AND b.currentQuantity > 0 AND b.expiryDate >= :today "
            + "ORDER BY b.expiryDate ASC, b.id ASC")
    List<MedicineStockBatch> findDispensableFefo(@Param("hospitalId") Long hospitalId,
            @Param("medicineId") Long medicineId, @Param("today") LocalDate today);

    /** Total usable stock for one medicine: the source of truth, never a stored aggregate. */
    @Query("SELECT COALESCE(SUM(b.currentQuantity), 0) FROM MedicineStockBatch b "
            + "WHERE b.hospitalId = :hospitalId AND b.medicineId = :medicineId "
            + "AND b.isActive = true AND b.currentQuantity > 0 AND b.expiryDate >= :today")
    int availableQuantity(@Param("hospitalId") Long hospitalId,
            @Param("medicineId") Long medicineId, @Param("today") LocalDate today);

    /**
     * Atomically take stock from one batch, refusing to go negative.
     *
     * <p>The sufficiency test lives in the WHERE clause so check and write are one statement:
     * read-check-then-save loses updates under concurrency. Returns rows affected -- 0 means
     * another transaction got there first and the caller must re-plan against fresh rows rather
     * than write a stale value.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE MedicineStockBatch b SET b.currentQuantity = b.currentQuantity - :qty "
            + "WHERE b.id = :id AND b.hospitalId = :hospitalId AND b.currentQuantity >= :qty")
    int deductAtomically(@Param("id") Long id, @Param("hospitalId") Long hospitalId,
            @Param("qty") Integer qty);

    /** Restock a batch (return in / positive adjustment). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE MedicineStockBatch b SET b.currentQuantity = b.currentQuantity + :qty "
            + "WHERE b.id = :id AND b.hospitalId = :hospitalId")
    int addAtomically(@Param("id") Long id, @Param("hospitalId") Long hospitalId,
            @Param("qty") Integer qty);
}
