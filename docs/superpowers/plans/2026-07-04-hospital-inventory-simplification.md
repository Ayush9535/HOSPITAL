# Hospital Inventory Catalog Simplification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce clicks in hospital inventory catalog setup (procedure templates + duplicate action) and fix the "service item shows ₹0 / needs fake stock" problem by adding a `hasOwnStock` flag to catalog items and filtering the consultation's item search to chargeable items only.

**Architecture:** One new nullable-with-default boolean column (`has_own_stock`, default `true`) on `inventory_items` preserves 100% of existing behavior for every item already configured. A new shared `HospitalInventoryService.consumeChargeableItem(...)` method consolidates the two nearly-identical stock-deduction blocks in `DoctorService.submitConsultation` and `IpdAdmissionService.administerHospitalItems` — branching on `hasOwnStock` to skip the parent item's own stock check/decrement for service-type items, while always cascading to related items via the existing `degradeRelativeItems`. Two new read-only endpoints add procedure templates and a duplicate-prefill lookup. On the frontend, the Consultation "Items Used" search switches from browsing physical stock (today, filtered to `stockQuantity > 0`, which is why zero-stock service items silently vanish) to browsing the catalog filtered to chargeable items (`linkedFeeId != null`) — solving both the ₹0-confusion and the zero-stock-hides-the-item problem in one change.

**Tech Stack:** Spring Boot / Java 17 / Hibernate (JPA) / MySQL 8, JUnit 5 + Mockito + AssertJ for backend tests. React / Vite frontend, no test runner configured (manual build + live verification).

---

## Task 1: `InventoryItem.hasOwnStock` field + DB migration

**Files:**
- Modify: `backend/src/main/java/com/hms/entity/InventoryItem.java`
- Modify: `backend/src/main/java/com/hms/config/DatabaseMigrationRunner.java`
- Modify: `setup/schema-full.sql`

- [ ] **Step 1: Add the field to the entity**

In `backend/src/main/java/com/hms/entity/InventoryItem.java`, add after the existing `relativeItemIds` field (currently the last field before the closing brace):

```java
    /**
     * true (default) = this item has its own physical stock, tracked in
     * HospitalInventory and checked/decremented when used (e.g. an
     * injection ampule). false = this is a pure service/procedure with no
     * stock of its own (e.g. "Dressing") -- its availability is determined
     * entirely by its related items (see relativeItemIds); its own stock
     * is never checked or decremented. Defaults to true so every existing
     * catalog item keeps behaving exactly as it does today.
     */
    @Column(name = "has_own_stock", nullable = false)
    private Boolean hasOwnStock = true;
```

- [ ] **Step 2: Add the migration**

In `backend/src/main/java/com/hms/config/DatabaseMigrationRunner.java`, read the file first to find `runMigrations()`'s current full call list and confirm the exact last line, then add a new call at the very end (do not reorder or remove any existing calls):

```java
        ensurePrescriptionPresetTables();
        ensureInventoryItemHasOwnStockColumn(); // NEW
```

Add the method itself, following the exact style of other `ALTER TABLE ... ADD COLUMN` migrations already in this file (same `jdbcTemplate`/`log` fields, same `information_schema.COLUMNS` existence-check pattern):

```java
    /**
     * Adds inventory_items.has_own_stock if it does not exist, defaulting
     * every existing row to true (1) so current catalog items keep their
     * exact current behavior (own-stock check + cascade to related items)
     * until an admin explicitly marks one as a service item.
     * ddl-auto=update can add columns but not backfill a specific default
     * for pre-existing rows in every MySQL configuration -- this runner
     * makes that explicit and idempotent.
     */
    private void ensureInventoryItemHasOwnStockColumn() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'inventory_items' AND COLUMN_NAME = 'has_own_stock'",
                Integer.class
            );
            if (count != null && count == 0) {
                jdbcTemplate.execute("ALTER TABLE inventory_items ADD COLUMN has_own_stock TINYINT(1) NOT NULL DEFAULT 1");
                log.info("DB migration applied: inventory_items.has_own_stock column added (defaulted to true for existing rows)");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (inventory_items.has_own_stock): {}", e.getMessage());
        }
    }
```

- [ ] **Step 3: Update the canonical schema**

In `setup/schema-full.sql`, find the `CREATE TABLE `inventory_items`` block and add the new column (after `relative_item_ids`, matching this file's convention of appending new columns at the end of the block rather than re-sorting):

```sql
  `has_own_stock` tinyint(1) NOT NULL DEFAULT '1',
```

- [ ] **Step 4: Compile check**

Run: `cd backend && mvn -q -o clean compile`
Expected: no output = success.

- [ ] **Step 5: Commit**

```bash
cd e:/Projects/HOSPITAL
git add backend/src/main/java/com/hms/entity/InventoryItem.java backend/src/main/java/com/hms/config/DatabaseMigrationRunner.java setup/schema-full.sql
git commit -m "Add hasOwnStock flag to InventoryItem (defaults true, no behavior change)"
```

Stage ONLY these three files — do not run `git add -A` or `git add .`. There may be unrelated uncommitted changes in the working tree from other work; leave them untouched.

---

## Task 2: Shared `consumeChargeableItem` service method with tests

**Files:**
- Modify: `backend/src/main/java/com/hms/service/hospital/HospitalInventoryService.java`
- Test: `backend/src/test/java/com/hms/service/hospital/HospitalInventoryServiceConsumeTest.java` (new)

This consolidates the near-identical stock-deduction block that exists separately in `DoctorService.submitConsultation` and `IpdAdmissionService.administerHospitalItems` into one shared, testable method. There is no existing test file for `HospitalInventoryService` — this follows the same Mockito pattern used throughout this codebase (e.g. `ConsultationNotePresetServiceTest`).

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/java/com/hms/service/hospital/HospitalInventoryServiceConsumeTest.java`:

```java
package com.hms.service.hospital;

import com.hms.entity.HospitalInventory;
import com.hms.entity.InventoryItem;
import com.hms.repository.HospitalInventoryPurchaseRepository;
import com.hms.repository.HospitalInventoryRepository;
import com.hms.repository.InventoryItemRepository;
import com.hms.security.HospitalWebSocketHandler;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class HospitalInventoryServiceConsumeTest {

    @Mock HospitalInventoryRepository hospitalInventoryRepository;
    @Mock InventoryItemRepository inventoryItemRepository;
    @Mock HospitalInventoryPurchaseRepository hospitalInventoryPurchaseRepository;
    @Mock SecurityContextHelper securityHelper;
    @Mock AuditLogService auditLogService;
    @Mock HospitalWebSocketHandler webSocketHandler;

    @InjectMocks HospitalInventoryService service;

    private InventoryItem catalogItem(String name, boolean hasOwnStock, String relativeIds) {
        InventoryItem item = new InventoryItem();
        item.setName(name);
        item.setHospitalId(1L);
        item.setHasOwnStock(hasOwnStock);
        item.setRelativeItemIds(relativeIds);
        return item;
    }

    private HospitalInventory stock(Long id, String name, int qty) {
        HospitalInventory s = new HospitalInventory();
        s.setId(id);
        s.setName(name);
        s.setHospitalId(1L);
        s.setStockQuantity(qty);
        s.setIsActive(true);
        return s;
    }

    @Test
    void consumeChargeableItem_stockedItem_decrementsOwnStockAndCascades() {
        when(inventoryItemRepository.findByNameAndHospitalId("Vitamin B12 Injection", 1L))
                .thenReturn(Optional.of(catalogItem("Vitamin B12 Injection", true, "[]")));
        HospitalInventory ampuleStock = stock(10L, "Vitamin B12 Injection", 5);
        when(hospitalInventoryRepository.findByIdAndHospitalId(10L, 1L)).thenReturn(Optional.of(ampuleStock));

        HospitalInventory result = service.consumeChargeableItem(10L, "Vitamin B12 Injection", 1, 1L);

        assertThat(result).isNotNull();
        assertThat(result.getStockQuantity()).isEqualTo(4);
        verify(hospitalInventoryRepository).save(ampuleStock);
    }

    @Test
    void consumeChargeableItem_stockedItem_insufficientStock_throws() {
        when(inventoryItemRepository.findByNameAndHospitalId("Vitamin B12 Injection", 1L))
                .thenReturn(Optional.of(catalogItem("Vitamin B12 Injection", true, "[]")));
        HospitalInventory ampuleStock = stock(10L, "Vitamin B12 Injection", 0);
        when(hospitalInventoryRepository.findByIdAndHospitalId(10L, 1L)).thenReturn(Optional.of(ampuleStock));

        assertThatThrownBy(() -> service.consumeChargeableItem(10L, "Vitamin B12 Injection", 1, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Insufficient stock");
    }

    @Test
    void consumeChargeableItem_stockedItem_missingStockId_throws() {
        when(inventoryItemRepository.findByNameAndHospitalId("Vitamin B12 Injection", 1L))
                .thenReturn(Optional.of(catalogItem("Vitamin B12 Injection", true, "[]")));

        assertThatThrownBy(() -> service.consumeChargeableItem(null, "Vitamin B12 Injection", 1, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Stock ID is required");
    }

    @Test
    void consumeChargeableItem_serviceTypeItem_skipsOwnStockCheck_returnsNull() {
        when(inventoryItemRepository.findByNameAndHospitalId("Dressing", 1L))
                .thenReturn(Optional.of(catalogItem("Dressing", false, "[]")));

        HospitalInventory result = service.consumeChargeableItem(null, "Dressing", 1, 1L);

        assertThat(result).isNull();
        verify(hospitalInventoryRepository, never()).findByIdAndHospitalId(any(), any());
        verify(hospitalInventoryRepository, never()).save(any());
    }

    @Test
    void consumeChargeableItem_serviceTypeItem_stillCascadesToRelativeItems() {
        when(inventoryItemRepository.findByNameAndHospitalId("Dressing", 1L))
                .thenReturn(Optional.of(catalogItem("Dressing", false, "[2]")));
        InventoryItem cotton = catalogItem("Cotton", true, "[]");
        cotton.setId(2L);
        when(inventoryItemRepository.findById(2L)).thenReturn(Optional.of(cotton));
        HospitalInventory cottonStock = stock(20L, "Cotton", 50);
        when(hospitalInventoryRepository.findByNameAndHospitalIdAndIsActiveTrue("Cotton", 1L))
                .thenReturn(List.of(cottonStock));

        service.consumeChargeableItem(null, "Dressing", 1, 1L);

        assertThat(cottonStock.getStockQuantity()).isEqualTo(49);
        verify(hospitalInventoryRepository).save(cottonStock);
    }

    @Test
    void consumeChargeableItem_catalogItemNotFound_throws() {
        when(inventoryItemRepository.findByNameAndHospitalId("Unknown Item", 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.consumeChargeableItem(null, "Unknown Item", 1, 1L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Catalog item not found");
    }

    @Test
    void consumeChargeableItem_legacyNullHasOwnStock_treatedAsStocked() {
        InventoryItem legacyItem = catalogItem("Legacy Item", true, "[]");
        legacyItem.setHasOwnStock(null); // simulates a row from before this migration ran, if ever possible
        when(inventoryItemRepository.findByNameAndHospitalId("Legacy Item", 1L)).thenReturn(Optional.of(legacyItem));
        HospitalInventory legacyStock = stock(30L, "Legacy Item", 3);
        when(hospitalInventoryRepository.findByIdAndHospitalId(30L, 1L)).thenReturn(Optional.of(legacyStock));

        HospitalInventory result = service.consumeChargeableItem(30L, "Legacy Item", 1, 1L);

        assertThat(result).isNotNull();
        assertThat(result.getStockQuantity()).isEqualTo(2);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && mvn test -Dtest=HospitalInventoryServiceConsumeTest -q`
Expected: FAIL (compile error) — `consumeChargeableItem` doesn't exist yet, and `InventoryItem.setHasOwnStock`/`getHasOwnStock` won't exist yet either if Task 1 wasn't run first (it should already be done by this point in the plan).

- [ ] **Step 3: Implement `consumeChargeableItem`**

In `backend/src/main/java/com/hms/service/hospital/HospitalInventoryService.java`, add this new public method right after the existing `degradeRelativeItems` method (before the closing brace of the class):

```java
    /**
     * Resolves a catalog item by name and, if it's marked as having its own
     * physical stock (hasOwnStock == true, or null for legacy rows created
     * before this flag existed), validates and decrements that stock --
     * exactly the logic that used to be duplicated inline in both
     * DoctorService.submitConsultation and
     * IpdAdmissionService.administerHospitalItems. If the item is a pure
     * service (hasOwnStock == false), its own stock is never checked or
     * touched. Either way, degradeRelativeItems always runs afterward to
     * cascade to the item's linked consumables.
     *
     * @return the decremented HospitalInventory row, or null if the item is
     *         service-type (has no stock of its own to return).
     */
    @Transactional
    public HospitalInventory consumeChargeableItem(Long stockId, String itemName, int quantity, Long hospitalId) {
        InventoryItem catalogItem = inventoryItemRepository.findByNameAndHospitalId(itemName, hospitalId)
                .orElseThrow(() -> new RuntimeException("Catalog item not found: " + itemName));

        boolean hasOwnStock = catalogItem.getHasOwnStock() == null || catalogItem.getHasOwnStock();
        HospitalInventory stock = null;

        if (hasOwnStock) {
            if (stockId == null) {
                throw new IllegalArgumentException("Stock ID is required for: " + itemName);
            }
            stock = hospitalInventoryRepository.findByIdAndHospitalId(stockId, hospitalId)
                    .orElseThrow(() -> new RuntimeException("Hospital item not found in active inventory: ID " + stockId));

            if (stock.getStockQuantity() < quantity) {
                throw new IllegalArgumentException("Insufficient stock for: " + stock.getName() + " (Requested: " + quantity + ", Available: " + stock.getStockQuantity() + ")");
            }

            int oldStock = stock.getStockQuantity();
            stock.setStockQuantity(oldStock - quantity);
            hospitalInventoryRepository.save(stock);

            try {
                auditLogService.logAction(
                        "INVENTORY_DEDUCTED",
                        "Deducted " + quantity + " units of " + stock.getName() + " for patient. Stock: " + oldStock + " -> " + stock.getStockQuantity(),
                        securityHelper.getCurrentUserEmail(),
                        hospitalId,
                        "INVENTORY",
                        stock.getId().toString(),
                        null
                );
            } catch (Exception e) {
                logger.warn("Failed to write audit log for chargeable item deduction", e);
            }
        }

        try {
            degradeRelativeItems(itemName, quantity, hospitalId);
        } catch (Exception e) {
            logger.warn("Failed to degrade relative inventory items for: " + itemName, e);
        }

        return stock;
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && mvn test -Dtest=HospitalInventoryServiceConsumeTest -q`
Expected: PASS (7 tests)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/hms/service/hospital/HospitalInventoryService.java backend/src/test/java/com/hms/service/hospital/HospitalInventoryServiceConsumeTest.java
git commit -m "Add HospitalInventoryService.consumeChargeableItem with hasOwnStock branching"
```

---

## Task 3: Wire `consumeChargeableItem` into OPD consultation submission

**Files:**
- Modify: `backend/src/main/java/com/hms/dto/ConsultationRequest.java`
- Modify: `backend/src/main/java/com/hms/service/hospital/DoctorService.java`

This task makes `stockId` optional in the OPD request DTO and replaces the inline stock-deduction block with a call to the new shared method. **No test file** for this task — `DoctorService.submitConsultation` is a large, deeply-wired method with no existing test harness in this codebase; this change is verified via the live-DB verification task (Task 6) instead, consistent with how this exact codebase's other OPD-submission changes have been verified in the past (no unit test infrastructure exists for this specific method).

- [ ] **Step 1: Make `stockId` optional in the DTO**

In `backend/src/main/java/com/hms/dto/ConsultationRequest.java`, find the `HospitalInventoryItem` inner class:

```java
    public static class HospitalInventoryItem {
        @NotNull(message = "Stock ID is required")
        private Long stockId;

        @NotBlank(message = "Item name is required")
        private String name;

        @NotNull(message = "Quantity is required")
        @Min(value = 1, message = "Quantity must be positive")
        private Integer quantity;
    }
```

Replace with (removes the `@NotNull` on `stockId` — service-type items send no stockId at all; `consumeChargeableItem` itself throws a clear error if a stocked item is missing one):

```java
    public static class HospitalInventoryItem {
        private Long stockId;

        @NotBlank(message = "Item name is required")
        private String name;

        @NotNull(message = "Quantity is required")
        @Min(value = 1, message = "Quantity must be positive")
        private Integer quantity;
    }
```

- [ ] **Step 2: Replace the inline stock-deduction block**

In `backend/src/main/java/com/hms/service/hospital/DoctorService.java`, find this exact block (currently around lines 771-827):

```java
            // --- Process Hospital Inventory Items Used (Stock Deductions) ---
            if (bill != null && request.getHospitalInventoryItems() != null && !request.getHospitalInventoryItems().isEmpty()) {
                for (com.hms.dto.ConsultationRequest.HospitalInventoryItem item : request.getHospitalInventoryItems()) {
                    if (item.getStockId() != null) {
                        com.hms.entity.HospitalInventory stock = hospitalInventoryRepository.findByIdAndHospitalId(item.getStockId(), hospitalId)
                            .orElseThrow(() -> new RuntimeException("Hospital item not found in active inventory: ID " + item.getStockId()));

                        if (stock.getStockQuantity() < item.getQuantity()) {
                            throw new IllegalArgumentException("Insufficient stock for: " + stock.getName() + " (Requested: " + item.getQuantity() + ", Available: " + stock.getStockQuantity() + ")");
                        }

                        // Deduct Stock
                        int oldStock = stock.getStockQuantity();
                        stock.setStockQuantity(oldStock - item.getQuantity());
                        hospitalInventoryRepository.save(stock);

                        // Degrade any relative catalog items
                        try {
                            hospitalInventoryService.degradeRelativeItems(stock.getName(), item.getQuantity(), hospitalId);
                        } catch (Exception e) {
                            logger.warn("Failed to degrade relative inventory items during OPD consultation", e);
                        }

                        // Audit Log for Stock deduction
                        try {
                            auditLogService.logAction(
                                "INVENTORY_DEDUCTED",
                                "Deducted " + item.getQuantity() + " units of " + stock.getName() + " for patient. Stock: " + oldStock + " -> " + stock.getStockQuantity(),
                                securityHelper.getCurrentUserEmail(),
                                hospitalId,
                                "INVENTORY",
                                stock.getId().toString(),
                                null
                            );
                        } catch (Exception e) {
                            logger.warn("Failed to write audit log for OPD hospital item deduction", e);
                        }

                        // Create BillingItem charge (only if it does not have a linked custom fee catalog mapping)
                        boolean hasLinkedFee = false;
                        java.util.Optional<com.hms.entity.InventoryItem> catalogItemOpt = inventoryItemRepository.findByNameAndHospitalId(stock.getName(), hospitalId);
                        if (catalogItemOpt.isPresent() && catalogItemOpt.get().getLinkedFeeId() != null) {
                            hasLinkedFee = true;
                        }

                        if (!hasLinkedFee) {
                            com.hms.entity.BillingItem bi = new com.hms.entity.BillingItem();
                            bi.setBillingId(bill.getId());
                            bi.setHospitalId(hospitalId);
                            bi.setDescription(stock.getName() + " (Qty: " + item.getQuantity() + ")");
                            double price = stock.getUnitPrice() != null ? stock.getUnitPrice() : 0.0;
                            bi.setAmount(java.math.BigDecimal.valueOf(price).multiply(java.math.BigDecimal.valueOf(item.getQuantity())));
                            billingItemRepository.save(bi);
                        }
                    }
                }
            }
```

Replace with (delegates stock resolution/decrementing/cascading to the shared method; keeps the exact same billing-item fallback logic, just using `item.getName()` instead of `stock.getName()` so it works whether or not a physical stock row exists):

```java
            // --- Process Hospital Inventory Items Used (Stock Deductions) ---
            if (bill != null && request.getHospitalInventoryItems() != null && !request.getHospitalInventoryItems().isEmpty()) {
                for (com.hms.dto.ConsultationRequest.HospitalInventoryItem item : request.getHospitalInventoryItems()) {
                    com.hms.entity.HospitalInventory stock = hospitalInventoryService.consumeChargeableItem(
                            item.getStockId(), item.getName(), item.getQuantity(), hospitalId);

                    // Create BillingItem charge (only if it does not have a linked custom fee catalog mapping)
                    boolean hasLinkedFee = false;
                    java.util.Optional<com.hms.entity.InventoryItem> catalogItemOpt = inventoryItemRepository.findByNameAndHospitalId(item.getName(), hospitalId);
                    if (catalogItemOpt.isPresent() && catalogItemOpt.get().getLinkedFeeId() != null) {
                        hasLinkedFee = true;
                    }

                    if (!hasLinkedFee) {
                        com.hms.entity.BillingItem bi = new com.hms.entity.BillingItem();
                        bi.setBillingId(bill.getId());
                        bi.setHospitalId(hospitalId);
                        bi.setDescription(item.getName() + " (Qty: " + item.getQuantity() + ")");
                        double price = stock != null && stock.getUnitPrice() != null ? stock.getUnitPrice() : 0.0;
                        bi.setAmount(java.math.BigDecimal.valueOf(price).multiply(java.math.BigDecimal.valueOf(item.getQuantity())));
                        billingItemRepository.save(bi);
                    }
                }
            }
```

- [ ] **Step 3: Compile check**

Run: `cd backend && mvn -q -o clean compile`
Expected: no output = success.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/hms/dto/ConsultationRequest.java backend/src/main/java/com/hms/service/hospital/DoctorService.java
git commit -m "Use consumeChargeableItem in OPD consultation submission"
```

---

## Task 4: Wire `consumeChargeableItem` into IPD item administration

**Files:**
- Modify: `backend/src/main/java/com/hms/service/hospital/IpdAdmissionService.java`

`AdministerHospitalItemsRequest.HospitalItem.stockId` is already nullable today (confirmed — no `@NotNull` annotation), so no DTO change is needed here, only the service logic. **No test file** for this task, same reasoning as Task 3 — verified via Task 6's live-DB check.

- [ ] **Step 1: Replace the inline stock-deduction block**

In `backend/src/main/java/com/hms/service/hospital/IpdAdmissionService.java`, find this exact block (currently around lines 739-788, inside `administerHospitalItems`):

```java
        if (items != null && !items.isEmpty()) {
            for (com.hms.dto.AdministerHospitalItemsRequest.HospitalItem item : items) {
                if (item.getStockId() != null) {
                    com.hms.entity.HospitalInventory stock = hospitalInventoryRepository.findById(item.getStockId())
                            .orElseThrow(() -> new RuntimeException("Hospital item not found in active inventory: ID " + item.getStockId()));

                    if (stock.getStockQuantity() < item.getQuantity()) {
                        throw new IllegalArgumentException("Insufficient stock for: " + stock.getName() + " (Requested: " + item.getQuantity() + ", Available: " + stock.getStockQuantity() + ")");
                    }

                    // Deduct Stock
                    int oldStock = stock.getStockQuantity();
                    stock.setStockQuantity(oldStock - item.getQuantity());
                    hospitalInventoryRepository.save(stock);

                    // Degrade relative items
                    try {
                        hospitalInventoryService.degradeRelativeItems(stock.getName(), item.getQuantity(), hospitalId);
                    } catch (Exception e) {
                        logger.warn("Failed to degrade relative inventory items during hospital item administration", e);
                    }

                    // Audit Log for Stock deduction
                    try {
                        auditLogService.logAction(
                                "INVENTORY_DEDUCTED",
                                "Deducted " + item.getQuantity() + " units of " + stock.getName() + " for patient. Stock: " + oldStock + " -> " + stock.getStockQuantity(),
                                securityHelper.getCurrentUserEmail(),
                                hospitalId,
                                "INVENTORY",
                                stock.getId().toString(),
                                null
                        );
                    } catch (Exception e) {
                        logger.warn("Failed to write audit log for hospital item deduction", e);
                    }

                    if (hasBillingModule && ipdBill != null) {
                        // Create BillingItem charge
                        com.hms.entity.BillingItem bi = new com.hms.entity.BillingItem();
                        bi.setBillingId(ipdBill.getId());
                        bi.setHospitalId(hospitalId);
                        bi.setDescription(stock.getName() + " (Qty: " + item.getQuantity() + ")");
                        java.math.BigDecimal unitPrice = item.getFeeAmount() != null ? item.getFeeAmount() :
                                (stock.getUnitPrice() != null ? java.math.BigDecimal.valueOf(stock.getUnitPrice()) : java.math.BigDecimal.ZERO);
                        bi.setAmount(unitPrice.multiply(java.math.BigDecimal.valueOf(item.getQuantity())));
                        billingItemRepository.save(bi);
                    }
                }
            }
```

Replace with (delegates to the shared method; note this also closes a small pre-existing gap where the old code used an un-scoped `hospitalInventoryRepository.findById` instead of hospital-scoped lookup — `consumeChargeableItem` always scopes by `hospitalId`, which is strictly safer and is a direct, minimal consequence of reusing the shared method, not a separate change):

```java
        if (items != null && !items.isEmpty()) {
            for (com.hms.dto.AdministerHospitalItemsRequest.HospitalItem item : items) {
                com.hms.entity.HospitalInventory stock = hospitalInventoryService.consumeChargeableItem(
                        item.getStockId(), item.getName(), item.getQuantity(), hospitalId);

                if (hasBillingModule && ipdBill != null) {
                    // Create BillingItem charge
                    com.hms.entity.BillingItem bi = new com.hms.entity.BillingItem();
                    bi.setBillingId(ipdBill.getId());
                    bi.setHospitalId(hospitalId);
                    bi.setDescription(item.getName() + " (Qty: " + item.getQuantity() + ")");
                    java.math.BigDecimal unitPrice = item.getFeeAmount() != null ? item.getFeeAmount() :
                            (stock != null && stock.getUnitPrice() != null ? java.math.BigDecimal.valueOf(stock.getUnitPrice()) : java.math.BigDecimal.ZERO);
                    bi.setAmount(unitPrice.multiply(java.math.BigDecimal.valueOf(item.getQuantity())));
                    billingItemRepository.save(bi);
                }
            }
```

If the exact surrounding brace/loop structure differs slightly from what's quoted (e.g. the closing braces after this block), leave everything outside the quoted lines untouched — only the content shown above changes.

- [ ] **Step 2: Compile check**

Run: `cd backend && mvn -q -o clean compile`
Expected: no output = success.

- [ ] **Step 3: Run full backend test suite**

Run: `cd backend && mvn test -q`
Expected: BUILD SUCCESS, all tests pass (should be 109 tests: the 102 from before this feature plus the 7 new `HospitalInventoryServiceConsumeTest` tests).

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/hms/service/hospital/IpdAdmissionService.java
git commit -m "Use consumeChargeableItem in IPD item administration"
```

---

## Task 5: Templates + Duplicate read-only endpoints

**Files:**
- Modify: `backend/src/main/java/com/hms/controller/hospital/HospitalInventoryController.java`
- Modify: `backend/src/main/java/com/hms/service/hospital/HospitalInventoryService.java`
- Test: `backend/src/test/java/com/hms/service/hospital/HospitalInventoryServiceTemplateTest.java` (new)

Both endpoints are read-only and additive — they don't create or modify any data. The template list is a fixed, hardcoded starter set (not a database table — it's a static reference list, not per-hospital editable data, per the design spec's explicit scope decision).

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/java/com/hms/service/hospital/HospitalInventoryServiceTemplateTest.java`:

```java
package com.hms.service.hospital;

import com.hms.dto.InventoryTemplateDTO;
import com.hms.entity.InventoryItem;
import com.hms.repository.HospitalInventoryPurchaseRepository;
import com.hms.repository.HospitalInventoryRepository;
import com.hms.repository.InventoryItemRepository;
import com.hms.security.HospitalWebSocketHandler;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HospitalInventoryServiceTemplateTest {

    @Mock HospitalInventoryRepository hospitalInventoryRepository;
    @Mock InventoryItemRepository inventoryItemRepository;
    @Mock HospitalInventoryPurchaseRepository hospitalInventoryPurchaseRepository;
    @Mock SecurityContextHelper securityHelper;
    @Mock AuditLogService auditLogService;
    @Mock HospitalWebSocketHandler webSocketHandler;

    @InjectMocks HospitalInventoryService service;

    @Test
    void getCatalogTemplates_returnsNonEmptyFixedList() {
        List<InventoryTemplateDTO> templates = service.getCatalogTemplates();

        assertThat(templates).isNotEmpty();
        assertThat(templates).anySatisfy(t -> assertThat(t.getName()).isEqualTo("Dressing (Small)"));
    }

    @Test
    void getCatalogTemplates_dressingTemplate_isServiceType() {
        List<InventoryTemplateDTO> templates = service.getCatalogTemplates();

        InventoryTemplateDTO dressing = templates.stream()
                .filter(t -> "Dressing (Small)".equals(t.getName()))
                .findFirst()
                .orElseThrow();

        assertThat(dressing.getHasOwnStock()).isFalse();
        assertThat(dressing.getSuggestedRelativeItemNames()).contains("Cotton", "Bandage");
    }

    @Test
    void getCatalogTemplates_injectionTemplate_isStockedType() {
        List<InventoryTemplateDTO> templates = service.getCatalogTemplates();

        InventoryTemplateDTO injection = templates.stream()
                .filter(t -> "Injection".equals(t.getName()))
                .findFirst()
                .orElseThrow();

        assertThat(injection.getHasOwnStock()).isTrue();
    }

    @Test
    void duplicateCatalogItem_returnsSourceItemFields() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);
        InventoryItem source = new InventoryItem();
        source.setId(5L);
        source.setName("Dressing (Small)");
        source.setType("Consumable");
        source.setHasOwnStock(false);
        source.setLinkedFeeId(42L);
        source.setRelativeItemIds("[1,2]");
        source.setHospitalId(1L);
        when(inventoryItemRepository.findByIdAndHospitalId(5L, 1L)).thenReturn(Optional.of(source));

        InventoryItem result = service.duplicateCatalogItem(5L);

        assertThat(result.getName()).isEqualTo("Dressing (Small)");
        assertThat(result.getHasOwnStock()).isFalse();
        assertThat(result.getLinkedFeeId()).isEqualTo(42L);
        assertThat(result.getRelativeItemIds()).isEqualTo("[1,2]");
    }

    @Test
    void duplicateCatalogItem_notFoundForHospital_throws() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);
        when(inventoryItemRepository.findByIdAndHospitalId(99L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.duplicateCatalogItem(99L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("not found");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && mvn test -Dtest=HospitalInventoryServiceTemplateTest -q`
Expected: FAIL (compile error) — `InventoryTemplateDTO`, `getCatalogTemplates`, `duplicateCatalogItem` don't exist yet.

- [ ] **Step 3: Create the `InventoryTemplateDTO`**

Create `backend/src/main/java/com/hms/dto/InventoryTemplateDTO.java`:

```java
package com.hms.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class InventoryTemplateDTO {
    private String name;
    private String type;
    private Boolean hasOwnStock;
    private List<String> suggestedRelativeItemNames;
}
```

- [ ] **Step 4: Implement `getCatalogTemplates` and `duplicateCatalogItem`**

In `backend/src/main/java/com/hms/service/hospital/HospitalInventoryService.java`, add these two methods right after `consumeChargeableItem` (before the closing brace of the class):

```java
    /**
     * Fixed, hardcoded starter list of common procedure templates (not a
     * database table -- this is a static reference list any hospital can
     * use as a one-click starting point for the catalog item form, not
     * per-hospital editable data). Suggested related-item names are just
     * search hints for the existing name-based related-items picker, not
     * IDs -- matching a hospital's actual stock still happens by name
     * search, same as manual catalog entry today.
     */
    public java.util.List<InventoryTemplateDTO> getCatalogTemplates() {
        return java.util.List.of(
            new InventoryTemplateDTO("Injection", "Surgical", true, java.util.List.of("Syringe 5ml", "Spirit Swab", "Cotton")),
            new InventoryTemplateDTO("Dressing (Small)", "Consumable", false, java.util.List.of("Cotton", "Bandage")),
            new InventoryTemplateDTO("Dressing (Large)", "Consumable", false, java.util.List.of("Cotton", "Bandage", "Adhesive Tape")),
            new InventoryTemplateDTO("IV Cannula", "Surgical", true, java.util.List.of("IV Cannula Set", "Spirit Swab", "Adhesive Tape")),
            new InventoryTemplateDTO("Nebulization", "Equipment", false, java.util.List.of("Nebulizer Mask", "Saline")),
            new InventoryTemplateDTO("Suturing", "Surgical", false, java.util.List.of("Suture Kit", "Cotton", "Spirit Swab")),
            new InventoryTemplateDTO("Catheterization", "Surgical", true, java.util.List.of("Foley Catheter", "Urine Bag", "Spirit Swab"))
        );
    }

    /**
     * Returns the source item's field values so the frontend can prefill a
     * new catalog item form with them (name left for the admin to change).
     * Does not persist anything -- purely a read for prefill convenience.
     */
    public InventoryItem duplicateCatalogItem(Long id) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        return inventoryItemRepository.findByIdAndHospitalId(id, hospitalId)
                .orElseThrow(() -> new RuntimeException("Catalog item not found"));
    }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd backend && mvn test -Dtest=HospitalInventoryServiceTemplateTest -q`
Expected: PASS (5 tests)

- [ ] **Step 6: Add the controller endpoints**

In `backend/src/main/java/com/hms/controller/hospital/HospitalInventoryController.java`, add two new endpoints right after the existing `deleteCatalogItem` endpoint (before the `// --- Purchase History Management ---` comment):

```java
    @GetMapping("/catalog/templates")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR', 'RECEPTIONIST')")
    public ResponseEntity<List<com.hms.dto.InventoryTemplateDTO>> getCatalogTemplates() {
        return ResponseEntity.ok(hospitalInventoryService.getCatalogTemplates());
    }

    @GetMapping("/catalog/{id}/duplicate")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR', 'RECEPTIONIST')")
    public ResponseEntity<InventoryItem> duplicateCatalogItem(@PathVariable Long id) {
        return ResponseEntity.ok(hospitalInventoryService.duplicateCatalogItem(id));
    }
```

- [ ] **Step 7: Compile check and full test suite**

Run: `cd backend && mvn -q -o clean compile && mvn test -q`
Expected: both succeed, no output on compile, full test suite passes (should be 114 tests now: 109 from before this task plus 5 new).

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/hms/dto/InventoryTemplateDTO.java backend/src/main/java/com/hms/service/hospital/HospitalInventoryService.java backend/src/main/java/com/hms/controller/hospital/HospitalInventoryController.java backend/src/test/java/com/hms/service/hospital/HospitalInventoryServiceTemplateTest.java
git commit -m "Add procedure templates and catalog item duplicate endpoints"
```

---

## Task 6: Backend live verification against a real database

No new automated test — verifies the migration and the full consumeChargeableItem/templates/duplicate flow against the actual dev database.

**Files:** none (verification only)

- [ ] **Step 1: Restart the backend**

Stop whatever backend process is currently running (`netstat -ano | grep :8080`, then stop that PID), then:

```bash
cd backend && (mvn -q spring-boot:run > /tmp/inv-simplify-verify.log 2>&1 &)
```

Wait for `Started HospitalManagementSystemApplication`, then check:

```bash
grep "has_own_stock" /tmp/inv-simplify-verify.log
```

Expected: `DB migration applied: inventory_items.has_own_stock column added...` (or no output if it already exists from a prior run).

- [ ] **Step 2: Verify the schema directly**

```bash
mysql -u root -p -D <db_name> -e "DESCRIBE inventory_items;"
```

Expected: `has_own_stock` column present, `tinyint(1)`, default `1`.

```bash
mysql -u root -p -D <db_name> -e "SELECT id, name, has_own_stock FROM inventory_items LIMIT 5;"
```

Expected: every existing row shows `has_own_stock = 1`.

- [ ] **Step 3: Verify templates and duplicate endpoints**

Craft a JWT for a test hospital admin (HS256, `JWT_SECRET` from `backend/.env`, matching this project's established pattern).

```bash
curl -s "http://localhost:8080/hospital/hospital-inventory/catalog/templates" -H "Authorization: Bearer <token>"
```

Expected: `200 OK`, JSON array of 7 templates including `"name":"Dressing (Small)"` with `"hasOwnStock":false`.

Create a catalog item to duplicate:

```bash
curl -s -X POST "http://localhost:8080/hospital/hospital-inventory/catalog" -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"name":"Dressing Test","type":"Consumable","hasOwnStock":false,"relativeItemIds":"[]"}'
```

Note the returned `id`, then:

```bash
curl -s "http://localhost:8080/hospital/hospital-inventory/catalog/<id>/duplicate" -H "Authorization: Bearer <token>"
```

Expected: `200 OK`, returns the same field values (`name`, `hasOwnStock: false`, etc.).

- [ ] **Step 4: Verify `consumeChargeableItem` end-to-end for a service-type item**

Set up: create two related consumable catalog items with real stock (via the purchase endpoint, e.g. "Cotton" and "Bandage" with quantity 50 each), then create a "Dressing Test 2" catalog item with `hasOwnStock: false` and `relativeItemIds` referencing Cotton/Bandage's catalog IDs, with a `linkedFeeId` pointing at an existing fee.

Submit an OPD consultation (via `POST /hospital/doctors/consultation` or whatever the existing submit-consultation endpoint is, matching a real appointment/OPD id in the dev DB) with a `hospitalInventoryItems` entry: `{"name": "Dressing Test 2", "quantity": 1}` — **no `stockId`**.

Expected: `200 OK` (previously would have required a `stockId` and thrown a validation error). Then:

```bash
mysql -u root -p -D <db_name> -e "SELECT name, stock_quantity FROM hospital_inventory WHERE name IN ('Cotton','Bandage');"
```

Expected: both dropped by 1 from their pre-submission quantities. Clean up any test catalog items/patients/OPD records created during this verification, per this project's established cleanup discipline.

- [ ] **Step 5: Verify full backend test suite one more time**

```bash
cd backend && mvn test -q
```

Expected: all 114 tests pass.

---

## Task 7: Frontend — `hospitalService.js` API functions

**Files:**
- Modify: `frontend/src/services/hospitalService.js`

- [ ] **Step 1: Add the two functions**

In `frontend/src/services/hospitalService.js`, add these two functions immediately after the existing `deleteHospitalInventoryCatalog` function:

```javascript
    getCatalogTemplates: async () => {
        const response = await apiClient.get('/hospital/hospital-inventory/catalog/templates');
        return response.data;
    },

    duplicateCatalogItem: async (id) => {
        const response = await apiClient.get(`/hospital/hospital-inventory/catalog/${id}/duplicate`);
        return response.data;
    },
```

- [ ] **Step 2: Verify no syntax errors**

Run: `cd frontend && npx tsc --noEmit`
Expected: no output = success.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/services/hospitalService.js
git commit -m "Add hospital inventory template and duplicate API functions"
```

---

## Task 8: Frontend — `hasOwnStock` toggle in the catalog item form

**Files:**
- Modify: `frontend/src/components/HospitalInventoryTab.jsx`

- [ ] **Step 1: Add state and prefill logic**

Find the existing `useEffect` that resets `selectedRelativeItems` when `catalogModal` opens (search for `if (catalogModal.isOpen) {`), and add a new state near the top of the component alongside `selectedRelativeItems` (search for `const [selectedRelativeItems, setSelectedRelativeItems] = useState([]);`):

```javascript
    const [hasOwnStock, setHasOwnStock] = useState(true);
```

In the existing `useEffect` for `catalogModal.isOpen` (the one that parses `relativeItemIds`), add prefill logic for the new field — find:

```javascript
    useEffect(() => {
        if (catalogModal.isOpen) {
            if (catalogModal.isEdit && catalogModal.data) {
                try {
                    const ids = JSON.parse(catalogModal.data.relativeItemIds || '[]');
                    const matched = catalogList.filter(x => ids.includes(x.id)).map(x => ({ id: x.id, name: x.name }));
                    setSelectedRelativeItems(matched);
                } catch (e) {
                    setSelectedRelativeItems([]);
                }
            } else {
                setSelectedRelativeItems([]);
            }
            setRelativeItemSearch('');
            setShowRelativeSuggestions(false);
        }
    }, [catalogModal.isOpen, catalogModal.isEdit, catalogModal.data, catalogList]);
```

Replace with (adds one line setting `hasOwnStock` from the item being edited, defaulting to `true` for new items, matching the backend's default):

```javascript
    useEffect(() => {
        if (catalogModal.isOpen) {
            if (catalogModal.isEdit && catalogModal.data) {
                try {
                    const ids = JSON.parse(catalogModal.data.relativeItemIds || '[]');
                    const matched = catalogList.filter(x => ids.includes(x.id)).map(x => ({ id: x.id, name: x.name }));
                    setSelectedRelativeItems(matched);
                } catch (e) {
                    setSelectedRelativeItems([]);
                }
                setHasOwnStock(catalogModal.data.hasOwnStock !== false);
            } else {
                setSelectedRelativeItems([]);
                setHasOwnStock(true);
            }
            setRelativeItemSearch('');
            setShowRelativeSuggestions(false);
        }
    }, [catalogModal.isOpen, catalogModal.isEdit, catalogModal.data, catalogList]);
```

- [ ] **Step 2: Add the toggle to the form JSX**

Find the "Linked Charge / Fee" field block (search for `Linked Charge / Fee`), and add the new toggle immediately after it, before the "Relative Items" section:

```jsx
                            <div>
                                <label className="block text-sm font-semibold text-gray-700 mb-1">Linked Charge / Fee</label>
                                <select
                                    name="linkedFeeId"
                                    defaultValue={catalogModal.data?.linkedFeeId || ''}
                                    className="w-full border border-gray-300 rounded-lg px-4 py-2 focus:ring-2 focus:ring-teal-500 focus:border-transparent outline-none bg-white text-gray-800"
                                >
                                     <option value="">-- No charge linked --</option>
                                     {availableFees.map(fee => (
                                         <option key={fee.id} value={fee.id}>{fee.displayName || fee.name}</option>
                                     ))}
                                 </select>
                                 <p className="text-xs text-gray-400 mt-1">Link a custom fee from the Fees tab. When this item is used in a consultation/IPD, the linked fee will be auto-applied to the bill.</p>
                            </div>
```

Add immediately after this closing `</div>`:

```jsx
                            <div>
                                <label className="block text-sm font-semibold text-gray-700 mb-1">Stock Type</label>
                                <div className="flex gap-4">
                                    <label className="flex items-center gap-2 text-sm text-gray-700">
                                        <input
                                            type="radio"
                                            name="hasOwnStock"
                                            checked={hasOwnStock === true}
                                            onChange={() => setHasOwnStock(true)}
                                        />
                                        Stocked — has its own physical quantity
                                    </label>
                                    <label className="flex items-center gap-2 text-sm text-gray-700">
                                        <input
                                            type="radio"
                                            name="hasOwnStock"
                                            checked={hasOwnStock === false}
                                            onChange={() => setHasOwnStock(false)}
                                        />
                                        Service — stock comes from related items
                                    </label>
                                </div>
                                <p className="text-xs text-gray-400 mt-1">Choose "Service" for procedures like Dressing that aren't purchased in units themselves — their availability is determined entirely by the related items below.</p>
                            </div>
```

- [ ] **Step 3: Include `hasOwnStock` in the submit payload**

Find `handleCatalogSubmit` (search for `const handleCatalogSubmit = async (e) => {`):

```javascript
        const payload = {
            name,
            type,
            manufacturer: manufacturer ? manufacturer : null,
            // Parse as number (custom fee ID is a Long in DB); null if empty/invalid
            linkedFeeId: linkedFeeId && !isNaN(linkedFeeId) ? Number(linkedFeeId) : null,
            relativeItemIds: JSON.stringify(selectedRelativeItems.map(x => x.id))
        };
```

Replace with:

```javascript
        const payload = {
            name,
            type,
            manufacturer: manufacturer ? manufacturer : null,
            // Parse as number (custom fee ID is a Long in DB); null if empty/invalid
            linkedFeeId: linkedFeeId && !isNaN(linkedFeeId) ? Number(linkedFeeId) : null,
            relativeItemIds: JSON.stringify(selectedRelativeItems.map(x => x.id)),
            hasOwnStock
        };
```

- [ ] **Step 4: Show the stock type in the catalog list table**

Find the catalog list table header (search for `<th className="pb-3 text-left">Linked Charge</th>`):

```jsx
                                <th className="pb-3 text-left">Linked Charge</th>
```

Add a new column immediately after:

```jsx
                                <th className="pb-3 text-left">Linked Charge</th>
                                <th className="pb-3 text-center">Stock Type</th>
```

Find the corresponding row cell (search for the `<td>` containing `item.linkedFeeId ? (` inside the catalog `.map`), and add a matching cell immediately after that `</td>`:

```jsx
                                    <td className="py-3 text-center">
                                        {item.hasOwnStock === false ? (
                                            <span className="px-2 py-0.5 text-xs bg-indigo-50 text-indigo-700 border border-indigo-100 rounded-full font-medium">Service</span>
                                        ) : (
                                            <span className="px-2 py-0.5 text-xs bg-slate-100 text-gray-600 rounded-full font-medium">Stocked</span>
                                        )}
                                    </td>
```

Also update the empty-state `colSpan` from `5` to `6` (search for `colSpan={5}` within the catalog list's empty-state row) to account for the new column.

- [ ] **Step 5: Verify no syntax errors**

Run: `cd frontend && npx tsc --noEmit`
Expected: no output = success.

- [ ] **Step 6: Full build**

Run: `cd frontend && npx vite build --mode development`
Expected: `✓ built` with no errors.

- [ ] **Step 7: Commit**

```bash
cd e:/Projects/HOSPITAL
git add frontend/src/components/HospitalInventoryTab.jsx
git commit -m "Add hasOwnStock toggle to catalog item form and list"
```

Stage ONLY this one file.

---

## Task 9: Frontend — "Add from Template" picker

**Files:**
- Modify: `frontend/src/components/HospitalInventoryTab.jsx`

- [ ] **Step 1: Add state and fetch logic**

Add new state alongside `hasOwnStock` (added in Task 8):

```javascript
    const [templates, setTemplates] = useState([]);
    const [templatePickerOpen, setTemplatePickerOpen] = useState(false);
```

In `fetchCatalog` (search for `const fetchCatalog = async () => {`), leave it unchanged, but add a new fetch function right after it:

```javascript
    const fetchTemplates = async () => {
        try {
            const res = await hospitalService.getCatalogTemplates();
            setTemplates(res || []);
        } catch (err) {
            console.error('Failed to load inventory templates', err);
        }
    };
```

In `loadData` (search for `const loadData = async () => {`), find the `else` branch that loads catalog data for the catalog sub-tab:

```javascript
            } else {
                await Promise.all([fetchCatalog(), fetchFees()]);
            }
```

Replace with:

```javascript
            } else {
                await Promise.all([fetchCatalog(), fetchFees(), fetchTemplates()]);
            }
```

- [ ] **Step 2: Add the "Add from Template" button and picker**

Find the "+ Add Catalog Item" button (search for `+ Add Catalog Item`):

```jsx
                {subTab === 'catalog' && (
                    <button
                        onClick={() => setCatalogModal({ isOpen: true, isEdit: false, data: null })}
                        className="px-4 py-2 bg-teal-600 text-white rounded-lg hover:bg-teal-700 transition font-semibold text-sm shadow-md shadow-teal-600/10 active:scale-95"
                    >
                        + Add Catalog Item
                    </button>
                )}
```

Replace with (adds a second button that opens the template picker):

```jsx
                {subTab === 'catalog' && (
                    <div className="flex gap-2">
                        <button
                            onClick={() => setTemplatePickerOpen(true)}
                            className="px-4 py-2 border border-teal-600 text-teal-700 rounded-lg hover:bg-teal-50 transition font-semibold text-sm active:scale-95"
                        >
                            Add from Template
                        </button>
                        <button
                            onClick={() => setCatalogModal({ isOpen: true, isEdit: false, data: null })}
                            className="px-4 py-2 bg-teal-600 text-white rounded-lg hover:bg-teal-700 transition font-semibold text-sm shadow-md shadow-teal-600/10 active:scale-95"
                        >
                            + Add Catalog Item
                        </button>
                    </div>
                )}
```

- [ ] **Step 3: Add the picker modal and prefill logic**

Add this new modal right before "MODAL 2: ADD/EDIT CATALOG DICTIONARY ITEM" (search for `{/* MODAL 2: ADD/EDIT CATALOG DICTIONARY ITEM */}`):

```jsx
            {/* TEMPLATE PICKER */}
            {templatePickerOpen && (
                <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50 p-4" onClick={() => setTemplatePickerOpen(false)}>
                    <div className="bg-white rounded-xl shadow-2xl w-full max-w-md overflow-hidden animate-fade-in-up" onClick={e => e.stopPropagation()}>
                        <div className="p-6 border-b border-gray-100 flex justify-between items-center bg-slate-50">
                            <h3 className="text-lg font-bold text-gray-800">Add from Template</h3>
                            <button onClick={() => setTemplatePickerOpen(false)} className="text-gray-400 hover:text-gray-600">
                                <svg className="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" /></svg>
                            </button>
                        </div>
                        <div className="p-4 divide-y divide-gray-100 max-h-96 overflow-y-auto">
                            {templates.map((t, i) => (
                                <button
                                    key={i}
                                    type="button"
                                    onClick={() => {
                                        const matchedRelativeItems = t.suggestedRelativeItemNames
                                            .map(suggestedName => catalogList.find(c => c.isActive !== false && c.name.toLowerCase() === suggestedName.toLowerCase()))
                                            .filter(Boolean)
                                            .map(c => ({ id: c.id, name: c.name }));
                                        setSelectedRelativeItems(matchedRelativeItems);
                                        setHasOwnStock(t.hasOwnStock !== false);
                                        setCatalogModal({ isOpen: true, isEdit: false, data: { name: t.name, type: t.type } });
                                        setTemplatePickerOpen(false);
                                    }}
                                    className="w-full text-left px-2 py-3 hover:bg-slate-50 transition"
                                >
                                    <div className="font-semibold text-gray-800 text-sm">{t.name}</div>
                                    <div className="text-xs text-gray-400 mt-0.5">Suggests: {t.suggestedRelativeItemNames.join(', ')}</div>
                                </button>
                            ))}
                        </div>
                    </div>
                </div>
            )}
```

Note: this sets `catalogModal.data` to `{ name: t.name, type: t.type }` (no `id`, and `isEdit: false`) — so the existing catalog form's `defaultValue={catalogModal.data?.name || ''}` prefills the name/type, while submission still goes through `addCatalogItem` (create), not update — matching the design spec's "opens the existing catalog item modal, pre-filled" requirement exactly.

- [ ] **Step 4: Verify no syntax errors**

Run: `cd frontend && npx tsc --noEmit`
Expected: no output = success.

- [ ] **Step 5: Full build**

Run: `cd frontend && npx vite build --mode development`
Expected: `✓ built` with no errors.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/components/HospitalInventoryTab.jsx
git commit -m "Add procedure template picker to hospital inventory catalog"
```

---

## Task 10: Frontend — "Duplicate" action on catalog rows

**Files:**
- Modify: `frontend/src/components/HospitalInventoryTab.jsx`

- [ ] **Step 1: Add the duplicate handler**

Add this function near `handleCatalogSubmit` (right after it):

```javascript
    const handleDuplicateCatalog = async (id) => {
        try {
            const source = await hospitalService.duplicateCatalogItem(id);
            const matchedRelativeItems = (() => {
                try {
                    const ids = JSON.parse(source.relativeItemIds || '[]');
                    return catalogList.filter(x => ids.includes(x.id)).map(x => ({ id: x.id, name: x.name }));
                } catch (e) {
                    return [];
                }
            })();
            setSelectedRelativeItems(matchedRelativeItems);
            setHasOwnStock(source.hasOwnStock !== false);
            setCatalogModal({
                isOpen: true,
                isEdit: false,
                data: { name: '', type: source.type, manufacturer: source.manufacturer, linkedFeeId: source.linkedFeeId }
            });
        } catch (err) {
            toastError('Failed to load item for duplication.');
        }
    };
```

Note: `name` is deliberately left blank (`''`) even though everything else is prefilled — the admin must type a new name, matching the design spec's "with the name field cleared for the admin to type a new one" requirement. `isEdit: false` means submission creates a new item rather than overwriting the source.

- [ ] **Step 2: Add the "Duplicate" button to each catalog row**

Find the catalog row's action cell (search for `onClick={() => handleDeactivateCatalog(item.id)}`):

```jsx
                                    <td className="py-3 text-right space-x-2">
                                        <button
                                            onClick={() => setCatalogModal({ isOpen: true, isEdit: true, data: item })}
                                            className="text-teal-600 hover:text-teal-800 font-semibold"
                                        >
                                            Edit
                                        </button>
                                        <button
                                            onClick={() => handleDeactivateCatalog(item.id)}
                                            className="text-red-500 hover:text-red-700 font-semibold"
                                        >
                                            Deactivate
                                        </button>
                                    </td>
```

Replace with:

```jsx
                                    <td className="py-3 text-right space-x-2">
                                        <button
                                            onClick={() => setCatalogModal({ isOpen: true, isEdit: true, data: item })}
                                            className="text-teal-600 hover:text-teal-800 font-semibold"
                                        >
                                            Edit
                                        </button>
                                        <button
                                            onClick={() => handleDuplicateCatalog(item.id)}
                                            className="text-indigo-600 hover:text-indigo-800 font-semibold"
                                        >
                                            Duplicate
                                        </button>
                                        <button
                                            onClick={() => handleDeactivateCatalog(item.id)}
                                            className="text-red-500 hover:text-red-700 font-semibold"
                                        >
                                            Deactivate
                                        </button>
                                    </td>
```

- [ ] **Step 3: Verify no syntax errors**

Run: `cd frontend && npx tsc --noEmit`
Expected: no output = success.

- [ ] **Step 4: Full build**

Run: `cd frontend && npx vite build --mode development`
Expected: `✓ built` with no errors.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/HospitalInventoryTab.jsx
git commit -m "Add Duplicate action to hospital inventory catalog rows"
```

---

## Task 11: Frontend — chargeable-only, catalog-driven "Items Used" search in Consultation

**Files:**
- Modify: `frontend/src/components/ConsultationModal.jsx`

This is the core UX fix: switching the search from browsing physical stock (today, filtered to `stockQuantity > 0` — which is exactly why a zero-stock service item like "Dressing" silently disappears) to browsing the catalog filtered to chargeable items (`linkedFeeId != null`). For a `hasOwnStock: true` item, the search still resolves and displays the matching `HospitalInventory` stock row (for the quantity ceiling and `stockId`); for a `hasOwnStock: false` item, there's no stock row to resolve, no `stockId` is sent, and there's no quantity ceiling.

- [ ] **Step 1: Replace the search/selection block**

Find the "Hospital Inventory Search" block (search for `{/* Hospital Inventory Search */}`):

```jsx
                                            {/* Hospital Inventory Search */}
                                            {hasHospitalInventory && (
                                                <div className="relative">
                                                    <input
                                                        type="text"
                                                        placeholder="Search hospital stock items (saline, syringe, gloves...)..."
                                                        value={hospitalInvSearch}
                                                        onChange={(e) => { setHospitalInvSearch(e.target.value); setHospitalInvDropdown(true); }}
                                                        onFocus={() => setHospitalInvDropdown(true)}
                                                        onBlur={() => setTimeout(() => setHospitalInvDropdown(false), 200)}
                                                        className="w-full border border-gray-300 px-3 py-2 text-sm rounded-lg focus:ring-2 focus:ring-teal-500 focus:border-transparent outline-none"
                                                    />
                                                    {hospitalInvDropdown && hospitalInvSearch.trim().length >= 1 && (
                                                        <div className="absolute left-0 right-0 mt-1 bg-white border border-gray-200 rounded-lg shadow-xl z-50 max-h-48 overflow-y-auto divide-y divide-gray-100">
                                                            {hospitalInventory
                                                                .filter(item => item.name?.toLowerCase().includes(hospitalInvSearch.toLowerCase()))
                                                                .map(item => {
                                                                    const catItem = hospitalInventoryCatalog.find(c => c.name?.toLowerCase() === item.name?.toLowerCase());
                                                                    const linkedFeeId = catItem?.linkedFeeId || null;
                                                                    return (
                                                                        <button
                                                                            key={item.id}
                                                                            type="button"
                                                                            onClick={() => {
                                                                                const existing = hospitalInvItems.find(x => x.stockId === item.id);
                                                                                if (existing) {
                                                                                    if (existing.qty < item.stockQuantity) {
                                                                                        setHospitalInvItems(prev => prev.map(x => x.stockId === item.id ? { ...x, qty: x.qty + 1 } : x));
                                                                                    }
                                                                                } else {
                                                                                    // Resolve fee info from linkedFeeId
                                                                                    // linkedFeeId is a numeric HospitalFee.id (custom fee only)
                                                                                    // Standard fees (consultation/casepaper) are applied universally via appliedCharges
                                                                                    let feeName = null, feeAmount = 0;
                                                                                    if (linkedFeeId) {
                                                                                        const fee = availableCustomFees.find(f => String(f.id) === String(linkedFeeId));
                                                                                        if (fee) { feeName = fee.name; feeAmount = Number(fee.defaultAmount); }
                                                                                    }
                                                                                    setHospitalInvItems(prev => [...prev, {
                                                                                        stockId: item.id,
                                                                                        name: item.name,
                                                                                        qty: 1,
                                                                                        maxStock: item.stockQuantity,
                                                                                        linkedFeeId,
                                                                                        feeName: feeName || item.name,
                                                                                        feeAmount
                                                                                    }]);
                                                                                }
                                                                                setHospitalInvSearch('');
                                                                                setHospitalInvDropdown(false);
                                                                            }}
                                                                            className="w-full text-left px-3 py-2 hover:bg-slate-50 flex justify-between items-center text-xs"
                                                                        >
                                                                            <div>
                                                                                <span className="font-semibold text-gray-800">{item.name}</span>
                                                                                {catItem?.linkedFeeId && <span className="ml-2 text-xs text-teal-600">+fee</span>}
                                                                            </div>
                                                                            <span className="text-gray-400">Stock: {item.stockQuantity}</span>
                                                                        </button>
                                                                    );
                                                                })}
                                                            {hospitalInventory.filter(item => item.name?.toLowerCase().includes(hospitalInvSearch.toLowerCase())).length === 0 && (
                                                                <div className="p-2 text-center text-xs text-gray-400">No matching stock items found.</div>
                                                            )}
                                                        </div>
                                                    )}
                                                </div>
                                            )}
```

Replace with (searches `hospitalInventoryCatalog` filtered to `linkedFeeId != null` instead of `hospitalInventory`; for each chargeable catalog item, looks up its matching stock row by name ONLY to get quantity info when `hasOwnStock` is true; service-type items have no stock row, no `maxStock` ceiling, and no `stockId`):

```jsx
                                            {/* Hospital Inventory Search — chargeable catalog items only */}
                                            {hasHospitalInventory && (
                                                <div className="relative">
                                                    <input
                                                        type="text"
                                                        placeholder="Search chargeable items (injection, dressing...)..."
                                                        value={hospitalInvSearch}
                                                        onChange={(e) => { setHospitalInvSearch(e.target.value); setHospitalInvDropdown(true); }}
                                                        onFocus={() => setHospitalInvDropdown(true)}
                                                        onBlur={() => setTimeout(() => setHospitalInvDropdown(false), 200)}
                                                        className="w-full border border-gray-300 px-3 py-2 text-sm rounded-lg focus:ring-2 focus:ring-teal-500 focus:border-transparent outline-none"
                                                    />
                                                    {hospitalInvDropdown && hospitalInvSearch.trim().length >= 1 && (
                                                        <div className="absolute left-0 right-0 mt-1 bg-white border border-gray-200 rounded-lg shadow-xl z-50 max-h-48 overflow-y-auto divide-y divide-gray-100">
                                                            {hospitalInventoryCatalog
                                                                .filter(catItem => catItem.isActive !== false && catItem.linkedFeeId && catItem.name?.toLowerCase().includes(hospitalInvSearch.toLowerCase()))
                                                                .map(catItem => {
                                                                    const isStocked = catItem.hasOwnStock !== false;
                                                                    const matchingStock = isStocked ? hospitalInventory.find(s => s.name?.toLowerCase() === catItem.name?.toLowerCase()) : null;
                                                                    const stockQty = matchingStock ? matchingStock.stockQuantity : null;
                                                                    const selectionKey = isStocked ? matchingStock?.id : catItem.name;
                                                                    const disabled = isStocked && (!matchingStock || stockQty <= 0);

                                                                    return (
                                                                        <button
                                                                            key={catItem.id}
                                                                            type="button"
                                                                            disabled={disabled}
                                                                            onClick={() => {
                                                                                if (disabled) return;
                                                                                const existing = hospitalInvItems.find(x => (isStocked ? x.stockId === selectionKey : x.name === selectionKey));
                                                                                if (existing) {
                                                                                    if (!isStocked || existing.qty < stockQty) {
                                                                                        setHospitalInvItems(prev => prev.map(x => x === existing ? { ...x, qty: x.qty + 1 } : x));
                                                                                    }
                                                                                } else {
                                                                                    let feeName = null, feeAmount = 0;
                                                                                    const fee = availableCustomFees.find(f => String(f.id) === String(catItem.linkedFeeId));
                                                                                    if (fee) { feeName = fee.name; feeAmount = Number(fee.defaultAmount); }
                                                                                    setHospitalInvItems(prev => [...prev, {
                                                                                        stockId: isStocked ? matchingStock?.id : null,
                                                                                        name: catItem.name,
                                                                                        qty: 1,
                                                                                        maxStock: isStocked ? stockQty : null,
                                                                                        linkedFeeId: catItem.linkedFeeId,
                                                                                        feeName: feeName || catItem.name,
                                                                                        feeAmount
                                                                                    }]);
                                                                                }
                                                                                setHospitalInvSearch('');
                                                                                setHospitalInvDropdown(false);
                                                                            }}
                                                                            className={`w-full text-left px-3 py-2 flex justify-between items-center text-xs ${disabled ? 'opacity-40 cursor-not-allowed' : 'hover:bg-slate-50'}`}
                                                                        >
                                                                            <span className="font-semibold text-gray-800">{catItem.name}</span>
                                                                            <span className="text-gray-400">{isStocked ? `Stock: ${stockQty ?? 0}` : 'Service'}</span>
                                                                        </button>
                                                                    );
                                                                })}
                                                            {hospitalInventoryCatalog.filter(catItem => catItem.isActive !== false && catItem.linkedFeeId && catItem.name?.toLowerCase().includes(hospitalInvSearch.toLowerCase())).length === 0 && (
                                                                <div className="p-2 text-center text-xs text-gray-400">No matching chargeable items found.</div>
                                                            )}
                                                        </div>
                                                    )}
                                                </div>
                                            )}
```

- [ ] **Step 2: Update the selected-items list to key on `stockId` OR `name`**

Since service-type items now have `stockId: null`, using `stockId` alone as a React key/match predicate would collide across multiple service items selected at once. Find the exact "Selected Items List" row block (`frontend/src/components/ConsultationModal.jsx`, inside the `{hasHospitalInventory && hospitalInvItems.length > 0 && (...)}` block):

```jsx
                                                            {hospitalInvItems.map((item) => (
                                                                <tr key={item.stockId} className="hover:bg-slate-50/50">
                                                                    <td className="px-3 py-2 font-semibold text-gray-800">{item.name}</td>
                                                                    <td className="px-3 py-2 text-center">
                                                                        <div className="inline-flex items-center gap-1">
                                                                            <button type="button" onClick={() => { if (item.qty > 1) setHospitalInvItems(prev => prev.map(x => x.stockId === item.stockId ? {...x, qty: x.qty - 1} : x)); }} className="w-5 h-5 border border-gray-300 rounded text-gray-500 hover:bg-slate-100">-</button>
                                                                            <span className="font-bold w-4 text-center">{item.qty}</span>
                                                                            <button type="button" onClick={() => { if (item.qty < item.maxStock) setHospitalInvItems(prev => prev.map(x => x.stockId === item.stockId ? {...x, qty: x.qty + 1} : x)); }} className="w-5 h-5 border border-gray-300 rounded text-gray-500 hover:bg-slate-100">+</button>
                                                                        </div>
                                                                    </td>
                                                                    <td className="px-3 py-2 text-right text-teal-700 font-semibold">
                                                                        {item.feeAmount ? `₹${item.feeAmount * item.qty}` : <span className="text-gray-400">No charge</span>}
                                                                        {item.feeName && item.feeAmount ? <div className="text-[10px] text-gray-400">{item.feeName}</div> : null}
                                                                    </td>
                                                                    <td className="px-3 py-2 text-right">
                                                                        <button type="button" onClick={() => setHospitalInvItems(prev => prev.filter(x => x.stockId !== item.stockId))} className="text-red-500 hover:text-red-700 font-semibold">Remove</button>
                                                                    </td>
                                                                </tr>
                                                            ))}
```

Replace with (keys/matches on `stockId || name` so a service-type item, which has `stockId: null`, is uniquely identified by its `name` instead; the `+` button's ceiling check now tolerates `maxStock === null` meaning "no ceiling"):

```jsx
                                                            {hospitalInvItems.map((item) => (
                                                                <tr key={item.stockId || item.name} className="hover:bg-slate-50/50">
                                                                    <td className="px-3 py-2 font-semibold text-gray-800">{item.name}</td>
                                                                    <td className="px-3 py-2 text-center">
                                                                        <div className="inline-flex items-center gap-1">
                                                                            <button type="button" onClick={() => { if (item.qty > 1) setHospitalInvItems(prev => prev.map(x => (x.stockId || x.name) === (item.stockId || item.name) ? {...x, qty: x.qty - 1} : x)); }} className="w-5 h-5 border border-gray-300 rounded text-gray-500 hover:bg-slate-100">-</button>
                                                                            <span className="font-bold w-4 text-center">{item.qty}</span>
                                                                            <button type="button" onClick={() => { if (item.maxStock == null || item.qty < item.maxStock) setHospitalInvItems(prev => prev.map(x => (x.stockId || x.name) === (item.stockId || item.name) ? {...x, qty: x.qty + 1} : x)); }} className="w-5 h-5 border border-gray-300 rounded text-gray-500 hover:bg-slate-100">+</button>
                                                                        </div>
                                                                    </td>
                                                                    <td className="px-3 py-2 text-right text-teal-700 font-semibold">
                                                                        {item.feeAmount ? `₹${item.feeAmount * item.qty}` : <span className="text-gray-400">No charge</span>}
                                                                        {item.feeName && item.feeAmount ? <div className="text-[10px] text-gray-400">{item.feeName}</div> : null}
                                                                    </td>
                                                                    <td className="px-3 py-2 text-right">
                                                                        <button type="button" onClick={() => setHospitalInvItems(prev => prev.filter(x => (x.stockId || x.name) !== (item.stockId || item.name)))} className="text-red-500 hover:text-red-700 font-semibold">Remove</button>
                                                                    </td>
                                                                </tr>
                                                            ))}
```

- [ ] **Step 3: Confirm the submit payload construction needs no change**

Confirmed (verified directly while writing this plan): both places building `payload.hospitalInventoryItems` in this file (line ~359, inside the OPD submit handler, and line ~415, inside the IPD-admit submit handler) already have this exact shape:

```javascript
        payload.hospitalInventoryItems = hospitalInvItems.map(item => ({
            stockId: item.stockId,
            name: item.name,
            quantity: item.qty
        }));
```

`item.stockId` will simply be `null` for a service-type item now (set that way in Step 1's new search/selection code) — this shape already sends that through correctly and is compatible with the backend changes from Tasks 3-4 (`stockId` is now optional). **No edit needed for this step** — it exists purely to confirm you don't need to touch these two blocks.

- [ ] **Step 4: Verify no syntax errors**

Run: `cd frontend && npx tsc --noEmit`
Expected: no output = success.

- [ ] **Step 5: Full build**

Run: `cd frontend && npx vite build --mode development`
Expected: `✓ built` with no errors.

- [ ] **Step 6: Commit**

```bash
cd e:/Projects/HOSPITAL
git add frontend/src/components/ConsultationModal.jsx
git commit -m "Filter Items Used search to chargeable catalog items, support service-type items"
```

---

## Task 12: Full-stack live verification

This project's established verification method: restart both servers cleanly, then drive the real UI with Playwright and inspect screenshots.

**Files:** none (verification only)

- [ ] **Step 1: Restart backend and frontend cleanly**

Same pattern as prior verification tasks: `mvn spring-boot:run` for backend, `npm run dev` for frontend, waiting for their respective ready log lines.

- [ ] **Step 2: Verify the catalog UI (Hospital Admin)**

Using a Playwright script (craft a JWT for a `HOSPITAL_ADMIN` test user, navigate to the Hospital Inventory → Catalog sub-tab):
- Click "Add from Template", pick "Dressing (Small)" — confirm the catalog form opens pre-filled with name="Dressing (Small)", "Service" stock-type radio selected, and Cotton/Bandage pre-populated in the related-items tags (assuming those catalog items already exist from earlier setup — if not, first create Cotton/Bandage catalog items with stock via Purchase Intake, then retry).
- Pick a fee, save — confirm the new catalog row shows a "Service" badge in the Stock Type column.
- Click "Duplicate" on it — confirm the form opens with everything prefilled except a blank name; type a new name, save — confirm a second catalog row appears.
- Screenshot each step.

- [ ] **Step 3: Verify the Consultation "Items Used" search**

Navigate to a doctor's Consultation view for a patient with an active OPD, open the Prescription/Items tab:
- Screenshot the "Items Used" search — confirm it's now labeled for chargeable items and shows "Dressing (Small)" with "Service" instead of a stock number, and does NOT show "Cotton" or "Bandage" individually anywhere in the results.
- Select "Dressing (Small)" — confirm it's added to the selected-items list with its fee amount shown (not ₹0).
- Submit the consultation — confirm no error, and afterward check via `curl`/SQL that Cotton and Bandage stock both dropped by the selected quantity, while there's no crash from a missing "Dressing (Small)" stock row.

- [ ] **Step 4: Verify a stocked chargeable item still works exactly as before**

Repeat step 3 with a `hasOwnStock: true` item that has real stock (e.g. "Injection" from the template, after setting up its own stock via Purchase Intake) — confirm it still shows a real "Stock: N" count, still enforces the max-quantity ceiling, and its own stock decrements correctly alongside its related items.

- [ ] **Step 5: Final full build and test check**

```bash
cd backend && mvn -q -o clean compile && mvn test -q
cd frontend && npx tsc --noEmit && npx vite build --mode development
```

Expected: both succeed with no errors, full backend test suite passes (114 tests).

Clean up all test catalog items/stock/patients/OPD records created during this verification, per this project's established cleanup discipline.
