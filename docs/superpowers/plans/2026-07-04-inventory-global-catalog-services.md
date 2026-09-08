# Inventory Global-Catalog + Services Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restructure hospital inventory into a platform-managed global item catalog + per-hospital "services" (billable procedures composed of relevant items), replacing the old per-hospital catalog / `hasOwnStock` flag / procedure templates.

**Architecture:** New global `inventory_master_items` (platform-managed, names only) + per-hospital `hospital_services` (name + charge) + `hospital_service_items` (join to master items). Consultation picks services; a shared `consumeService` validates every relevant item has stock (toast/reject "some items out of stock" otherwise) and deducts it FEFO, returning the service charge for billing. Old `inventory_items` catalog endpoints and `hasOwnStock`/template/duplicate code are removed. Low-stock items are surfaced on role/tenant-gated dashboard banners.

**Tech Stack:** Spring Boot / Java 17 / Hibernate (JPA) / MySQL 8, JUnit 5 + Mockito + AssertJ + `@WebMvcTest`/MockMvc for backend tests. React / Vite frontend, no test runner (manual build + live verification).

**Sequencing note:** Backend new tables + services + consumeService come first (Tasks 1-6); then OPD/IPD are switched over to services (Tasks 7-8) — after which the old catalog code is removed (Task 9); then frontend (Tasks 11-16). This ordering keeps the app compiling and OPD/IPD working at every commit.

---

## Task 1: `InventoryMasterItem` entity + repository + platform CRUD/CSV

**Files:**
- Create: `backend/src/main/java/com/hms/entity/InventoryMasterItem.java`
- Create: `backend/src/main/java/com/hms/repository/InventoryMasterItemRepository.java`
- Create: `backend/src/main/java/com/hms/service/platform/PlatformInventoryItemService.java`
- Create: `backend/src/main/java/com/hms/controller/platform/PlatformInventoryItemController.java`
- Test: `backend/src/test/java/com/hms/service/platform/PlatformInventoryItemServiceTest.java` (new)

- [ ] **Step 1: Create the entity**

Create `backend/src/main/java/com/hms/entity/InventoryMasterItem.java`:

```java
package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/**
 * Global (platform-wide) inventory item name dictionary. Curated by the
 * Super Admin; every hospital picks from this list for purchases and for a
 * service's relevant items. No hospital_id -- these are shared names only.
 */
@Entity
@Table(name = "inventory_master_items")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InventoryMasterItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
```

- [ ] **Step 2: Create the repository**

Create `backend/src/main/java/com/hms/repository/InventoryMasterItemRepository.java`:

```java
package com.hms.repository;

import com.hms.entity.InventoryMasterItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InventoryMasterItemRepository extends JpaRepository<InventoryMasterItem, Long> {
    List<InventoryMasterItem> findAllByOrderByNameAsc();
    Optional<InventoryMasterItem> findByNameIgnoreCase(String name);
    boolean existsByNameIgnoreCase(String name);
}
```

- [ ] **Step 3: Write the failing service tests**

Create `backend/src/test/java/com/hms/service/platform/PlatformInventoryItemServiceTest.java`:

```java
package com.hms.service.platform;

import com.hms.entity.InventoryMasterItem;
import com.hms.repository.InventoryMasterItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class PlatformInventoryItemServiceTest {

    @Mock InventoryMasterItemRepository repository;

    @InjectMocks PlatformInventoryItemService service;

    @Test
    void createItem_blankName_throws() {
        assertThatThrownBy(() -> service.createItem("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required");
    }

    @Test
    void createItem_duplicate_throws() {
        when(repository.existsByNameIgnoreCase("Cotton")).thenReturn(true);

        assertThatThrownBy(() -> service.createItem("Cotton"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void createItem_valid_savesTrimmed() {
        when(repository.existsByNameIgnoreCase("Cotton")).thenReturn(false);
        when(repository.save(any(InventoryMasterItem.class))).thenAnswer(inv -> inv.getArgument(0));

        InventoryMasterItem result = service.createItem("  Cotton  ");

        assertThat(result.getName()).isEqualTo("Cotton");
    }

    @Test
    void importCsv_addsNewSkipsDuplicatesAndBlanks() throws Exception {
        when(repository.existsByNameIgnoreCase("Cotton")).thenReturn(false);
        when(repository.existsByNameIgnoreCase("Syringe")).thenReturn(true);
        when(repository.save(any(InventoryMasterItem.class))).thenAnswer(inv -> inv.getArgument(0));

        String csv = "name\nCotton\nSyringe\n\n";
        MockMultipartFile file = new MockMultipartFile("file", "items.csv", "text/csv", csv.getBytes());

        Map<String, Object> result = service.importCsv(file);

        assertThat(result.get("imported")).isEqualTo(1);
        assertThat(result.get("skipped")).isEqualTo(1);
        verify(repository, times(1)).save(any(InventoryMasterItem.class));
    }

    @Test
    void listItems_returnsOrdered() {
        InventoryMasterItem a = new InventoryMasterItem();
        a.setName("Bandage");
        when(repository.findAllByOrderByNameAsc()).thenReturn(List.of(a));

        assertThat(service.listItems()).containsExactly(a);
    }
}
```

- [ ] **Step 4: Run tests to verify they fail**

Run: `cd backend && mvn test -Dtest=PlatformInventoryItemServiceTest -q`
Expected: FAIL (compile error) — `PlatformInventoryItemService` doesn't exist.

- [ ] **Step 5: Create the service**

Create `backend/src/main/java/com/hms/service/platform/PlatformInventoryItemService.java`:

```java
package com.hms.service.platform;

import com.hms.entity.InventoryMasterItem;
import com.hms.repository.InventoryMasterItemRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class PlatformInventoryItemService {

    @Autowired
    private InventoryMasterItemRepository repository;

    public List<InventoryMasterItem> listItems() {
        return repository.findAllByOrderByNameAsc();
    }

    public InventoryMasterItem createItem(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Item name is required");
        }
        String trimmed = name.trim();
        if (repository.existsByNameIgnoreCase(trimmed)) {
            throw new IllegalArgumentException("Item already exists: " + trimmed);
        }
        InventoryMasterItem item = new InventoryMasterItem();
        item.setName(trimmed);
        return repository.save(item);
    }

    public void deleteItem(Long id) {
        repository.deleteById(id);
    }

    public Map<String, Object> importCsv(MultipartFile file) throws Exception {
        int imported = 0, skipped = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), "UTF-8"))) {
            String line;
            int lineNum = 0;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                String trimmed = line.trim();
                if (trimmed.isEmpty()) { continue; }
                if (lineNum == 1 && trimmed.toLowerCase().startsWith("name")) { continue; }
                String name = trimmed.split(",", -1)[0].trim().replaceAll("^\"|\"$", "");
                if (name.isEmpty()) { continue; }
                if (repository.existsByNameIgnoreCase(name)) { skipped++; continue; }
                InventoryMasterItem item = new InventoryMasterItem();
                item.setName(name);
                repository.save(item);
                imported++;
            }
        }
        Map<String, Object> result = new HashMap<>();
        result.put("imported", imported);
        result.put("skipped", skipped);
        return result;
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd backend && mvn test -Dtest=PlatformInventoryItemServiceTest -q`
Expected: PASS (5 tests)

- [ ] **Step 7: Create the controller**

Create `backend/src/main/java/com/hms/controller/platform/PlatformInventoryItemController.java`:

```java
package com.hms.controller.platform;

import com.hms.entity.InventoryMasterItem;
import com.hms.service.platform.PlatformInventoryItemService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/platform/inventory-items")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class PlatformInventoryItemController {

    @Autowired
    private PlatformInventoryItemService service;

    @GetMapping
    public ResponseEntity<List<InventoryMasterItem>> getItems() {
        return ResponseEntity.ok(service.listItems());
    }

    @PostMapping
    public ResponseEntity<?> createItem(@RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(service.createItem(body.get("name")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteItem(@PathVariable Long id) {
        service.deleteItem(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/import-csv")
    public ResponseEntity<?> importCsv(@RequestParam("file") MultipartFile file) {
        try {
            return ResponseEntity.ok(service.importCsv(file));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
```

- [ ] **Step 8: Compile check**

Run: `cd backend && mvn -q -o compile`
Expected: no output = success.

- [ ] **Step 9: Commit**

```bash
cd e:/Projects/HOSPITAL
git add backend/src/main/java/com/hms/entity/InventoryMasterItem.java backend/src/main/java/com/hms/repository/InventoryMasterItemRepository.java backend/src/main/java/com/hms/service/platform/PlatformInventoryItemService.java backend/src/main/java/com/hms/controller/platform/PlatformInventoryItemController.java backend/src/test/java/com/hms/service/platform/PlatformInventoryItemServiceTest.java
git commit -m "Add global InventoryMasterItem catalog (platform-managed) with CSV import"
```

Stage ONLY these five files.

---

## Task 2: `HospitalService` + `HospitalServiceItem` entities + repositories

**Files:**
- Create: `backend/src/main/java/com/hms/entity/HospitalServiceEntity.java`
- Create: `backend/src/main/java/com/hms/entity/HospitalServiceItem.java`
- Create: `backend/src/main/java/com/hms/repository/HospitalServiceRepository.java`
- Create: `backend/src/main/java/com/hms/repository/HospitalServiceItemRepository.java`

Note: the entity is named `HospitalServiceEntity` (class) mapped to table `hospital_services`, to avoid colliding with the many `*Service` Spring beans in `com.hms.service`.

- [ ] **Step 1: Create the `HospitalServiceEntity`**

Create `backend/src/main/java/com/hms/entity/HospitalServiceEntity.java`:

```java
package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A per-hospital billable service/procedure (e.g. "Dressing", "Injection")
 * with a standalone charge. The physical items it consumes live in
 * HospitalServiceItem, referencing global InventoryMasterItem names.
 */
@Entity
@Table(name = "hospital_services")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HospitalServiceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal charge;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
```

- [ ] **Step 2: Create the `HospitalServiceItem` entity**

Create `backend/src/main/java/com/hms/entity/HospitalServiceItem.java`:

```java
package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A relevant item consumed by a HospitalServiceEntity, referencing a global
 * InventoryMasterItem. One row per (service, item) pair.
 */
@Entity
@Table(name = "hospital_service_items")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HospitalServiceItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "service_id", nullable = false)
    private Long serviceId;

    @Column(name = "master_item_id", nullable = false)
    private Long masterItemId;
}
```

- [ ] **Step 3: Create the repositories**

Create `backend/src/main/java/com/hms/repository/HospitalServiceRepository.java`:

```java
package com.hms.repository;

import com.hms.entity.HospitalServiceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface HospitalServiceRepository extends JpaRepository<HospitalServiceEntity, Long> {
    List<HospitalServiceEntity> findByHospitalIdAndIsActiveTrueOrderByNameAsc(Long hospitalId);
    Optional<HospitalServiceEntity> findByIdAndHospitalId(Long id, Long hospitalId);
}
```

Create `backend/src/main/java/com/hms/repository/HospitalServiceItemRepository.java`:

```java
package com.hms.repository;

import com.hms.entity.HospitalServiceItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface HospitalServiceItemRepository extends JpaRepository<HospitalServiceItem, Long> {
    List<HospitalServiceItem> findByServiceId(Long serviceId);
    void deleteByServiceId(Long serviceId);
}
```

- [ ] **Step 4: Compile check**

Run: `cd backend && mvn -q -o compile`
Expected: no output = success.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/hms/entity/HospitalServiceEntity.java backend/src/main/java/com/hms/entity/HospitalServiceItem.java backend/src/main/java/com/hms/repository/HospitalServiceRepository.java backend/src/main/java/com/hms/repository/HospitalServiceItemRepository.java
git commit -m "Add HospitalServiceEntity and HospitalServiceItem entities and repositories"
```

---

## Task 3: DB migration for the three new tables + canonical schema

**Files:**
- Modify: `backend/src/main/java/com/hms/config/DatabaseMigrationRunner.java`
- Modify: `setup/schema-full.sql`

- [ ] **Step 1: Add the migration method**

In `backend/src/main/java/com/hms/config/DatabaseMigrationRunner.java`, append a new call at the very end of `runMigrations()`'s existing call list (read the file first to confirm the exact last call; do not reorder/remove any):

```java
        ensureInventoryItemHasOwnStockColumn();
        ensureInventoryServicesTables(); // NEW
```

Add the method (following the exact style of the existing `ensure...Table` methods — same `jdbcTemplate`/`log` fields, same `information_schema.TABLES` existence-check):

```java
    /**
     * Creates the global inventory catalog + per-hospital services tables if
     * absent: inventory_master_items (platform-global item names),
     * hospital_services (per-hospital billable procedures), and
     * hospital_service_items (join to master items). Idempotent, each checked
     * independently.
     */
    private void ensureInventoryServicesTables() {
        try {
            Integer masterCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'inventory_master_items'",
                Integer.class);
            if (masterCount != null && masterCount == 0) {
                jdbcTemplate.execute(
                    "CREATE TABLE inventory_master_items (" +
                    "  id BIGINT NOT NULL AUTO_INCREMENT," +
                    "  name VARCHAR(255) NOT NULL," +
                    "  created_at DATETIME(6) NOT NULL," +
                    "  PRIMARY KEY (id)" +
                    ")");
                log.info("DB migration applied: inventory_master_items table created");
            }

            Integer svcCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'hospital_services'",
                Integer.class);
            if (svcCount != null && svcCount == 0) {
                jdbcTemplate.execute(
                    "CREATE TABLE hospital_services (" +
                    "  id BIGINT NOT NULL AUTO_INCREMENT," +
                    "  hospital_id BIGINT NOT NULL," +
                    "  name VARCHAR(150) NOT NULL," +
                    "  charge DECIMAL(10,2) NOT NULL," +
                    "  is_active TINYINT(1) NOT NULL DEFAULT 1," +
                    "  created_at DATETIME(6) NOT NULL," +
                    "  PRIMARY KEY (id)," +
                    "  FOREIGN KEY (hospital_id) REFERENCES hospitals(id) ON DELETE CASCADE" +
                    ")");
                log.info("DB migration applied: hospital_services table created");
            }

            Integer itemCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'hospital_service_items'",
                Integer.class);
            if (itemCount != null && itemCount == 0) {
                jdbcTemplate.execute(
                    "CREATE TABLE hospital_service_items (" +
                    "  id BIGINT NOT NULL AUTO_INCREMENT," +
                    "  service_id BIGINT NOT NULL," +
                    "  master_item_id BIGINT NOT NULL," +
                    "  PRIMARY KEY (id)," +
                    "  FOREIGN KEY (service_id) REFERENCES hospital_services(id) ON DELETE CASCADE" +
                    ")");
                log.info("DB migration applied: hospital_service_items table created");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (inventory services tables): {}", e.getMessage());
        }
    }
```

- [ ] **Step 2: Update the canonical schema**

In `setup/schema-full.sql`, add the three `CREATE TABLE` blocks near the existing `inventory_items` block:

```sql
CREATE TABLE `inventory_master_items` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(255) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `hospital_services` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `hospital_id` bigint NOT NULL,
  `name` varchar(150) NOT NULL,
  `charge` decimal(10,2) NOT NULL,
  `is_active` tinyint(1) NOT NULL DEFAULT '1',
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FK_hospital_services_hospital` (`hospital_id`),
  CONSTRAINT `FK_hospital_services_hospital` FOREIGN KEY (`hospital_id`) REFERENCES `hospitals` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `hospital_service_items` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `service_id` bigint NOT NULL,
  `master_item_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FK_hospital_service_items_service` (`service_id`),
  CONSTRAINT `FK_hospital_service_items_service` FOREIGN KEY (`service_id`) REFERENCES `hospital_services` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

- [ ] **Step 3: Compile + full test suite**

Run: `cd backend && mvn -q -o clean compile && mvn test -q`
Expected: both succeed; suite passes. This task adds no tests, so the count equals the count after Task 1 (which added the 5 `PlatformInventoryItemServiceTest` tests) — i.e. the pre-feature total plus 5.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/hms/config/DatabaseMigrationRunner.java setup/schema-full.sql
git commit -m "Add DB migration for inventory_master_items and hospital_services tables"
```

---

## Task 4: `HospitalServiceService` (CRUD) with tests

**Files:**
- Create: `backend/src/main/java/com/hms/dto/HospitalServiceDTO.java`
- Create: `backend/src/main/java/com/hms/service/hospital/HospitalServiceService.java`
- Test: `backend/src/test/java/com/hms/service/hospital/HospitalServiceServiceTest.java` (new)

- [ ] **Step 1: Create the DTO**

Create `backend/src/main/java/com/hms/dto/HospitalServiceDTO.java`:

```java
package com.hms.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class HospitalServiceDTO {
    private Long id;
    private String name;
    private BigDecimal charge;
    // Relevant items expressed as master item ids (for save) and, on read,
    // enriched names are provided via itemNames.
    private List<Long> masterItemIds;
    private List<String> itemNames;
}
```

- [ ] **Step 2: Write the failing tests**

Create `backend/src/test/java/com/hms/service/hospital/HospitalServiceServiceTest.java`:

```java
package com.hms.service.hospital;

import com.hms.entity.HospitalServiceEntity;
import com.hms.entity.HospitalServiceItem;
import com.hms.entity.InventoryMasterItem;
import com.hms.repository.HospitalServiceItemRepository;
import com.hms.repository.HospitalServiceRepository;
import com.hms.repository.InventoryMasterItemRepository;
import com.hms.security.SecurityContextHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class HospitalServiceServiceTest {

    @Mock HospitalServiceRepository serviceRepository;
    @Mock HospitalServiceItemRepository serviceItemRepository;
    @Mock InventoryMasterItemRepository masterItemRepository;
    @Mock SecurityContextHelper securityHelper;

    @InjectMocks HospitalServiceService service;

    @Test
    void createService_blankName_throws() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);
        assertThatThrownBy(() -> service.createService("  ", new BigDecimal("100"), List.of(2L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name is required");
    }

    @Test
    void createService_noItems_throws() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);
        assertThatThrownBy(() -> service.createService("Dressing", new BigDecimal("100"), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one");
    }

    @Test
    void createService_negativeCharge_throws() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);
        assertThatThrownBy(() -> service.createService("Dressing", new BigDecimal("-5"), List.of(2L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("charge");
    }

    @Test
    void createService_valid_savesServiceAndJoinRows() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);
        when(serviceRepository.save(any(HospitalServiceEntity.class))).thenAnswer(inv -> {
            HospitalServiceEntity s = inv.getArgument(0);
            s.setId(9L);
            return s;
        });

        HospitalServiceEntity result = service.createService("Dressing", new BigDecimal("150"), List.of(2L, 3L));

        assertThat(result.getName()).isEqualTo("Dressing");
        assertThat(result.getHospitalId()).isEqualTo(1L);
        assertThat(result.getCharge()).isEqualByComparingTo("150");
        verify(serviceItemRepository, times(2)).save(any(HospitalServiceItem.class));
    }

    @Test
    void listServices_returnsHospitalScoped() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);
        HospitalServiceEntity s = new HospitalServiceEntity();
        s.setName("Dressing");
        when(serviceRepository.findByHospitalIdAndIsActiveTrueOrderByNameAsc(1L)).thenReturn(List.of(s));

        assertThat(service.listServices()).containsExactly(s);
    }

    @Test
    void getItemNamesForService_resolvesMasterNames() {
        HospitalServiceItem link = new HospitalServiceItem();
        link.setMasterItemId(2L);
        when(serviceItemRepository.findByServiceId(9L)).thenReturn(List.of(link));
        InventoryMasterItem cotton = new InventoryMasterItem();
        cotton.setName("Cotton");
        when(masterItemRepository.findById(2L)).thenReturn(Optional.of(cotton));

        assertThat(service.getItemNamesForService(9L)).containsExactly("Cotton");
    }

    @Test
    void updateService_notFound_throws() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);
        when(serviceRepository.findByIdAndHospitalId(99L, 1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.updateService(99L, "X", new BigDecimal("1"), List.of(2L)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void updateService_replacesItems() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);
        HospitalServiceEntity existing = new HospitalServiceEntity();
        existing.setId(5L);
        existing.setHospitalId(1L);
        when(serviceRepository.findByIdAndHospitalId(5L, 1L)).thenReturn(Optional.of(existing));
        when(serviceRepository.save(any(HospitalServiceEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateService(5L, "New", new BigDecimal("200"), List.of(7L));

        verify(serviceItemRepository).deleteByServiceId(5L);
        verify(serviceItemRepository).save(any(HospitalServiceItem.class));
    }

    @Test
    void deleteService_softDeletes() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);
        HospitalServiceEntity existing = new HospitalServiceEntity();
        existing.setId(5L);
        existing.setHospitalId(1L);
        existing.setIsActive(true);
        when(serviceRepository.findByIdAndHospitalId(5L, 1L)).thenReturn(Optional.of(existing));
        when(serviceRepository.save(any(HospitalServiceEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.deleteService(5L);

        assertThat(existing.getIsActive()).isFalse();
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `cd backend && mvn test -Dtest=HospitalServiceServiceTest -q`
Expected: FAIL (compile error) — `HospitalServiceService` doesn't exist.

- [ ] **Step 4: Create the service**

Create `backend/src/main/java/com/hms/service/hospital/HospitalServiceService.java`:

```java
package com.hms.service.hospital;

import com.hms.entity.HospitalServiceEntity;
import com.hms.entity.HospitalServiceItem;
import com.hms.repository.HospitalServiceItemRepository;
import com.hms.repository.HospitalServiceRepository;
import com.hms.repository.InventoryMasterItemRepository;
import com.hms.security.SecurityContextHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
public class HospitalServiceService {

    @Autowired
    private HospitalServiceRepository serviceRepository;

    @Autowired
    private HospitalServiceItemRepository serviceItemRepository;

    @Autowired
    private InventoryMasterItemRepository masterItemRepository;

    @Autowired
    private SecurityContextHelper securityHelper;

    public List<HospitalServiceEntity> listServices() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        return serviceRepository.findByHospitalIdAndIsActiveTrueOrderByNameAsc(hospitalId);
    }

    public List<String> getItemNamesForService(Long serviceId) {
        List<String> names = new ArrayList<>();
        for (HospitalServiceItem link : serviceItemRepository.findByServiceId(serviceId)) {
            masterItemRepository.findById(link.getMasterItemId())
                    .ifPresent(m -> names.add(m.getName()));
        }
        return names;
    }

    public List<Long> getMasterItemIdsForService(Long serviceId) {
        List<Long> ids = new ArrayList<>();
        for (HospitalServiceItem link : serviceItemRepository.findByServiceId(serviceId)) {
            ids.add(link.getMasterItemId());
        }
        return ids;
    }

    @Transactional
    public HospitalServiceEntity createService(String name, BigDecimal charge, List<Long> masterItemIds) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        validate(name, charge, masterItemIds);

        HospitalServiceEntity svc = new HospitalServiceEntity();
        svc.setHospitalId(hospitalId);
        svc.setName(name.trim());
        svc.setCharge(charge);
        svc.setIsActive(true);
        HospitalServiceEntity saved = serviceRepository.save(svc);

        saveItems(saved.getId(), masterItemIds);
        return saved;
    }

    @Transactional
    public HospitalServiceEntity updateService(Long id, String name, BigDecimal charge, List<Long> masterItemIds) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        validate(name, charge, masterItemIds);
        HospitalServiceEntity svc = serviceRepository.findByIdAndHospitalId(id, hospitalId)
                .orElseThrow(() -> new RuntimeException("Service not found"));
        svc.setName(name.trim());
        svc.setCharge(charge);
        serviceRepository.save(svc);

        serviceItemRepository.deleteByServiceId(id);
        saveItems(id, masterItemIds);
        return svc;
    }

    @Transactional
    public void deleteService(Long id) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        HospitalServiceEntity svc = serviceRepository.findByIdAndHospitalId(id, hospitalId)
                .orElseThrow(() -> new RuntimeException("Service not found"));
        svc.setIsActive(false);
        serviceRepository.save(svc);
    }

    private void validate(String name, BigDecimal charge, List<Long> masterItemIds) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Service name is required");
        }
        if (charge == null || charge.signum() < 0) {
            throw new IllegalArgumentException("Service charge must be zero or positive");
        }
        if (masterItemIds == null || masterItemIds.isEmpty()) {
            throw new IllegalArgumentException("Service must have at least one relevant item");
        }
    }

    private void saveItems(Long serviceId, List<Long> masterItemIds) {
        for (Long itemId : masterItemIds) {
            HospitalServiceItem link = new HospitalServiceItem();
            link.setServiceId(serviceId);
            link.setMasterItemId(itemId);
            serviceItemRepository.save(link);
        }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd backend && mvn test -Dtest=HospitalServiceServiceTest -q`
Expected: PASS (9 tests)

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/hms/dto/HospitalServiceDTO.java backend/src/main/java/com/hms/service/hospital/HospitalServiceService.java backend/src/test/java/com/hms/service/hospital/HospitalServiceServiceTest.java
git commit -m "Add HospitalServiceService with per-hospital service CRUD"
```

---

## Task 5: `HospitalServiceController` + hospital master-items read endpoint + tests

**Files:**
- Create: `backend/src/main/java/com/hms/controller/hospital/HospitalServiceController.java`
- Test: `backend/src/test/java/com/hms/controller/hospital/HospitalServiceControllerTest.java` (new)

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/java/com/hms/controller/hospital/HospitalServiceControllerTest.java`:

```java
package com.hms.controller.hospital;

import com.hms.entity.HospitalServiceEntity;
import com.hms.repository.InventoryMasterItemRepository;
import com.hms.security.JwtUtil;
import com.hms.service.AuditLogService;
import com.hms.service.hospital.HospitalServiceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(HospitalServiceController.class)
@Import(HospitalServiceControllerTest.MethodSecurityTestConfig.class)
class HospitalServiceControllerTest {

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {}

    @Autowired private MockMvc mockMvc;

    @MockBean private HospitalServiceService serviceService;
    @MockBean private InventoryMasterItemRepository masterItemRepository;
    @MockBean private JwtUtil jwtUtil;
    @MockBean private AuditLogService auditLogService;

    @Test
    @WithMockUser(roles = "DOCTOR")
    void listServices_okForDoctor() throws Exception {
        when(serviceService.listServices()).thenReturn(Collections.emptyList());
        mockMvc.perform(get("/hospital/services").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "PHARMACIST")
    void listServices_forbiddenForPharmacist() throws Exception {
        mockMvc.perform(get("/hospital/services").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "HOSPITAL_ADMIN")
    void createService_okWhenServiceSucceeds() throws Exception {
        HospitalServiceEntity saved = new HospitalServiceEntity();
        saved.setId(1L);
        saved.setName("Dressing");
        saved.setCharge(new BigDecimal("150"));
        when(serviceService.createService(eq("Dressing"), any(), anyList())).thenReturn(saved);
        when(serviceService.getItemNamesForService(1L)).thenReturn(Collections.emptyList());
        when(serviceService.getMasterItemIdsForService(1L)).thenReturn(Collections.emptyList());

        mockMvc.perform(post("/hospital/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Dressing\",\"charge\":150,\"masterItemIds\":[2]}")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Dressing"));
    }

    @Test
    @WithMockUser(roles = "HOSPITAL_ADMIN")
    void createService_badRequestWhenServiceThrows() throws Exception {
        when(serviceService.createService(anyString(), any(), anyList()))
                .thenThrow(new IllegalArgumentException("Service name is required"));
        mockMvc.perform(post("/hospital/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"charge\":150,\"masterItemIds\":[]}")
                        .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "DOCTOR")
    void listMasterItems_okForDoctor() throws Exception {
        when(masterItemRepository.findAllByOrderByNameAsc()).thenReturn(Collections.emptyList());
        mockMvc.perform(get("/hospital/inventory-master").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "HOSPITAL_ADMIN")
    void deleteService_okForAdmin() throws Exception {
        mockMvc.perform(delete("/hospital/services/7").with(csrf()))
                .andExpect(status().isOk());
    }
}
```

If Spring complains about additional missing beans at context startup, add the named `@MockBean`s (the error names them) without changing assertions.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && mvn test -Dtest=HospitalServiceControllerTest -q`
Expected: FAIL (compile error) — controller doesn't exist.

- [ ] **Step 3: Create the controller**

Create `backend/src/main/java/com/hms/controller/hospital/HospitalServiceController.java`:

```java
package com.hms.controller.hospital;

import com.hms.dto.HospitalServiceDTO;
import com.hms.entity.HospitalServiceEntity;
import com.hms.entity.InventoryMasterItem;
import com.hms.repository.InventoryMasterItemRepository;
import com.hms.service.hospital.HospitalServiceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/hospital")
public class HospitalServiceController {

    @Autowired
    private HospitalServiceService serviceService;

    @Autowired
    private InventoryMasterItemRepository masterItemRepository;

    private HospitalServiceDTO toDto(HospitalServiceEntity s) {
        return new HospitalServiceDTO(
                s.getId(), s.getName(), s.getCharge(),
                serviceService.getMasterItemIdsForService(s.getId()),
                serviceService.getItemNamesForService(s.getId()));
    }

    // --- Global master item list (read-only for hospital roles) ---
    @GetMapping("/inventory-master")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR', 'RECEPTIONIST')")
    public ResponseEntity<List<InventoryMasterItem>> listMasterItems() {
        return ResponseEntity.ok(masterItemRepository.findAllByOrderByNameAsc());
    }

    // --- Per-hospital services ---
    @GetMapping("/services")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR', 'RECEPTIONIST')")
    public ResponseEntity<List<HospitalServiceDTO>> listServices() {
        List<HospitalServiceDTO> dtos = serviceService.listServices().stream()
                .map(this::toDto).collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    @PostMapping("/services")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR', 'RECEPTIONIST')")
    public ResponseEntity<?> createService(@RequestBody HospitalServiceDTO dto) {
        try {
            HospitalServiceEntity saved = serviceService.createService(dto.getName(), dto.getCharge(), dto.getMasterItemIds());
            return ResponseEntity.ok(toDto(saved));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PutMapping("/services/{id}")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR', 'RECEPTIONIST')")
    public ResponseEntity<?> updateService(@PathVariable Long id, @RequestBody HospitalServiceDTO dto) {
        try {
            HospitalServiceEntity saved = serviceService.updateService(id, dto.getName(), dto.getCharge(), dto.getMasterItemIds());
            return ResponseEntity.ok(toDto(saved));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @DeleteMapping("/services/{id}")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR', 'RECEPTIONIST')")
    public ResponseEntity<?> deleteService(@PathVariable Long id) {
        try {
            serviceService.deleteService(id);
            return ResponseEntity.ok("Service deleted successfully");
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && mvn test -Dtest=HospitalServiceControllerTest -q`
Expected: PASS (6 tests)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/hms/controller/hospital/HospitalServiceController.java backend/src/test/java/com/hms/controller/hospital/HospitalServiceControllerTest.java
git commit -m "Add HospitalServiceController and hospital master-items read endpoint"
```

---

## Task 6: `consumeService` + low-stock endpoint with tests

**Files:**
- Modify: `backend/src/main/java/com/hms/service/hospital/HospitalInventoryService.java`
- Modify: `backend/src/main/java/com/hms/controller/hospital/HospitalInventoryController.java`
- Test: `backend/src/test/java/com/hms/service/hospital/HospitalInventoryServiceConsumeServiceTest.java` (new)

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/java/com/hms/service/hospital/HospitalInventoryServiceConsumeServiceTest.java`:

```java
package com.hms.service.hospital;

import com.hms.entity.HospitalInventory;
import com.hms.entity.HospitalServiceEntity;
import com.hms.entity.HospitalServiceItem;
import com.hms.entity.InventoryMasterItem;
import com.hms.repository.*;
import com.hms.security.HospitalWebSocketHandler;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class HospitalInventoryServiceConsumeServiceTest {

    @Mock HospitalInventoryRepository hospitalInventoryRepository;
    @Mock InventoryItemRepository inventoryItemRepository;
    @Mock HospitalInventoryPurchaseRepository hospitalInventoryPurchaseRepository;
    @Mock HospitalServiceRepository hospitalServiceRepository;
    @Mock HospitalServiceItemRepository hospitalServiceItemRepository;
    @Mock InventoryMasterItemRepository inventoryMasterItemRepository;
    @Mock SecurityContextHelper securityHelper;
    @Mock AuditLogService auditLogService;
    @Mock HospitalWebSocketHandler webSocketHandler;

    @InjectMocks HospitalInventoryService service;

    private HospitalServiceEntity svc(Long id, String name, String charge) {
        HospitalServiceEntity s = new HospitalServiceEntity();
        s.setId(id); s.setName(name); s.setHospitalId(1L); s.setCharge(new BigDecimal(charge));
        return s;
    }
    private HospitalServiceItem link(Long masterId) {
        HospitalServiceItem l = new HospitalServiceItem();
        l.setMasterItemId(masterId);
        return l;
    }
    private InventoryMasterItem master(Long id, String name) {
        InventoryMasterItem m = new InventoryMasterItem();
        m.setId(id); m.setName(name);
        return m;
    }
    private HospitalInventory stock(Long id, String name, int qty) {
        HospitalInventory s = new HospitalInventory();
        s.setId(id); s.setName(name); s.setHospitalId(1L); s.setStockQuantity(qty); s.setIsActive(true);
        return s;
    }

    @Test
    void consumeService_allItemsInStock_deductsAndReturnsCharge() {
        when(hospitalServiceRepository.findByIdAndHospitalId(9L, 1L)).thenReturn(Optional.of(svc(9L, "Dressing", "150")));
        when(hospitalServiceItemRepository.findByServiceId(9L)).thenReturn(List.of(link(2L), link(3L)));
        when(inventoryMasterItemRepository.findById(2L)).thenReturn(Optional.of(master(2L, "Cotton")));
        when(inventoryMasterItemRepository.findById(3L)).thenReturn(Optional.of(master(3L, "Bandage")));
        HospitalInventory cotton = stock(20L, "Cotton", 50);
        HospitalInventory bandage = stock(21L, "Bandage", 30);
        when(hospitalInventoryRepository.findByNameAndHospitalIdAndIsActiveTrue("Cotton", 1L)).thenReturn(new ArrayList<>(List.of(cotton)));
        when(hospitalInventoryRepository.findByNameAndHospitalIdAndIsActiveTrue("Bandage", 1L)).thenReturn(new ArrayList<>(List.of(bandage)));

        BigDecimal charge = service.consumeService(9L, 1, 1L);

        assertThat(charge).isEqualByComparingTo("150");
        assertThat(cotton.getStockQuantity()).isEqualTo(49);
        assertThat(bandage.getStockQuantity()).isEqualTo(29);
    }

    @Test
    void consumeService_itemOutOfStock_throwsAndDeductsNothing() {
        when(hospitalServiceRepository.findByIdAndHospitalId(9L, 1L)).thenReturn(Optional.of(svc(9L, "Dressing", "150")));
        when(hospitalServiceItemRepository.findByServiceId(9L)).thenReturn(List.of(link(2L), link(3L)));
        when(inventoryMasterItemRepository.findById(2L)).thenReturn(Optional.of(master(2L, "Cotton")));
        when(inventoryMasterItemRepository.findById(3L)).thenReturn(Optional.of(master(3L, "Bandage")));
        when(hospitalInventoryRepository.findByNameAndHospitalIdAndIsActiveTrue("Cotton", 1L)).thenReturn(new ArrayList<>(List.of(stock(20L, "Cotton", 50))));
        when(hospitalInventoryRepository.findByNameAndHospitalIdAndIsActiveTrue("Bandage", 1L)).thenReturn(new ArrayList<>()); // no bandage stock

        assertThatThrownBy(() -> service.consumeService(9L, 1, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("out of stock");
    }

    @Test
    void consumeService_quantityExceedsStock_throws() {
        when(hospitalServiceRepository.findByIdAndHospitalId(9L, 1L)).thenReturn(Optional.of(svc(9L, "Dressing", "150")));
        when(hospitalServiceItemRepository.findByServiceId(9L)).thenReturn(List.of(link(2L)));
        when(inventoryMasterItemRepository.findById(2L)).thenReturn(Optional.of(master(2L, "Cotton")));
        when(hospitalInventoryRepository.findByNameAndHospitalIdAndIsActiveTrue("Cotton", 1L)).thenReturn(new ArrayList<>(List.of(stock(20L, "Cotton", 2))));

        assertThatThrownBy(() -> service.consumeService(9L, 3, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("out of stock");
    }

    @Test
    void consumeService_serviceNotFound_throws() {
        when(hospitalServiceRepository.findByIdAndHospitalId(99L, 1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.consumeService(99L, 1, 1L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Service not found");
    }

    @Test
    void getLowStockItems_returnsBelowMinLevel() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(1L);
        HospitalInventory low = stock(1L, "Cotton", 5); low.setMinStockLevel(10);
        HospitalInventory good = stock(2L, "Gauze", 40); good.setMinStockLevel(10);
        when(hospitalInventoryRepository.findByHospitalId(1L)).thenReturn(List.of(low, good));

        List<HospitalInventory> result = service.getLowStockItems();

        assertThat(result).containsExactly(low);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && mvn test -Dtest=HospitalInventoryServiceConsumeServiceTest -q`
Expected: FAIL (compile error) — the new methods + injected repos don't exist yet.

- [ ] **Step 3: Add the repos + methods to `HospitalInventoryService`**

In `backend/src/main/java/com/hms/service/hospital/HospitalInventoryService.java`, add three new `@Autowired` fields near the existing ones (after `inventoryItemRepository`):

```java
    @Autowired
    private com.hms.repository.HospitalServiceRepository hospitalServiceRepository;

    @Autowired
    private com.hms.repository.HospitalServiceItemRepository hospitalServiceItemRepository;

    @Autowired
    private com.hms.repository.InventoryMasterItemRepository inventoryMasterItemRepository;
```

Add these two public methods near the end of the class (before the closing brace):

```java
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
                .orElseThrow(() -> new RuntimeException("Service not found: " + serviceId));

        java.util.List<com.hms.entity.HospitalServiceItem> links = hospitalServiceItemRepository.findByServiceId(serviceId);

        // Resolve each relevant item's name + its available stock rows, and
        // validate availability BEFORE deducting anything.
        java.util.List<String> shortNames = new java.util.ArrayList<>();
        java.util.Map<String, java.util.List<com.hms.entity.HospitalInventory>> stocksByName = new java.util.LinkedHashMap<>();
        for (com.hms.entity.HospitalServiceItem link : links) {
            java.util.Optional<com.hms.entity.InventoryMasterItem> masterOpt = inventoryMasterItemRepository.findById(link.getMasterItemId());
            if (!masterOpt.isPresent()) continue;
            String itemName = masterOpt.get().getName();
            java.util.List<com.hms.entity.HospitalInventory> stocks = hospitalInventoryRepository.findByNameAndHospitalIdAndIsActiveTrue(itemName, hospitalId);
            int available = 0;
            for (com.hms.entity.HospitalInventory s : stocks) {
                available += (s.getStockQuantity() != null ? s.getStockQuantity() : 0);
            }
            if (available < quantity) {
                shortNames.add(itemName);
            }
            stocksByName.put(itemName, stocks);
        }

        if (!shortNames.isEmpty()) {
            throw new IllegalArgumentException("Some items are out of stock: " + String.join(", ", shortNames));
        }

        // All available -- deduct FEFO.
        for (java.util.Map.Entry<String, java.util.List<com.hms.entity.HospitalInventory>> entry : stocksByName.entrySet()) {
            java.util.List<com.hms.entity.HospitalInventory> stocks = entry.getValue();
            stocks.sort((a, b) -> {
                if (a.getExpiryDate() == null && b.getExpiryDate() == null) return a.getId().compareTo(b.getId());
                if (a.getExpiryDate() == null) return 1;
                if (b.getExpiryDate() == null) return -1;
                return a.getExpiryDate().compareTo(b.getExpiryDate());
            });
            int required = quantity;
            for (com.hms.entity.HospitalInventory s : stocks) {
                if (required <= 0) break;
                int avail = s.getStockQuantity() != null ? s.getStockQuantity() : 0;
                if (avail <= 0) continue;
                int toDeduct = Math.min(avail, required);
                s.setStockQuantity(avail - toDeduct);
                hospitalInventoryRepository.save(s);
                required -= toDeduct;
                try {
                    auditLogService.logAction(
                        "INVENTORY_DEDUCTED",
                        "Deducted " + toDeduct + " units of " + s.getName() + " for service '" + svc.getName() + "'. Stock: " + avail + " -> " + s.getStockQuantity(),
                        securityHelper.getCurrentUserEmail(), hospitalId, "INVENTORY", s.getId().toString(), null);
                } catch (Exception e) {
                    logger.warn("Failed to write audit log for service item deduction", e);
                }
            }
        }

        return svc.getCharge().multiply(java.math.BigDecimal.valueOf(quantity));
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
```

- [ ] **Step 4: Add the low-stock controller endpoint**

In `backend/src/main/java/com/hms/controller/hospital/HospitalInventoryController.java`, add a new endpoint after the existing `getInventoryItems` GET (before the closing brace):

```java
    @GetMapping("/low-stock")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR', 'RECEPTIONIST')")
    public ResponseEntity<List<HospitalInventory>> getLowStockItems() {
        return ResponseEntity.ok(hospitalInventoryService.getLowStockItems());
    }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd backend && mvn test -Dtest=HospitalInventoryServiceConsumeServiceTest -q`
Expected: PASS (5 tests)

- [ ] **Step 6: Compile check**

Run: `cd backend && mvn -q -o clean compile`
Expected: no output = success.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/hms/service/hospital/HospitalInventoryService.java backend/src/main/java/com/hms/controller/hospital/HospitalInventoryController.java backend/src/test/java/com/hms/service/hospital/HospitalInventoryServiceConsumeServiceTest.java
git commit -m "Add consumeService (service-based deduction) and low-stock endpoint"
```

---

## Task 7: Switch OPD consultation to service-based consumption

**Files:**
- Modify: `backend/src/main/java/com/hms/dto/ConsultationRequest.java`
- Modify: `backend/src/main/java/com/hms/service/hospital/DoctorService.java`

**No test file** — `DoctorService.submitConsultation` has no test harness; verified live in Task 10.

- [ ] **Step 1: Change the DTO's inventory item shape to carry a serviceId**

In `backend/src/main/java/com/hms/dto/ConsultationRequest.java`, find the `HospitalInventoryItem` inner class (currently `{ Long stockId; String name; Integer quantity; }`) and replace it with:

```java
    public static class HospitalInventoryItem {
        @NotNull(message = "Service ID is required")
        private Long serviceId;

        @NotNull(message = "Quantity is required")
        @Min(value = 1, message = "Quantity must be positive")
        private Integer quantity;
    }
```

- [ ] **Step 2: Rework the OPD stock-deduction block**

In `backend/src/main/java/com/hms/service/hospital/DoctorService.java`, find the block that iterates `request.getHospitalInventoryItems()` (currently calling `hospitalInventoryService.consumeChargeableItem(...)` and building a `BillingItem` fallback). Read the file to locate it by the comment `Process Hospital Inventory Items Used`. Replace the entire `if (bill != null && request.getHospitalInventoryItems() != null ...) { ... }` block with:

```java
            // --- Process Services Used (charge + relevant-item stock deduction) ---
            if (bill != null && request.getHospitalInventoryItems() != null && !request.getHospitalInventoryItems().isEmpty()) {
                for (com.hms.dto.ConsultationRequest.HospitalInventoryItem item : request.getHospitalInventoryItems()) {
                    java.math.BigDecimal serviceCharge = hospitalInventoryService.consumeService(
                            item.getServiceId(), item.getQuantity(), hospitalId);

                    com.hms.entity.HospitalServiceEntity svc = hospitalServiceRepository.findByIdAndHospitalId(item.getServiceId(), hospitalId).orElse(null);
                    String svcName = svc != null ? svc.getName() : ("Service #" + item.getServiceId());

                    com.hms.entity.BillingItem bi = new com.hms.entity.BillingItem();
                    bi.setBillingId(bill.getId());
                    bi.setHospitalId(hospitalId);
                    bi.setDescription(svcName + " (Qty: " + item.getQuantity() + ")");
                    bi.setAmount(serviceCharge);
                    billingItemRepository.save(bi);
                }
            }
```

Add an autowired `HospitalServiceRepository` field to `DoctorService` if not already present (near its other repository fields):

```java
    @Autowired
    private com.hms.repository.HospitalServiceRepository hospitalServiceRepository;
```

Note: the service charge is now billed directly here (not via the frontend `charges` array). The old `charges`-array-based inventory fee flow for hospital items is replaced by this direct billing. The standard-fee `charges` (consultation/case-paper) processing elsewhere in the method is untouched.

- [ ] **Step 3: Compile check**

Run: `cd backend && mvn -q -o clean compile`
Expected: no output = success.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/hms/dto/ConsultationRequest.java backend/src/main/java/com/hms/service/hospital/DoctorService.java
git commit -m "Switch OPD consultation to service-based inventory consumption"
```

---

## Task 8: Switch IPD administration to service-based consumption

**Files:**
- Modify: `backend/src/main/java/com/hms/dto/AdministerHospitalItemsRequest.java`
- Modify: `backend/src/main/java/com/hms/service/hospital/IpdAdmissionService.java`

**No test file** — verified live in Task 10.

- [ ] **Step 1: Change the IPD request DTO to carry serviceId**

In `backend/src/main/java/com/hms/dto/AdministerHospitalItemsRequest.java`, find the `HospitalItem` inner class (currently `{ Long stockId; String name; Integer quantity; String feeName; BigDecimal feeAmount; }`) and replace it with:

```java
    public static class HospitalItem {
        @NotNull(message = "Service ID is required")
        private Long serviceId;

        @NotNull(message = "Quantity is required")
        @Min(value = 1, message = "Quantity must be positive")
        private Integer quantity;
    }
```

(Remove the now-unused `name`/`feeName`/`feeAmount` fields; keep the `jakarta.validation` imports.)

- [ ] **Step 2: Rework the IPD administration block**

In `backend/src/main/java/com/hms/service/hospital/IpdAdmissionService.java`, find the block inside `administerHospitalItems` that iterates `items` (currently calling `consumeChargeableItem` and building a `BillingItem` with `feeAmount`). Replace the entire `if (items != null && !items.isEmpty()) { for (...) { ... } }` loop body with:

```java
        if (items != null && !items.isEmpty()) {
            for (com.hms.dto.AdministerHospitalItemsRequest.HospitalItem item : items) {
                java.math.BigDecimal serviceCharge = hospitalInventoryService.consumeService(
                        item.getServiceId(), item.getQuantity(), hospitalId);

                if (hasBillingModule && ipdBill != null) {
                    com.hms.entity.HospitalServiceEntity svc = hospitalServiceRepository.findByIdAndHospitalId(item.getServiceId(), hospitalId).orElse(null);
                    String svcName = svc != null ? svc.getName() : ("Service #" + item.getServiceId());
                    com.hms.entity.BillingItem bi = new com.hms.entity.BillingItem();
                    bi.setBillingId(ipdBill.getId());
                    bi.setHospitalId(hospitalId);
                    bi.setDescription(svcName + " (Qty: " + item.getQuantity() + ")");
                    bi.setAmount(serviceCharge);
                    billingItemRepository.save(bi);
                }
            }
```

(Leave the closing braces and the subsequent `recalculateTotal` / websocket broadcast sections untouched.)

Add an autowired `HospitalServiceRepository` field to `IpdAdmissionService` if not already present:

```java
    @Autowired
    private com.hms.repository.HospitalServiceRepository hospitalServiceRepository;
```

- [ ] **Step 3: Compile check**

Run: `cd backend && mvn -q -o clean compile`
Expected: no output = success.

- [ ] **Step 4: Run full backend test suite**

Run: `cd backend && mvn test -q`
Expected: BUILD SUCCESS. (Some old tests referencing the removed inventory-item consumption paths may still pass since those methods aren't deleted yet — that happens in Task 9.)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/hms/dto/AdministerHospitalItemsRequest.java backend/src/main/java/com/hms/service/hospital/IpdAdmissionService.java
git commit -m "Switch IPD administration to service-based inventory consumption"
```

---

## Task 9: Remove old catalog code (inventory_items / hasOwnStock / templates / duplicate / consumeChargeableItem)

**Files:**
- Modify: `backend/src/main/java/com/hms/controller/hospital/HospitalInventoryController.java`
- Modify: `backend/src/main/java/com/hms/service/hospital/HospitalInventoryService.java`
- Delete: `backend/src/test/java/com/hms/service/hospital/HospitalInventoryServiceConsumeTest.java`
- Delete: `backend/src/test/java/com/hms/service/hospital/HospitalInventoryServiceTemplateTest.java`

Now that OPD/IPD use `consumeService`, the old catalog/consume/template/duplicate code is dead and can be removed. The `InventoryItem` entity + `inventory_items` table are LEFT in place (per the spec's non-breaking guarantee — residual rows harmlessly ignored; the auto-catalog-on-purchase behavior is also left, since it writes to a now-unused table without erroring).

- [ ] **Step 1: Remove the dead catalog + consume + template + duplicate methods from the service**

In `backend/src/main/java/com/hms/service/hospital/HospitalInventoryService.java`, delete these methods (read the file to find their exact spans): `searchInventoryCatalog`, `getCatalogItems`, `addCatalogItem`, `updateCatalogItem`, `deleteCatalogItem`, `degradeRelativeItems`, `consumeChargeableItem`, `getCatalogTemplates`, `duplicateCatalogItem`. Keep everything else (purchases, active-stock CRUD, `consumeService`, `getLowStockItems`). If any now-unused imports remain (e.g. `InventoryTemplateDTO`), remove them.

- [ ] **Step 2: Remove the corresponding controller endpoints**

In `backend/src/main/java/com/hms/controller/hospital/HospitalInventoryController.java`, delete the catalog-related endpoints: `searchInventoryCatalog` (`/search`), `getCatalogItems` (`GET /catalog`), `addCatalogItem` (`POST /catalog`), `updateCatalogItem` (`PUT /catalog/{id}`), `deleteCatalogItem` (`DELETE /catalog/{id}`), `getCatalogTemplates` (`GET /catalog/templates`), `duplicateCatalogItem` (`GET /catalog/{id}/duplicate`). Keep purchases, active-stock CRUD, and the new `/low-stock` endpoint. Remove the now-unused `InventoryItem` import if nothing else uses it.

- [ ] **Step 3: Delete the obsolete tests**

```bash
cd e:/Projects/HOSPITAL
rm backend/src/test/java/com/hms/service/hospital/HospitalInventoryServiceConsumeTest.java
rm backend/src/test/java/com/hms/service/hospital/HospitalInventoryServiceTemplateTest.java
```

(These tested `consumeChargeableItem`/`getCatalogTemplates`/`duplicateCatalogItem`, all now removed.)

- [ ] **Step 4: Remove the `InventoryTemplateDTO` (no longer referenced)**

```bash
rm backend/src/main/java/com/hms/dto/InventoryTemplateDTO.java
```

- [ ] **Step 5: Compile + full test suite**

Run: `cd backend && mvn -q -o clean compile && mvn test -q`
Expected: both succeed. Grep to confirm nothing else references the removed methods: `grep -rn "consumeChargeableItem\|degradeRelativeItems\|getCatalogTemplates\|duplicateCatalogItem\|addCatalogItem\|updateCatalogItem" backend/src/main/java` should return zero hits.

- [ ] **Step 6: Commit**

```bash
git add -A backend/src/main/java/com/hms/service/hospital/HospitalInventoryService.java backend/src/main/java/com/hms/controller/hospital/HospitalInventoryController.java backend/src/main/java/com/hms/dto/InventoryTemplateDTO.java backend/src/test/java/com/hms/service/hospital/HospitalInventoryServiceConsumeTest.java backend/src/test/java/com/hms/service/hospital/HospitalInventoryServiceTemplateTest.java
git commit -m "Remove old catalog/hasOwnStock/template/duplicate/consumeChargeableItem code"
```

(Using `git add -A` on the specific listed paths stages the deletions correctly.)

---

## Task 10: Backend live verification against a real database

**Files:** none (verification only)

- [ ] **Step 1: Restart backend, confirm migrations**

Stop the running backend (`netstat -ano | grep :8080`, stop the PID), then `cd backend && (mvn -q spring-boot:run > /tmp/inv2-verify.log 2>&1 &)`. Wait for `Started HospitalManagementSystemApplication`, then `grep "inventory_master_items\|hospital_services" /tmp/inv2-verify.log` — expect the three "table created" log lines (or none if already created).

- [ ] **Step 2: Verify schema**

`mysql -u root -p -D <db> -e "DESCRIBE inventory_master_items; DESCRIBE hospital_services; DESCRIBE hospital_service_items;"` — confirm all three exist with the expected columns.

- [ ] **Step 3: Platform global-item endpoints**

Craft a `SUPER_ADMIN` JWT. `POST /platform/inventory-items {"name":"Cotton"}` → 200; again with "Cotton" → 400 (duplicate). `POST /platform/inventory-items/import-csv` with a small CSV (`name\nSyringe 5ml\nBandage`) → 200 with `imported`/`skipped` counts. `GET /platform/inventory-items` → lists all three ordered.

- [ ] **Step 4: Hospital service + consumption end-to-end**

Craft a `HOSPITAL_ADMIN` JWT for a real hospital. Note the master-item ids from `GET /hospital/inventory-master`. Add per-hospital stock for Cotton/Bandage via `POST /hospital/hospital-inventory/purchases` (quantity 50/30, minStockLevel 10). `POST /hospital/services {"name":"Dressing","charge":150,"masterItemIds":[<cottonId>,<bandageId>]}` → 200. Then submit an OPD consultation (as in prior sessions) with `hospitalInventoryItems:[{"serviceId":<id>,"quantity":1}]` and a real opdId. Expect 200; verify Cotton/Bandage each dropped by 1 and the bill has a "Dressing (Qty: 1)" line = ₹150.

- [ ] **Step 5: Out-of-stock rejection**

Set a relevant item's stock to 0 (`UPDATE hospital_inventory SET stock_quantity=0 WHERE name='Bandage' ...`), submit the same service consultation → expect a 400/500 whose message contains "Some items are out of stock" and NO stock change / NO bill line. Then `GET /hospital/inventory/low-stock` → includes Bandage.

- [ ] **Step 6: Full suite**

`cd backend && mvn test -q` → all pass. Clean up all test rows created (services, master items, stock, purchases, bills, opds) per this project's cleanup discipline.

---

## Task 11: Frontend — API service functions

**Files:**
- Modify: `frontend/src/services/platformService.js`
- Modify: `frontend/src/services/hospitalService.js`

- [ ] **Step 1: Add platform inventory-item functions**

In `frontend/src/services/platformService.js`, add (near the existing medicine functions):

```javascript
    getInventoryItems: async () => {
        const response = await apiClient.get('/platform/inventory-items');
        return response.data;
    },

    createInventoryItem: async (name) => {
        const response = await apiClient.post('/platform/inventory-items', { name });
        return response.data;
    },

    deleteInventoryItem: async (id) => {
        const response = await apiClient.delete(`/platform/inventory-items/${id}`);
        return response.data;
    },

    importInventoryItemsCsv: async (file) => {
        const formData = new FormData();
        formData.append('file', file);
        const response = await apiClient.post('/platform/inventory-items/import-csv', formData, {
            headers: { 'Content-Type': 'multipart/form-data' }
        });
        return response.data;
    },
```

(Match the exact `apiClient`/export style already used in that file — read the medicine CSV function first for the multipart header convention.)

- [ ] **Step 2: Add hospital service + master + low-stock functions**

In `frontend/src/services/hospitalService.js`, add (near the existing hospital-inventory functions, replacing nothing):

```javascript
    getInventoryMasterItems: async () => {
        const response = await apiClient.get('/hospital/inventory-master');
        return response.data;
    },

    getHospitalServices: async () => {
        const response = await apiClient.get('/hospital/services');
        return response.data;
    },

    createHospitalService: async (data) => {
        const response = await apiClient.post('/hospital/services', data);
        return response.data;
    },

    updateHospitalService: async (id, data) => {
        const response = await apiClient.put(`/hospital/services/${id}`, data);
        return response.data;
    },

    deleteHospitalService: async (id) => {
        const response = await apiClient.delete(`/hospital/services/${id}`);
        return response.data;
    },

    getLowStockItems: async () => {
        const response = await apiClient.get('/hospital/hospital-inventory/low-stock');
        return response.data;
    },
```

Also remove the now-dead functions from `hospitalService.js`: `searchHospitalInventoryCatalog`, `getHospitalInventoryCatalog`, `addHospitalInventoryCatalog`, `updateHospitalInventoryCatalog`, `deleteHospitalInventoryCatalog`, `getCatalogTemplates`, `duplicateCatalogItem` (their endpoints were removed in Task 9). Grep after removal to confirm no remaining caller references them (later frontend tasks stop using them; if any file still imports them at this point it'll be reworked in Tasks 13/15).

- [ ] **Step 3: Verify + commit**

Run: `cd frontend && npx tsc --noEmit`
Expected: no output = success (if a `.jsx` still calls a removed function, that's fixed in Tasks 13/15 — but tsc on a JS/JSX project without types generally won't fail on that; if the build breaks, proceed to the frontend tasks that fix the callers, then re-verify).

```bash
git add frontend/src/services/platformService.js frontend/src/services/hospitalService.js
git commit -m "Add inventory master-item, service, and low-stock API functions; remove old catalog ones"
```

---

## Task 12: Frontend — Platform Admin "Inventory Items" tab

**Files:**
- Create: `frontend/src/components/PlatformInventoryItemsTab.jsx`
- Modify: `frontend/src/pages/platform/PlatformDashboard.jsx`

- [ ] **Step 1: Create the tab component**

Create `frontend/src/components/PlatformInventoryItemsTab.jsx`:

```jsx
import React, { useState, useEffect, useCallback, useRef } from 'react';
import platformService from '../services/platformService';
import { useToast } from '../context/ToastContext';

/**
 * Platform Admin global inventory-item catalog: list, add-by-name, delete,
 * and CSV bulk import. These names are shared by every hospital.
 */
const PlatformInventoryItemsTab = () => {
    const { success, error: toastError } = useToast();
    const [items, setItems] = useState([]);
    const [loading, setLoading] = useState(true);
    const [newName, setNewName] = useState('');
    const [adding, setAdding] = useState(false);
    const [modalOpen, setModalOpen] = useState(false);
    const fileInputRef = useRef(null);

    const load = useCallback(async () => {
        setLoading(true);
        try {
            const data = await platformService.getInventoryItems();
            setItems(data || []);
        } catch (err) {
            toastError('Failed to load inventory items');
        } finally {
            setLoading(false);
        }
    }, [toastError]);

    useEffect(() => { load(); }, [load]);

    const handleAdd = async (e) => {
        e.preventDefault();
        if (!newName.trim()) return;
        setAdding(true);
        try {
            const created = await platformService.createInventoryItem(newName.trim());
            setItems(prev => [...prev, created].sort((a, b) => a.name.localeCompare(b.name)));
            setNewName('');
            setModalOpen(false);
            success('Item added');
        } catch (err) {
            toastError(err?.response?.data || 'Failed to add item');
        } finally {
            setAdding(false);
        }
    };

    const handleDelete = async (id) => {
        try {
            await platformService.deleteInventoryItem(id);
            setItems(prev => prev.filter(i => i.id !== id));
            success('Item removed');
        } catch (err) {
            toastError('Failed to remove item');
        }
    };

    const handleCsv = async (e) => {
        const file = e.target.files?.[0];
        if (!file) return;
        try {
            const res = await platformService.importInventoryItemsCsv(file);
            success(`Imported ${res.imported ?? 0}, skipped ${res.skipped ?? 0}`);
            load();
        } catch (err) {
            toastError(err?.response?.data || 'CSV import failed');
        } finally {
            if (fileInputRef.current) fileInputRef.current.value = '';
        }
    };

    return (
        <div className="bg-white p-6 rounded-2xl border border-gray-200 shadow-sm space-y-6">
            <div className="flex justify-between items-start">
                <div>
                    <h3 className="text-lg font-semibold text-gray-900 mb-1">Inventory Items</h3>
                    <p className="text-xs text-gray-500">Global item names shared by all hospitals for purchases and services.</p>
                </div>
                <div className="flex gap-2">
                    <label className="px-4 py-2 border border-gray-300 text-gray-700 rounded-lg hover:bg-gray-50 transition font-semibold text-sm cursor-pointer">
                        Import CSV
                        <input ref={fileInputRef} type="file" accept=".csv" onChange={handleCsv} className="hidden" />
                    </label>
                    <button onClick={() => setModalOpen(true)} className="px-4 py-2 bg-gray-950 text-white rounded-lg hover:bg-gray-800 transition font-semibold text-sm">+ Add Item</button>
                </div>
            </div>

            {loading ? (
                <p className="text-sm text-gray-500">Loading...</p>
            ) : items.length === 0 ? (
                <div className="text-center py-8 border border-dashed border-gray-200 rounded-xl bg-gray-50">
                    <p className="text-sm text-gray-500">No inventory items yet. Add one or import a CSV.</p>
                </div>
            ) : (
                <div className="divide-y divide-gray-100">
                    {items.map(item => (
                        <div key={item.id} className="flex justify-between items-center py-2.5">
                            <span className="text-sm text-gray-800">{item.name}</span>
                            <button onClick={() => handleDelete(item.id)} className="text-red-500 hover:text-red-700 text-sm font-medium">Delete</button>
                        </div>
                    ))}
                </div>
            )}

            {modalOpen && (
                <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50 p-4" onClick={() => setModalOpen(false)}>
                    <div className="bg-white rounded-xl shadow-2xl w-full max-w-sm p-6" onClick={e => e.stopPropagation()}>
                        <h3 className="text-lg font-bold text-gray-900 mb-4">Add Inventory Item</h3>
                        <form onSubmit={handleAdd} className="space-y-4">
                            <input type="text" value={newName} onChange={e => setNewName(e.target.value)} placeholder="Item name (e.g. Cotton)" maxLength={255} autoFocus className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-gray-900 outline-none" />
                            <div className="flex gap-2 justify-end">
                                <button type="button" onClick={() => setModalOpen(false)} className="px-4 py-2 text-sm text-gray-600 hover:text-gray-800">Cancel</button>
                                <button type="submit" disabled={adding || !newName.trim()} className="px-4 py-2 bg-gray-950 text-white rounded-lg text-sm font-semibold disabled:opacity-50">Add</button>
                            </div>
                        </form>
                    </div>
                </div>
            )}
        </div>
    );
};

export default PlatformInventoryItemsTab;
```

- [ ] **Step 2: Wire the tab into PlatformDashboard**

In `frontend/src/pages/platform/PlatformDashboard.jsx`: add the import at the top (with the other component imports):

```javascript
import PlatformInventoryItemsTab from '../../components/PlatformInventoryItemsTab';
```

Add a tab entry to the tabs array (after the `medicines` entry, matching the existing `{ id, label }` shape):

```javascript
        { id: 'medicines', label: 'Medicines' },
        { id: 'inventory-items', label: 'Inventory Items' },
```

Find where tab content is rendered (the `activeTab === 'medicines'` render block) and add a sibling block after it:

```jsx
                {activeTab === 'inventory-items' && (
                    <PlatformInventoryItemsTab />
                )}
```

(Read the file to match the exact conditional-render pattern used for the other tabs — if it uses a switch or a map, follow that instead.)

- [ ] **Step 3: Verify + commit**

Run: `cd frontend && npx tsc --noEmit && npx vite build --mode development`
Expected: both succeed.

```bash
git add frontend/src/components/PlatformInventoryItemsTab.jsx frontend/src/pages/platform/PlatformDashboard.jsx
git commit -m "Add Platform Admin Inventory Items tab with CSV import"
```

---

## Task 13: Frontend — "Service Lookup" replaces catalog in HospitalInventoryTab

**Files:**
- Modify: `frontend/src/components/HospitalInventoryTab.jsx`

This is the largest frontend change: rip out the old catalog sub-tab (catalog list, add-catalog modal, template picker, duplicate, stock-type toggle, relative-items catalog search) and replace it with a "Service Lookup" sub-tab (services list + add/edit-service modal whose relevant-items picker searches the global master list). Read the whole file first.

- [ ] **Step 1: Replace catalog state + data with services + master items**

Remove state: `catalogList`, `catalogModal`, `selectedRelativeItems`/`relativeItemSearch`/`showRelativeSuggestions`, `hasOwnStock`, `templates`/`templatePickerOpen`, `availableFees` (services carry their own charge now). Add:

```javascript
    const [servicesList, setServicesList] = useState([]);
    const [masterItems, setMasterItems] = useState([]);
    const [serviceModal, setServiceModal] = useState({ isOpen: false, isEdit: false, data: null });
    const [svcName, setSvcName] = useState('');
    const [svcCharge, setSvcCharge] = useState('');
    const [svcItems, setSvcItems] = useState([]); // [{id, name}]
    const [itemSearch, setItemSearch] = useState('');
    const [showItemSuggest, setShowItemSuggest] = useState(false);
```

- [ ] **Step 2: Replace catalog fetching**

Replace `fetchCatalog`/`fetchFees`/`fetchTemplates` with:

```javascript
    const fetchServices = async () => {
        try {
            const res = await hospitalService.getHospitalServices();
            setServicesList(res || []);
        } catch (err) { console.error(err); }
    };

    const fetchMasterItems = async () => {
        try {
            const res = await hospitalService.getInventoryMasterItems();
            setMasterItems(res || []);
        } catch (err) { console.error(err); }
    };
```

In `loadData`, the catalog sub-tab branch becomes `await Promise.all([fetchServices(), fetchMasterItems()])`; the purchase branch also calls `fetchMasterItems()` (for the purchase autocomplete in Task 14); the inventory branch calls `fetchMasterItems()` too if it needs names.

- [ ] **Step 3: Prefill the service modal on open**

Add an effect: when `serviceModal.isOpen`, if editing, set `svcName`/`svcCharge`/`svcItems` from `serviceModal.data` (`data.name`, `data.charge`, and `data.masterItemIds`+`data.itemNames` zipped into `[{id,name}]`); else clear them. Also reset `itemSearch`/`showItemSuggest`.

```javascript
    useEffect(() => {
        if (serviceModal.isOpen) {
            if (serviceModal.isEdit && serviceModal.data) {
                setSvcName(serviceModal.data.name || '');
                setSvcCharge(serviceModal.data.charge != null ? String(serviceModal.data.charge) : '');
                const ids = serviceModal.data.masterItemIds || [];
                const names = serviceModal.data.itemNames || [];
                setSvcItems(ids.map((id, i) => ({ id, name: names[i] || String(id) })));
            } else {
                setSvcName(''); setSvcCharge(''); setSvcItems([]);
            }
            setItemSearch(''); setShowItemSuggest(false);
        }
    }, [serviceModal.isOpen, serviceModal.isEdit, serviceModal.data]);
```

- [ ] **Step 4: Service submit + delete handlers**

Replace `handleCatalogSubmit`/`handleDeactivateCatalog`/`handleDuplicateCatalog` with:

```javascript
    const handleServiceSubmit = async (e) => {
        e.preventDefault();
        const name = svcName.trim();
        const charge = parseFloat(svcCharge);
        if (!name || isNaN(charge) || charge < 0 || svcItems.length === 0) {
            toastError('Enter a name, a valid charge, and at least one relevant item.');
            return;
        }
        const payload = { name, charge, masterItemIds: svcItems.map(x => x.id) };
        try {
            setLoading(true);
            if (serviceModal.isEdit) {
                await hospitalService.updateHospitalService(serviceModal.data.id, payload);
                success('Service updated.');
            } else {
                await hospitalService.createHospitalService(payload);
                success('Service created.');
            }
            setServiceModal({ isOpen: false, isEdit: false, data: null });
            loadData();
        } catch (err) {
            toastError(err.response?.data || 'Failed to save service.');
        } finally {
            setLoading(false);
        }
    };

    const handleDeleteService = (id) => {
        setConfirmState({
            open: true,
            title: 'Delete Service',
            message: 'Are you sure you want to delete this service?',
            onConfirm: async () => {
                try {
                    await hospitalService.deleteHospitalService(id);
                    success('Service deleted.');
                    setServicesList(prev => prev.filter(s => s.id !== id));
                } catch (err) { toastError('Failed to delete service.'); }
            }
        });
    };
```

- [ ] **Step 5: Rename the sub-tab and rebuild the catalog UI as Service Lookup**

- Change the third sub-tab button label from "Catalog Lookup" to "Service Lookup" (keep `setSubTab('catalog')` id, or rename the id to `'services'` consistently — pick `'services'` and update all `subTab === 'catalog'` / `setSubTab('catalog')` references in the file to `'services'`).
- Replace the catalog list table with a services list table: columns Name / Charge / Relevant Items / Actions. Each row shows `service.name`, `₹service.charge`, `service.itemNames.join(', ')`, and Edit/Delete buttons (`setServiceModal({isOpen:true,isEdit:true,data:service})` / `handleDeleteService(service.id)`).
- Replace the "+ Add Catalog Item"/"Add from Template" buttons with a single "+ Add Service" button → `setServiceModal({ isOpen: true, isEdit: false, data: null })`.
- Replace MODAL 2 (catalog) with a Service modal: a form with Service Name (text), Service Charge (number, min 0), and a Relevant Items search-multi-select that filters `masterItems` by `itemSearch` (excluding already-selected), adds `{id,name}` to `svcItems` on click, and renders removable tags — same interaction the old relative-items picker used, but sourcing from `masterItems` instead of `catalogList`. Submit calls `handleServiceSubmit`.

Use the existing modal/table Tailwind classes from the file for visual consistency (teal buttons, `divide-y`, etc.).

- [ ] **Step 6: Verify + commit**

Run: `cd frontend && npx tsc --noEmit && npx vite build --mode development`
Expected: both succeed.

```bash
git add frontend/src/components/HospitalInventoryTab.jsx
git commit -m "Replace catalog lookup with Service Lookup (services + global-item relevant items)"
```

---

## Task 14: Frontend — Purchase autocomplete from the global master list

**Files:**
- Modify: `frontend/src/components/HospitalInventoryTab.jsx`

- [ ] **Step 1: Point the purchase item-name autocomplete at `masterItems`**

In the Add-Stock (Purchase Intake) modal, the item-name field currently autocompletes against the old catalog (`catalogList`/`searchHospitalInventoryCatalog`). Change it to filter `masterItems` (already fetched in Task 13's `fetchMasterItems`) by the typed query, showing matching global item names; selecting one fills the name field. Remove any dependency on the removed `searchHospitalInventoryCatalog`/`getHospitalInventoryCatalog`.

(The purchase submit itself is unchanged — it still posts to `/hospital/hospital-inventory/purchases` which upserts the per-hospital stock row by name.)

- [ ] **Step 2: Verify + commit**

Run: `cd frontend && npx tsc --noEmit && npx vite build --mode development`
Expected: both succeed.

```bash
git add frontend/src/components/HospitalInventoryTab.jsx
git commit -m "Purchase item-name autocomplete now sources from global master items"
```

---

## Task 15: Frontend — ConsultationModal service search + out-of-stock toast

**Files:**
- Modify: `frontend/src/components/ConsultationModal.jsx`

- [ ] **Step 1: Fetch services + master stock on open**

The modal already fetches hospital inventory stock (`hospitalInventory`) and had a catalog fetch. Replace the catalog fetch with a services fetch: add state `const [hospitalServices, setHospitalServices] = useState([]);` and, in the `isOpen` effect that loaded inventory/catalog, load `hospitalService.getHospitalServices()` into it (remove the `getHospitalInventoryCatalog` call). Keep loading `hospitalInventory` (per-hospital stock) — it's needed for the out-of-stock check.

- [ ] **Step 2: Rework the "Items Used" search to list Services**

Replace the search block (which currently searches `hospitalInventoryCatalog`) so it filters `hospitalServices` by name against the search text. For each service result, compute whether it's addable: for every relevant item name in `service.itemNames`, sum the matching `hospitalInventory` stock (`s.name` case-insensitive equals the item name, `stockQuantity`), and mark the service `outOfStock` if ANY relevant item's total available `< qtyToAdd` (qtyToAdd starts at 1). Show the service name and, if out of stock, a muted "Out of stock" label.

On click:
- If out of stock → `toastError('Some items are out of stock')` and do not add.
- Else add `{ serviceId: service.id, name: service.name, qty: 1, charge: service.charge, itemNames: service.itemNames }` to the selected list (`hospitalInvItems`), or increment its qty if already present (re-checking stock for the new qty; if the increment would exceed available, toast and don't increment).

- [ ] **Step 3: Update the selected-items list + charge display**

The selected-items table now shows `item.name`, a qty stepper (the `+` re-checks stock across relevant items and toasts "Some items are out of stock" if insufficient), and charge `₹(item.charge * item.qty)`. Key rows by `item.serviceId`.

- [ ] **Step 4: Update the submit payload**

Both `payload.hospitalInventoryItems` builders (OPD submit + IPD-admit submit) become:

```javascript
        payload.hospitalInventoryItems = hospitalInvItems.map(item => ({
            serviceId: item.serviceId,
            quantity: item.qty
        }));
```

Also remove the old `inventoryCharges`/`charges`-array contribution for hospital items — the service charge is now billed server-side by `consumeService`, so do NOT add inventory items into `payload.charges` (leave the standard consultation/case-paper `appliedCharges` in `payload.charges` untouched).

- [ ] **Step 5: Verify + commit**

Run: `cd frontend && npx tsc --noEmit && npx vite build --mode development`
Expected: both succeed.

```bash
git add frontend/src/components/ConsultationModal.jsx
git commit -m "Consultation Items Used now picks Services with out-of-stock guard"
```

---

## Task 16: Frontend — low-stock alert banner (role/tenant-gated)

**Files:**
- Create: `frontend/src/components/LowStockBanner.jsx`
- Modify: `frontend/src/pages/hospital/HospitalAdminDashboard.jsx`
- Modify: `frontend/src/pages/hospital/ReceptionistDashboard.jsx`
- Modify: `frontend/src/pages/hospital/DoctorDashboard.jsx`

- [ ] **Step 1: Create the banner component**

Create `frontend/src/components/LowStockBanner.jsx`:

```jsx
import React, { useState, useEffect } from 'react';
import hospitalService from '../services/hospitalService';

/**
 * Dashboard banner listing hospital-inventory items at/below their min stock
 * level. Rendered only where the caller decides (role/tenant-gated). Silent
 * (renders nothing) when there are no low-stock items or the module is off.
 */
const LowStockBanner = () => {
    const [items, setItems] = useState([]);

    useEffect(() => {
        let active = true;
        hospitalService.getLowStockItems()
            .then(data => { if (active) setItems(data || []); })
            .catch(() => { if (active) setItems([]); });
        return () => { active = false; };
    }, []);

    if (!items || items.length === 0) return null;

    return (
        <div className="mb-4 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3">
            <div className="text-sm font-semibold text-amber-800 mb-1">Low stock alert</div>
            <div className="text-xs text-amber-700">
                {items.map(i => `${i.name} (${i.stockQuantity} left, min ${i.minStockLevel})`).join(' · ')}
            </div>
        </div>
    );
};

export default LowStockBanner;
```

- [ ] **Step 2: Render it, gated by role + tenant type**

In each of the three dashboards, import `LowStockBanner` and render it near the top of the Overview content (only when the hospital-inventory module is enabled — reuse whatever `modules.includes('HOSPITAL_INVENTORY')` check the file already uses). Gating rules:

- `HospitalAdminDashboard.jsx`: render whenever the module is on (admin sees it for both hospital and clinic).
- `ReceptionistDashboard.jsx`: render whenever the module is on (receptionist sees it for both hospital and clinic).
- `DoctorDashboard.jsx`: render ONLY when `user?.hospitalType === 'CLINIC'` AND the module is on (doctor sees it for clinic only, per spec).

Read each file to find the Overview render location and the existing `user`/`modules` accessors; place `{moduleOn && <LowStockBanner />}` (plus the `hospitalType === 'CLINIC'` condition for the doctor) accordingly.

- [ ] **Step 3: Verify + commit**

Run: `cd frontend && npx tsc --noEmit && npx vite build --mode development`
Expected: both succeed.

```bash
git add frontend/src/components/LowStockBanner.jsx frontend/src/pages/hospital/HospitalAdminDashboard.jsx frontend/src/pages/hospital/ReceptionistDashboard.jsx frontend/src/pages/hospital/DoctorDashboard.jsx
git commit -m "Add role/tenant-gated low-stock alert banner to dashboards"
```

---

## Task 17: Full-stack live verification

**Files:** none (verification only)

- [ ] **Step 1: Restart both servers cleanly** (backend `mvn spring-boot:run`, frontend `npm run dev`), waiting for ready log lines.

- [ ] **Step 2: Platform Admin** — as SUPER_ADMIN in the browser, open the new "Inventory Items" tab; add "Cotton" via the form; import a small CSV (Syringe, Bandage); confirm the list shows all three and delete works. Screenshot.

- [ ] **Step 3: Hospital setup** — as HOSPITAL_ADMIN, Hospital Inventory → Purchase: add stock for Cotton/Bandage (autocomplete now lists the global names) with min level 10; Service Lookup: "+ Add Service" → name "Dressing", charge 150, relevant items Cotton + Bandage (searched from global list); save; confirm the service row shows the charge and the two item names. Screenshot.

- [ ] **Step 4: Consultation** — open a doctor consultation, Items Used search shows "Dressing" (a Service); select it → added with ₹150; submit → confirm (via SQL/curl) Cotton/Bandage each dropped by 1 and the bill has a ₹150 "Dressing" line. Then set Bandage stock to 0, reopen a consultation, try to add "Dressing" → confirm the **"Some items are out of stock"** toast appears and it is NOT added.

- [ ] **Step 5: Low-stock banner** — with Bandage below min level, confirm the banner appears on the Hospital Admin and Receptionist dashboards; for a CLINIC-type login, confirm it also appears for the Doctor; for a HOSPITAL-type login, confirm it does NOT appear for the Doctor.

- [ ] **Step 6: Final build + test** — `cd backend && mvn -q -o clean compile && mvn test -q` (all pass); `cd frontend && npx tsc --noEmit && npx vite build --mode development` (both succeed). Clean up all test data (platform items, services, stock, purchases, bills, opds).
