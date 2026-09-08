package com.hms.service.pharmacy;

import com.hms.dto.pharmacy.PurchaseRequest;
import com.hms.entity.pharmacy.*;
import com.hms.repository.pharmacy.*;
import com.hms.security.HospitalWebSocketHandler;
import com.hms.security.SecurityContextHelper;
import com.hms.exception.ResourceNotFoundException;
import com.hms.exception.UnauthorizedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class PurchaseService {

    private static final Logger logger = LoggerFactory.getLogger(PurchaseService.class);

    @Autowired
    private PurchaseInvoiceRepository invoiceRepository;

    @Autowired
    private MedicineBatchRepository batchRepository;

    @Autowired
    private InventoryTransactionRepository transactionRepository;

    @Autowired
    private SecurityContextHelper securityHelper;

    @Autowired
    private HospitalWebSocketHandler webSocketHandler;

    @Autowired
    private MedicineMasterRepository medicineMasterRepository;

    @Transactional
    public PurchaseInvoice createPurchase(PurchaseRequest req) {
        Long hospitalId = securityHelper.getCurrentHospitalId();

        PurchaseInvoice invoice = buildInvoiceHeader(req, hospitalId);
        invoice.setItems(buildInvoiceItems(req, invoice));

        PurchaseInvoice saved = invoiceRepository.save(invoice);

        if ("POSTED".equalsIgnoreCase(saved.getPostingStatus())) {
            updateInventory(saved);
            broadcastRefresh(hospitalId);
        }

        return saved;
    }

    private PurchaseInvoice buildInvoiceHeader(PurchaseRequest req, Long hospitalId) {
        PurchaseInvoice invoice = new PurchaseInvoice();
        invoice.setHospitalId(hospitalId);
        invoice.setBranchId(securityHelper.getCurrentBranchId());
        invoice.setSupplierId(req.getSupplierId());
        invoice.setInvoiceNumber(req.getInvoiceNumber());
        invoice.setInvoiceDate(req.getInvoiceDate());
        invoice.setSubtotal(req.getSubtotal());
        invoice.setDiscountAmount(req.getDiscountAmount());
        invoice.setGstAmount(req.getGstAmount());
        invoice.setTotalAmount(req.getTotalAmount());
        invoice.setPostingStatus(req.getPostingStatus());
        invoice.setPaymentStatus("PENDING");
        invoice.setCreatedBy(securityHelper.getCurrentUserId());
        return invoice;
    }

    private List<PurchaseInvoiceItem> buildInvoiceItems(PurchaseRequest req, PurchaseInvoice invoice) {
        List<PurchaseInvoiceItem> items = new ArrayList<>();
        if (req.getItems() != null) {
            for (PurchaseRequest.PurchaseItemRequest itemReq : req.getItems()) {
                validatePurchaseItem(itemReq);
                items.add(buildInvoiceItem(itemReq, invoice));
            }
        }
        return items;
    }

    private void validatePurchaseItem(PurchaseRequest.PurchaseItemRequest itemReq) {
        validatePurchaseItemPricing(itemReq);
        validatePurchaseItemExpiryAndGst(itemReq);
    }

    private void validatePurchaseItemPricing(PurchaseRequest.PurchaseItemRequest itemReq) {
        if (itemReq.getQuantity() == null || itemReq.getQuantity().compareTo(java.math.BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Purchase quantity must be positive");
        }
        if (itemReq.getFreeQuantity() == null || itemReq.getFreeQuantity().compareTo(java.math.BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Free quantity cannot be negative");
        }
        if (itemReq.getMrp() == null || itemReq.getMrp().compareTo(java.math.BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("MRP must be greater than zero");
        }
        if (itemReq.getPurchaseRate() == null || itemReq.getPurchaseRate().compareTo(java.math.BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Purchase rate must be greater than zero");
        }
        if (itemReq.getSellingPrice() == null || itemReq.getSellingPrice().compareTo(java.math.BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Selling price must be greater than zero");
        }
        if (itemReq.getSellingPrice().compareTo(itemReq.getMrp()) > 0) {
            throw new IllegalArgumentException("Selling price cannot exceed MRP");
        }
        if (itemReq.getPurchaseRate().compareTo(itemReq.getMrp()) > 0) {
            throw new IllegalArgumentException("Purchase rate cannot exceed MRP");
        }
    }

    private void validatePurchaseItemExpiryAndGst(PurchaseRequest.PurchaseItemRequest itemReq) {
        if (itemReq.getExpiryDate() == null) {
            throw new IllegalArgumentException("Expiry date is required");
        }
        if (itemReq.getExpiryDate().isBefore(java.time.LocalDate.now(java.time.ZoneId.systemDefault()))) {
            throw new IllegalArgumentException("Expiry date cannot be in the past");
        }
        if (itemReq.getGstPercentage() != null && (itemReq.getGstPercentage().compareTo(java.math.BigDecimal.ZERO) < 0 || itemReq.getGstPercentage().compareTo(java.math.BigDecimal.valueOf(100)) > 0)) {
            throw new IllegalArgumentException("GST percentage must be between 0% and 100%");
        }
    }

    private PurchaseInvoiceItem buildInvoiceItem(PurchaseRequest.PurchaseItemRequest itemReq, PurchaseInvoice invoice) {
        PurchaseInvoiceItem item = new PurchaseInvoiceItem();
        item.setMedicineId(resolveMedicineId(itemReq, invoice.getHospitalId()));
        item.setBatchNumber(itemReq.getBatchNumber());
        item.setExpiryDate(itemReq.getExpiryDate());
        item.setQuantity(itemReq.getQuantity());
        item.setFreeQuantity(itemReq.getFreeQuantity());
        item.setPurchaseRate(itemReq.getPurchaseRate());
        item.setMrp(itemReq.getMrp());
        item.setSellingPrice(itemReq.getSellingPrice());
        item.setGstPercentage(itemReq.getGstPercentage());
        item.setLineTotal(itemReq.getLineTotal());
        item.setPurchaseInvoice(invoice);
        return item;
    }

    /**
     * Resolve the local MedicineMaster id for a purchase line. If the client sent an
     * explicit medicineId (existing local medicine), use it. Otherwise find-or-create
     * a MedicineMaster for this hospital from the platform-sourced name/type plus the
     * free-text manufacturer — the standalone pharmacy has no Medicine Master tab, so
     * medicines enter the catalog through purchases.
     */
    private Long resolveMedicineId(PurchaseRequest.PurchaseItemRequest itemReq, Long hospitalId) {
        if (itemReq.getMedicineId() != null) {
            return itemReq.getMedicineId();
        }
        String name = itemReq.getMedicineName() != null ? itemReq.getMedicineName().trim() : null;
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Medicine name is required");
        }
        return medicineMasterRepository.findFirstScopedByName(hospitalId, securityHelper.getCurrentBranchId(), name)
                .map(MedicineMaster::getId)
                .orElseGet(() -> {
                    MedicineMaster m = new MedicineMaster();
                    m.setHospitalId(hospitalId);
                    m.setBranchId(securityHelper.getCurrentBranchId());
                    m.setMedicineName(name);
                    m.setMedicineType(itemReq.getMedicineType());
                    m.setManufacturerName(itemReq.getManufacturerName());
                    if (itemReq.getGstPercentage() != null) {
                        m.setGstPercentage(itemReq.getGstPercentage());
                    }
                    MedicineMaster saved = medicineMasterRepository.save(m);
                    saved.setMedicineCode("MED" + (1000 + saved.getId()));
                    return medicineMasterRepository.save(saved).getId();
                });
    }

    private void broadcastRefresh(Long hospitalId) {
        try {
            webSocketHandler.broadcast(hospitalId, "{\"type\":\"REFRESH_DATA\"}");
        } catch (Exception e) {
            logger.warn("Failed to broadcast WebSocket refresh after purchase invoice creation", e);
        }
    }

    @Transactional
    public PurchaseInvoice postInvoice(Long id) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        PurchaseInvoice invoice = invoiceRepository.findByIdScoped(id, hospitalId, securityHelper.getCurrentBranchId())
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found"));

        if ("POSTED".equalsIgnoreCase(invoice.getPostingStatus())) {
            throw new IllegalArgumentException("Invoice already posted");
        }

        invoice.setPostingStatus("POSTED");
        PurchaseInvoice saved = invoiceRepository.save(invoice);
        updateInventory(saved);
        try { webSocketHandler.broadcast(hospitalId, "{\"type\":\"REFRESH_DATA\"}"); } catch (Exception e) {
            logger.warn("Failed to broadcast WebSocket refresh after invoice posting", e);
        }
        return saved;
    }

    private void updateInventory(PurchaseInvoice invoice) {
        Long hospitalId = invoice.getHospitalId();
        for (PurchaseInvoiceItem item : invoice.getItems()) {
            // 1. Find or create batch with Pessimistic Lock
            MedicineBatch batch = batchRepository.findByHospitalIdAndMedicineIdAndBatchNumberForUpdate(
                    hospitalId, invoice.getBranchId(), item.getMedicineId(), item.getBatchNumber())
                    .orElse(new MedicineBatch());

            BigDecimal qtyBefore = batch.getCurrentQuantity() != null ? batch.getCurrentQuantity() : BigDecimal.ZERO;
            BigDecimal purchaseQty = item.getQuantity() != null ? item.getQuantity() : BigDecimal.ZERO;
            BigDecimal freeQty = item.getFreeQuantity() != null ? item.getFreeQuantity() : BigDecimal.ZERO;
            BigDecimal totalInward = purchaseQty.add(freeQty);

            if (batch.getId() == null) {
                batch.setHospitalId(hospitalId);
                batch.setBranchId(invoice.getBranchId());
                batch.setMedicineId(item.getMedicineId());
                batch.setBatchNumber(item.getBatchNumber());
                batch.setCurrentQuantity(totalInward);
            } else {
                batch.setCurrentQuantity(qtyBefore.add(totalInward));
            }

            // Update prices/dates from latest purchase
            batch.setExpiryDate(item.getExpiryDate());
            batch.setMrp(item.getMrp());
            batch.setPurchaseRate(item.getPurchaseRate());
            batch.setSellingPrice(item.getSellingPrice());
            batch.setSupplierId(invoice.getSupplierId());
            batch.setPurchaseInvoiceItemId(item.getId());
            batch.setGstPercentage(item.getGstPercentage());
            batch.setStatus("ACTIVE");

            MedicineBatch savedBatch = batchRepository.save(batch);

            // 2. Record Transaction
            InventoryTransaction tx = new InventoryTransaction();
            tx.setHospitalId(hospitalId);
            tx.setBranchId(invoice.getBranchId());
            tx.setMedicineBatchId(savedBatch.getId());
            tx.setTransactionType("PURCHASE");
            tx.setQuantity(totalInward);
            tx.setQuantityBefore(qtyBefore);
            tx.setQuantityAfter(savedBatch.getCurrentQuantity());
            tx.setReferenceType("PURCHASE_INVOICE");
            tx.setReferenceId(invoice.getId());
            tx.setRemarks("Purchase Inward: Inv #" + invoice.getInvoiceNumber());
            transactionRepository.save(tx);
        }
    }

    public Page<PurchaseInvoice> listInvoices(Pageable pageable) {
        return invoiceRepository.findScopedHistory(securityHelper.getCurrentHospitalId(), securityHelper.getCurrentBranchId(), pageable);
    }

    public PurchaseInvoice getInvoice(Long id) {
        return invoiceRepository.findByIdScoped(id, securityHelper.getCurrentHospitalId(), securityHelper.getCurrentBranchId())
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found"));
    }
}

