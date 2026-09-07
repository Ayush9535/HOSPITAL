package com.hms.repository;

import com.hms.entity.StockMovement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {

    /** Idempotency lookup: has this exact stock-posting command already been applied? */
    Optional<StockMovement> findByHospitalIdAndIdempotencyKey(Long hospitalId, String idempotencyKey);

    /**
     * Every row a single command posted, in order.
     *
     * <p>One FEFO consumption spans as many batches as it needs and posts one movement per batch,
     * all under the same key. Replaying it must report what that command actually took, so the
     * caller sees the same allocation twice rather than an empty or partial answer. Reading it
     * back by key is also the only honest way to do it -- scanning the item's whole ledger and
     * filtering in memory grows with the item's history, not with the command.
     */
    List<StockMovement> findByHospitalIdAndIdempotencyKeyOrderByIdAsc(Long hospitalId, String idempotencyKey);

    List<StockMovement> findByHospitalIdAndInventoryDomainAndItemIdOrderByIdAsc(
            Long hospitalId, String inventoryDomain, Long itemId);

    List<StockMovement> findByHospitalIdAndBatchIdOrderByIdAsc(Long hospitalId, Long batchId);

    /**
     * Reconciliation: the ledger's own view of current stock, as a signed sum. A batch's
     * current_quantity must equal this; a divergence means something mutated stock without
     * posting a movement.
     */
    @Query("SELECT COALESCE(SUM(CASE WHEN m.direction = 'OUT' THEN -m.quantity ELSE m.quantity END), 0) "
            + "FROM StockMovement m WHERE m.hospitalId = :hospitalId AND m.batchId = :batchId")
    int reconciledBatchQuantity(@Param("hospitalId") Long hospitalId, @Param("batchId") Long batchId);

    @Query("SELECT COALESCE(SUM(CASE WHEN m.direction = 'OUT' THEN -m.quantity ELSE m.quantity END), 0) "
            + "FROM StockMovement m WHERE m.hospitalId = :hospitalId "
            + "AND m.inventoryDomain = :domain AND m.itemId = :itemId")
    int reconciledItemQuantity(@Param("hospitalId") Long hospitalId,
            @Param("domain") String domain, @Param("itemId") Long itemId);

    /**
     * How much has already gone out against one prescription.
     *
     * <p>Read from the ledger rather than stored on the order, because the ledger is where the
     * fact lives: a course can be issued in parts, and each part is a movement. Counting only OUT
     * movements means a returned issue nets off correctly once returns post their own rows.
     */
    @Query("SELECT COALESCE(SUM(m.quantity), 0) FROM StockMovement m "
            + "WHERE m.hospitalId = :hospitalId AND m.referenceType = 'PRESCRIPTION' "
            + "AND m.referenceId = :prescriptionId AND m.direction = 'OUT'")
    int dispensedForPrescription(@Param("hospitalId") Long hospitalId,
            @Param("prescriptionId") Long prescriptionId);

    /** Same, for a batch of prescriptions, so a queue does not issue one query per row. */
    @Query("SELECT m.referenceId, COALESCE(SUM(m.quantity), 0) FROM StockMovement m "
            + "WHERE m.hospitalId = :hospitalId AND m.referenceType = 'PRESCRIPTION' "
            + "AND m.referenceId IN :prescriptionIds AND m.direction = 'OUT' GROUP BY m.referenceId")
    List<Object[]> dispensedForPrescriptions(@Param("hospitalId") Long hospitalId,
            @Param("prescriptionIds") List<Long> prescriptionIds);
}
