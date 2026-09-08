package com.hms.repository;

import com.hms.entity.Medicine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INV-P0-3: stock decrement must be atomic.
 *
 * <p>Every hospital/clinic decrement path was read -> check {@code qty >= n} -> subtract -> save.
 * Two callers taking the last units both pass the in-memory check against the same starting value,
 * and the second save overwrites the first: one dose is dispensed but only one is deducted (lost
 * update), and with enough contention the column goes negative -- there is no DB CHECK behind it.
 *
 * <p>These tests exercise the conditional UPDATE directly, which is where the guarantee lives:
 * the sufficiency check is inside the WHERE clause, so the database serialises the decision and a
 * losing caller updates 0 rows instead of writing a stale value.
 */
@SpringBootTest
@ActiveProfiles("test")
class MedicineStockConcurrencyTest {

    @Autowired MedicineRepository medicineRepository;
    @Autowired TransactionTemplate txTemplate;

    private static final Long HOSPITAL = 9100L;

    private Medicine seed(int stock) {
        Medicine m = new Medicine();
        m.setName("Concurrency Test Medicine " + System.nanoTime());
        m.setHospitalId(HOSPITAL);
        m.setStockQuantity(stock);
        m.setUnitPrice(5.0);
        m.setMinStockLevel(0);
        m.setIsActive(true);
        return medicineRepository.save(m);
    }

    /** 8 callers race for 1 unit: exactly one may win, and stock must land on 0, never -7. */
    @Test
    void concurrentDecrementsOfTheLastUnit_letExactlyOneWin_andNeverGoNegative() throws Exception {
        Medicine m = seed(1);
        int threads = 8;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<Integer>> jobs = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            jobs.add(() -> txTemplate.execute(
                    status -> medicineRepository.deductStockAtomically(m.getId(), HOSPITAL, 1)));
        }
        List<Future<Integer>> results = pool.invokeAll(jobs);
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        int winners = 0;
        for (Future<Integer> f : results) {
            if (f.get() == 1) winners++;
        }

        Medicine after = medicineRepository.findById(m.getId()).orElseThrow();
        assertThat(winners).as("exactly one caller may take the last unit").isEqualTo(1);
        assertThat(after.getStockQuantity()).as("stock must never go negative").isEqualTo(0);
    }

    /** No lost update: 10 concurrent single-unit decrements against 10 units must land on 0. */
    @Test
    void concurrentDecrements_doNotLoseUpdates() throws Exception {
        int stock = 10;
        Medicine m = seed(stock);

        ExecutorService pool = Executors.newFixedThreadPool(stock);
        List<Callable<Integer>> jobs = new ArrayList<>();
        for (int i = 0; i < stock; i++) {
            jobs.add(() -> txTemplate.execute(
                    status -> medicineRepository.deductStockAtomically(m.getId(), HOSPITAL, 1)));
        }
        List<Future<Integer>> results = pool.invokeAll(jobs);
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        int winners = 0;
        for (Future<Integer> f : results) {
            if (f.get() == 1) winners++;
        }

        assertThat(winners).as("all ten single-unit takes should succeed").isEqualTo(stock);
        assertThat(medicineRepository.findById(m.getId()).orElseThrow().getStockQuantity())
                .as("10 units minus 10 successful decrements").isEqualTo(0);
    }

    /** Over-drawing is refused outright rather than partially applied. */
    @Test
    void requestingMoreThanAvailable_changesNothing() {
        Medicine m = seed(3);

        Integer updated = txTemplate.execute(
                status -> medicineRepository.deductStockAtomically(m.getId(), HOSPITAL, 4));

        assertThat(updated).as("0 rows updated = refused").isZero();
        assertThat(medicineRepository.findById(m.getId()).orElseThrow().getStockQuantity())
                .as("no partial mutation").isEqualTo(3);
    }

    /** The tenant predicate is re-asserted in the UPDATE itself, not just in an earlier lookup. */
    @Test
    void aForeignHospitalIdCannotDecrement() {
        Medicine m = seed(5);

        Integer updated = txTemplate.execute(
                status -> medicineRepository.deductStockAtomically(m.getId(), HOSPITAL + 1, 1));

        assertThat(updated).isZero();
        assertThat(medicineRepository.findById(m.getId()).orElseThrow().getStockQuantity()).isEqualTo(5);
    }
}
