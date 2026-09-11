# Nursing Management — Phase C Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the four-state bed lifecycle (Available / Occupied / Cleaning Required / Under Maintenance) with a cleaning workflow, a fully audited `BedStatusService` (dedicated `bed_status_audits` table + per-bed history), Available-only admission selection, an incharge Beds screen, and a `my wards` endpoint.

**Architecture:** A single `BedStatusService.change(bedId, newStatus, remarks)` becomes the only place a bed's status is written. Every call records `previous → new` into `bed_status_audits` plus a best-effort `AuditLogService` entry. System transitions (admit, discharge, transfer, OT) call it directly; user-initiated transitions (mark cleaned, maintenance) additionally pass through `NurseInchargeGuard` ward scoping.

**Tech Stack:** Spring Boot 3.3.5 / Java 17 / Maven / MySQL (JdbcTemplate migrations), JUnit 5 + Mockito + AssertJ. React 18 / Vite, axios (`apiClient`).

---

## Conventions
- Bed status values are **lowercase strings** already in the DB: `"available"`, `"occupied"`, `"maintenance"`. We add `"cleaning"`.
- Tenant scope via `SecurityContextHelper.getCurrentHospitalId()`. Gate `@RequireModule("NURSING")`.
- Errors: `IllegalArgumentException`→400, `UnauthorizedException`→401, `AccessDeniedException`→403.
- Migrations: add `ensureXxxTable()` to `DatabaseMigrationRunner` (copy `ensureShiftTemplatesTable` shape; logger `log`, field `jdbcTemplate`), call from `runMigrations()`, mirror in `setup/schema-full.sql`.
- Build/test: `cd backend && mvn -o test`; `cd frontend && npx vite build --mode development` (never vite from backend).
- **Commit at milestone boundaries** (C1, C2, C3).
- Existing facts: `Bed` has `getBedId()/getHospitalId()/getWardId()/getBedCode()/getStatus()/setStatus(String)/getCurrentIpdAdmissionId()/setCurrentIpdAdmissionId(Long)`. `BedRepository.findById/save/findByWardIdAndHospitalId/findByHospitalIdAndStatus`. `NurseInchargeGuard.assertWardAccess(Long)/myWardIds()`. `AuditLogService.logAction(action, details, email, hospitalId, entityType, entityId, reason)`. Bed writes today: `IpdAdmissionService:168` (`occupied` on admit), `:1094` (`available` on confirmDischarge), `:1201/:1208` (transfer old→available, new→occupied), `SurgeryService:151` (`occupied` on start), `:248` (`available` in `freeOtBed`). `WardRepository.findById`, `Ward.getWardName()`.

---

# Milestone C1 — Status constants, audit table, `BedStatusService`

### Task 1: `BedStatus` constants

**Files:** Create `backend/src/main/java/com/hms/entity/BedStatus.java`

- [ ] **Step 1**
```java
package com.hms.entity;

/** Bed lifecycle states (stored lowercase in bed.status). */
public final class BedStatus {
    public static final String AVAILABLE = "available";
    public static final String OCCUPIED = "occupied";
    public static final String CLEANING = "cleaning";      // vacated, awaiting cleaning
    public static final String MAINTENANCE = "maintenance";
    private BedStatus() {}

    public static boolean isValid(String s) {
        return AVAILABLE.equals(s) || OCCUPIED.equals(s) || CLEANING.equals(s) || MAINTENANCE.equals(s);
    }
}
```
- [ ] **Step 2:** `cd backend && mvn -o -q compile` → BUILD SUCCESS.

---

### Task 2: `BedStatusAudit` entity + repository + migration

**Files:** Create `entity/BedStatusAudit.java`, `repository/BedStatusAuditRepository.java`; modify `DatabaseMigrationRunner`, `setup/schema-full.sql`

- [ ] **Step 1: Entity**
```java
package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor; import lombok.Data; import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;

/** One bed status transition: previous -> new, who, when, why. */
@Entity
@Table(name = "bed_status_audits")
@Data @NoArgsConstructor @AllArgsConstructor
public class BedStatusAudit {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true) private String publicId;
    @Column(name = "hospital_id", nullable = false) private Long hospitalId;
    @Column(name = "bed_id", nullable = false) private Long bedId;
    @Column(name = "ward_id") private Long wardId;
    @Column(name = "previous_status", length = 20) private String previousStatus;
    @Column(name = "new_status", nullable = false, length = 20) private String newStatus;
    @Column(name = "changed_by_user_id") private Long changedByUserId;
    @Column(name = "remarks", length = 255) private String remarks;
    @CreationTimestamp @Column(name = "changed_at", nullable = false, updatable = false) private LocalDateTime changedAt;
    @PrePersist public void pre() { if (publicId == null) publicId = java.util.UUID.randomUUID().toString(); }
}
```
- [ ] **Step 2: Repository**
```java
package com.hms.repository;
import com.hms.entity.BedStatusAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface BedStatusAuditRepository extends JpaRepository<BedStatusAudit, Long> {
    List<BedStatusAudit> findByBedIdOrderByChangedAtDesc(Long bedId);
}
```
- [ ] **Step 3: Migration** — add to `DatabaseMigrationRunner` and call from `runMigrations()`:
```java
    private void ensureBedStatusAuditsTable() {
        try {
            Integer c = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bed_status_audits'", Integer.class);
            if (c != null && c == 0) {
                jdbcTemplate.execute("CREATE TABLE bed_status_audits (" +
                    "id BIGINT NOT NULL AUTO_INCREMENT, public_id VARCHAR(255) NOT NULL, hospital_id BIGINT NOT NULL," +
                    "bed_id BIGINT NOT NULL, ward_id BIGINT, previous_status VARCHAR(20), new_status VARCHAR(20) NOT NULL," +
                    "changed_by_user_id BIGINT, remarks VARCHAR(255), changed_at DATETIME(6) NOT NULL," +
                    "PRIMARY KEY (id), UNIQUE KEY UK_bsa_public (public_id)," +
                    "KEY idx_bsa_bed_time (bed_id, changed_at), KEY idx_bsa_hospital (hospital_id)," +
                    "FOREIGN KEY (hospital_id) REFERENCES hospitals(id) ON DELETE CASCADE)");
                log.info("DB migration applied: bed_status_audits table created");
            }
        } catch (Exception e) { log.warn("DB migration skipped (bed_status_audits): {}", e.getMessage()); }
    }
```
Mirror the CREATE TABLE in `setup/schema-full.sql`.
- [ ] **Step 4:** `mvn -o -q compile` → BUILD SUCCESS.

---

### Task 3: `BedStatusService` (TDD)

**Files:** Create `service/hospital/BedStatusService.java`; Test `BedStatusServiceTest`

- [ ] **Step 1: Failing test** `backend/src/test/java/com/hms/service/hospital/BedStatusServiceTest.java`
```java
package com.hms.service.hospital;

import com.hms.entity.Bed;
import com.hms.entity.BedStatus;
import com.hms.entity.BedStatusAudit;
import com.hms.repository.BedRepository;
import com.hms.repository.BedStatusAuditRepository;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks; import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BedStatusServiceTest {
    @Mock BedRepository bedRepository;
    @Mock BedStatusAuditRepository auditRepository;
    @Mock SecurityContextHelper securityHelper;
    @Mock AuditLogService auditLogService;
    @InjectMocks BedStatusService service;

    private Bed bed(String status) {
        Bed b = new Bed(); b.setBedId(50L); b.setHospitalId(7L); b.setWardId(3L); b.setStatus(status);
        return b;
    }

    @Test void change_recordsPreviousAndNew_andSavesAudit() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(securityHelper.getCurrentUserId()).thenReturn(20L);
        Bed b = bed(BedStatus.OCCUPIED);
        when(bedRepository.findById(50L)).thenReturn(Optional.of(b));
        when(bedRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(auditRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.change(50L, BedStatus.CLEANING, "IPD discharge");

        assertThat(b.getStatus()).isEqualTo(BedStatus.CLEANING);
        ArgumentCaptor<BedStatusAudit> cap = ArgumentCaptor.forClass(BedStatusAudit.class);
        verify(auditRepository).save(cap.capture());
        assertThat(cap.getValue().getPreviousStatus()).isEqualTo(BedStatus.OCCUPIED);
        assertThat(cap.getValue().getNewStatus()).isEqualTo(BedStatus.CLEANING);
        assertThat(cap.getValue().getChangedByUserId()).isEqualTo(20L);
        assertThat(cap.getValue().getRemarks()).isEqualTo("IPD discharge");
    }

    @Test void change_rejectsUnknownStatus() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(bedRepository.findById(50L)).thenReturn(Optional.of(bed(BedStatus.AVAILABLE)));
        assertThatThrownBy(() -> service.change(50L, "sparkly", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void change_rejectsCrossTenantBed() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        Bed b = bed(BedStatus.AVAILABLE); b.setHospitalId(999L);
        when(bedRepository.findById(50L)).thenReturn(Optional.of(b));
        assertThatThrownBy(() -> service.change(50L, BedStatus.MAINTENANCE, null))
                .isInstanceOf(com.hms.exception.UnauthorizedException.class);
    }
}
```
Run — expect FAIL.

- [ ] **Step 2: Implement**
```java
package com.hms.service.hospital;

import com.hms.entity.Bed;
import com.hms.entity.BedStatus;
import com.hms.entity.BedStatusAudit;
import com.hms.exception.UnauthorizedException;
import com.hms.repository.BedRepository;
import com.hms.repository.BedStatusAuditRepository;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * BedStatusService - the single place a bed's status changes (Nursing Mgmt
 * Phase C). Every transition records previous -> new into bed_status_audits
 * plus a best-effort general audit entry. Ward scoping for user-initiated
 * changes is applied by the caller (BedController) via NurseInchargeGuard;
 * system transitions (admit/discharge/transfer/OT) call change() directly.
 */
@Service
public class BedStatusService {

    private static final Logger logger = LoggerFactory.getLogger(BedStatusService.class);

    @Autowired private BedRepository bedRepository;
    @Autowired private BedStatusAuditRepository auditRepository;
    @Autowired private SecurityContextHelper securityHelper;
    @Autowired private AuditLogService auditLogService;

    @Transactional
    public Bed change(Long bedId, String newStatus, String remarks) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        Bed bed = bedRepository.findById(bedId)
                .orElseThrow(() -> new IllegalArgumentException("Bed not found"));
        if (hospitalId != null && !hospitalId.equals(bed.getHospitalId())) {
            throw new UnauthorizedException("Bed belongs to another hospital");
        }
        if (!BedStatus.isValid(newStatus)) throw new IllegalArgumentException("Unknown bed status: " + newStatus);

        String previous = bed.getStatus();
        bed.setStatus(newStatus);
        if (!BedStatus.OCCUPIED.equals(newStatus)) bed.setCurrentIpdAdmissionId(null);
        Bed saved = bedRepository.save(bed);

        BedStatusAudit a = new BedStatusAudit();
        a.setHospitalId(bed.getHospitalId());
        a.setBedId(bed.getBedId());
        a.setWardId(bed.getWardId());
        a.setPreviousStatus(previous);
        a.setNewStatus(newStatus);
        a.setChangedByUserId(safeUserId());
        a.setRemarks(remarks);
        auditRepository.save(a);

        try {
            auditLogService.logAction("BED_STATUS_CHANGED", previous + " -> " + newStatus,
                    securityHelper.getCurrentUserEmail(), bed.getHospitalId(), "BED", String.valueOf(bedId), remarks);
        } catch (Exception e) { logger.warn("Bed status audit log failed: {}", e.getMessage()); }
        return saved;
    }

    public List<BedStatusAudit> history(Long bedId) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        Bed bed = bedRepository.findById(bedId).orElseThrow(() -> new IllegalArgumentException("Bed not found"));
        if (hospitalId != null && !hospitalId.equals(bed.getHospitalId())) {
            throw new UnauthorizedException("Bed belongs to another hospital");
        }
        return auditRepository.findByBedIdOrderByChangedAtDesc(bedId);
    }

    private Long safeUserId() {
        try { return securityHelper.getCurrentUserId(); } catch (Exception e) { return null; }
    }
}
```
NOTE: `change()` clears `currentIpdAdmissionId` whenever the new status isn't `occupied`. Verify no caller depends on it being preserved.
- [ ] **Step 3:** `mvn -o -q -Dtest=BedStatusServiceTest test` → 3/3 PASS. Then `mvn -o test` → BUILD SUCCESS.
- [ ] **Step 4: Commit C1** — `git add -A && git commit -m "feat(nurse-mgmt): Phase C1 — bed status constants, audit table, BedStatusService"`

---

# Milestone C2 — Route transitions through `BedStatusService`

### Task 4: Discharge + transfer + admit

**Files:** Modify `service/hospital/IpdAdmissionService.java`

- [ ] **Step 1:** Inject `@Autowired private BedStatusService bedStatusService;`.
- [ ] **Step 2:** Replace the raw bed writes:
  - **Admit** (~line 168, currently `bed.setStatus("occupied"); bed.setCurrentIpdAdmissionId(saved.getId()); bedRepository.save(bed);`):
    ```java
        bedStatusService.change(bed.getBedId(), com.hms.entity.BedStatus.OCCUPIED, "IPD admission");
        bed.setCurrentIpdAdmissionId(saved.getId());
        bedRepository.save(bed);
    ```
    (`change()` nulls `currentIpdAdmissionId` for non-occupied only, so setting it after is safe; alternatively set it before calling change — verify the resulting row has both `occupied` and the admission id.)
  - **confirmDischarge** (~line 1094, currently `bed.setStatus("available")`):
    ```java
        bedStatusService.change(bed.getBedId(), com.hms.entity.BedStatus.CLEANING, "IPD discharge");
    ```
    Remove the now-redundant `setStatus` / `setCurrentIpdAdmissionId(null)` / `bedRepository.save(bed)` lines it replaces (keep the surrounding try/catch and its warn log; update the log text to "Failed to mark bed for cleaning during IPD discharge").
  - **Bed transfer** (~line 1201 old bed, ~1208 new bed):
    ```java
        bedStatusService.change(oldBed.getBedId(), com.hms.entity.BedStatus.CLEANING, "Bed transfer (vacated)");
        ...
        bedStatusService.change(newBed.getBedId(), com.hms.entity.BedStatus.OCCUPIED, "Bed transfer");
        newBed.setCurrentIpdAdmissionId(<admissionId>);
        bedRepository.save(newBed);
    ```
- [ ] **Step 3:** `mvn -o test`. `IpdAdmissionServiceTest` mocks may need `@Mock BedStatusService bedStatusService;` added (so `@InjectMocks` wires it) — `change()` returns a Bed; stub with `when(bedStatusService.change(anyLong(), anyString(), any())).thenAnswer(...)` only where the return value is used. If a test asserts `bed.getStatus()` equals `"available"` after discharge, update it to `"cleaning"`. Iterate until the FULL suite is green.

---

### Task 5: OT theatre bed → cleaning

**Files:** Modify `service/hospital/SurgeryService.java`, `test/.../SurgeryServiceTest.java`

- [ ] **Step 1:** Inject `@Autowired private BedStatusService bedStatusService;`.
- [ ] **Step 2:** In `start(...)`, replace `bed.setStatus("occupied"); bed.setCurrentIpdAdmissionId(...); bedRepository.save(bed);` with a `bedStatusService.change(bed.getBedId(), BedStatus.OCCUPIED, "Surgery started")` followed by setting `currentIpdAdmissionId` + save (as in Task 4).
- [ ] **Step 3:** In `freeOtBed(Surgery s)` (currently sets `"available"`), change to:
```java
    private void freeOtBed(Surgery s, String remark) {
        if (s.getOtBedId() == null) return;
        try { bedStatusService.change(s.getOtBedId(), com.hms.entity.BedStatus.CLEANING, remark); }
        catch (Exception e) { logger.warn("Failed to mark OT bed for cleaning: {}", e.getMessage()); }
    }
```
Update its two call sites: `complete(...)` → `freeOtBed(s, "Surgery completed")`; `cancel(...)` → `freeOtBed(s, "Surgery cancelled")`.
- [ ] **Step 4: Update the test.** In `SurgeryServiceTest`:
  - `complete_freesOtBed` asserts `bed.getStatus()).isEqualTo("available")` — the service no longer touches the Bed object directly. Add `@Mock BedStatusService bedStatusService;` and change the assertion to verify the delegation:
    ```java
        verify(bedStatusService).change(eq(50L), eq(com.hms.entity.BedStatus.CLEANING), any());
    ```
    (drop the `bedRepository.findById(50L)` / `bedRepository.save` stubs if they become unnecessary — avoid `UnnecessaryStubbingException`).
  - `start_requiresAvailableOtBed_thenMarksOccupied` — `start` still queries `bedRepository.findByWardIdAndHospitalId(...)` for an `available` bed; keep that stub, and change the "marks occupied" assertion to `verify(bedStatusService).change(eq(50L), eq(com.hms.entity.BedStatus.OCCUPIED), any());`.
  - Rename the tests if their names no longer describe them (e.g. `complete_marksOtBedForCleaning`).
- [ ] **Step 5:** `mvn -o test` → BUILD SUCCESS (full suite).

---

### Task 6: Available-only admission selection

**Files:** Verify/modify `controller/hospital/BedController.java` and any service feeding the admit modal.

- [ ] **Step 1:** Read `BedController` `GET /available` and whatever service it calls. Confirm it returns ONLY beds with `status = "available"` (grep `findByHospitalIdAndStatus("available")` or similar). If it returns beds by ward without a status filter, add the filter. Add a comment: `// Nursing Mgmt: only Available beds may be selected for admission`.
- [ ] **Step 2:** Also confirm `WardService.getWardsForAdmission()` (Phase A) counts only `available` beds when deciding a ward has a free bed; fix if it counts non-available.
- [ ] **Step 3:** `mvn -o test` → BUILD SUCCESS.
- [ ] **Step 4: Commit C2** — `git add -A && git commit -m "feat(nurse-mgmt): Phase C2 — vacated beds require cleaning (discharge/transfer/OT)"`

---

# Milestone C3 — Endpoints, "my wards", incharge Beds screen

### Task 7: Bed status endpoints + `my wards`

**Files:** Create `dto/BedStatusChangeRequest.java`; modify `controller/hospital/BedController.java`; modify `controller/hospital/NurseInchargeController.java` + `NurseWorkspaceService` (my wards)

- [ ] **Step 1: DTO**
```java
package com.hms.dto; import lombok.Data;
@Data public class BedStatusChangeRequest { private String remarks; }
```
- [ ] **Step 2: Bed endpoints** — add to `BedController` (import `NurseInchargeGuard`, `BedStatusService`, `BedStatus`, `BedRepository`). Each loads the bed, ward-guards, validates the transition, then delegates:
```java
    @PostMapping("/{bedId}/cleaned")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')")
    public ResponseEntity<?> markCleaned(@PathVariable Long bedId, @RequestBody(required = false) com.hms.dto.BedStatusChangeRequest req) {
        com.hms.entity.Bed bed = requireBedForWardAccess(bedId);
        if (!com.hms.entity.BedStatus.CLEANING.equals(bed.getStatus()))
            throw new IllegalArgumentException("Only a bed awaiting cleaning can be marked cleaned");
        bedStatusService.change(bedId, com.hms.entity.BedStatus.AVAILABLE, remarksOf(req, "Marked cleaned"));
        return ResponseEntity.ok(java.util.Map.of("message", "Bed marked cleaned"));
    }

    @PostMapping("/{bedId}/maintenance")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')")
    public ResponseEntity<?> markMaintenance(@PathVariable Long bedId, @RequestBody(required = false) com.hms.dto.BedStatusChangeRequest req) {
        com.hms.entity.Bed bed = requireBedForWardAccess(bedId);
        if (com.hms.entity.BedStatus.OCCUPIED.equals(bed.getStatus()))
            throw new IllegalArgumentException("An occupied bed cannot be put under maintenance");
        bedStatusService.change(bedId, com.hms.entity.BedStatus.MAINTENANCE, remarksOf(req, "Under maintenance"));
        return ResponseEntity.ok(java.util.Map.of("message", "Bed under maintenance"));
    }

    @PostMapping("/{bedId}/available")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')")
    public ResponseEntity<?> markAvailable(@PathVariable Long bedId, @RequestBody(required = false) com.hms.dto.BedStatusChangeRequest req) {
        com.hms.entity.Bed bed = requireBedForWardAccess(bedId);
        if (!com.hms.entity.BedStatus.MAINTENANCE.equals(bed.getStatus()))
            throw new IllegalArgumentException("Only a bed under maintenance can be returned to available");
        bedStatusService.change(bedId, com.hms.entity.BedStatus.AVAILABLE, remarksOf(req, "Back to available"));
        return ResponseEntity.ok(java.util.Map.of("message", "Bed available"));
    }

    @GetMapping("/{bedId}/history")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')")
    public ResponseEntity<?> history(@PathVariable Long bedId) {
        requireBedForWardAccess(bedId);
        return ResponseEntity.ok(bedStatusService.history(bedId));
    }

    private com.hms.entity.Bed requireBedForWardAccess(Long bedId) {
        com.hms.entity.Bed bed = bedRepository.findById(bedId)
                .orElseThrow(() -> new IllegalArgumentException("Bed not found"));
        nurseInchargeGuard.assertWardAccess(bed.getWardId());
        return bed;
    }
    private String remarksOf(com.hms.dto.BedStatusChangeRequest req, String fallback) {
        return (req != null && req.getRemarks() != null && !req.getRemarks().isBlank()) ? req.getRemarks() : fallback;
    }
```
If `BedController` lacks `@RequireModule("NURSING")`, do NOT add it at class level (other bed endpoints are used by reception) — annotate only these new methods, or leave ungated but role-guarded. Report your choice.
- [ ] **Step 3: `my wards`** — add to `NurseWorkspaceService`:
```java
    /** The wards the caller is incharge of (all hospital wards for admin), with bed counts. */
    public java.util.List<java.util.Map<String, Object>> getMyWards() {
        java.util.List<Long> wardIds = nurseInchargeGuard.myWardIds();
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        for (Long id : wardIds) {
            wardRepository.findById(id).ifPresent(w -> {
                java.util.Map<String, Object> m = new java.util.HashMap<>();
                m.put("wardId", w.getWardId());
                m.put("wardName", w.getWardName());
                m.put("beds", bedRepository.findByWardIdAndHospitalId(w.getWardId(), w.getHospitalId()));
                out.add(m);
            });
        }
        return out;
    }
```
and to `NurseInchargeController`:
```java
    @GetMapping("/wards")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')")
    public ResponseEntity<?> myWards() { return ResponseEntity.ok(workspaceService.getMyWards()); }
```
- [ ] **Step 4:** `mvn -o test` → BUILD SUCCESS.

---

### Task 8: Frontend — incharge Beds screen + scheduler ward selector

**Files:** modify `frontend/src/services/nurseService.js`; create `frontend/src/pages/hospital/nurse-incharge/WardBedsView.jsx`; modify `NurseInchargeDashboard.jsx`, `nurse-incharge/ShiftScheduleView.jsx`

- [ ] **Step 1: Service methods** (`nurseService.js`)
```javascript
    getMyWards: async () => (await apiClient.get('/hospital/nurse-incharge/wards')).data,
    markBedCleaned: async (bedId, remarks) => (await apiClient.post(`/hospital/beds/${bedId}/cleaned`, { remarks })).data,
    markBedMaintenance: async (bedId, remarks) => (await apiClient.post(`/hospital/beds/${bedId}/maintenance`, { remarks })).data,
    markBedAvailable: async (bedId, remarks) => (await apiClient.post(`/hospital/beds/${bedId}/available`, { remarks })).data,
    getBedHistory: async (bedId) => (await apiClient.get(`/hospital/beds/${bedId}/history`)).data,
```
- [ ] **Step 2: `WardBedsView.jsx`** — ward selector from `getMyWards()`; a grid of that ward's beds (`beds` array on the ward object) showing `bedCode` + a coloured status badge (available=green, occupied=blue, cleaning=amber, maintenance=grey). Actions per bed: **Mark Cleaned** (only when `cleaning`), **Under Maintenance** (when not `occupied`), **Back to Available** (when `maintenance`) — each prompts for optional remarks (a small modal/inline input) then calls the service, toasts, and reloads. A **History** button opens a panel listing `getBedHistory(bedId)` rows: `previousStatus → newStatus`, `changedAt`, `remarks`.
- [ ] **Step 3:** Add a **"Beds"** tab to `NurseInchargeDashboard.jsx` rendering `<WardBedsView />` (match its existing tab pattern).
- [ ] **Step 4:** In `ShiftScheduleView.jsx`, replace the ward-list workaround (derived from `getWardPatients()`) with `nurseService.getMyWards()`.
- [ ] **Step 5:** `cd frontend && npx vite build --mode development` → `✓ built`.
- [ ] **Step 6: Commit C3** — `git add -A && git commit -m "feat(nurse-mgmt): Phase C3 — bed cleaning endpoints, my-wards, incharge Beds screen"`

---

### Task 9: End-to-end verification
- [ ] `cd backend && mvn -o test` → BUILD SUCCESS.
- [ ] `cd frontend && npx vite build --mode development` → `✓ built`.
- [ ] Manual smoke: discharge a patient → their bed shows **Cleaning Required** and no longer appears in the admit modal's bed list; the incharge sees it under Beds, clicks **Mark Cleaned** with a remark → it becomes **Available** and reappears for admission; Bed History shows `occupied → cleaning` (IPD discharge) then `cleaning → available` (the remark). Put a free bed **Under Maintenance** → it disappears from admission selection; **Back to Available** restores it. Complete a surgery → the OT bed becomes Cleaning Required and the next surgery cannot **Start** until it's marked cleaned. Clean up test data.

---

## Self-Review (completed during authoring)
- **Spec coverage:** four statuses + constants → Task 1. `bed_status_audits` + history → Tasks 2, 3, 7. Central audited service → Task 3. Discharge/transfer/OT → cleaning → Tasks 4, 5. Available-only admission → Task 6. Endpoints + ward guard → Task 7. `my wards` → Task 7 Step 3. Incharge Beds screen + scheduler switch → Task 8. Verification → Task 9.
- **Placeholder scan:** none. Test-fix steps name the exact assertions to change (`SurgeryServiceTest` `available` → verify `change(..., CLEANING, ...)`).
- **Type consistency:** `BedStatus.*` constants, `BedStatusService.change(Long,String,String)→Bed` / `history(Long)`, `BedStatusAudit` field names, and the four endpoint paths are consistent across tasks.
- **Flagged for the implementer:** whether `BedController` should be `@RequireModule("NURSING")` at class level (it serves reception too) — annotate methods instead; `change()` nulls `currentIpdAdmissionId` for non-occupied statuses, so set the admission id *after* calling change on admit/transfer; `IpdAdmissionServiceTest` and `SurgeryServiceTest` both need `@Mock BedStatusService`.
