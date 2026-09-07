package com.hms.service.pharmacy;

import com.hms.dto.pharmacy.PharmacySaleRequest;
import com.hms.entity.pharmacy.*;
import com.hms.repository.pharmacy.*;
import com.hms.security.SecurityContextHelper;
import com.hms.exception.ResourceNotFoundException;
import com.hms.exception.UnauthorizedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class PharmacySaleService {

    @Autowired
    private PharmacySaleRepository saleRepository;

    @Autowired
    private MedicineBatchRepository batchRepository;

    @Autowired
    private InventoryTransactionRepository transactionRepository;

    @Autowired
    private SecurityContextHelper securityHelper;

    @Autowired
    private com.hms.repository.pharmacy.PharmacySaleItemRepository saleItemRepository;

    @Autowired
    private com.hms.repository.PrescriptionRepository prescriptionRepository;

    @Autowired private com.hms.service.RealtimeNotifier notifier;

    @Transactional
    public PharmacySale createSale(PharmacySaleRequest request) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        Long userId = securityHelper.getCurrentUserId();

        PharmacySale sale = new PharmacySale();
        sale.setHospitalId(hospitalId);
        sale.setBranchId(securityHelper.getCurrentBranchId());
        sale.setPatientId(request.getPatientId());
        sale.setPatientName(request.getPatientName());
        sale.setSubtotal(request.getSubtotal());
        sale.setTaxAmount(request.getTaxAmount());
        sale.setTaxAmountRaw(request.getTaxAmount());
        sale.setDiscountAmount(request.getDiscountAmount());
        sale.setNetAmount(request.getNetAmount());
        sale.setNetAmountRaw(request.getNetAmount());
        sale.setPaymentMethod(request.getPaymentMethod());
        sale.setPaymentStatus("PAID"); // Default for now
        sale.setPostingStatus("POSTED");
        sale.setIsIpdBill(request.getIsIpdBill());
        sale.setIpdAdmissionId(request.getIpdAdmissionId());
        sale.setPrescriptionId(request.getPrescriptionId());
        sale.setDoctorId(request.getDoctorId());
        
        String resolvedSaleType = "WALK-IN";
        if (request.getPrescriptionId() != null) {
            resolvedSaleType = "PRESCRIPTION";
        } else if (request.getIsIpdBill() != null && request.getIsIpdBill()) {
            resolvedSaleType = "IPD";
        }
        sale.setSaleType(resolvedSaleType);
        sale.setPharmacistId(userId);

        // The stock transactions are built here but persisted after the sale is saved: until
        // then the sale has no id and no bill number, so a transaction written now records
        // referenceId=null and "Sale Bill #null" and the stock movement cannot be traced
        // back to the sale that caused it.
        List<InventoryTransaction> stockMovements = new ArrayList<>();
        List<PharmacySaleItem> items = new ArrayList<>();
        for (PharmacySaleRequest.SaleItemRequest itemReq : request.getItems()) {
            items.add(processSaleItem(itemReq, sale, hospitalId, userId, stockMovements));
        }

        sale.setItems(items);
        PharmacySale savedSale = saleRepository.save(sale);

        for (InventoryTransaction tx : stockMovements) {
            tx.setReferenceId(savedSale.getId());
            tx.setRemarks("Sale Bill #" + savedSale.getBillNumber());
        }
        transactionRepository.saveAll(stockMovements);

        // Auto-complete the prescription status to DISPENSED for all consultation records
        if (request.getPrescriptionId() != null) {
            markPrescriptionsDispensed(request.getPrescriptionId());
        }

        // A sale deducts stock. A second pharmacist at the same counter must see the new quantity
        // immediately, or they will sell against a batch that is already empty.
        notifier.refresh(hospitalId);
        return savedSale;
    }

    /**
     * Validates a single sale line, deducts the batch under a pessimistic lock,
     * records the inventory transaction, and returns the sale item. Extracted
     * from createSale to keep that method's complexity manageable.
     */
    private PharmacySaleItem processSaleItem(PharmacySaleRequest.SaleItemRequest itemReq, PharmacySale sale, Long hospitalId, Long userId,
                                             List<InventoryTransaction> stockMovements) {
        // Validation: Prevent negative or zero quantity theft
        if (itemReq.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Sale quantity must be positive");
        }
        if (itemReq.getTaxPercentage() != null && (itemReq.getTaxPercentage().compareTo(BigDecimal.ZERO) < 0 || itemReq.getTaxPercentage().compareTo(BigDecimal.valueOf(100)) > 0)) {
            throw new IllegalArgumentException("Tax percentage must be between 0% and 100%");
        }

        // 1. Fetch and update batch with Pessimistic Lock (Prevents Race Conditions)
        // Also prevents IDOR by including hospitalId in query
        MedicineBatch batch = batchRepository.findByIdAndHospitalIdForUpdate(itemReq.getMedicineBatchId(), hospitalId, securityHelper.getCurrentBranchId())
                .orElseThrow(() -> new ResourceNotFoundException("Batch not found or unauthorized: " + itemReq.getMedicineBatchId()));

        if (batch.getCurrentQuantity().compareTo(itemReq.getQuantity()) < 0) {
            throw new IllegalArgumentException("Insufficient stock for batch: " + batch.getBatchNumber());
        }

        BigDecimal qtyBefore = batch.getCurrentQuantity();
        batch.setCurrentQuantity(qtyBefore.subtract(itemReq.getQuantity()));
        batchRepository.save(batch);

        // 2. Record Inventory Transaction
        InventoryTransaction tx = new InventoryTransaction();
        tx.setHospitalId(hospitalId);
        tx.setBranchId(securityHelper.getCurrentBranchId());
        tx.setMedicineBatchId(batch.getId());
        tx.setTransactionType("SALE");
        tx.setQuantity(itemReq.getQuantity().negate()); // Negative for sales
        tx.setQuantityBefore(qtyBefore);
        tx.setQuantityAfter(batch.getCurrentQuantity());
        tx.setReferenceType("PHARMACY_SALE");
        tx.setCreatedBy(userId);
        // referenceId and remarks are backfilled by createSale once the sale has an id.
        stockMovements.add(tx);

        // 3. Create Sale Item
        PharmacySaleItem item = new PharmacySaleItem();
        item.setPharmacySale(sale);
        item.setMedicineId(itemReq.getMedicineId());
        item.setMedicineBatchId(itemReq.getMedicineBatchId());
        item.setQuantity(itemReq.getQuantity());
        item.setQuantityRaw(itemReq.getQuantity());
        item.setUnitPrice(itemReq.getUnitPrice());
        item.setTaxPercentage(itemReq.getTaxPercentage());
        item.setTaxPercentageRaw(itemReq.getTaxPercentage());
        item.setTaxAmount(itemReq.getTaxAmount());
        item.setDiscountPercentage(itemReq.getDiscountPercentage());
        item.setDiscountAmount(itemReq.getDiscountAmount());
        item.setTotalAmount(itemReq.getTotalAmount());
        item.setTotalAmountRaw(itemReq.getTotalAmount());
        return item;
    }

    /** Best-effort: a prescription update failure must not crash the sale transaction. */
    private void markPrescriptionsDispensed(Long prescriptionId) {
        try {
            List<com.hms.entity.Prescription> rxList = prescriptionRepository.findByMedicalRecordId(prescriptionId);
            for (com.hms.entity.Prescription rx : rxList) {
                rx.setStatus("DISPENSED");
                prescriptionRepository.save(rx);
            }
        } catch (Exception e) {
            // Ensure prescription update failure does not crash the sale transaction
        }
    }

    public Page<PharmacySale> getSalesHistory(Pageable pageable) {
        return saleRepository.findScopedHistory(securityHelper.getCurrentHospitalId(), securityHelper.getCurrentBranchId(), pageable);
    }

    public PharmacySale getSaleDetails(Long id) {
        return saleRepository.findByIdScoped(id, securityHelper.getCurrentHospitalId(), securityHelper.getCurrentBranchId())
                .orElseThrow(() -> new ResourceNotFoundException("Sale record not found"));
    }

    public BigDecimal getTodaySalesTotal() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        LocalDateTime startOfDay = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);
        
        // Use a custom query for efficiency
        return saleRepository.getSumOfSalesBetween(hospitalId, startOfDay, LocalDateTime.now());
    }

    public java.util.Map<String, Object> getDashboardStats() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        LocalDateTime startOfDay = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);
        LocalDateTime now = LocalDateTime.now();

        BigDecimal todayTotal = saleRepository.getSumOfSalesBetween(hospitalId, startOfDay, now);
        if (todayTotal == null) todayTotal = BigDecimal.ZERO;

        long todayCount = saleRepository.countByHospitalIdAndCreatedAtBetween(hospitalId, startOfDay, now);

        java.util.Map<String, Object> stats = new java.util.HashMap<>();
        stats.put("todaySalesTotal", todayTotal);
        stats.put("todaySalesCount", todayCount);
        
        return stats;
    }

    public PharmacySale findByBillNumber(String billNumber, Long hospitalId) {
        return saleRepository.findByBillNumberScoped(billNumber, hospitalId, securityHelper.getCurrentBranchId())
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found: " + billNumber));
    }

    @Transactional
    public java.util.Map<String, Object> processPatientReturn(Long saleId, List<java.util.Map<String, Object>> returnItems) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        Long userId = securityHelper.getCurrentUserId();

        PharmacySale sale = saleRepository.findByIdScoped(saleId, hospitalId, securityHelper.getCurrentBranchId())
                .orElseThrow(() -> new ResourceNotFoundException("Pharmacy sale invoice not found"));

        BigDecimal refundTotal = BigDecimal.ZERO;

        for (java.util.Map<String, Object> item : returnItems) {
            Long batchId = Long.valueOf(item.get("medicineBatchId").toString());
            BigDecimal qtyToReturn = new BigDecimal(item.get("quantityToReturn").toString());

            if (qtyToReturn.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Return quantity must be positive");
            }

            // Find matching sold item in original sale to validate
            PharmacySaleItem soldItem = sale.getItems().stream()
                    .filter(i -> i.getMedicineBatchId().equals(batchId))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("Medicine batch was not part of original invoice"));

            // The bound is what is STILL returnable, not what was originally sold. Checked and
            // taken in one statement: a read-then-write would let two returns submitted together
            // each see the same remaining quantity and both refund it.
            if (saleItemRepository.claimReturn(soldItem.getId(), qtyToReturn) != 1) {
                java.math.BigDecimal already = soldItem.getReturnedQuantity() == null
                        ? java.math.BigDecimal.ZERO : soldItem.getReturnedQuantity();
                java.math.BigDecimal remaining = soldItem.getQuantity().subtract(already);
                throw new IllegalArgumentException(
                        "Cannot return " + qtyToReturn + " for batch " + batchId
                                + ": only " + remaining.max(java.math.BigDecimal.ZERO)
                                + " of the " + soldItem.getQuantity() + " sold remain returnable.");
            }

            // Expiry safety check: Reject returns if the medicine batch has expired
            // Tenant- and branch-scoped. This was an unscoped findById, which let a caller name
            // another facility's batch id; with the restock branch gone it is the only batch
            // lookup left on this path, so it has to be the safe one.
            MedicineBatch batchToCheck = batchRepository
                    .findByIdAndHospitalIdForUpdate(batchId, hospitalId, securityHelper.getCurrentBranchId())
                    .orElseThrow(() -> new ResourceNotFoundException("Batch not found or unauthorized"));
            if (batchToCheck.getExpiryDate() != null && batchToCheck.getExpiryDate().isBefore(java.time.LocalDate.now())) {
                throw new IllegalArgumentException("Cannot return medicine '" + 
                    (batchToCheck.getMedicine() != null ? batchToCheck.getMedicine().getMedicineName() : batchToCheck.getBatchNumber()) + 
                    "' because the batch has already expired on " + batchToCheck.getExpiryDate() + "!");
            }

            // Calculate refund fraction (sellingPrice * qtyToReturn)
            BigDecimal refundValue = soldItem.getUnitPrice().multiply(qtyToReturn);
            refundTotal = refundTotal.add(refundValue);

            // Medicine that has been in a patient's possession does not go back on the shelf.
            //
            // This used to be the caller's choice: a `restock` flag in the request body decided
            // whether the quantity was added straight back to the saleable batch. Nothing
            // verified how the medicine had been stored, or whether it was still the medicine
            // that left, and a client that sent `true` put it back into the pool the next
            // patient is dispensed from. There is no quarantine model yet, so the honest
            // behaviour is to refund the money and leave saleable stock alone.
            //
            // The ledger row says exactly that: quantity zero, balance unchanged. It records
            // that a return happened without asserting that stock increased.
            InventoryTransaction tx = new InventoryTransaction();
            tx.setHospitalId(hospitalId);
            tx.setBranchId(securityHelper.getCurrentBranchId());
            tx.setMedicineBatchId(batchId);
            tx.setTransactionType("RETURN");
            tx.setQuantity(BigDecimal.ZERO);
            tx.setQuantityBefore(batchToCheck.getCurrentQuantity());
            tx.setQuantityAfter(batchToCheck.getCurrentQuantity());
            tx.setReferenceType("PHARMACY_SALE");
            tx.setReferenceId(sale.getId());
            tx.setRemarks("Patient return for Bill #" + sale.getBillNumber()
                    + " - refunded, not returned to saleable stock");
            tx.setCreatedBy(userId);
            transactionRepository.save(tx);
        }

        java.util.Map<String, Object> result = new java.util.HashMap<>();
        result.put("status", "SUCCESS");
        result.put("refundAmount", refundTotal);
        result.put("message", "Patient return and refund processed successfully!");
        return result;
    }
}

