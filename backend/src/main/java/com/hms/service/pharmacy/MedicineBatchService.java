package com.hms.service.pharmacy;

import com.hms.entity.pharmacy.InventoryTransaction;
import com.hms.entity.pharmacy.MedicineBatch;
import com.hms.repository.pharmacy.InventoryTransactionRepository;
import com.hms.repository.pharmacy.MedicineBatchRepository;
import com.hms.security.SecurityContextHelper;
import com.hms.exception.ResourceNotFoundException;
import com.hms.exception.UnauthorizedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
public class MedicineBatchService {

    @Autowired private com.hms.service.RealtimeNotifier notifier;

    @Autowired
    private MedicineBatchRepository repository;

    @Autowired
    private InventoryTransactionRepository transactionRepository;

    @Autowired
    private SecurityContextHelper securityHelper;

    public Page<MedicineBatch> getInventory(String query, Long categoryId, Pageable pageable) {
        Long hid = securityHelper.getCurrentHospitalId();
        Long branchId = securityHelper.getCurrentBranchId();

        if (categoryId != null) {
            if (query != null && !query.trim().isEmpty()) {
                return repository.searchInventoryWithCategory(hid, branchId, query, categoryId, pageable);
            }
            return repository.findScopedByCategory(hid, branchId, categoryId, pageable);
        }

        if (query != null && !query.trim().isEmpty()) {
            return repository.searchInventory(hid, branchId, query, pageable);
        }
        return repository.findScopedInventory(hid, branchId, pageable);
    }

    public Page<MedicineBatch> getLowStockInventory(Pageable pageable) {
        Long hid = securityHelper.getCurrentHospitalId();
        Long branchId = securityHelper.getCurrentBranchId();
        Page<MedicineBatch> page = repository.findLowStock(hid, branchId, pageable);
        for (MedicineBatch b : page.getContent()) {
            java.math.BigDecimal totalQty = repository.sumCurrentQuantityByMedicineId(hid, branchId, b.getMedicineId());
            if (totalQty == null) totalQty = java.math.BigDecimal.ZERO;
            b.setCurrentQuantity(totalQty);
        }
        return page;
    }

    public Page<MedicineBatch> getExpiringInventory(Integer daysThreshold, Pageable pageable) {
        LocalDate dateLimit = LocalDate.now().plusDays(daysThreshold != null ? daysThreshold : 30);
        return repository.findExpiringSoon(securityHelper.getCurrentHospitalId(),
                securityHelper.getCurrentBranchId(), dateLimit, pageable);
    }

    @org.springframework.transaction.annotation.Transactional
    public MedicineBatch createManualBatch(MedicineBatch batch) {
        if (batch.getMedicineId() == null) {
            throw new IllegalArgumentException("Medicine ID is required");
        }
        if (batch.getBatchNumber() == null || batch.getBatchNumber().trim().isEmpty()) {
            throw new IllegalArgumentException("Batch number is required");
        }
        if (batch.getExpiryDate() == null) {
            throw new IllegalArgumentException("Expiry date is required");
        }
        if (batch.getExpiryDate().isBefore(java.time.LocalDate.now())) {
            throw new IllegalArgumentException("Expiry date cannot be in the past");
        }
        if (batch.getManufacturingDate() != null && batch.getManufacturingDate().isAfter(java.time.LocalDate.now())) {
            throw new IllegalArgumentException("Manufacturing date cannot be in the future");
        }
        if (batch.getManufacturingDate() != null && batch.getExpiryDate() != null && batch.getManufacturingDate().isAfter(batch.getExpiryDate())) {
            throw new IllegalArgumentException("Manufacturing date cannot be after expiry date");
        }
        if (batch.getMrp() == null || batch.getMrp().compareTo(java.math.BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("MRP must be greater than zero");
        }
        if (batch.getPurchaseRate() == null || batch.getPurchaseRate().compareTo(java.math.BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Purchase rate must be greater than zero");
        }
        if (batch.getSellingPrice() == null || batch.getSellingPrice().compareTo(java.math.BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Selling price must be greater than zero");
        }
        if (batch.getSellingPrice().compareTo(batch.getMrp()) > 0) {
            throw new IllegalArgumentException("Selling price cannot exceed MRP");
        }
        if (batch.getPurchaseRate().compareTo(batch.getMrp()) > 0) {
            throw new IllegalArgumentException("Purchase rate cannot exceed MRP");
        }
        if (batch.getCurrentQuantity() == null || batch.getCurrentQuantity().compareTo(java.math.BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Current quantity must be greater than zero");
        }
        if (batch.getGstPercentage() != null && (batch.getGstPercentage().compareTo(java.math.BigDecimal.ZERO) < 0 || batch.getGstPercentage().compareTo(java.math.BigDecimal.valueOf(100)) > 0)) {
            throw new IllegalArgumentException("GST percentage must be between 0% and 100%");
        }
 
        Long hid = securityHelper.getCurrentHospitalId();
        batch.setHospitalId(hid);
        batch.setBranchId(securityHelper.getCurrentBranchId());
        batch.setStatus("ACTIVE");
        
        // Ensure we don't have a duplicate batch for the same medicine and number
        java.util.Optional<MedicineBatch> existing = repository.findByHospitalIdAndMedicineIdAndBatchNumber(
            hid, securityHelper.getCurrentBranchId(), batch.getMedicineId(), batch.getBatchNumber());
        
        if (existing.isPresent()) {
            throw new IllegalArgumentException("Batch with this number already exists for this medicine. Please use adjustment instead.");
        }
        
        MedicineBatch saved = repository.save(batch);

        // Create audit transaction
        InventoryTransaction tx = new InventoryTransaction();
        tx.setHospitalId(hid);
        tx.setBranchId(securityHelper.getCurrentBranchId());
        tx.setMedicineBatchId(saved.getId());
        tx.setTransactionType("OPENING_STOCK");
        tx.setQuantity(saved.getCurrentQuantity());
        tx.setQuantityBefore(java.math.BigDecimal.ZERO);
        tx.setQuantityAfter(saved.getCurrentQuantity());
        tx.setRemarks(batch.getRemarks() != null ? batch.getRemarks() : "Opening Stock");
        tx.setCreatedBy(securityHelper.getCurrentUserId());
        transactionRepository.save(tx);

        // New stock: every inventory screen in the pharmacy should show it without a reload.
        notifier.refresh(hid);
        return saved;
    }

    public java.util.List<MedicineBatch> searchAvailableBatchesFEFO(String query) {
        Long hid = securityHelper.getCurrentHospitalId();
        return repository.searchAvailableBatchesFEFO(hid, securityHelper.getCurrentBranchId(), query != null ? query.trim() : "");
    }

    @org.springframework.transaction.annotation.Transactional
    public MedicineBatch blockBatch(Long id) {
        Long hid = securityHelper.getCurrentHospitalId();
        MedicineBatch batch = repository.findByIdAndHospitalIdForUpdate(id, hid, securityHelper.getCurrentBranchId())
                .orElseThrow(() -> new ResourceNotFoundException("Batch not found"));
        batch.setStatus("BLOCKED");
        MedicineBatch saved = repository.save(batch);
        notifier.refresh(hid);
        return saved;
    }

    @org.springframework.transaction.annotation.Transactional
    public MedicineBatch disposeBatch(Long id, String remarks) {
        Long hid = securityHelper.getCurrentHospitalId();
        MedicineBatch batch = repository.findByIdAndHospitalIdForUpdate(id, hid, securityHelper.getCurrentBranchId())
                .orElseThrow(() -> new ResourceNotFoundException("Batch not found"));
        
        java.math.BigDecimal qtyBefore = batch.getCurrentQuantity();
        if (qtyBefore == null) qtyBefore = java.math.BigDecimal.ZERO;

        if (qtyBefore.compareTo(java.math.BigDecimal.ZERO) > 0) {
            // Write off the remaining stock to 0
            batch.setCurrentQuantity(java.math.BigDecimal.ZERO);
            
            InventoryTransaction tx = new InventoryTransaction();
            tx.setHospitalId(hid);
            tx.setBranchId(securityHelper.getCurrentBranchId());
            tx.setMedicineBatchId(batch.getId());
            tx.setTransactionType("ADJUSTMENT");
            tx.setQuantity(qtyBefore.negate());
            tx.setQuantityBefore(qtyBefore);
            tx.setQuantityAfter(java.math.BigDecimal.ZERO);
            tx.setReferenceType("STOCK_DISPOSAL");
            tx.setRemarks(remarks != null ? remarks : "Disposed due to expiry");
            tx.setCreatedBy(securityHelper.getCurrentUserId());
            transactionRepository.save(tx);
        }

        batch.setStatus("DISPOSED");
        MedicineBatch saved = repository.save(batch);
        notifier.refresh(securityHelper.getCurrentHospitalId());
        return saved;
    }

    @org.springframework.transaction.annotation.Transactional
    public java.util.Map<String, Object> processSupplierReturn(Long supplierId, java.util.List<java.util.Map<String, Object>> items) {
        Long hid = securityHelper.getCurrentHospitalId();
        Long userId = securityHelper.getCurrentUserId();
        
        java.math.BigDecimal totalClaimed = java.math.BigDecimal.ZERO;
        
        for (java.util.Map<String, Object> item : items) {
            Long batchId = Long.valueOf(item.get("medicineBatchId").toString());
            java.math.BigDecimal qtyToReturn = new java.math.BigDecimal(item.get("quantityToReturn").toString());
            
            if (qtyToReturn.compareTo(java.math.BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Return quantity must be positive");
            }
            
            MedicineBatch batch = repository.findByIdAndHospitalIdForUpdate(batchId, hid, securityHelper.getCurrentBranchId())
                    .orElseThrow(() -> new ResourceNotFoundException("Batch not found or unauthorized"));
            
            if (batch.getCurrentQuantity().compareTo(qtyToReturn) < 0) {
                throw new IllegalArgumentException("Insufficient stock in batch " + batch.getBatchNumber() + " to return. Available: " + batch.getCurrentQuantity());
            }
            
            java.math.BigDecimal qtyBefore = batch.getCurrentQuantity();
            batch.setCurrentQuantity(qtyBefore.subtract(qtyToReturn));
            repository.save(batch);
            
            // Calculate claim total
            // An explicit returnRate (e.g. expired stock taken back at a lower rate)
            // overrides the batch purchase rate when provided.
            Object returnRateObj = item.get("returnRate");
            java.math.BigDecimal rate;
            if (returnRateObj != null && !returnRateObj.toString().trim().isEmpty()) {
                rate = new java.math.BigDecimal(returnRateObj.toString());
                if (rate.compareTo(java.math.BigDecimal.ZERO) < 0) {
                    throw new IllegalArgumentException("Return rate cannot be negative");
                }
            } else {
                rate = batch.getPurchaseRate() != null ? batch.getPurchaseRate() : java.math.BigDecimal.ZERO;
            }
            totalClaimed = totalClaimed.add(qtyToReturn.multiply(rate));
            
            // Record Return Transaction
            InventoryTransaction tx = new InventoryTransaction();
            tx.setHospitalId(hid);
            tx.setBranchId(securityHelper.getCurrentBranchId());
            tx.setMedicineBatchId(batch.getId());
            tx.setTransactionType("RETURN");
            tx.setQuantity(qtyToReturn.negate()); // Negative because stock leaves inventory
            tx.setQuantityBefore(qtyBefore);
            tx.setQuantityAfter(batch.getCurrentQuantity());
            tx.setReferenceType("SUPPLIER_RETURN");
            tx.setRemarks("Returned to Supplier ID: " + supplierId + " @ rate " + rate);
            tx.setCreatedBy(userId);
            transactionRepository.save(tx);
        }
        
        java.util.Map<String, Object> res = new java.util.HashMap<>();
        res.put("status", "SUCCESS");
        res.put("totalClaimed", totalClaimed);
        res.put("message", "Supplier return note dispatched successfully!");
        return res;
    }
}

