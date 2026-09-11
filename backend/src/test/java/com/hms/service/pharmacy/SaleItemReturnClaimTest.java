package com.hms.service.pharmacy;

import com.hms.entity.pharmacy.PharmacySale;
import com.hms.entity.pharmacy.PharmacySaleItem;
import com.hms.repository.pharmacy.PharmacySaleItemRepository;
import com.hms.repository.pharmacy.PharmacySaleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The same sold units cannot be refunded twice.
 *
 * <p>Against a real database rather than a mock, because the guarantee lives in the WHERE clause:
 * the claim only succeeds while the running total stays inside what was sold. A read-then-write
 * check would pass both halves of a concurrent pair and refund ten units twice.
 */
@SpringBootTest
@ActiveProfiles("test")
class SaleItemReturnClaimTest {

    private static final AtomicLong SEQ = new AtomicLong();
    private static long uniq() { return System.nanoTime() + SEQ.incrementAndGet(); }

    @Autowired PharmacySaleRepository saleRepository;
    @Autowired PharmacySaleItemRepository itemRepository;
    @Autowired com.hms.repository.pharmacy.MedicineBatchRepository batchRepository;
    @Autowired com.hms.repository.pharmacy.MedicineMasterRepository medicineMasterRepository;
    @Autowired org.springframework.transaction.support.TransactionTemplate txTemplate;

    private Long itemId;

    /** A sale of ten units, saved through the aggregate as the application does. */
    @BeforeEach
    void setUp() {
        // The sale line is foreign-keyed to a real batch, so the fixture builds one.
        com.hms.entity.pharmacy.MedicineMaster med = new com.hms.entity.pharmacy.MedicineMaster();
        med.setHospitalId(1L);
        med.setMedicineName("Paracetamol " + uniq());
        Long medicineId = medicineMasterRepository.save(med).getId();

        com.hms.entity.pharmacy.MedicineBatch batch = new com.hms.entity.pharmacy.MedicineBatch();
        batch.setHospitalId(1L);
        batch.setMedicineId(medicineId);
        batch.setBatchNumber("B-" + uniq());
        batch.setExpiryDate(java.time.LocalDate.now().plusYears(1));
        batch.setMrp(new BigDecimal("25"));
        batch.setPurchaseRate(new BigDecimal("15"));
        batch.setSellingPrice(new BigDecimal("20"));
        batch.setCurrentQuantity(new BigDecimal("100"));
        Long batchId = batchRepository.save(batch).getId();

        PharmacySale sale = new PharmacySale();
        sale.setHospitalId(1L);
        sale.setBillNumber("BILL-" + uniq());
        sale.setSaleType("WALK-IN");
        sale.setSubtotal(new BigDecimal("200"));
        sale.setNetAmount(new BigDecimal("200"));
        sale.setPaymentStatus("PAID");
        sale.setPaymentMethod("CASH");

        PharmacySaleItem item = new PharmacySaleItem();
        item.setMedicineId(medicineId);
        item.setMedicineBatchId(batchId);
        item.setQuantity(new BigDecimal("10"));
        item.setUnitPrice(new BigDecimal("20"));
        item.setTotalAmount(new BigDecimal("200"));
        item.setPharmacySale(sale);
        sale.setItems(new ArrayList<>(List.of(item)));

        itemId = txTemplate.execute(status -> saleRepository.save(sale).getItems().get(0).getId());
    }

    private BigDecimal returnedSoFar() {
        BigDecimal v = txTemplate.execute(
                status -> itemRepository.findById(itemId).orElseThrow().getReturnedQuantity());
        return v == null ? BigDecimal.ZERO : v;
    }

    /**
     * Each claim in its own transaction — which is also what the concurrency cases need, since
     * two callers arriving together are two transactions, not two statements in one.
     */
    private int claim(String qty) {
        return txTemplate.execute(status -> itemRepository.claimReturn(itemId, new BigDecimal(qty)));
    }

    @Test
    void aFullReturnCannotBeMadeTwice() {
        assertThat(claim("10")).as("the first full return is accepted").isEqualTo(1);
        assertThat(claim("10")).as("and the same ten units cannot come back again").isEqualTo(0);
        assertThat(returnedSoFar()).isEqualByComparingTo(new BigDecimal("10"));
    }

    @Test
    void partialReturnsAccumulateUntilTheLineIsExhausted() {
        assertThat(claim("3")).isEqualTo(1);
        assertThat(claim("4")).isEqualTo(1);
        assertThat(claim("3")).isEqualTo(1);
        assertThat(returnedSoFar()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(claim("1")).as("nothing remains returnable").isEqualTo(0);
    }

    @Test
    void aReturnThatWouldOvershootWhatRemainsIsRefused() {
        assertThat(claim("3")).isEqualTo(1);
        assertThat(claim("8")).as("only seven remain").isEqualTo(0);
        assertThat(returnedSoFar())
                .as("and the refused attempt changed nothing").isEqualByComparingTo(new BigDecimal("3"));
    }

    @Test
    void aRowWrittenBeforeTheColumnExistedIsTreatedAsNothingReturned() {
        txTemplate.execute(status -> {
            PharmacySaleItem row = itemRepository.findById(itemId).orElseThrow();
            row.setReturnedQuantity(null); // as an un-migrated historical row reads
            return itemRepository.save(row);
        });

        assertThat(claim("10")).as("NULL must behave as zero, not poison the sum").isEqualTo(1);
        assertThat(returnedSoFar()).isEqualByComparingTo(new BigDecimal("10"));
    }

    /** Two returns of seven against a sale of ten: only one can fit. */
    @Test
    void concurrentReturnsCannotBetweenThemExceedWhatWasSold() throws Exception {
        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<Integer>> jobs = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            jobs.add(() -> claim("7"));
        }
        List<Future<Integer>> results = pool.invokeAll(jobs);
        pool.shutdown();
        pool.awaitTermination(60, TimeUnit.SECONDS);

        int accepted = 0;
        for (Future<Integer> f : results) accepted += f.get();

        assertThat(accepted).as("seven twice does not fit inside ten").isEqualTo(1);
        assertThat(returnedSoFar())
                .as("and the total never passes what was sold")
                .isEqualByComparingTo(new BigDecimal("7"));
    }

    @Test
    void concurrentFullReturnsResolveToExactlyOne() throws Exception {
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<Integer>> jobs = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            jobs.add(() -> claim("10"));
        }
        List<Future<Integer>> results = pool.invokeAll(jobs);
        pool.shutdown();
        pool.awaitTermination(60, TimeUnit.SECONDS);

        int accepted = 0;
        for (Future<Integer> f : results) accepted += f.get();

        assertThat(accepted).as("exactly one of six may refund the line").isEqualTo(1);
        assertThat(returnedSoFar()).isEqualByComparingTo(new BigDecimal("10"));
    }
}
