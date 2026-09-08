# Nursing Management — Phase A Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the Nurse Incharge foundation — the `NURSE_INCHARGE` role, ward↔incharge assignment with a ward-scope RBAC guard, admin nurse management (create/promote/demote/activate), the incharge-mediated patient-assignment flow (replacing least-loaded auto-assign), and the Separate Nurse Login toggle with "Performed By" attribution.

**Architecture:** Extends the existing Spring Boot + React HMS. Pragmatic RBAC: existing `@PreAuthorize` + `@RequireModule("NURSING")` + tenant scope, plus a new `NurseInchargeGuard` that restricts a Nurse Incharge to their assigned wards. Nurses are `NurseProfile` rows (staff nurses may have no login); an incharge is a `NurseProfile` with `is_incharge=true` and a `User` of role `NURSE_INCHARGE`. Wards carry `incharge_nurse_id`. Migrations are idempotent (`DatabaseMigrationRunner.ensureXxx`) and mirrored in `setup/schema-full.sql`.

**Tech Stack:** Spring Boot 3.3.5 / Java 17 / Maven / MySQL (JdbcTemplate migrations), JUnit 5 + Mockito + AssertJ. React 18 / Vite, axios (`apiClient`).

---

## Conventions (read once)

- **Tenant isolation:** every query filters by `hospitalId = securityHelper.getCurrentHospitalId()`.
- **Module gate:** nurse-management controllers use `@RequireModule("NURSING")`.
- **Roles:** admin actions `@PreAuthorize("hasRole('HOSPITAL_ADMIN')")`; incharge actions `@PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')")`.
- **Errors:** `IllegalArgumentException`→400, `com.hms.exception.UnauthorizedException`→401, `AccessDeniedException`→403 (handled by `GlobalExceptionHandler`); `ApiResponse.error(msg)` puts text in the **`error`** field.
- **Migrations:** add an idempotent `ensureXxx()` to `DatabaseMigrationRunner`, call it from `runMigrations()`, mirror the DDL in `setup/schema-full.sql`.
- **Audit:** `auditLogService.logAction(action, details, securityHelper.getCurrentUserEmail(), hospitalId, entityType, entityId, reason)` — best-effort, wrapped in try/catch.
- **Build/test:** backend `cd backend && mvn -o test`; frontend `cd frontend && npx vite build --mode development` (never run vite from `backend/`).
- **Existing facts:** `SecurityContextHelper` → `getCurrentHospitalId()/getCurrentUserId()/getCurrentUserEmail()/getCurrentUserRole()`. `NurseProfileRepository` has `findByUserId(Long)`, `findByEmailAndHospitalId`. `WardRepository.findByHospitalId(Long)`. `Ward` has `getWardId()/getHospitalId()/getWardName()`. `NurseAssignmentService.assignNurse(admissionId, nurseUserId, notes)` and `.autoAssignForAdmission(...)` (called at `IpdAdmissionService.java:164`). `AuditLogService.logAction(7 args)`. `NotificationService.create(recipientUserId, hospitalId, type, title, message, refType, refId)`.
- **Commit discipline:** the commit steps below are the intended cadence; if the standing "no commit" rule is active, run everything up to the commit and skip `git commit`.

---

## File Structure

**Backend — new**
- `backend/src/main/java/com/hms/security/NurseInchargeGuard.java` — ward-scope guard.
- `backend/src/main/java/com/hms/service/hospital/PatientAssignmentService.java` — new admission→nurse rule (A2).
- `backend/src/main/java/com/hms/controller/hospital/NurseInchargeController.java` — incharge patient list + assign (A2).
- `backend/src/main/java/com/hms/security/PerformingNurseResolver.java` — "Performed By" resolver (A3).
- DTOs: `AssignPatientNurseRequest`, `CreateInchargeRequest`, `SetWardInchargeRequest`.
- Tests: `NurseInchargeGuardTest`, `PatientAssignmentServiceTest`, `PerformingNurseResolverTest`, plus additions to `NurseServiceTest`.

**Backend — modified**
- `entity/NurseProfile.java` (+`isIncharge`,`gender`,`qualification`,`registrationNumber`,`joiningDate`).
- `entity/Ward.java` (+`inchargeNurseId`), `repository/WardRepository.java` (+finder).
- `entity/HospitalSetting.java` (+`separateNurseLogin`).
- `entity/VitalsRecord.java`, `NursingNote.java`, `MedicationAdministration.java`, `SugarChartEntry.java`, `SurgeryForm.java` (+`performedByNurseId`) (A3).
- `service/hospital/NurseService.java` (create/promote/demote/activate), `WardService.java` (set/transfer incharge).
- `service/hospital/IpdAdmissionService.java` (block incharge-less admission; call `PatientAssignmentService`).
- write services: `VitalsService`, `NursingNoteService`, `MedicationAdministrationService`, `SugarChartService`, `SurgeryFormService` (call resolver) (A3).
- `config/DatabaseMigrationRunner.java`, `setup/schema-full.sql`.
- `config/SecurityConfig.java`, and login response (role routing) as needed.
- controllers: `NurseController` / admin nurse endpoints.

**Frontend — modified/new**
- `services/nurseService.js` / new `nurseInchargeService.js`.
- Admin nurse UI (`HospitalAdminDashboard.jsx`) + Settings toggle.
- New `pages/hospital/NurseInchargeDashboard.jsx` (shell) + routing in `App.jsx`.
- "Performed By" dropdown in nursing entry panels (A3).

---

# Milestone A1 — Role, migrations, guard, ward-incharge, admin management

### Task 1: `NurseProfile` new columns

**Files:** Modify `backend/src/main/java/com/hms/entity/NurseProfile.java`

- [ ] **Step 1: Add fields** after the existing `wardId` field:

```java
    @Column(name = "is_incharge", nullable = false)
    private Boolean isIncharge = false;

    @Column(length = 10)
    private String gender;

    @Column(length = 120)
    private String qualification;

    @Column(name = "registration_number", length = 60)
    private String registrationNumber;

    @Column(name = "joining_date")
    private java.time.LocalDate joiningDate;
```

- [ ] **Step 2: Compile** — `cd backend && mvn -o -q compile` → BUILD SUCCESS.
- [ ] **Step 3: Commit** — `git add backend/src/main/java/com/hms/entity/NurseProfile.java && git commit -m "feat(nurse-mgmt): NurseProfile incharge + profile fields"`

---

### Task 2: `Ward.inchargeNurseId` + repository finder

**Files:** Modify `entity/Ward.java`, `repository/WardRepository.java`

- [ ] **Step 1: Add field to `Ward.java`** after `floorNumber`:

```java
    @Column(name = "incharge_nurse_id")
    private Long inchargeNurseId;
```

- [ ] **Step 2: Add finder to `WardRepository.java`:**

```java
    java.util.List<Ward> findByHospitalIdAndInchargeNurseId(Long hospitalId, Long inchargeNurseId);
```

- [ ] **Step 3: Compile & commit**

Run: `cd backend && mvn -o -q compile` → BUILD SUCCESS.
```bash
git add backend/src/main/java/com/hms/entity/Ward.java backend/src/main/java/com/hms/repository/WardRepository.java
git commit -m "feat(nurse-mgmt): ward incharge column"
```

---

### Task 3: `HospitalSetting.separateNurseLogin`

**Files:** Modify `entity/HospitalSetting.java`

- [ ] **Step 1: Add field** after `barcodeEnabled`:

```java
    @Column(name = "separate_nurse_login", nullable = false)
    private Boolean separateNurseLogin = false;
```

- [ ] **Step 2: Compile & commit** — `mvn -o -q compile`; `git add … && git commit -m "feat(nurse-mgmt): separate_nurse_login setting"`

---

### Task 4: Migrations for Tasks 1–3

**Files:** Modify `config/DatabaseMigrationRunner.java`, `setup/schema-full.sql`

- [ ] **Step 1: Add ensure methods** (place beside other `ensure...` methods; the logger field is `log`, confirm against an existing method like `ensureNurseProfileWardColumn`):

```java
    private void ensureNurseProfilePhaseAColumns() {
        addColumnIfMissing("nurse_profiles", "is_incharge", "TINYINT(1) NOT NULL DEFAULT 0");
        addColumnIfMissing("nurse_profiles", "gender", "VARCHAR(10) NULL");
        addColumnIfMissing("nurse_profiles", "qualification", "VARCHAR(120) NULL");
        addColumnIfMissing("nurse_profiles", "registration_number", "VARCHAR(60) NULL");
        addColumnIfMissing("nurse_profiles", "joining_date", "DATE NULL");
    }

    private void ensureWardInchargeColumn() {
        addColumnIfMissing("wards", "incharge_nurse_id", "BIGINT NULL");
    }

    private void ensureSeparateNurseLoginColumn() {
        addColumnIfMissing("hospital_settings", "separate_nurse_login", "TINYINT(1) NOT NULL DEFAULT 0");
    }

    /** Adds a column only if it does not already exist. Idempotent. */
    private void addColumnIfMissing(String table, String column, String definition) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                Integer.class, table, column);
            if (count != null && count == 0) {
                jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
                log.info("DB migration applied: added {}.{}", table, column);
            }
        } catch (Exception e) {
            log.warn("DB migration skipped ({}.{}): {}", table, column, e.getMessage());
        }
    }
```

Note: check whether `wards` PK column is `ward_id` — the table name is `wards`; the `ALTER` only adds a column, so PK name is irrelevant here.

- [ ] **Step 2: Call from `runMigrations()`** (after existing nurse-module ensures):

```java
        ensureNurseProfilePhaseAColumns(); // NEW — Nursing Mgmt Phase A
        ensureWardInchargeColumn();
        ensureSeparateNurseLoginColumn();
```

- [ ] **Step 3: Mirror in `setup/schema-full.sql`** — add the 5 columns to the `nurse_profiles` table, `incharge_nurse_id bigint DEFAULT NULL` to `wards`, and `separate_nurse_login tinyint(1) NOT NULL DEFAULT 0` to `hospital_settings`.

- [ ] **Step 4: Compile & commit** — `mvn -o -q compile`; `git add … && git commit -m "feat(nurse-mgmt): phase A migrations"`

(The app applies these on next boot; Hibernate `ddl-auto=update` also adds them from the entities.)

---

### Task 5: `NURSE_INCHARGE` role authorization

**Files:** Modify `config/SecurityConfig.java` (and confirm login role-routing).

- [ ] **Step 1: Grant the role on `/hospital/**` and `/ws/**`.** In `SecurityConfig.java`, add `"NURSE_INCHARGE"` to the `hasAnyRole(...)` lists that currently include `"NURSE"` for `/hospital/**` and `/ws/**` (lines ~81 and ~89). Example for the `/hospital/**` matcher:

```java
                        .hasAnyRole("HOSPITAL_ADMIN", "DOCTOR", "RECEPTIONIST", "PHARMACIST", "NURSE", "NURSE_INCHARGE")
```

- [ ] **Step 2: Verify role prefix handling.** Run `cd backend && grep -rn "ROLE_\|SimpleGrantedAuthority\|getAuthorities" src/main/java/com/hms/security/` and confirm authorities are built as `ROLE_<role>` from the JWT `role` claim (so `NURSE_INCHARGE` becomes `ROLE_NURSE_INCHARGE` automatically). If there is an explicit allow-list of role strings for login, add `NURSE_INCHARGE` there.

- [ ] **Step 3: Compile & commit** — `mvn -o -q compile`; `git add … && git commit -m "feat(nurse-mgmt): authorize NURSE_INCHARGE on /hospital"`

---

### Task 6: `NurseInchargeGuard` (TDD)

**Files:** Create `security/NurseInchargeGuard.java`; Test `test/.../security/NurseInchargeGuardTest.java`

- [ ] **Step 1: Write the failing test** `backend/src/test/java/com/hms/security/NurseInchargeGuardTest.java`:

```java
package com.hms.security;

import com.hms.entity.NurseProfile;
import com.hms.entity.Ward;
import com.hms.repository.IpdAdmissionRepository;
import com.hms.repository.NurseProfileRepository;
import com.hms.repository.WardRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NurseInchargeGuardTest {

    @Mock NurseProfileRepository nurseProfileRepository;
    @Mock WardRepository wardRepository;
    @Mock IpdAdmissionRepository ipdAdmissionRepository;
    @Mock SecurityContextHelper securityHelper;

    @InjectMocks NurseInchargeGuard guard;

    private Ward ward(Long id, Long hospitalId, Long inchargeNurseId) {
        Ward w = new Ward(); w.setWardId(id); w.setHospitalId(hospitalId); w.setInchargeNurseId(inchargeNurseId);
        return w;
    }

    @Test
    void admin_hasAccessToAnyWard() {
        when(securityHelper.getCurrentUserRole()).thenReturn("HOSPITAL_ADMIN");
        assertThatCode(() -> guard.assertWardAccess(3L)).doesNotThrowAnyException();
    }

    @Test
    void incharge_allowedForOwnWard() {
        when(securityHelper.getCurrentUserRole()).thenReturn("NURSE_INCHARGE");
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(securityHelper.getCurrentUserId()).thenReturn(20L);
        NurseProfile me = new NurseProfile(); me.setId(11L); me.setHospitalId(7L); me.setIsIncharge(true);
        when(nurseProfileRepository.findByUserId(20L)).thenReturn(Optional.of(me));
        when(wardRepository.findById(3L)).thenReturn(Optional.of(ward(3L, 7L, 11L)));
        assertThatCode(() -> guard.assertWardAccess(3L)).doesNotThrowAnyException();
    }

    @Test
    void incharge_deniedForOtherWard() {
        when(securityHelper.getCurrentUserRole()).thenReturn("NURSE_INCHARGE");
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(securityHelper.getCurrentUserId()).thenReturn(20L);
        NurseProfile me = new NurseProfile(); me.setId(11L); me.setHospitalId(7L); me.setIsIncharge(true);
        when(nurseProfileRepository.findByUserId(20L)).thenReturn(Optional.of(me));
        when(wardRepository.findById(3L)).thenReturn(Optional.of(ward(3L, 7L, 99L)));
        assertThatThrownBy(() -> guard.assertWardAccess(3L)).isInstanceOf(AccessDeniedException.class);
    }
}
```

- [ ] **Step 2: Run — expect FAIL** (class missing): `cd backend && mvn -o -q -Dtest=NurseInchargeGuardTest test`

- [ ] **Step 3: Implement** `backend/src/main/java/com/hms/security/NurseInchargeGuard.java`:

```java
package com.hms.security;

import com.hms.entity.IpdAdmission;
import com.hms.entity.NurseProfile;
import com.hms.entity.Ward;
import com.hms.repository.IpdAdmissionRepository;
import com.hms.repository.NurseProfileRepository;
import com.hms.repository.WardRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * NurseInchargeGuard - restricts a Nurse Incharge to their assigned wards.
 * HOSPITAL_ADMIN has access to all wards. The "only your wards" rule lives here.
 */
@Component
public class NurseInchargeGuard {

    @Autowired private NurseProfileRepository nurseProfileRepository;
    @Autowired private WardRepository wardRepository;
    @Autowired private IpdAdmissionRepository ipdAdmissionRepository;
    @Autowired private SecurityContextHelper securityHelper;

    private boolean isAdmin() {
        return "HOSPITAL_ADMIN".equals(securityHelper.getCurrentUserRole());
    }

    /** The NurseProfile id of the current incharge, or null. */
    private Long currentInchargeProfileId() {
        return nurseProfileRepository.findByUserId(securityHelper.getCurrentUserId())
                .map(NurseProfile::getId).orElse(null);
    }

    public void assertWardAccess(Long wardId) {
        if (isAdmin()) return;
        Ward ward = wardRepository.findById(wardId)
                .orElseThrow(() -> new IllegalArgumentException("Ward not found"));
        Long me = currentInchargeProfileId();
        if (me == null || !me.equals(ward.getInchargeNurseId())) {
            throw new AccessDeniedException("You are not the incharge of this ward");
        }
    }

    public void assertAdmissionInMyWard(Long ipdAdmissionId) {
        IpdAdmission a = ipdAdmissionRepository.findById(ipdAdmissionId)
                .orElseThrow(() -> new IllegalArgumentException("IPD admission not found"));
        assertWardAccess(a.getWardId());
    }

    /** Ward ids the current user may see. Admin → all hospital wards. */
    public List<Long> myWardIds() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (isAdmin()) {
            return wardRepository.findByHospitalId(hospitalId).stream()
                    .map(Ward::getWardId).collect(Collectors.toList());
        }
        Long me = currentInchargeProfileId();
        if (me == null) return List.of();
        return wardRepository.findByHospitalIdAndInchargeNurseId(hospitalId, me).stream()
                .map(Ward::getWardId).collect(Collectors.toList());
    }
}
```

- [ ] **Step 4: Run — expect PASS** (3/3): `mvn -o -q -Dtest=NurseInchargeGuardTest test`
- [ ] **Step 5: Commit** — `git add backend/src/main/java/com/hms/security/NurseInchargeGuard.java backend/src/test/java/com/hms/security/NurseInchargeGuardTest.java && git commit -m "feat(nurse-mgmt): NurseInchargeGuard + tests"`

---

### Task 7: `WardService` set/transfer incharge

**Files:** Modify `service/hospital/WardService.java`; Create DTO `dto/SetWardInchargeRequest.java`

- [ ] **Step 1: DTO** `backend/src/main/java/com/hms/dto/SetWardInchargeRequest.java`:

```java
package com.hms.dto;

import lombok.Data;

/** Admin: assign (or clear) a ward's Nurse Incharge. */
@Data
public class SetWardInchargeRequest {
    private Long wardId;               // required
    private Long inchargeNurseProfileId; // null to clear
}
```

- [ ] **Step 2: Add service method** to `WardService` (match its existing field names — it has `wardRepository`, `securityHelper`; confirm and reuse an audit service if present, else inject `AuditLogService`):

```java
    @org.springframework.transaction.annotation.Transactional
    public void setIncharge(Long wardId, Long inchargeNurseProfileId) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        com.hms.entity.Ward ward = wardRepository.findById(wardId)
                .orElseThrow(() -> new IllegalArgumentException("Ward not found"));
        if (!hospitalId.equals(ward.getHospitalId())) {
            throw new com.hms.exception.UnauthorizedException("Ward belongs to another hospital");
        }
        Long previous = ward.getInchargeNurseId();
        if (inchargeNurseProfileId != null) {
            com.hms.entity.NurseProfile p = nurseProfileRepository.findById(inchargeNurseProfileId)
                    .orElseThrow(() -> new IllegalArgumentException("Nurse not found"));
            if (!hospitalId.equals(p.getHospitalId()) || !Boolean.TRUE.equals(p.getIsActive())
                    || !Boolean.TRUE.equals(p.getIsIncharge())) {
                throw new IllegalArgumentException("Target must be an active Nurse Incharge in this hospital");
            }
        }
        ward.setInchargeNurseId(inchargeNurseProfileId);
        wardRepository.save(ward);
        auditLogService.logAction("WARD_INCHARGE_SET",
                "Ward " + ward.getWardName() + " incharge " + previous + " -> " + inchargeNurseProfileId,
                securityHelper.getCurrentUserEmail(), hospitalId, "WARD", String.valueOf(wardId), null);
    }
```

Inject `nurseProfileRepository` and `auditLogService` into `WardService` if not already present.

- [ ] **Step 3: Compile & commit** — `mvn -o -q compile`; `git add … && git commit -m "feat(nurse-mgmt): WardService.setIncharge"`

---

### Task 8: `NurseService` — create incharge, promote, demote, activate (TDD)

**Files:** Modify `service/hospital/NurseService.java`; Test add to `test/.../NurseServiceTest.java`; DTO `dto/CreateInchargeRequest.java`

- [ ] **Step 1: Write failing tests** — add to `NurseServiceTest` (create the file if missing, mirroring other service tests; mock `nurseProfileRepository`, `userRepository`, `wardRepository`, `securityHelper`, `auditLogService`, `passwordEncoder` as the service uses):

```java
    @Test
    void demote_blockedWhileHoldingWards() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        com.hms.entity.NurseProfile p = new com.hms.entity.NurseProfile();
        p.setId(11L); p.setHospitalId(7L); p.setIsIncharge(true);
        when(nurseProfileRepository.findById(11L)).thenReturn(java.util.Optional.of(p));
        when(wardRepository.findByHospitalIdAndInchargeNurseId(7L, 11L))
                .thenReturn(java.util.List.of(new com.hms.entity.Ward()));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.demote(11L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ward");
    }

    @Test
    void promote_setsInchargeFlag() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        com.hms.entity.NurseProfile p = new com.hms.entity.NurseProfile();
        p.setId(11L); p.setHospitalId(7L); p.setIsIncharge(false); p.setEmail("n@x.com"); p.setUserId(20L);
        when(nurseProfileRepository.findById(11L)).thenReturn(java.util.Optional.of(p));
        com.hms.entity.User u = new com.hms.entity.User(); u.setId(20L); u.setRole("NURSE");
        when(userRepository.findById(20L)).thenReturn(java.util.Optional.of(u));
        when(nurseProfileRepository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(i -> i.getArgument(0));
        when(userRepository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(i -> i.getArgument(0));

        service.promote(11L);

        org.assertj.core.api.Assertions.assertThat(p.getIsIncharge()).isTrue();
        org.assertj.core.api.Assertions.assertThat(u.getRole()).isEqualTo("NURSE_INCHARGE");
    }
```

- [ ] **Step 2: Run — expect FAIL:** `mvn -o -q -Dtest=NurseServiceTest test`

- [ ] **Step 3: Implement** in `NurseService` (inject `wardRepository` if missing):

```java
    @org.springframework.transaction.annotation.Transactional
    public void promote(Long nurseProfileId) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        com.hms.entity.NurseProfile p = requireProfile(nurseProfileId, hospitalId);
        p.setIsIncharge(true);
        if (p.getUserId() != null) {
            userRepository.findById(p.getUserId()).ifPresent(u -> {
                u.setRole("NURSE_INCHARGE");
                userRepository.save(u);
            });
        }
        nurseProfileRepository.save(p);
        audit("NURSE_PROMOTED", "Promoted nurse " + p.getName() + " to incharge", hospitalId, nurseProfileId);
    }

    @org.springframework.transaction.annotation.Transactional
    public void demote(Long nurseProfileId) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        com.hms.entity.NurseProfile p = requireProfile(nurseProfileId, hospitalId);
        if (!wardRepository.findByHospitalIdAndInchargeNurseId(hospitalId, nurseProfileId).isEmpty()) {
            throw new IllegalArgumentException("Reassign this incharge's wards before demoting");
        }
        p.setIsIncharge(false);
        if (p.getUserId() != null) {
            userRepository.findById(p.getUserId()).ifPresent(u -> {
                u.setRole("NURSE");
                userRepository.save(u);
            });
        }
        nurseProfileRepository.save(p);
        audit("NURSE_DEMOTED", "Demoted incharge " + p.getName() + " to nurse", hospitalId, nurseProfileId);
    }

    @org.springframework.transaction.annotation.Transactional
    public void setActive(Long nurseProfileId, boolean active) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        com.hms.entity.NurseProfile p = requireProfile(nurseProfileId, hospitalId);
        if (!active && Boolean.TRUE.equals(p.getIsIncharge())
                && !wardRepository.findByHospitalIdAndInchargeNurseId(hospitalId, nurseProfileId).isEmpty()) {
            throw new IllegalArgumentException("Reassign this incharge's wards before deactivating");
        }
        p.setIsActive(active);
        nurseProfileRepository.save(p);
        audit(active ? "NURSE_ACTIVATED" : "NURSE_DEACTIVATED", p.getName(), hospitalId, nurseProfileId);
    }

    private com.hms.entity.NurseProfile requireProfile(Long id, Long hospitalId) {
        com.hms.entity.NurseProfile p = nurseProfileRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Nurse not found"));
        if (!hospitalId.equals(p.getHospitalId())) {
            throw new com.hms.exception.UnauthorizedException("Nurse belongs to another hospital");
        }
        return p;
    }

    private void audit(String action, String details, Long hospitalId, Long id) {
        try {
            auditLogService.logAction(action, details, securityHelper.getCurrentUserEmail(), hospitalId,
                    "NURSE", String.valueOf(id), null);
        } catch (Exception e) { /* best-effort */ }
    }
```

(If `NurseService` lacks `auditLogService`/`userRepository`/`wardRepository`, inject them. Confirm `User` has `setRole`.)

- [ ] **Step 4: Run — expect PASS:** `mvn -o -q -Dtest=NurseServiceTest test`
- [ ] **Step 5: Commit** — `git add … && git commit -m "feat(nurse-mgmt): NurseService promote/demote/activate + tests"`

---

### Task 9: Block admission to incharge-less wards

**Files:** Modify `service/hospital/IpdAdmissionService.java` and the reception bed/ward-selection endpoint source.

- [ ] **Step 1: Guard the admission path.** In `IpdAdmissionService`, before marking the bed occupied (near line 156), after the ward is resolved, add:

```java
        if (ward.getInchargeNurseId() == null) {
            throw new IllegalArgumentException("This ward has no Nurse Incharge assigned. Assign an incharge before admitting.");
        }
```

(Use the actual `Ward` variable already loaded in that method; if the method only has `wardId`, load it: `Ward ward = wardRepository.findById(saved.getWardId()).orElseThrow(...)`.)

- [ ] **Step 2: Filter ward selection.** Find the endpoint that returns wards/beds for reception admission (grep: `cd backend && grep -rln "getWards\|available.*bed\|wards" src/main/java/com/hms/controller src/main/java/com/hms/service | head`). In the service that lists wards for admission, filter to `ward.getInchargeNurseId() != null`. Add a code comment explaining incharge-less wards are hidden from admission.

- [ ] **Step 3: Compile & commit** — `mvn -o -q compile`; `git add … && git commit -m "feat(nurse-mgmt): block admission to incharge-less wards"`

---

### Task 10: Admin controller endpoints for nurse management

**Files:** Modify `controller/hospital/NurseController.java` (or create admin endpoints there); DTO `dto/CreateInchargeRequest.java`

- [ ] **Step 1: DTO** `CreateInchargeRequest.java`:

```java
package com.hms.dto;

import lombok.Data;
import java.time.LocalDate;

/** Admin: create a Nurse Incharge (always has a login). */
@Data
public class CreateInchargeRequest {
    private String name;
    private String email;
    private String password;
    private String phone;
    private String gender;
    private String qualification;
    private String registrationNumber;
    private LocalDate joiningDate;
    private Long primaryWardId;
}
```

- [ ] **Step 2: Endpoints** in `NurseController` (admin-only), delegating to `NurseService`/`WardService`:

```java
    @PostMapping("/{publicId}/promote")
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<?> promote(@PathVariable String publicId) {
        nurseService.promote(nurseService.resolveProfileId(publicId));
        return ResponseEntity.ok(java.util.Map.of("message", "Nurse promoted to incharge"));
    }

    @PostMapping("/{publicId}/demote")
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<?> demote(@PathVariable String publicId) {
        nurseService.demote(nurseService.resolveProfileId(publicId));
        return ResponseEntity.ok(java.util.Map.of("message", "Incharge demoted to nurse"));
    }

    @PostMapping("/{publicId}/active/{active}")
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<?> setActive(@PathVariable String publicId, @PathVariable boolean active) {
        nurseService.setActive(nurseService.resolveProfileId(publicId), active);
        return ResponseEntity.ok(java.util.Map.of("message", active ? "Activated" : "Deactivated"));
    }

    @PostMapping("/ward-incharge")
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<?> setWardIncharge(@RequestBody com.hms.dto.SetWardInchargeRequest req) {
        wardService.setIncharge(req.getWardId(), req.getInchargeNurseProfileId());
        return ResponseEntity.ok(java.util.Map.of("message", "Ward incharge updated"));
    }
```

Add a helper `resolveProfileId(String publicId)` to `NurseService` returning the `NurseProfile.id` for a nurse public id (the controller works in public ids; the service methods take profile ids). Confirm/adjust to however `NurseController` currently identifies a nurse (it may already use `publicId`); if it uses profile id directly, pass through.

- [ ] **Step 3: Compile & commit** — `mvn -o -q compile`; `git add … && git commit -m "feat(nurse-mgmt): admin promote/demote/activate/ward-incharge endpoints"`

---

### Task 11: Frontend — admin nurse management + settings toggle + incharge shell

**Files:** `frontend/src/services/nurseService.js`, `frontend/src/pages/hospital/HospitalAdminDashboard.jsx`, new `frontend/src/pages/hospital/NurseInchargeDashboard.jsx`, `frontend/src/App.jsx`.

- [ ] **Step 1: Service methods** — add to `nurseService.js`:

```javascript
    promoteNurse: async (publicId) => (await apiClient.post(`/hospital/nurses/${publicId}/promote`)).data,
    demoteNurse: async (publicId) => (await apiClient.post(`/hospital/nurses/${publicId}/demote`)).data,
    setNurseActive: async (publicId, active) => (await apiClient.post(`/hospital/nurses/${publicId}/active/${active}`)).data,
    setWardIncharge: async (wardId, inchargeNurseProfileId) =>
        (await apiClient.post('/hospital/nurses/ward-incharge', { wardId, inchargeNurseProfileId })).data,
```

(Confirm the nurse base path — grep `grep -n "/hospital/nurses" src/services/*.js`; match it.)

- [ ] **Step 2: Admin nurse rows** — in the admin nurses table, add row actions **Promote**/**Demote** (based on the nurse's `isIncharge` flag) and **Activate/Deactivate**, calling the service methods and refreshing. Add a Ward→Incharge assignment control in Wards & Beds (a dropdown of incharge nurses per ward → `setWardIncharge`).

- [ ] **Step 3: Separate Nurse Login toggle** — in Settings, add a toggle bound to the hospital setting `separateNurseLogin` (extend the existing settings GET/PUT payload to include it; confirm the settings endpoint and add the field on both ends).

- [ ] **Step 4: Incharge dashboard shell** — create `NurseInchargeDashboard.jsx` (mirror `NurseDashboard.jsx` layout) with tabs *My Nurses* and *My Ward Patients* (patients wired in A2). Route it: in `App.jsx` add a protected route for role `NURSE_INCHARGE` (e.g., `/hospital/nurse-incharge`), and in `authService`/login redirect logic send `NURSE_INCHARGE` there.

- [ ] **Step 5: Build & commit**

Run: `cd frontend && npx vite build --mode development` → `✓ built`.
```bash
git add frontend/src/services/nurseService.js frontend/src/pages/hospital/HospitalAdminDashboard.jsx frontend/src/pages/hospital/NurseInchargeDashboard.jsx frontend/src/App.jsx
git commit -m "feat(nurse-mgmt): admin nurse mgmt UI + incharge dashboard shell"
```

- [ ] **Step 6: Full backend test** — `cd backend && mvn -o test` → BUILD SUCCESS.

---

# Milestone A2 — Patient assignment flow

### Task 12: `PatientAssignmentService` (TDD)

**Files:** Create `service/hospital/PatientAssignmentService.java`; Test `test/.../PatientAssignmentServiceTest.java`

- [ ] **Step 1: Write failing tests:**

```java
package com.hms.service.hospital;

import com.hms.entity.HospitalSetting;
import com.hms.entity.IpdAdmission;
import com.hms.entity.NurseProfile;
import com.hms.repository.HospitalSettingRepository;
import com.hms.repository.NurseProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PatientAssignmentServiceTest {

    @Mock HospitalSettingRepository hospitalSettingRepository;
    @Mock NurseProfileRepository nurseProfileRepository;
    @Mock NurseAssignmentService nurseAssignmentService;

    @InjectMocks PatientAssignmentService service;

    private IpdAdmission admission() {
        IpdAdmission a = new IpdAdmission();
        a.setId(1L); a.setHospitalId(7L); a.setPatientId(500L); a.setWardId(3L);
        return a;
    }
    private NurseProfile nurse(Long id, Long userId, boolean incharge) {
        NurseProfile p = new NurseProfile();
        p.setId(id); p.setUserId(userId); p.setHospitalId(7L); p.setWardId(3L);
        p.setIsIncharge(incharge); p.setIsActive(true);
        return p;
    }
    private void loginMode(boolean on) {
        HospitalSetting s = new HospitalSetting(); s.setSeparateNurseLogin(on);
        when(hospitalSettingRepository.findByHospitalId(7L)).thenReturn(Optional.of(s));
    }

    @Test
    void loginOff_noAssignment() {
        loginMode(false);
        service.onAdmission(admission());
        verify(nurseAssignmentService, never()).assignNurse(anyLong(), anyLong(), any());
    }

    @Test
    void loginOn_oneStaffNurse_autoAssigns() {
        loginMode(true);
        when(nurseProfileRepository.findByWardIdAndIsInchargeFalseAndIsActiveTrue(3L))
                .thenReturn(List.of(nurse(11L, 20L, false)));
        service.onAdmission(admission());
        verify(nurseAssignmentService).assignNurse(eq(1L), eq(20L), any());
    }

    @Test
    void loginOn_multipleStaffNurses_noAutoAssign() {
        loginMode(true);
        when(nurseProfileRepository.findByWardIdAndIsInchargeFalseAndIsActiveTrue(3L))
                .thenReturn(List.of(nurse(11L, 20L, false), nurse(12L, 21L, false)));
        service.onAdmission(admission());
        verify(nurseAssignmentService, never()).assignNurse(anyLong(), anyLong(), any());
    }

    @Test
    void loginOn_zeroStaffNurses_noAutoAssign() {
        loginMode(true);
        when(nurseProfileRepository.findByWardIdAndIsInchargeFalseAndIsActiveTrue(3L)).thenReturn(List.of());
        service.onAdmission(admission());
        verify(nurseAssignmentService, never()).assignNurse(anyLong(), anyLong(), any());
    }
}
```

- [ ] **Step 2: Add the repository finder** to `NurseProfileRepository`:

```java
    java.util.List<NurseProfile> findByWardIdAndIsInchargeFalseAndIsActiveTrue(Long wardId);
```

- [ ] **Step 3: Confirm `HospitalSettingRepository.findByHospitalId`** exists (grep: `grep -n "findByHospitalId" src/main/java/com/hms/repository/HospitalSettingRepository.java`). If it is keyed differently (e.g., by `hospital.id`), adjust the test + service accordingly.

- [ ] **Step 4: Run — expect FAIL:** `mvn -o -q -Dtest=PatientAssignmentServiceTest test`

- [ ] **Step 5: Implement** `PatientAssignmentService.java`:

```java
package com.hms.service.hospital;

import com.hms.entity.IpdAdmission;
import com.hms.entity.NurseProfile;
import com.hms.repository.HospitalSettingRepository;
import com.hms.repository.NurseProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * PatientAssignmentService - decides staff-nurse assignment at admission
 * (Nursing Mgmt Phase A). Replaces least-loaded auto-assign:
 *  - Separate Nurse Login OFF -> no assignment (incharge handles the patient).
 *  - ON + exactly one non-incharge active staff nurse in the ward -> auto-assign.
 *  - ON + more than one -> incharge assigns manually.
 *  - ON + none -> incharge only.
 * Incharge visibility is derived from ward membership, not from an assignment row.
 */
@Service
public class PatientAssignmentService {

    private static final Logger logger = LoggerFactory.getLogger(PatientAssignmentService.class);

    @Autowired private HospitalSettingRepository hospitalSettingRepository;
    @Autowired private NurseProfileRepository nurseProfileRepository;
    @Autowired private NurseAssignmentService nurseAssignmentService;

    public void onAdmission(IpdAdmission admission) {
        try {
            boolean separateLogin = hospitalSettingRepository.findByHospitalId(admission.getHospitalId())
                    .map(s -> Boolean.TRUE.equals(s.getSeparateNurseLogin())).orElse(false);
            if (!separateLogin) return; // incharge handles the patient; no staff assignment

            List<NurseProfile> staff = nurseProfileRepository
                    .findByWardIdAndIsInchargeFalseAndIsActiveTrue(admission.getWardId());
            if (staff.size() == 1 && staff.get(0).getUserId() != null) {
                nurseAssignmentService.assignNurse(admission.getId(), staff.get(0).getUserId(),
                        "Auto-assigned (sole staff nurse in ward)");
            }
            // 0 or >1 -> incharge assigns manually (no auto-assignment)
        } catch (Exception e) {
            logger.warn("PatientAssignmentService.onAdmission failed for admission {}: {}",
                    admission.getId(), e.getMessage());
        }
    }
}
```

- [ ] **Step 6: Run — expect PASS (4/4):** `mvn -o -q -Dtest=PatientAssignmentServiceTest test`
- [ ] **Step 7: Commit** — `git add … && git commit -m "feat(nurse-mgmt): PatientAssignmentService + tests"`

---

### Task 13: Wire `PatientAssignmentService` into admission (replace auto-assign)

**Files:** Modify `service/hospital/IpdAdmissionService.java`

- [ ] **Step 1: Replace the auto-assign call** (lines ~161-169) with:

```java
        // Nursing Mgmt Phase A: incharge-mediated assignment. Best-effort.
        try {
            patientAssignmentService.onAdmission(saved);
        } catch (Exception e) {
            logger.warn("Failed to run patient assignment for admission {}", saved.getId(), e);
        }
```

Inject `@Autowired private PatientAssignmentService patientAssignmentService;`. Remove the now-unused `nurseAssignmentService.autoAssignForAdmission` call (keep the `nurseAssignmentService` field only if used elsewhere in the file).

- [ ] **Step 2: Compile & full test** — `mvn -o test` → BUILD SUCCESS (existing tests still green; `autoAssignForAdmission` may now be unused — that is fine, keep the method for reuse/history).
- [ ] **Step 3: Commit** — `git add … && git commit -m "feat(nurse-mgmt): use incharge-mediated assignment at admission"`

---

### Task 14: Incharge patient list + assign endpoints

**Files:** Create `controller/hospital/NurseInchargeController.java`, `dto/AssignPatientNurseRequest.java`; add a service method (in `NurseWorkspaceService` or a new `NurseInchargeService`).

- [ ] **Step 1: DTO** `AssignPatientNurseRequest.java`:

```java
package com.hms.dto;

import lombok.Data;

@Data
public class AssignPatientNurseRequest {
    private Long ipdAdmissionId;   // required
    private Long nurseProfileId;   // required — the staff nurse to assign
}
```

- [ ] **Step 2: Service** — add to `NurseWorkspaceService` (it already has `assignmentRepository`, `ipdAdmissionRepository`, `patientRepository`, `wardRepository`, `securityHelper`; inject `NurseInchargeGuard`, `NurseProfileRepository`, `NurseAssignmentService`):

```java
    /** Patients across the incharge's wards (or all wards for admin). */
    public java.util.List<com.hms.dto.MyPatientDTO> getWardPatients() {
        java.util.List<Long> wardIds = nurseInchargeGuard.myWardIds();
        java.util.List<com.hms.dto.MyPatientDTO> out = new java.util.ArrayList<>();
        if (wardIds.isEmpty()) return out;
        for (com.hms.entity.IpdAdmission ipd :
                ipdAdmissionRepository.findByHospitalIdAndStatus(securityHelper.getCurrentHospitalId(), "ADMITTED")) {
            if (!wardIds.contains(ipd.getWardId())) continue;
            com.hms.dto.MyPatientDTO dto = new com.hms.dto.MyPatientDTO();
            dto.setIpdAdmissionId(ipd.getId());
            dto.setIpdNumber(ipd.getIpdNumber());
            dto.setStatus(ipd.getStatus());
            patientRepository.findById(ipd.getPatientId()).ifPresent(p -> {
                dto.setPatientName(p.getName()); dto.setAge(p.getAge()); dto.setGender(p.getGender());
            });
            if (ipd.getWardId() != null)
                wardRepository.findById(ipd.getWardId()).ifPresent(w -> dto.setWardName(w.getWardName()));
            out.add(dto);
        }
        return out;
    }

    @org.springframework.transaction.annotation.Transactional
    public void assignPatientNurse(Long ipdAdmissionId, Long nurseProfileId) {
        nurseInchargeGuard.assertAdmissionInMyWard(ipdAdmissionId);
        com.hms.entity.NurseProfile p = nurseProfileRepository.findById(nurseProfileId)
                .orElseThrow(() -> new IllegalArgumentException("Nurse not found"));
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (!hospitalId.equals(p.getHospitalId()) || !Boolean.TRUE.equals(p.getIsActive())
                || Boolean.TRUE.equals(p.getIsIncharge()) || p.getUserId() == null) {
            throw new IllegalArgumentException("Select an active staff nurse with a login");
        }
        nurseAssignmentService.assignNurse(ipdAdmissionId, p.getUserId(), "Assigned by incharge");
    }
```

(`nurseAssignmentService.assignNurse` already deactivates a prior active assignment / creates a new one — confirm by reading it; if not, close the previous active assignment first.)

- [ ] **Step 3: Controller** `NurseInchargeController.java`:

```java
package com.hms.controller.hospital;

import com.hms.dto.AssignPatientNurseRequest;
import com.hms.security.RequireModule;
import com.hms.service.hospital.NurseWorkspaceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/hospital/nurse-incharge")
@RequireModule("NURSING")
public class NurseInchargeController {

    @Autowired private NurseWorkspaceService workspaceService;

    @GetMapping("/patients")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')")
    public ResponseEntity<?> wardPatients() {
        return ResponseEntity.ok(workspaceService.getWardPatients());
    }

    @PostMapping("/assign")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')")
    public ResponseEntity<?> assign(@RequestBody AssignPatientNurseRequest req) {
        workspaceService.assignPatientNurse(req.getIpdAdmissionId(), req.getNurseProfileId());
        return ResponseEntity.ok(Map.of("message", "Nurse assigned"));
    }
}
```

- [ ] **Step 4: Compile & commit** — `mvn -o -q compile`; `git add … && git commit -m "feat(nurse-mgmt): incharge ward-patients + assign endpoints"`

---

### Task 15: Frontend — incharge My Ward Patients + assign

**Files:** `frontend/src/services/nurseService.js` (or `nurseInchargeService.js`), `frontend/src/pages/hospital/NurseInchargeDashboard.jsx`

- [ ] **Step 1: Service methods:**

```javascript
    getWardPatients: async () => (await apiClient.get('/hospital/nurse-incharge/patients')).data,
    assignPatientNurse: async (ipdAdmissionId, nurseProfileId) =>
        (await apiClient.post('/hospital/nurse-incharge/assign', { ipdAdmissionId, nurseProfileId })).data,
```

- [ ] **Step 2: My Ward Patients tab** — list `getWardPatients()`; each row shows patient + assigned nurse; an **Assign Nurse** action opens a dropdown of that ward's staff nurses (reuse the nurses list filtered by ward, `isIncharge=false`) → `assignPatientNurse`. Hide the Assign action when the hospital's Separate Nurse Login is OFF (nothing to assign to).

- [ ] **Step 3: Build & commit** — `cd frontend && npx vite build --mode development` → `✓ built`; `git add … && git commit -m "feat(nurse-mgmt): incharge ward patients + assign UI"`

---

# Milestone A3 — Separate Nurse Login + Performed By

### Task 16: `performed_by_nurse_id` columns + migration

**Files:** Modify `entity/VitalsRecord.java`, `NursingNote.java`, `MedicationAdministration.java`, `SugarChartEntry.java`, `SurgeryForm.java`; `DatabaseMigrationRunner.java`; `setup/schema-full.sql`

- [ ] **Step 1: Add the field** to each of the five entities:

```java
    @Column(name = "performed_by_nurse_id")
    private Long performedByNurseId;
```

- [ ] **Step 2: Migration** — add to `runMigrations()`:

```java
        for (String t : new String[]{"vitals_records","nursing_notes","medication_administrations","sugar_chart_entries","surgery_forms"}) {
            addColumnIfMissing(t, "performed_by_nurse_id", "BIGINT NULL");
        }
```

(`addColumnIfMissing` was added in Task 4.) Mirror the column in `setup/schema-full.sql` for each table.

- [ ] **Step 3: Compile & commit** — `mvn -o -q compile`; `git add … && git commit -m "feat(nurse-mgmt): performed_by_nurse_id columns"`

---

### Task 17: `PerformingNurseResolver` (TDD)

**Files:** Create `security/PerformingNurseResolver.java`; Test `test/.../security/PerformingNurseResolverTest.java`

- [ ] **Step 1: Write failing test:**

```java
package com.hms.security;

import com.hms.entity.HospitalSetting;
import com.hms.entity.NurseProfile;
import com.hms.repository.HospitalSettingRepository;
import com.hms.repository.NurseProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PerformingNurseResolverTest {

    @Mock HospitalSettingRepository hospitalSettingRepository;
    @Mock NurseProfileRepository nurseProfileRepository;
    @Mock SecurityContextHelper securityHelper;

    @InjectMocks PerformingNurseResolver resolver;

    private void loginMode(boolean on) {
        HospitalSetting s = new HospitalSetting(); s.setSeparateNurseLogin(on);
        when(hospitalSettingRepository.findByHospitalId(7L)).thenReturn(Optional.of(s));
    }

    @Test
    void loginOn_usesLoggedInNurse_ignoresRequested() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(securityHelper.getCurrentUserId()).thenReturn(20L);
        loginMode(true);
        NurseProfile me = new NurseProfile(); me.setId(11L); me.setHospitalId(7L); me.setIsActive(true);
        when(nurseProfileRepository.findByUserId(20L)).thenReturn(Optional.of(me));

        assertThat(resolver.resolve(999L)).isEqualTo(11L);
    }

    @Test
    void loginOff_requiresRequestedId() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        loginMode(false);
        assertThatThrownBy(() -> resolver.resolve(null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Performed By");
    }

    @Test
    void loginOff_validatesAndReturnsRequested() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        loginMode(false);
        NurseProfile p = new NurseProfile(); p.setId(12L); p.setHospitalId(7L); p.setIsActive(true);
        when(nurseProfileRepository.findById(12L)).thenReturn(Optional.of(p));
        assertThat(resolver.resolve(12L)).isEqualTo(12L);
    }
}
```

- [ ] **Step 2: Run — expect FAIL:** `mvn -o -q -Dtest=PerformingNurseResolverTest test`

- [ ] **Step 3: Implement** `PerformingNurseResolver.java`:

```java
package com.hms.security;

import com.hms.entity.NurseProfile;
import com.hms.exception.UnauthorizedException;
import com.hms.repository.HospitalSettingRepository;
import com.hms.repository.NurseProfileRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * PerformingNurseResolver - decides which NurseProfile is credited as the
 * caregiver for a nursing record (Nursing Mgmt Phase A, "Performed By").
 * Separate Nurse Login ON  -> the logged-in nurse (requested id ignored).
 * Separate Nurse Login OFF -> the "Performed By" selection (required + validated).
 * Returns the NurseProfile id to store in performed_by_nurse_id (may be null if
 * ON and the actor is not a nurse, e.g. an incharge entering their own record).
 */
@Component
public class PerformingNurseResolver {

    @Autowired private HospitalSettingRepository hospitalSettingRepository;
    @Autowired private NurseProfileRepository nurseProfileRepository;
    @Autowired private SecurityContextHelper securityHelper;

    public Long resolve(Long requestedNurseProfileId) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        boolean separateLogin = hospitalSettingRepository.findByHospitalId(hospitalId)
                .map(s -> Boolean.TRUE.equals(s.getSeparateNurseLogin())).orElse(false);

        if (separateLogin) {
            return nurseProfileRepository.findByUserId(securityHelper.getCurrentUserId())
                    .map(NurseProfile::getId).orElse(null);
        }
        if (requestedNurseProfileId == null) {
            throw new IllegalArgumentException("Performed By nurse is required");
        }
        NurseProfile p = nurseProfileRepository.findById(requestedNurseProfileId)
                .orElseThrow(() -> new IllegalArgumentException("Performed By nurse not found"));
        if (!hospitalId.equals(p.getHospitalId()) || !Boolean.TRUE.equals(p.getIsActive())) {
            throw new UnauthorizedException("Performed By nurse is invalid for this hospital");
        }
        return p.getId();
    }
}
```

- [ ] **Step 4: Run — expect PASS (3/3):** `mvn -o -q -Dtest=PerformingNurseResolverTest test`
- [ ] **Step 5: Commit** — `git add … && git commit -m "feat(nurse-mgmt): PerformingNurseResolver + tests"`

---

### Task 18: Wire resolver into nursing write services

**Files:** Modify `VitalsService`, `NursingNoteService`, `MedicationAdministrationService`, `SugarChartService`, `SurgeryFormService`; add `performedByNurseId` to their request DTOs.

- [ ] **Step 1: DTO fields** — add to `VitalsRequest`, `NursingNoteRequest`, `MedicationAdminRequest`, `SugarChartRequest`, `SaveSurgeryFormRequest`:

```java
    private Long performedByNurseId; // optional; required when Separate Nurse Login is OFF
```

- [ ] **Step 2: In each `create(...)` method**, inject `PerformingNurseResolver performingNurseResolver` and set the column right after the existing author assignment:

```java
        entity.setPerformedByNurseId(performingNurseResolver.resolve(req.getPerformedByNurseId()));
```

(For `NurseAccessGuard.assertAssigned` writes that require login-ON assignment, the resolver returns the logged-in nurse — unchanged behavior. When OFF, the incharge is the actor and the resolver requires the dropdown value.)

- [ ] **Step 3: Relax the assignment gate when login is OFF.** Where a write currently calls `nurseAccessGuard.assertAssigned(admissionId)` (nurse-only), that guard assumes the actor is an assigned staff nurse. When Separate Login is OFF the actor is the incharge — replace that specific gate with: if the current role is `NURSE` → `nurseAccessGuard.assertAssigned(...)`; if `NURSE_INCHARGE`/`HOSPITAL_ADMIN` → `nurseInchargeGuard.assertAdmissionInMyWard(...)`. Add a tiny helper (e.g. in a shared component) `assertCanWriteFor(admissionId)` to avoid duplication across the five services.

- [ ] **Step 4: Compile & full test** — `mvn -o test` → BUILD SUCCESS (existing nurse tests may need the resolver mocked; where a write-service test breaks, add `@Mock PerformingNurseResolver` returning a stub id).
- [ ] **Step 5: Commit** — `git add … && git commit -m "feat(nurse-mgmt): Performed By on nursing writes"`

---

### Task 19: Frontend — "Performed By" dropdown + settings exposure

**Files:** `frontend/src/services/nurseService.js`, nursing entry panels (`VitalsPanel`, `NotesPanel`, `MedicationPanel`, `SugarChartPanel`, `SurgeryFormFrame`), settings payload.

- [ ] **Step 1: Expose the setting** — ensure the hospital settings / login payload includes `separateNurseLogin` so the frontend knows the mode. Add `getSeparateNurseLogin` if not already available, or read from the existing `user`/settings object.

- [ ] **Step 2: Ward nurses for the dropdown** — add `getWardNurses: async (wardId) => (await apiClient.get(\`/hospital/nurses?wardId=${wardId}&staffOnly=true\`)).data,` (add a backend query param to the nurses list to filter by ward and `isIncharge=false`; confirm/extend the nurse list endpoint).

- [ ] **Step 3: Dropdown in entry forms** — in each nursing entry panel, when `separateNurseLogin === false`, render a required "Performed By Nurse" `<select>` (ward's staff nurses) and include `performedByNurseId` in the create payload. When ON, omit it.

- [ ] **Step 4: Build & commit** — `cd frontend && npx vite build --mode development` → `✓ built`; `git add … && git commit -m "feat(nurse-mgmt): Performed By dropdown in nursing entry forms"`

---

### Task 20: End-to-end verification

- [ ] **Step 1: Full backend tests** — `cd backend && mvn -o test` → BUILD SUCCESS.
- [ ] **Step 2: Frontend build** — `cd frontend && npx vite build --mode development` → `✓ built`.
- [ ] **Step 3: Manual smoke (dev servers; NURSING enabled; a hospital with wards):**
  1. Admin creates a Nurse Incharge; assigns them a ward. Promote/demote a nurse; demote is blocked while they hold a ward.
  2. Reception can only pick wards that have an incharge + Available bed; admitting to an incharge-less ward is rejected.
  3. Separate Login OFF: admit a patient → shows under the ward incharge; no staff assignment; nursing entry asks "Performed By".
  4. Separate Login ON, ward with one staff nurse → patient auto-appears on that nurse's My Patients; incharge also sees it. Ward with two staff nurses → incharge assigns; then it appears for the chosen nurse.
- [ ] **Step 4: Clean up any curl/test data.**

---

## Self-Review (completed during authoring)

- **Spec coverage:** A1 role/migrations/guard/ward-incharge/admin-mgmt/block-admission/incharge-shell → Tasks 1–11. A2 assignment rule + list/assign + replace auto-assign → Tasks 12–15. A3 separate-login + performed_by + resolver + UI → Tasks 16–19. Audit calls included in Tasks 7–8, 10, 14. Verification → Task 20.
- **Placeholder scan:** none; the few "confirm the real name" steps (nurse base path, settings endpoint, `HospitalSettingRepository.findByHospitalId`, `User.setRole`, nurse public-id vs profile-id) are explicit grep-and-adjust steps with fallbacks, not deferred work.
- **Type consistency:** `NurseProfile.isIncharge`/`getUserId()`, `Ward.inchargeNurseId`, `HospitalSetting.separateNurseLogin`, `NurseInchargeGuard.myWardIds()/assertWardAccess/assertAdmissionInMyWard`, `PatientAssignmentService.onAdmission(IpdAdmission)`, `PerformingNurseResolver.resolve(Long)→Long`, and `performedByNurseId` are used consistently across tasks.
- **Flagged for the implementer:** how `NurseController` identifies a nurse (public id vs profile id) — Task 10 adds `resolveProfileId`; `HospitalSettingRepository.findByHospitalId` shape — Task 12 Step 3; existing write-service tests may need the resolver mocked — Task 18 Step 4.
