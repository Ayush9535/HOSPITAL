package com.hms.service.hospital;

import com.hms.entity.Medicine;
import com.hms.entity.MedicineStockBatch;
import com.hms.entity.StockMovement;
import com.hms.exception.ConflictException;
import com.hms.exception.ResourceNotFoundException;
import com.hms.exception.UnauthorizedException;
import com.hms.repository.MedicineRepository;
import com.hms.repository.MedicineStockBatchRepository;
import com.hms.repository.StockMovementRepository;
import com.hms.security.SecurityContextHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Batch-aware stock for Hospital/Clinic medicines: receipt, availability, and FEFO consumption,
 * with every physical change posted to the {@link StockMovement} ledger.
 *
 * <p>This is the only component permitted to move medicine stock. Callers state intent
 * (dispense this much of this medicine) and never pick batches or touch quantities themselves.
 *
 * <p>Availability is always computed from batches -- {@link Medicine#getStockQuantity()} is a
 * legacy read cache kept in step for screens not yet migrated, and is never consulted to decide
 * whether stock exists.
 */
@Service
public class MedicineStockService {

    @Autowired private MedicineStockBatchRepository batchRepository;
    @Autowired private StockMovementRepository movementRepository;
    @Autowired private MedicineRepository medicineRepository;
    @Autowired private SecurityContextHelper securityHelper;

    /** One batch's contribution to a consumption, for callers that need to show what was taken. */
    public record BatchAllocation(Long batchId, String batchNumber, LocalDate expiryDate, int quantity) {}

    /**
     * The lot name to use when a receipt carries no supplier batch number.
     *
     * <p>Naming the lot after its own expiry is the one thing we can say about it truthfully. It
     * keeps the two properties that matter: stock expiring on different dates lands in different
     * rows (so the older lot cannot inherit the newer date), and re-receiving stock with the same
     * expiry tops up the row it belongs to instead of fragmenting it. A fabricated batch number
     * would do neither, and would read on screen as though the supplier had provided one.
     */
    public static String lotNameForExpiry(LocalDate expiryDate) {
        return "EXP-" + expiryDate;
    }

    // ---------------------------------------------------------------- reads

    public int availableQuantity(Long medicineId) {
        Long hospitalId = requireHospitalId();
        requireOwnedMedicine(medicineId, hospitalId);
        return batchRepository.availableQuantity(hospitalId, medicineId, LocalDate.now());
    }

    /**
     * Usable stock for a medicine belonging to a stated facility.
     *
     * <p>Takes the hospital id rather than reading it from the security context so callers that
     * have already established the tenant -- the medication chart resolves it once for the whole
     * chart -- cannot drift from it row by row.
     */
    public int availableQuantityFor(Long medicineId, Long hospitalId) {
        requireOwnedMedicine(medicineId, hospitalId);
        return batchRepository.availableQuantity(hospitalId, medicineId, LocalDate.now());
    }

    /** Expiry of the lot FEFO would take first, or null when nothing is usable. */
    public LocalDate earliestUsableExpiry(Long medicineId, Long hospitalId) {
        requireOwnedMedicine(medicineId, hospitalId);
        List<MedicineStockBatch> usable = batchRepository.findDispensableFefo(hospitalId, medicineId, LocalDate.now());
        return usable.isEmpty() ? null : usable.get(0).getExpiryDate();
    }

    /**
     * One medicine as a dispensing user needs to see it: what it is, how much is usable, and the
     * expiry of the lot that would go out first.
     */
    public record StockOption(Long medicineId, String name, String type, Integer availableQuantity,
            LocalDate earliestExpiry) {}

    /**
     * The facility's medicines, for choosing what to hand over against an order.
     *
     * <p>Scoped to the caller's facility in the query, and drawn from {@code medicines} only --
     * general hospital inventory (gloves, syringes, linen) lives in its own table and is not a
     * dispensable medicine, so it can never appear here.
     *
     * <p>Ordered by name, and never narrowed to a single automatic answer: the caller sees the
     * candidates and picks. Two rows can share a name -- different strengths, a duplicate entry, a
     * typo -- and choosing between them is a person's job.
     */
    public List<StockOption> dispensableOptions(String query) {
        Long hospitalId = requireHospitalId();
        LocalDate today = LocalDate.now();
        String needle = query == null ? "" : query.trim().toLowerCase();

        List<StockOption> out = new ArrayList<>();
        for (Medicine m : medicineRepository.findByHospitalIdAndIsActiveTrue(hospitalId)) {
            if (!needle.isEmpty()
                    && (m.getName() == null || !m.getName().toLowerCase().contains(needle))) {
                continue;
            }
            List<MedicineStockBatch> usable = batchRepository.findDispensableFefo(hospitalId, m.getId(), today);
            int available = batchRepository.availableQuantity(hospitalId, m.getId(), today);
            out.add(new StockOption(m.getId(), m.getName(), m.getType(), available,
                    usable.isEmpty() ? null : usable.get(0).getExpiryDate()));
        }
        out.sort(java.util.Comparator.comparing(o -> o.name() == null ? "" : o.name().toLowerCase()));
        return out;
    }

    /** All batches for a medicine, newest expiry last -- includes expired/empty for the UI to show. */
    public List<MedicineStockBatch> batchesFor(Long medicineId) {
        Long hospitalId = requireHospitalId();
        requireOwnedMedicine(medicineId, hospitalId);
        return batchRepository.findByHospitalIdAndMedicineIdOrderByExpiryDateAsc(hospitalId, medicineId);
    }

    // ------------------------------------------------------------- receipt

    /**
     * Receive stock against a medicine, as a batch.
     *
     * <p>Re-receiving an existing (medicine, batchNumber) tops that lot up rather than creating a
     * second row -- the unique constraint makes that the only correct outcome. A DIFFERENT batch
     * number always becomes its own row, which is the whole point: the previous behaviour merged
     * every purchase into one aggregate and overwrote its expiry date, so older stock silently
     * inherited a later expiry and stayed dispensable.
     */
    @Transactional
    public MedicineStockBatch receiveBatch(Long medicineId, String batchNumber, LocalDate expiryDate,
            int quantity, Double unitPrice, String idempotencyKey, String remarks) {
        Long hospitalId = requireHospitalId();
        Medicine medicine = requireOwnedMedicine(medicineId, hospitalId);

        if (quantity <= 0) throw new IllegalArgumentException("Received quantity must be a positive number");
        if (batchNumber == null || batchNumber.isBlank()) {
            throw new IllegalArgumentException("Batch number is required");
        }
        if (expiryDate == null) throw new IllegalArgumentException("Expiry date is required");
        if (expiryDate.isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("Expiry date cannot be in the past");
        }

        StockMovement replay = findReplay(hospitalId, idempotencyKey);
        if (replay != null) {
            return batchRepository.findByIdAndHospitalId(replay.getBatchId(), hospitalId).orElseThrow(
                    () -> new ResourceNotFoundException("Batch not found"));
        }

        String cleanBatch = batchNumber.trim();
        MedicineStockBatch batch = batchRepository
                .findByHospitalIdAndMedicineIdAndBatchNumber(hospitalId, medicineId, cleanBatch)
                .orElse(null);

        if (batch == null) {
            batch = new MedicineStockBatch();
            batch.setHospitalId(hospitalId);
            batch.setMedicineId(medicineId);
            batch.setBatchNumber(cleanBatch);
            batch.setExpiryDate(expiryDate);
            batch.setReceivedQuantity(quantity);
            batch.setCurrentQuantity(quantity);
            batch.setUnitPrice(unitPrice);
            batch.setIsActive(true);
            batch.setReceivedAt(LocalDateTime.now());
            batch = batchRepository.save(batch);
        } else {
            if (!batch.getExpiryDate().equals(expiryDate)) {
                throw new ConflictException("Batch " + cleanBatch + " already exists for this medicine with expiry "
                        + batch.getExpiryDate() + ". Use a different batch number for stock expiring "
                        + expiryDate + ".");
            }
            batch.setReceivedQuantity(batch.getReceivedQuantity() + quantity);
            batch.setCurrentQuantity(batch.getCurrentQuantity() + quantity);
            if (unitPrice != null) batch.setUnitPrice(unitPrice);
            batch.setIsActive(true);
            batch = batchRepository.save(batch);
        }

        postMovement(hospitalId, medicineId, batch.getId(), StockMovement.PURCHASE_RECEIPT,
                StockMovement.IN, quantity, batch.getCurrentQuantity(),
                "MEDICINE_PURCHASE", null, idempotencyKey, remarks);
        syncLegacyAggregate(medicine, hospitalId);
        return batch;
    }

    // ------------------------------------------------------------ consume

    /**
     * Consume {@code quantity} of a medicine using FEFO, across as many batches as needed.
     *
     * <p>All-or-nothing: total dispensable stock is checked first, then each batch is taken with a
     * conditional UPDATE. If any step loses a race the whole transaction rolls back, so a caller
     * never sees a partial decrement. Expired, deactivated and empty lots are excluded by the
     * query, so expired stock can neither be counted nor consumed.
     */
    @Transactional
    public List<BatchAllocation> consumeFefo(Long medicineId, int quantity, String movementType,
            String referenceType, Long referenceId, String idempotencyKey, String remarks) {
        Long hospitalId = requireHospitalId();
        Medicine medicine = requireOwnedMedicine(medicineId, hospitalId);
        if (quantity <= 0) throw new IllegalArgumentException("Quantity must be a positive number");

        StockMovement replay = findReplay(hospitalId, idempotencyKey);
        if (replay != null) {
            // Already applied. Report what that posting took rather than taking it again.
            List<BatchAllocation> out = new ArrayList<>();
            for (StockMovement m : movementRepository
                    .findByHospitalIdAndIdempotencyKeyOrderByIdAsc(hospitalId, idempotencyKey)) {
                if (m.getBatchId() == null) continue;
                batchRepository.findByIdAndHospitalId(m.getBatchId(), hospitalId).ifPresent(
                        b -> out.add(new BatchAllocation(b.getId(), b.getBatchNumber(),
                                b.getExpiryDate(), m.getQuantity())));
            }
            return out;
        }

        LocalDate today = LocalDate.now();
        int available = batchRepository.availableQuantity(hospitalId, medicineId, today);
        if (available < quantity) {
            throw new ConflictException("Insufficient stock for " + medicine.getName()
                    + ": requested " + quantity + ", available " + available);
        }

        List<MedicineStockBatch> candidates = batchRepository.findDispensableFefo(hospitalId, medicineId, today);
        List<BatchAllocation> allocations = new ArrayList<>();
        int remaining = quantity;

        for (MedicineStockBatch b : candidates) {
            if (remaining <= 0) break;
            int take = Math.min(b.getCurrentQuantity(), remaining);
            if (take <= 0) continue;

            if (batchRepository.deductAtomically(b.getId(), hospitalId, take) == 0) {
                // Someone else took this lot between the plan and the write. Rolling back is the
                // honest outcome: a partially-served dispense is worse than a retryable failure.
                throw new ConflictException("Stock for " + medicine.getName()
                        + " changed while dispensing. Please retry.");
            }
            remaining -= take;
            int balanceAfter = b.getCurrentQuantity() - take;
            allocations.add(new BatchAllocation(b.getId(), b.getBatchNumber(), b.getExpiryDate(), take));
            postMovement(hospitalId, medicineId, b.getId(), movementType, StockMovement.OUT,
                    take, balanceAfter, referenceType, referenceId, idempotencyKey, remarks);
        }

        if (remaining > 0) {
            throw new ConflictException("Insufficient stock for " + medicine.getName()
                    + ": short by " + remaining);
        }
        syncLegacyAggregate(medicine, hospitalId);
        return allocations;
    }

    // ------------------------------------------------------------ helpers

    /**
     * The ledger row is written with the caller's idempotency key; the unique constraint on
     * (hospital_id, idempotency_key) is what actually makes a replayed command safe. This lookup
     * short-circuits the common case before we touch stock.
     */
    private StockMovement findReplay(Long hospitalId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return null;
        return movementRepository.findByHospitalIdAndIdempotencyKey(hospitalId, idempotencyKey).orElse(null);
    }

    private void postMovement(Long hospitalId, Long medicineId, Long batchId, String type, String direction,
            int quantity, int balanceAfter, String referenceType, Long referenceId,
            String idempotencyKey, String remarks) {
        StockMovement m = new StockMovement();
        m.setHospitalId(hospitalId);
        m.setInventoryDomain(StockMovement.DOMAIN_MEDICINE);
        m.setItemId(medicineId);
        m.setBatchId(batchId);
        m.setMovementType(type);
        m.setDirection(direction);
        m.setQuantity(quantity);
        m.setBalanceAfter(balanceAfter);
        m.setReferenceType(referenceType);
        m.setReferenceId(referenceId);
        m.setIdempotencyKey(idempotencyKey);
        m.setPerformedByUserId(securityHelper.getCurrentUserId());
        m.setRemarks(remarks);
        movementRepository.save(m);
    }

    /**
     * Keep the legacy Medicine.stockQuantity column in step with the batches.
     *
     * <p>It is a cache, not a source of truth: nothing decides availability from it. It exists so
     * screens and reports not yet moved onto batches keep showing a sane number instead of a
     * frozen one, and it is recomputed from batches rather than incremented independently, so the
     * two cannot drift.
     */
    private void syncLegacyAggregate(Medicine medicine, Long hospitalId) {
        int available = batchRepository.availableQuantity(hospitalId, medicine.getId(), LocalDate.now());
        medicine.setStockQuantity(available);
        medicineRepository.save(medicine);
    }

    private Medicine requireOwnedMedicine(Long medicineId, Long hospitalId) {
        return medicineRepository.findByIdAndHospitalId(medicineId, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Medicine not found"));
    }

    private Long requireHospitalId() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) throw new UnauthorizedException("Hospital ID not found in context");
        return hospitalId;
    }
}
