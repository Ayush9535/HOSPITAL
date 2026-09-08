package com.hms.service.hospital;

import com.hms.entity.Medicine;
import com.hms.entity.MedicineStockBatch;
import com.hms.entity.StockMovement;
import com.hms.exception.ConflictException;
import com.hms.repository.MedicineRepository;
import com.hms.repository.MedicineStockBatchRepository;
import com.hms.repository.StockMovementRepository;
import com.hms.security.SecurityContextHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * INV-2/3/4: batch-aware medicine stock, FEFO consumption, the append-only ledger, and
 * idempotent stock posting -- exercised against a real database rather than mocked repositories,
 * because the guarantees being tested (atomic conditional UPDATE, unique idempotency key,
 * SUM-over-batches) live in SQL and are invisible to a mock.
 */
@SpringBootTest
@ActiveProfiles("test")
class MedicineStockServiceTest {

    private static final Long HOSPITAL = 8800L;
    private static final Long OTHER_HOSPITAL = 8801L;

    @Autowired MedicineStockService stockService;
    @Autowired MedicineRepository medicineRepository;
    @Autowired MedicineStockBatchRepository batchRepository;
    @Autowired StockMovementRepository movementRepository;

    @MockBean SecurityContextHelper securityHelper;

    private Long medicineId;

    private Long newMedicine(Long hospitalId, String name) {
        Medicine m = new Medicine();
        m.setName(name + " " + System.nanoTime());
        m.setHospitalId(hospitalId);
        m.setStockQuantity(0);
        m.setUnitPrice(4.0);
        m.setMinStockLevel(0);
        m.setIsActive(true);
        return medicineRepository.save(m).getId();
    }

    @BeforeEach
    void setUp() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(HOSPITAL);
        when(securityHelper.getCurrentUserId()).thenReturn(77L);
        medicineId = newMedicine(HOSPITAL, "Paracetamol");
    }

    /** The core of the batch decision: two receipts with different expiries stay distinct. */
    @Test
    void twoBatchesWithDifferentExpiries_remainSeparate_andSumToTotalAvailable() {
        stockService.receiveBatch(medicineId, "BATCH-A", LocalDate.now().plusMonths(1), 100, 3.0, null, null);
        stockService.receiveBatch(medicineId, "BATCH-B", LocalDate.now().plusMonths(6), 50, 3.5, null, null);

        List<MedicineStockBatch> batches = stockService.batchesFor(medicineId);
        assertThat(batches).hasSize(2);
        assertThat(stockService.availableQuantity(medicineId)).isEqualTo(150);

        // The regression this replaces: the old merge overwrote the aggregate's expiry, so the
        // earlier lot silently inherited the later date and stayed dispensable past its own.
        assertThat(batches).extracting(MedicineStockBatch::getExpiryDate).doesNotHaveDuplicates();
    }

    /** FEFO: the earliest-expiring lot is emptied before the later one is touched. */
    @Test
    void consumptionTakesEarliestExpiryFirst_andSpansBatchesWhenNeeded() {
        stockService.receiveBatch(medicineId, "SOON", LocalDate.now().plusMonths(1), 30, 3.0, null, null);
        stockService.receiveBatch(medicineId, "LATER", LocalDate.now().plusMonths(9), 40, 3.0, null, null);

        List<MedicineStockService.BatchAllocation> taken = stockService.consumeFefo(
                medicineId, 45, StockMovement.DISPENSE, "PRESCRIPTION", 1L, null, null);

        assertThat(taken).hasSize(2);
        assertThat(taken.get(0).batchNumber()).isEqualTo("SOON");
        assertThat(taken.get(0).quantity()).isEqualTo(30);
        assertThat(taken.get(1).batchNumber()).isEqualTo("LATER");
        assertThat(taken.get(1).quantity()).isEqualTo(15);
        assertThat(stockService.availableQuantity(medicineId)).isEqualTo(25);
    }

    /** Expired stock is neither counted nor consumable. */
    @Test
    void expiredBatchesAreExcludedFromAvailabilityAndFromDispensing() {
        MedicineStockBatch expired = new MedicineStockBatch();
        expired.setHospitalId(HOSPITAL);
        expired.setMedicineId(medicineId);
        expired.setBatchNumber("EXPIRED");
        expired.setExpiryDate(LocalDate.now().minusDays(1));
        expired.setReceivedQuantity(500);
        expired.setCurrentQuantity(500);
        expired.setIsActive(true);
        batchRepository.save(expired);

        assertThat(stockService.availableQuantity(medicineId))
                .as("500 expired units are not stock").isZero();

        assertThatThrownBy(() -> stockService.consumeFefo(
                medicineId, 1, StockMovement.DISPENSE, "PRESCRIPTION", 1L, null, null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Insufficient stock");

        assertThat(batchRepository.findById(expired.getId()).orElseThrow().getCurrentQuantity())
                .as("the expired lot must not be touched").isEqualTo(500);
    }

    /** Over-drawing is refused whole; no batch is partially emptied. */
    @Test
    void insufficientStockIsAtomic_nothingIsDecremented() {
        stockService.receiveBatch(medicineId, "ONLY", LocalDate.now().plusMonths(2), 10, 3.0, null, null);

        assertThatThrownBy(() -> stockService.consumeFefo(
                medicineId, 11, StockMovement.DISPENSE, "PRESCRIPTION", 1L, null, null))
                .isInstanceOf(ConflictException.class);

        assertThat(stockService.availableQuantity(medicineId)).isEqualTo(10);
    }

    /** A replayed stock-posting command must move stock once, not twice. */
    @Test
    void replayingTheSameDispenseCommand_decrementsOnlyOnce() {
        stockService.receiveBatch(medicineId, "IDEM", LocalDate.now().plusMonths(3), 20, 3.0, null, null);
        String key = "dispense-" + System.nanoTime();

        stockService.consumeFefo(medicineId, 5, StockMovement.DISPENSE, "PRESCRIPTION", 1L, key, null);
        stockService.consumeFefo(medicineId, 5, StockMovement.DISPENSE, "PRESCRIPTION", 1L, key, null);

        assertThat(stockService.availableQuantity(medicineId))
                .as("double-submitted dispense takes 5, not 10").isEqualTo(15);
    }

    /** Receiving the same purchase twice under one key must not double-post stock. */
    @Test
    void replayingTheSameReceiptCommand_addsOnlyOnce() {
        String key = "receipt-" + System.nanoTime();
        stockService.receiveBatch(medicineId, "RCPT", LocalDate.now().plusMonths(4), 25, 3.0, key, null);
        stockService.receiveBatch(medicineId, "RCPT", LocalDate.now().plusMonths(4), 25, 3.0, key, null);

        assertThat(stockService.availableQuantity(medicineId)).isEqualTo(25);
    }

    /** Every physical change is on the ledger, and the ledger reconciles to the batch. */
    @Test
    void theLedgerReconcilesToBatchQuantity() {
        MedicineStockBatch b = stockService.receiveBatch(
                medicineId, "LEDGER", LocalDate.now().plusMonths(5), 60, 3.0, null, null);
        stockService.consumeFefo(medicineId, 20, StockMovement.DISPENSE, "PRESCRIPTION", 1L, null, null);

        int reconciled = movementRepository.reconciledBatchQuantity(HOSPITAL, b.getId());
        int actual = batchRepository.findById(b.getId()).orElseThrow().getCurrentQuantity();

        assertThat(actual).isEqualTo(40);
        assertThat(reconciled).as("SUM(signed movements) must equal current stock").isEqualTo(actual);

        assertThat(movementRepository.findByHospitalIdAndBatchIdOrderByIdAsc(HOSPITAL, b.getId()))
                .extracting(StockMovement::getMovementType)
                .containsExactly(StockMovement.PURCHASE_RECEIPT, StockMovement.DISPENSE);
    }

    /** Another facility's medicine is not reachable, even with a valid id. */
    @Test
    void aForeignHospitalsMedicineIsNotFound() {
        Long foreign = newMedicine(OTHER_HOSPITAL, "Ceftriaxone");

        assertThatThrownBy(() -> stockService.availableQuantity(foreign))
                .isInstanceOf(com.hms.exception.ResourceNotFoundException.class);
        assertThatThrownBy(() -> stockService.receiveBatch(
                foreign, "X", LocalDate.now().plusMonths(1), 10, 1.0, null, null))
                .isInstanceOf(com.hms.exception.ResourceNotFoundException.class);
    }

    /** Reusing a batch number with a different expiry is a conflict, not a silent overwrite. */
    @Test
    void reusingABatchNumberWithADifferentExpiry_isRejected() {
        stockService.receiveBatch(medicineId, "DUP", LocalDate.now().plusMonths(2), 10, 3.0, null, null);

        assertThatThrownBy(() -> stockService.receiveBatch(
                medicineId, "DUP", LocalDate.now().plusMonths(8), 10, 3.0, null, null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already exists");
    }

    /** A refused consumption must leave no trace on the ledger, not a rolled-back-looking one. */
    @Test
    void aFailedConsumptionPostsNoMovement() {
        stockService.receiveBatch(medicineId, "NOMOVE", LocalDate.now().plusMonths(2), 5, 3.0, null, null);
        int before = movementRepository
                .findByHospitalIdAndInventoryDomainAndItemIdOrderByIdAsc(
                        HOSPITAL, StockMovement.DOMAIN_MEDICINE, medicineId).size();

        assertThatThrownBy(() -> stockService.consumeFefo(
                medicineId, 99, StockMovement.DISPENSE, "PRESCRIPTION", 1L, null, null))
                .isInstanceOf(ConflictException.class);

        assertThat(movementRepository
                .findByHospitalIdAndInventoryDomainAndItemIdOrderByIdAsc(
                        HOSPITAL, StockMovement.DOMAIN_MEDICINE, medicineId))
                .as("a refused dispense is not a stock event").hasSize(before);
        assertThat(stockService.availableQuantity(medicineId)).isEqualTo(5);
    }

    /**
     * Concurrent dispensing cannot oversell.
     *
     * <p>Ten threads each try to take 10 units from a lot of 50. At most five can succeed; the
     * batch must never go negative, and the ledger must still reconcile to whatever is left.
     */
    @Test
    void concurrentDispensingCannotOversell() throws Exception {
        MedicineStockBatch batch = stockService.receiveBatch(
                medicineId, "RACE", LocalDate.now().plusMonths(6), 50, 3.0, null, null);

        int threads = 10;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.List<java.util.concurrent.Callable<Boolean>> jobs = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            jobs.add(() -> {
                try {
                    stockService.consumeFefo(medicineId, 10, StockMovement.DISPENSE,
                            "PRESCRIPTION", 1L, null, null);
                    return true;
                } catch (RuntimeException expectedWhenStockRunsOut) {
                    return false;
                }
            });
        }
        java.util.List<java.util.concurrent.Future<Boolean>> results = pool.invokeAll(jobs);
        pool.shutdown();
        pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS);

        int granted = 0;
        for (java.util.concurrent.Future<Boolean> f : results) {
            if (Boolean.TRUE.equals(f.get())) granted++;
        }

        assertThat(granted).as("50 units cannot serve more than five requests of 10").isLessThanOrEqualTo(5);
        int remaining = batchRepository.findById(batch.getId()).orElseThrow().getCurrentQuantity();
        assertThat(remaining).as("stock must never go negative").isGreaterThanOrEqualTo(0);
        assertThat(remaining).isEqualTo(50 - granted * 10);
        assertThat(movementRepository.reconciledBatchQuantity(HOSPITAL, batch.getId()))
                .as("the ledger still explains what is on the shelf").isEqualTo(remaining);
    }

    /** Re-receiving the identical lot tops it up rather than creating a second row. */
    @Test
    void reReceivingTheSameBatchAndExpiry_topsUpTheSameRow() {
        LocalDate exp = LocalDate.now().plusMonths(7);
        stockService.receiveBatch(medicineId, "TOPUP", exp, 10, 3.0, null, null);
        stockService.receiveBatch(medicineId, "TOPUP", exp, 15, 3.0, null, null);

        assertThat(stockService.batchesFor(medicineId)).hasSize(1);
        assertThat(stockService.availableQuantity(medicineId)).isEqualTo(25);
    }
}
