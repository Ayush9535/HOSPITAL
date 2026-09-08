package com.hms.service.pharmacy;

import com.hms.entity.pharmacy.MedicineBatch;
import com.hms.entity.pharmacy.InventoryTransaction;
import com.hms.entity.pharmacy.PharmacySale;
import com.hms.entity.pharmacy.PharmacySaleItem;
import com.hms.exception.ResourceNotFoundException;
import com.hms.repository.pharmacy.InventoryTransactionRepository;
import com.hms.repository.pharmacy.MedicineBatchRepository;
import com.hms.repository.pharmacy.PharmacySaleRepository;
import com.hms.security.SecurityContextHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Medicine that has been in a patient's possession does not go back on the shelf.
 *
 * <p>It used to, at the caller's discretion: the request body carried a {@code restock} flag and,
 * when it was true, the returned quantity was added straight back to the saleable batch. Nothing
 * checked how the medicine had been stored or whether it was still what left the counter, so the
 * next patient could be dispensed it. There is no quarantine model yet, so the money is refunded
 * and saleable stock is left alone.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PatientReturnStockSafetyTest {

    @Mock PharmacySaleRepository saleRepository;
    @Mock MedicineBatchRepository batchRepository;
    @Mock InventoryTransactionRepository transactionRepository;
    @Mock SecurityContextHelper securityHelper;
    @Mock com.hms.repository.PrescriptionRepository prescriptionRepository;
    @Mock com.hms.service.RealtimeNotifier notifier;
    @Mock com.hms.repository.pharmacy.PharmacySaleItemRepository saleItemRepository;

    @InjectMocks PharmacySaleService saleService;

    private static final Long HOSPITAL = 1L;
    private static final Long BATCH = 10L;
    private static final BigDecimal STOCK_ON_SHELF = new BigDecimal("40");

    private MedicineBatch batch;
    private PharmacySale sale;

    @BeforeEach
    void setUp() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(HOSPITAL);
        when(securityHelper.getCurrentUserId()).thenReturn(7L);
        when(securityHelper.getCurrentBranchId()).thenReturn(null);

        batch = new MedicineBatch();
        batch.setId(BATCH);
        batch.setHospitalId(HOSPITAL);
        batch.setBatchNumber("B-1");
        batch.setExpiryDate(LocalDate.now().plusYears(1));
        batch.setCurrentQuantity(STOCK_ON_SHELF);

        PharmacySaleItem sold = new PharmacySaleItem();
        sold.setMedicineBatchId(BATCH);
        sold.setQuantity(new BigDecimal("10"));
        sold.setUnitPrice(new BigDecimal("25"));

        sale = new PharmacySale();
        sale.setId(100L);
        sale.setBillNumber("BILL-1");
        sale.setHospitalId(HOSPITAL);
        sale.setItems(List.of(sold));

        when(saleRepository.findByIdScoped(100L, HOSPITAL, null)).thenReturn(Optional.of(sale));
        when(batchRepository.findByIdAndHospitalIdForUpdate(BATCH, HOSPITAL, null))
                .thenReturn(Optional.of(batch));
        // The atomic claim is what accepts or refuses a return; success unless a test says otherwise.
        when(saleItemRepository.claimReturn(any(), any())).thenReturn(1);
    }

    /** The request may still carry restock=true; it is ignored. */
    private List<Map<String, Object>> returnOf(String qty, boolean restockRequested) {
        return List.of(Map.of(
                "medicineBatchId", BATCH.toString(),
                "quantityToReturn", qty,
                "restock", restockRequested));
    }

    // ── the containment ──────────────────────────────────────────────────────

    @Test
    void aPatientReturnDoesNotPutMedicineBackOnTheShelf() {
        saleService.processPatientReturn(100L, returnOf("4", true));

        assertThat(batch.getCurrentQuantity())
                .as("saleable stock is untouched even though the caller asked to restock")
                .isEqualByComparingTo(STOCK_ON_SHELF);
        verify(batchRepository, never()).save(any(MedicineBatch.class));
    }

    /** The old behaviour, stated as the thing that must not happen again. */
    @Test
    void theRestockFlagNoLongerGrantsTheCallerControlOverStock() {
        saleService.processPatientReturn(100L, returnOf("4", true));
        BigDecimal afterRestockTrue = batch.getCurrentQuantity();

        batch.setCurrentQuantity(STOCK_ON_SHELF);
        saleService.processPatientReturn(100L, returnOf("4", false));

        assertThat(afterRestockTrue)
                .as("restock=true and restock=false must now behave identically")
                .isEqualByComparingTo(batch.getCurrentQuantity())
                .isEqualByComparingTo(STOCK_ON_SHELF);
    }

    /** A request that omits the flag entirely used to throw; it is no longer read at all. */
    @Test
    void aRequestWithoutTheRestockFlagIsAccepted() {
        List<Map<String, Object>> body = List.of(Map.of(
                "medicineBatchId", BATCH.toString(), "quantityToReturn", "2"));

        Map<String, Object> result = saleService.processPatientReturn(100L, body);

        assertThat(result.get("status")).isEqualTo("SUCCESS");
        assertThat(batch.getCurrentQuantity()).isEqualByComparingTo(STOCK_ON_SHELF);
    }

    // ── the money still moves ────────────────────────────────────────────────

    @Test
    void theRefundIsStillCalculatedFromWhatWasPaid() {
        Map<String, Object> result = saleService.processPatientReturn(100L, returnOf("4", false));

        assertThat(result.get("status")).isEqualTo("SUCCESS");
        assertThat((BigDecimal) result.get("refundAmount"))
                .as("4 units at the price they were sold for")
                .isEqualByComparingTo(new BigDecimal("100"));
    }

    // ── the ledger must not lie ──────────────────────────────────────────────

    @Test
    void theLedgerRecordsAReturnWithoutClaimingStockIncreased() {
        saleService.processPatientReturn(100L, returnOf("4", true));

        ArgumentCaptor<InventoryTransaction> captor = ArgumentCaptor.forClass(InventoryTransaction.class);
        verify(transactionRepository).save(captor.capture());
        InventoryTransaction tx = captor.getValue();

        assertThat(tx.getTransactionType()).isEqualTo("RETURN");
        assertThat(tx.getQuantity()).as("no stock entered inventory").isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(tx.getQuantityBefore())
                .as("and the balance either side is the same number")
                .isEqualByComparingTo(tx.getQuantityAfter())
                .isEqualByComparingTo(STOCK_ON_SHELF);
        assertThat(tx.getReferenceId()).isEqualTo(sale.getId());
        assertThat(tx.getCreatedBy()).isEqualTo(7L);
    }

    // ── the controls that already existed must survive ───────────────────────

    @Test
    void moreCannotBeReturnedThanRemainsReturnable() {
        when(saleItemRepository.claimReturn(any(), any())).thenReturn(0); // the bound refused it
        assertThatThrownBy(() -> saleService.processPatientReturn(100L, returnOf("11", false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("remain returnable");
        assertThat(batch.getCurrentQuantity()).isEqualByComparingTo(STOCK_ON_SHELF);
    }

    /** Every accepted return goes through the atomic claim rather than a read-then-write check. */
    @Test
    void acceptanceIsDecidedByTheAtomicClaim() {
        saleService.processPatientReturn(100L, returnOf("4", false));
        verify(saleItemRepository).claimReturn(any(), org.mockito.ArgumentMatchers.eq(new BigDecimal("4")));
    }

    @Test
    void aZeroOrNegativeReturnIsRefused() {
        assertThatThrownBy(() -> saleService.processPatientReturn(100L, returnOf("0", false)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void expiredMedicineIsStillRefused() {
        batch.setExpiryDate(LocalDate.now().minusDays(1));
        assertThatThrownBy(() -> saleService.processPatientReturn(100L, returnOf("2", false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void aBatchThatWasNotOnTheInvoiceIsRefused() {
        List<Map<String, Object>> body = List.of(Map.of(
                "medicineBatchId", "999", "quantityToReturn", "1", "restock", false));
        assertThatThrownBy(() -> saleService.processPatientReturn(100L, body))
                .isInstanceOf(RuntimeException.class);
    }

    // ── tenancy ──────────────────────────────────────────────────────────────

    @Test
    void anotherFacilitysSaleCannotBeReturnedAgainst() {
        when(saleRepository.findByIdScoped(anyLong(), any(), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> saleService.processPatientReturn(999L, returnOf("1", false)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    /**
     * The batch is now looked up tenant- and branch-scoped. It used to be a bare findById, which
     * with the restock branch removed would have been the only batch lookup on this path.
     */
    @Test
    void aBatchBelongingToAnotherFacilityIsRefused() {
        when(batchRepository.findByIdAndHospitalIdForUpdate(BATCH, HOSPITAL, null))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> saleService.processPatientReturn(100L, returnOf("1", false)))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(batchRepository, never()).save(any(MedicineBatch.class));
    }
}
