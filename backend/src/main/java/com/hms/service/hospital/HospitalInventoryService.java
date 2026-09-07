package com.hms.service.hospital;

import com.hms.entity.HospitalInventory;
import com.hms.entity.InventoryItem;
import com.hms.repository.HospitalInventoryRepository;
import com.hms.repository.InventoryItemRepository;
import com.hms.security.SecurityContextHelper;

import com.hms.exception.ResourceNotFoundException;
import com.hms.exception.UnauthorizedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@Service
public class HospitalInventoryService {

    private static final Logger logger = LoggerFactory.getLogger(HospitalInventoryService.class);

    @Autowired
    private HospitalInventoryRepository hospitalInventoryRepository;

    @Autowired
    private InventoryItemRepository inventoryItemRepository;

    @Autowired
    private com.hms.repository.HospitalInventoryPurchaseRepository hospitalInventoryPurchaseRepository;

    @Autowired
    private com.hms.repository.HospitalServiceRepository hospitalServiceRepository;

    @Autowired
    private com.hms.repository.HospitalServiceItemRepository hospitalServiceItemRepository;

    @Autowired
    private com.hms.repository.InventoryMasterItemRepository inventoryMasterItemRepository;

    @Autowired
    private SecurityContextHelper securityHelper;

    @Autowired
    private com.hms.service.AuditLogService auditLogService;

    @Autowired
    private com.hms.security.HospitalWebSocketHandler webSocketHandler;



    // --- Purchase History Management ---

    public List<com.hms.entity.HospitalInventoryPurchase> getHospitalInventoryPurchases() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            throw new UnauthorizedException("Hospital ID not found in context");
        }
        return hospitalInventoryPurchaseRepository.findByHospitalIdOrderByPurchaseDateDesc(hospitalId);
    }

    @Transactional
    public com.hms.entity.HospitalInventoryPurchase addHospitalInventoryPurchase(com.hms.entity.HospitalInventoryPurchase purchase) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            throw new UnauthorizedException("Hospital ID not found in context");
        }

        purchase.setHospitalId(hospitalId);
        com.hms.entity.HospitalInventoryPurchase savedPurchase = hospitalInventoryPurchaseRepository.save(purchase);

        // Find existing active stock by name
        List<HospitalInventory> activeStocks = hospitalInventoryRepository.findByNameAndHospitalIdAndIsActiveTrue(purchase.getName(), hospitalId);
        
        HospitalInventory stock;
        if (!activeStocks.isEmpty()) {
            stock = activeStocks.get(0);
            stock.setStockQuantity(stock.getStockQuantity() + purchase.getQuantity());
            stock.setUnitPrice(purchase.getUnitPrice());
            stock.setExpiryDate(purchase.getExpiryDate());
            stock.setManufacturer(purchase.getManufacturer());
            stock.setMinStockLevel(purchase.getMinStockLevel());
            stock.setType(purchase.getType());
            stock.setIsActive(true);
        } else {
            stock = new HospitalInventory();
            stock.setName(purchase.getName());
            stock.setStockQuantity(purchase.getQuantity());
            stock.setUnitPrice(purchase.getUnitPrice());
            stock.setExpiryDate(purchase.getExpiryDate());
            stock.setMinStockLevel(purchase.getMinStockLevel());
            stock.setType(purchase.getType());
            stock.setManufacturer(purchase.getManufacturer());
            stock.setHospitalId(hospitalId);
            stock.setIsActive(true);
        }
        hospitalInventoryRepository.save(stock);

        // Auto-catalog item in lookup dictionary if it does not exist
        if (!inventoryItemRepository.existsByNameAndHospitalId(purchase.getName(), hospitalId)) {
            InventoryItem newCatalog = new InventoryItem();
            newCatalog.setName(purchase.getName());
            newCatalog.setType(purchase.getType() != null ? purchase.getType() : "Consumable");
            newCatalog.setManufacturer(purchase.getManufacturer());
            newCatalog.setHospitalId(hospitalId);
            inventoryItemRepository.save(newCatalog);
        }

        // Audit Log
        try {
            auditLogService.logAction(
                    "INVENTORY_PURCHASE_ADDED",
                    "Recorded purchase of " + savedPurchase.getName() + " (Qty: " + savedPurchase.getQuantity() + ", Cost: ₹" + savedPurchase.getUnitPrice() + ")",
                    securityHelper.getCurrentUserEmail(),
                    hospitalId,
                    "INVENTORY",
                    savedPurchase.getId().toString(),
                    null
            );
        } catch (Exception e) {
            logger.warn("Failed to write audit log for inventory purchase add", e);
        }

        try {
            webSocketHandler.broadcast(hospitalId, "{\"type\":\"REFRESH_DATA\"}");
        } catch (Exception e) {
            logger.warn("Failed to broadcast WebSocket refresh after inventory purchase", e);
        }

        return savedPurchase;
    }

    // --- Active Stock Inventory CRUD ---

    public List<HospitalInventory> getInventoryItems() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            throw new UnauthorizedException("Hospital ID not found in context");
        }
        return hospitalInventoryRepository.findByHospitalId(hospitalId);
    }

    @Transactional
    public HospitalInventory addInventoryItem(HospitalInventory stock) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            throw new UnauthorizedException("Hospital ID not found in context");
        }

        if (hospitalInventoryRepository.existsByNameAndHospitalId(stock.getName(), hospitalId)) {
            throw new IllegalArgumentException("Item already exists in stock inventory");
        }

        // Auto-catalog if it doesn't exist
        if (!inventoryItemRepository.existsByNameAndHospitalId(stock.getName(), hospitalId)) {
            InventoryItem newCatalog = new InventoryItem();
            newCatalog.setName(stock.getName());
            newCatalog.setType(stock.getType() != null ? stock.getType() : "Consumable");
            newCatalog.setManufacturer(stock.getManufacturer());
            newCatalog.setHospitalId(hospitalId);
            inventoryItemRepository.save(newCatalog);
        }

        stock.setHospitalId(hospitalId);
        HospitalInventory saved = hospitalInventoryRepository.save(stock);

        // Audit Log
        try {
            auditLogService.logAction(
                    "INVENTORY_ITEM_RESTOCKED",
                    "Added " + saved.getName() + " to active stock inventory. Quantity: " + saved.getStockQuantity(),
                    securityHelper.getCurrentUserEmail(),
                    hospitalId,
                    "INVENTORY",
                    saved.getId().toString(),
                    null
            );
        } catch (Exception e) {
            logger.warn("Failed to write audit log for inventory item restock", e);
        }

        try {
            webSocketHandler.broadcast(hospitalId, "{\"type\":\"REFRESH_DATA\"}");
        } catch (Exception e) {
            logger.warn("Failed to broadcast WebSocket refresh after inventory restock", e);
        }

        return saved;
    }

    @Transactional
    public HospitalInventory updateInventoryItem(Long id, HospitalInventory request) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        HospitalInventory stock = hospitalInventoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Stock inventory record not found"));

        if (!stock.getHospitalId().equals(hospitalId)) {
            throw new UnauthorizedException("Unauthorized access to stock inventory");
        }

        Integer oldStock = stock.getStockQuantity();
        stock.setName(request.getName());
        stock.setStockQuantity(request.getStockQuantity());
        stock.setUnitPrice(request.getUnitPrice());
        stock.setMinStockLevel(request.getMinStockLevel());
        stock.setExpiryDate(request.getExpiryDate());
        stock.setType(request.getType());
        stock.setManufacturer(request.getManufacturer());
        if (request.getIsActive() != null) {
            stock.setIsActive(request.getIsActive());
        }

        HospitalInventory saved = hospitalInventoryRepository.save(stock);

        // Audit Log
        try {
            auditLogService.logAction(
                    "INVENTORY_ITEM_MODIFIED",
                    "Modified " + saved.getName() + " stock from " + oldStock + " to " + saved.getStockQuantity(),
                    securityHelper.getCurrentUserEmail(),
                    hospitalId,
                    "INVENTORY",
                    saved.getId().toString(),
                    null
            );
        } catch (Exception e) {
            logger.warn("Failed to write audit log for inventory item modification", e);
        }

        try {
            webSocketHandler.broadcast(hospitalId, "{\"type\":\"REFRESH_DATA\"}");
        } catch (Exception e) {
            logger.warn("Failed to broadcast WebSocket refresh after inventory update", e);
        }

        return saved;
    }

    @Transactional
    public void deleteInventoryItem(Long id) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        HospitalInventory stock = hospitalInventoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Stock inventory record not found"));

        if (!stock.getHospitalId().equals(hospitalId)) {
            throw new UnauthorizedException("Unauthorized access to stock inventory");
        }

        stock.setIsActive(false);
        hospitalInventoryRepository.save(stock);

        // Audit Log
        try {
            auditLogService.logAction(
                    "INVENTORY_ITEM_DEACTIVATED",
                    "Deactivated active stock inventory record for " + stock.getName(),
                    securityHelper.getCurrentUserEmail(),
                    hospitalId,
                    "INVENTORY",
                    stock.getId().toString(),
                    null
            );
        } catch (Exception e) {
            logger.warn("Failed to write audit log for inventory item deactivation", e);
        }

        try {
            webSocketHandler.broadcast(hospitalId, "{\"type\":\"REFRESH_DATA\"}");
        } catch (Exception e) {
            logger.warn("Failed to broadcast WebSocket refresh after inventory deactivation", e);
        }
    }



    /**
     * Validates that every relevant item of the given service has at least
     * `quantity` units of stock, then deducts `quantity` units of each
     * (FEFO by expiry) and returns the service's charge * quantity for
     * billing. Throws IllegalArgumentException("Some items are out of
     * stock: ...") if ANY relevant item is short -- deducting nothing.
     */
    @Transactional
    public java.math.BigDecimal consumeService(Long serviceId, int quantity, Long hospitalId) {
        com.hms.entity.HospitalServiceEntity svc = hospitalServiceRepository.findByIdAndHospitalId(serviceId, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Service not found: " + serviceId));

        java.util.List<com.hms.entity.HospitalServiceItem> links = hospitalServiceItemRepository.findByServiceId(serviceId);

        // Validate availability BEFORE deducting anything, then deduct FEFO.
        java.util.Map<String, java.util.List<com.hms.entity.HospitalInventory>> stocksByName =
                collectAndValidateStocks(links, quantity, hospitalId);
        deductStocksFefo(stocksByName, quantity, svc, hospitalId);

        return svc.getCharge().multiply(java.math.BigDecimal.valueOf(quantity));
    }

    /**
     * Resolves each relevant item's name -> its active stock rows, and throws
     * IllegalArgumentException("Some items are out of stock: ...") if ANY item
     * has fewer than {@code quantity} units available (deducting nothing).
     */
    private java.util.Map<String, java.util.List<com.hms.entity.HospitalInventory>> collectAndValidateStocks(
            java.util.List<com.hms.entity.HospitalServiceItem> links, int quantity, Long hospitalId) {
        java.util.List<String> shortNames = new java.util.ArrayList<>();
        java.util.Map<String, java.util.List<com.hms.entity.HospitalInventory>> stocksByName = new java.util.LinkedHashMap<>();
        for (com.hms.entity.HospitalServiceItem link : links) {
            java.util.Optional<com.hms.entity.InventoryMasterItem> masterOpt = inventoryMasterItemRepository.findById(link.getMasterItemId());
            if (!masterOpt.isPresent()) continue;
            String itemName = masterOpt.get().getName();
            java.util.List<com.hms.entity.HospitalInventory> stocks = hospitalInventoryRepository.findByNameAndHospitalIdAndIsActiveTrue(itemName, hospitalId);
            if (totalAvailable(stocks) < quantity) {
                shortNames.add(itemName);
            }
            stocksByName.put(itemName, stocks);
        }
        if (!shortNames.isEmpty()) {
            throw new IllegalArgumentException("Some items are out of stock: " + String.join(", ", shortNames));
        }
        return stocksByName;
    }

    private int totalAvailable(java.util.List<com.hms.entity.HospitalInventory> stocks) {
        int available = 0;
        for (com.hms.entity.HospitalInventory s : stocks) {
            available += (s.getStockQuantity() != null ? s.getStockQuantity() : 0);
        }
        return available;
    }

    /** FEFO: earliest expiry first; nulls (no expiry) last; tie-break by id. */
    private static int compareFefo(com.hms.entity.HospitalInventory a, com.hms.entity.HospitalInventory b) {
        if (a.getExpiryDate() == null && b.getExpiryDate() == null) return a.getId().compareTo(b.getId());
        if (a.getExpiryDate() == null) return 1;
        if (b.getExpiryDate() == null) return -1;
        return a.getExpiryDate().compareTo(b.getExpiryDate());
    }

    private void deductStocksFefo(java.util.Map<String, java.util.List<com.hms.entity.HospitalInventory>> stocksByName,
            int quantity, com.hms.entity.HospitalServiceEntity svc, Long hospitalId) {
        for (java.util.List<com.hms.entity.HospitalInventory> stocks : stocksByName.values()) {
            stocks.sort(HospitalInventoryService::compareFefo);
            deductFromStocks(stocks, quantity, svc, hospitalId);
        }
    }

    private void deductFromStocks(java.util.List<com.hms.entity.HospitalInventory> stocks,
            int quantity, com.hms.entity.HospitalServiceEntity svc, Long hospitalId) {
        int required = quantity;
        for (com.hms.entity.HospitalInventory s : stocks) {
            if (required <= 0) break;
            int avail = s.getStockQuantity() != null ? s.getStockQuantity() : 0;
            if (avail <= 0) continue;
            int toDeduct = Math.min(avail, required);
            s.setStockQuantity(avail - toDeduct);
            hospitalInventoryRepository.save(s);
            required -= toDeduct;
            logDeduction(s, toDeduct, avail, svc, hospitalId);
        }
    }

    private void logDeduction(com.hms.entity.HospitalInventory s, int toDeduct, int avail,
            com.hms.entity.HospitalServiceEntity svc, Long hospitalId) {
        try {
            auditLogService.logAction(
                "INVENTORY_DEDUCTED",
                "Deducted " + toDeduct + " units of " + s.getName() + " for service '" + svc.getName() + "'. Stock: " + avail + " -> " + s.getStockQuantity(),
                securityHelper.getCurrentUserEmail(), hospitalId, "INVENTORY", s.getId().toString(), null);
        } catch (Exception e) {
            logger.warn("Failed to write audit log for service item deduction", e);
        }
    }

    /**
     * Returns the current hospital's active stock rows at or below their
     * min stock level (used for the low-stock dashboard alert).
     */
    public java.util.List<com.hms.entity.HospitalInventory> getLowStockItems() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        java.util.List<com.hms.entity.HospitalInventory> all = hospitalInventoryRepository.findByHospitalId(hospitalId);
        java.util.List<com.hms.entity.HospitalInventory> low = new java.util.ArrayList<>();
        for (com.hms.entity.HospitalInventory s : all) {
            if (s.getIsActive() != null && !s.getIsActive()) continue;
            Integer qty = s.getStockQuantity();
            Integer min = s.getMinStockLevel();
            if (qty != null && min != null && qty <= min) {
                low.add(s);
            }
        }
        return low;
    }
}

