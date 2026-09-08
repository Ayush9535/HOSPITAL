# Files & Access — Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a Hospital Admin control, per hospital, which clinical forms are active and who may edit each (Doctor / Nurse / Both) from a "Files & Access" settings table, and enforce it on the nurse side (hide when Off, read-only when the nurse lacks edit access).

**Architecture:** A new `hospital_form_access` table stores only *overrides*; a missing row means the default (enabled + BOTH). A backend `FormRegistry` constant lists the 19 access-controlled forms. `FormAccessService` computes each form's effective verdict (HIDDEN / READ_ONLY / EDITABLE) for a role. The admin edits the config in Settings; the nurse patient detail fetches the effective map and hides/locks tabs accordingly.

**Tech Stack:** Spring Boot 3 / JPA / Maven (backend), React 18 / Vite / axios (frontend). Mirrors existing patterns (`HospitalCalendar`, `NurseCoverage`).

**Reference spec:** `docs/superpowers/specs/2026-07-10-files-and-access-phase1-design.md`.

**Conventions (verified):**
- Migrations: idempotent `ensureXxxTable()` in `DatabaseMigrationRunner` (fields `jdbcTemplate`, logger `log`), wired into `runMigrations()`, mirrored in `setup/schema-full.sql`.
- Errors flow through `GlobalExceptionHandler`; throw `IllegalArgumentException` / `UnauthorizedException`.
- Audit: `auditLogService.logAction(action, details, email, hospitalId, entityType, entityId, reason)` in try/catch.
- `securityHelper.getCurrentHospitalId()`, `getCurrentUserRole()`, `getCurrentUserEmail()` exist.
- WebSocket: `webSocketHandler.broadcast(hospitalId, "{\"type\":\"REFRESH_DATA\"}")`.
- Frontend build (from frontend dir only): `npx vite build --mode development`.

---

## Milestone P1-A — Backend

### Task 1: Entity, repository, registry, migration, schema

**Files:**
- Create: `backend/src/main/java/com/hms/entity/HospitalFormAccess.java`
- Create: `backend/src/main/java/com/hms/repository/HospitalFormAccessRepository.java`
- Create: `backend/src/main/java/com/hms/service/hospital/FormRegistry.java`
- Modify: `backend/src/main/java/com/hms/config/DatabaseMigrationRunner.java`
- Modify: `setup/schema-full.sql`

- [ ] **Step 1: Create the entity**

`backend/src/main/java/com/hms/entity/HospitalFormAccess.java`:

```java
package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/** Per-hospital override for a clinical form's availability + who may edit it
 *  (Files & Access, Phase 1). A missing row means enabled + BOTH. */
@Entity
@Table(name = "hospital_form_access",
        uniqueConstraints = @UniqueConstraint(columnNames = {"hospital_id", "form_key"}))
@Data @NoArgsConstructor @AllArgsConstructor
public class HospitalFormAccess {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;
    @Column(name = "form_key", nullable = false, length = 60)
    private String formKey;
    @Column(nullable = false)
    private Boolean enabled = true;
    @Column(name = "access_role", nullable = false, length = 10)
    private String accessRole = "BOTH"; // DOCTOR | NURSE | BOTH
    @UpdateTimestamp @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 2: Create the repository**

`backend/src/main/java/com/hms/repository/HospitalFormAccessRepository.java`:

```java
package com.hms.repository;

import com.hms.entity.HospitalFormAccess;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HospitalFormAccessRepository extends JpaRepository<HospitalFormAccess, Long> {
    Optional<HospitalFormAccess> findByHospitalIdAndFormKey(Long hospitalId, String formKey);
    List<HospitalFormAccess> findByHospitalId(Long hospitalId);
}
```

- [ ] **Step 3: Create the form registry**

`backend/src/main/java/com/hms/service/hospital/FormRegistry.java`:

```java
package com.hms.service.hospital;

import java.util.List;

/** Canonical list of access-controlled clinical forms (Files & Access, Phase 1).
 *  Keys reuse the frontend registry `type`/panel ids so both sides agree. */
public final class FormRegistry {
    private FormRegistry() {}

    public record Form(String key, String label, String category) {}

    public static final List<Form> FORMS = List.of(
        // Nursing records (4)
        new Form("VITALS", "Vitals", "NURSING"),
        new Form("INITIAL_ASSESSMENT", "Initial Assessment", "NURSING"),
        new Form("VULNERABILITY_ASSESSMENT", "Vulnerability Assessment", "NURSING"),
        new Form("SUGAR_CHART", "Sugar Chart", "NURSING"),
        // OT / NABH surgery forms (15)
        new Form("BLOOD_CONSENT", "Blood Consent Form", "OT"),
        new Form("IO_CHART", "Input & Output Chart", "OT"),
        new Form("GA_CONSENT", "Consent Form for General Anaesthesia", "OT"),
        new Form("DRUG_ADMIN_SHEET", "Drug Administration Sheet", "OT"),
        new Form("INFORMED_CONSENT_ANAES", "Informed Consent — Anaesthesia", "OT"),
        new Form("INFORMED_CONSENT_SURGERY", "Informed Consent — Surgery", "OT"),
        new Form("PRE_OP_CHECKLIST", "Pre-Operative Checklist", "OT"),
        new Form("PRE_ANAES_EVAL", "Pre-Anaesthesia Evaluation", "OT"),
        new Form("GENERAL_ANAESTHESIA", "General Anaesthesia", "OT"),
        new Form("SURGICAL_CASE_RECORD", "Surgical Case Record", "OT"),
        new Form("POST_OP_CARE_PLAN", "Post-Operative Care Plan", "OT"),
        new Form("POST_OP_CHECKLIST_10", "Post-Operative Checklist", "OT"),
        new Form("POST_OP_CHECKLIST_02", "Post-Operative Checklist (+ I/O page)", "OT"),
        new Form("POST_ANAES_RECOVERY", "Post-Anaesthesia Recovery Chart", "OT"),
        new Form("WHO_CHECKLIST", "WHO Surgical Safety Checklist", "OT")
    );

    public static boolean isValidKey(String key) {
        return FORMS.stream().anyMatch(f -> f.key().equals(key));
    }
}
```

- [ ] **Step 4: Add the migration**

In `DatabaseMigrationRunner.runMigrations()`, add after the `ensureCalendarEventsTable();` line:

```java
        ensureHospitalFormAccessTable();
```

Add this method next to the other `ensureXxxTable()` methods:

```java
    private void ensureHospitalFormAccessTable() {
        try {
            Integer exists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'hospital_form_access'",
                    Integer.class);
            if (exists == null || exists == 0) {
                jdbcTemplate.execute(
                        "CREATE TABLE hospital_form_access (" +
                        "  id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                        "  hospital_id BIGINT NOT NULL," +
                        "  form_key VARCHAR(60) NOT NULL," +
                        "  enabled TINYINT(1) NOT NULL DEFAULT 1," +
                        "  access_role VARCHAR(10) NOT NULL DEFAULT 'BOTH'," +
                        "  updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP," +
                        "  UNIQUE KEY uq_form_access_hosp_key (hospital_id, form_key)," +
                        "  CONSTRAINT fk_form_access_hospital FOREIGN KEY (hospital_id) REFERENCES hospitals(id) ON DELETE CASCADE" +
                        ")");
                log.info("Created hospital_form_access table");
            }
        } catch (Exception e) {
            log.warn("ensureHospitalFormAccessTable failed: {}", e.getMessage());
        }
    }
```

- [ ] **Step 5: Mirror in `setup/schema-full.sql`** (after the `calendar_events` block):

```sql
-- Per-hospital clinical form availability + edit access (Files & Access, Phase 1)
CREATE TABLE IF NOT EXISTS hospital_form_access (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    hospital_id BIGINT NOT NULL,
    form_key VARCHAR(60) NOT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    access_role VARCHAR(10) NOT NULL DEFAULT 'BOTH',
    updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_form_access_hosp_key (hospital_id, form_key),
    CONSTRAINT fk_form_access_hospital FOREIGN KEY (hospital_id) REFERENCES hospitals(id) ON DELETE CASCADE
);
```

- [ ] **Step 6: Compile** — `cd backend && mvn -o -q -DskipTests compile` → BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/hms/entity/HospitalFormAccess.java \
        backend/src/main/java/com/hms/repository/HospitalFormAccessRepository.java \
        backend/src/main/java/com/hms/service/hospital/FormRegistry.java \
        backend/src/main/java/com/hms/config/DatabaseMigrationRunner.java setup/schema-full.sql
git commit -m "feat(files-access): hospital_form_access entity/repo/registry/migration"
```

---

### Task 2: FormAccessService (TDD)

**Files:**
- Create: `backend/src/main/java/com/hms/dto/FormAccessRequest.java`
- Create: `backend/src/main/java/com/hms/service/hospital/FormAccessService.java`
- Test: `backend/src/test/java/com/hms/service/hospital/FormAccessServiceTest.java`

- [ ] **Step 1: Create the request DTO**

`backend/src/main/java/com/hms/dto/FormAccessRequest.java`:

```java
package com.hms.dto;

public class FormAccessRequest {
    private Boolean enabled;
    private String accessRole;

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getAccessRole() { return accessRole; }
    public void setAccessRole(String accessRole) { this.accessRole = accessRole; }
}
```

- [ ] **Step 2: Write the failing test**

`backend/src/test/java/com/hms/service/hospital/FormAccessServiceTest.java`:

```java
package com.hms.service.hospital;

import com.hms.dto.FormAccessRequest;
import com.hms.entity.HospitalFormAccess;
import com.hms.repository.HospitalFormAccessRepository;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FormAccessServiceTest {
    @Mock HospitalFormAccessRepository repository;
    @Mock SecurityContextHelper securityHelper;
    @Mock AuditLogService auditLogService;
    @Mock com.hms.security.HospitalWebSocketHandler webSocketHandler;
    @InjectMocks FormAccessService service;

    @Test void list_defaultsToEnabledBoth_whenNoRows() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(repository.findByHospitalId(7L)).thenReturn(List.of());

        List<Map<String, Object>> list = service.list();

        assertThat(list).hasSize(19);
        Map<String, Object> vitals = list.stream()
                .filter(m -> "VITALS".equals(m.get("key"))).findFirst().orElseThrow();
        assertThat(vitals.get("enabled")).isEqualTo(true);
        assertThat(vitals.get("accessRole")).isEqualTo("BOTH");
        assertThat(vitals.get("label")).isEqualTo("Vitals");
    }

    @Test void list_appliesOverride() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        HospitalFormAccess row = new HospitalFormAccess();
        row.setHospitalId(7L); row.setFormKey("VITALS"); row.setEnabled(false); row.setAccessRole("DOCTOR");
        when(repository.findByHospitalId(7L)).thenReturn(List.of(row));

        Map<String, Object> vitals = service.list().stream()
                .filter(m -> "VITALS".equals(m.get("key"))).findFirst().orElseThrow();
        assertThat(vitals.get("enabled")).isEqualTo(false);
        assertThat(vitals.get("accessRole")).isEqualTo("DOCTOR");
    }

    @Test void effectiveForRole_nurse_readOnlyWhenDoctorOnly() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        HospitalFormAccess docOnly = new HospitalFormAccess();
        docOnly.setHospitalId(7L); docOnly.setFormKey("VITALS"); docOnly.setEnabled(true); docOnly.setAccessRole("DOCTOR");
        HospitalFormAccess off = new HospitalFormAccess();
        off.setHospitalId(7L); off.setFormKey("SUGAR_CHART"); off.setEnabled(false); off.setAccessRole("BOTH");
        when(repository.findByHospitalId(7L)).thenReturn(List.of(docOnly, off));

        Map<String, String> verdicts = service.effectiveForRole("NURSE");

        assertThat(verdicts.get("VITALS")).isEqualTo("READ_ONLY");
        assertThat(verdicts.get("SUGAR_CHART")).isEqualTo("HIDDEN");
        assertThat(verdicts.get("INITIAL_ASSESSMENT")).isEqualTo("EDITABLE"); // default BOTH
    }

    @Test void effectiveForRole_normalizesInchargeToNurse() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        HospitalFormAccess nurseOnly = new HospitalFormAccess();
        nurseOnly.setHospitalId(7L); nurseOnly.setFormKey("VITALS"); nurseOnly.setEnabled(true); nurseOnly.setAccessRole("NURSE");
        when(repository.findByHospitalId(7L)).thenReturn(List.of(nurseOnly));

        assertThat(service.effectiveForRole("NURSE_INCHARGE").get("VITALS")).isEqualTo("EDITABLE");
        assertThat(service.effectiveForRole("DOCTOR").get("VITALS")).isEqualTo("READ_ONLY");
    }

    @Test void update_rejectsUnknownKey() {
        lenient().when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        FormAccessRequest req = new FormAccessRequest();
        req.setEnabled(true); req.setAccessRole("BOTH");
        assertThatThrownBy(() -> service.update("NOPE", req))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void update_rejectsBadRole() {
        lenient().when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        FormAccessRequest req = new FormAccessRequest();
        req.setEnabled(true); req.setAccessRole("EVERYONE");
        assertThatThrownBy(() -> service.update("VITALS", req))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void update_upsertsRow() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(repository.findByHospitalIdAndFormKey(7L, "VITALS")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        FormAccessRequest req = new FormAccessRequest();
        req.setEnabled(false); req.setAccessRole("DOCTOR");

        HospitalFormAccess saved = service.update("VITALS", req);

        assertThat(saved.getFormKey()).isEqualTo("VITALS");
        assertThat(saved.getEnabled()).isEqualTo(false);
        assertThat(saved.getAccessRole()).isEqualTo("DOCTOR");
        assertThat(saved.getHospitalId()).isEqualTo(7L);
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd backend && mvn -o -q -Dtest=FormAccessServiceTest test`
Expected: FAIL — `FormAccessService` does not exist / compile error.

- [ ] **Step 4: Implement the service**

`backend/src/main/java/com/hms/service/hospital/FormAccessService.java`:

```java
package com.hms.service.hospital;

import com.hms.dto.FormAccessRequest;
import com.hms.entity.HospitalFormAccess;
import com.hms.exception.UnauthorizedException;
import com.hms.repository.HospitalFormAccessRepository;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * FormAccessService - per-hospital form availability + edit access (Files &
 * Access, Phase 1). A missing row means enabled + BOTH, so the table only holds
 * overrides. effectiveForRole() maps each form to HIDDEN / READ_ONLY / EDITABLE.
 */
@Service
public class FormAccessService {
    @Autowired private HospitalFormAccessRepository repository;
    @Autowired private SecurityContextHelper securityHelper;
    @Autowired private AuditLogService auditLogService;
    @Autowired private com.hms.security.HospitalWebSocketHandler webSocketHandler;

    private static final Set<String> ROLES = Set.of("DOCTOR", "NURSE", "BOTH");

    /** All registry forms with their effective enabled + accessRole (for the settings table). */
    public List<Map<String, Object>> list() {
        Long hospitalId = requireHospitalId();
        Map<String, HospitalFormAccess> byKey = new HashMap<>();
        for (HospitalFormAccess r : repository.findByHospitalId(hospitalId)) byKey.put(r.getFormKey(), r);

        List<Map<String, Object>> out = new ArrayList<>();
        for (FormRegistry.Form f : FormRegistry.FORMS) {
            HospitalFormAccess r = byKey.get(f.key());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", f.key());
            m.put("label", f.label());
            m.put("category", f.category());
            m.put("enabled", r == null || Boolean.TRUE.equals(r.getEnabled()));
            m.put("accessRole", r == null ? "BOTH" : r.getAccessRole());
            out.add(m);
        }
        return out;
    }

    /** For the given role, each form's verdict: HIDDEN / READ_ONLY / EDITABLE. */
    public Map<String, String> effectiveForRole(String role) {
        Long hospitalId = requireHospitalId();
        String norm = normalizeRole(role);
        Map<String, HospitalFormAccess> byKey = new HashMap<>();
        for (HospitalFormAccess r : repository.findByHospitalId(hospitalId)) byKey.put(r.getFormKey(), r);

        Map<String, String> out = new LinkedHashMap<>();
        for (FormRegistry.Form f : FormRegistry.FORMS) {
            HospitalFormAccess r = byKey.get(f.key());
            boolean enabled = r == null || Boolean.TRUE.equals(r.getEnabled());
            String access = r == null ? "BOTH" : r.getAccessRole();
            if (!enabled) { out.put(f.key(), "HIDDEN"); continue; }
            boolean canEdit = "BOTH".equals(access) || access.equals(norm);
            out.put(f.key(), canEdit ? "EDITABLE" : "READ_ONLY");
        }
        return out;
    }

    @Transactional
    public HospitalFormAccess update(String formKey, FormAccessRequest req) {
        Long hospitalId = requireHospitalId();
        if (!FormRegistry.isValidKey(formKey)) throw new IllegalArgumentException("Unknown form: " + formKey);
        String role = req.getAccessRole() == null ? null : req.getAccessRole().toUpperCase();
        if (role == null || !ROLES.contains(role)) throw new IllegalArgumentException("Invalid access role");
        boolean enabled = req.getEnabled() == null || req.getEnabled();

        HospitalFormAccess row = repository.findByHospitalIdAndFormKey(hospitalId, formKey)
                .orElseGet(() -> {
                    HospitalFormAccess n = new HospitalFormAccess();
                    n.setHospitalId(hospitalId);
                    n.setFormKey(formKey);
                    return n;
                });
        row.setEnabled(enabled);
        row.setAccessRole(role);
        HospitalFormAccess saved = repository.save(row);
        audit("FORM_ACCESS_UPDATED", formKey + " enabled=" + enabled + " access=" + role, hospitalId, saved.getId());
        broadcastRefresh(hospitalId);
        return saved;
    }

    /** NURSE_INCHARGE counts as NURSE for form access; anything else maps to itself. */
    private String normalizeRole(String role) {
        if (role == null) return "";
        if ("NURSE_INCHARGE".equals(role) || "NURSE".equals(role)) return "NURSE";
        if ("HOSPITAL_ADMIN".equals(role)) return "BOTH"; // admin previews as full access
        return role;
    }

    private Long requireHospitalId() {
        Long h = securityHelper.getCurrentHospitalId();
        if (h == null) throw new UnauthorizedException("Hospital ID not found");
        return h;
    }

    private void audit(String a, String d, Long h, Long id) {
        try {
            auditLogService.logAction(a, d, securityHelper.getCurrentUserEmail(), h, "FORM_ACCESS", String.valueOf(id), null);
        } catch (Exception e) { /* best-effort */ }
    }

    private void broadcastRefresh(Long hospitalId) {
        try { webSocketHandler.broadcast(hospitalId, "{\"type\":\"REFRESH_DATA\"}"); } catch (Exception e) { /* best-effort */ }
    }
}
```

- [ ] **Step 5: Run the test** — `cd backend && mvn -o -q -Dtest=FormAccessServiceTest test` → `Tests run: 7, Failures: 0`.

> Note on `normalizeRole`: when the caller is `HOSPITAL_ADMIN`, `effectiveForRole` returns EDITABLE for all enabled forms (admin preview). The nurse UI calls it as a NURSE, which is what matters for enforcement.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/hms/dto/FormAccessRequest.java \
        backend/src/main/java/com/hms/service/hospital/FormAccessService.java \
        backend/src/test/java/com/hms/service/hospital/FormAccessServiceTest.java
git commit -m "feat(files-access): FormAccessService (list/update/effectiveForRole) + tests"
```

---

### Task 3: FormAccessController

**Files:**
- Create: `backend/src/main/java/com/hms/controller/hospital/FormAccessController.java`

- [ ] **Step 1: Create the controller**

`backend/src/main/java/com/hms/controller/hospital/FormAccessController.java`:

```java
package com.hms.controller.hospital;

import com.hms.dto.FormAccessRequest;
import com.hms.security.SecurityContextHelper;
import com.hms.service.hospital.FormAccessService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * FormAccessController - Files & Access config (Phase 1). Admin lists/updates
 * the per-hospital form access; any hospital staff role reads the effective
 * verdict map for the forms UI. Hospital-tenant only.
 */
@RestController
@RequestMapping("/hospital/form-access")
public class FormAccessController {

    @Autowired private FormAccessService formAccessService;
    @Autowired private SecurityContextHelper securityHelper;

    @GetMapping
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(formAccessService.list());
    }

    @PutMapping("/{formKey}")
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<?> update(@PathVariable String formKey, @RequestBody FormAccessRequest req) {
        return ResponseEntity.ok(formAccessService.update(formKey, req));
    }

    @GetMapping("/effective")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','DOCTOR','NURSE','NURSE_INCHARGE')")
    public ResponseEntity<?> effective() {
        return ResponseEntity.ok(formAccessService.effectiveForRole(securityHelper.getCurrentUserRole()));
    }
}
```

- [ ] **Step 2: Run the full backend suite** — `cd backend && mvn -o test`
Expected: `BUILD SUCCESS`, `Tests run: 228` (221 prior + 7 new), 0 failures.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/hms/controller/hospital/FormAccessController.java
git commit -m "feat(files-access): FormAccessController (/hospital/form-access)"
```

---

## Milestone P1-B — Frontend settings table

### Task 4: `formAccessService.js` + `FilesAndAccessCard` + wire into Settings

**Files:**
- Create: `frontend/src/services/formAccessService.js`
- Create: `frontend/src/pages/hospital/FilesAndAccessCard.jsx`
- Modify: `frontend/src/pages/hospital/HospitalAdminDashboard.jsx`

- [ ] **Step 1: Create the service**

`frontend/src/services/formAccessService.js`:

```javascript
import apiClient from './apiService';

/** formAccessService - Files & Access config (Phase 1). */
const formAccessService = {
    list: async () => (await apiClient.get('/hospital/form-access')).data,
    update: async (formKey, payload) => (await apiClient.put(`/hospital/form-access/${formKey}`, payload)).data,
    effective: async () => (await apiClient.get('/hospital/form-access/effective')).data,
};

export default formAccessService;
```

- [ ] **Step 2: Create the settings card/table**

`frontend/src/pages/hospital/FilesAndAccessCard.jsx`:

```jsx
import React, { useState, useEffect, useCallback } from 'react';
import formAccessService from '../../services/formAccessService';
import { useToast } from '../../context/ToastContext';

const ACCESS_OPTIONS = [
    { value: 'DOCTOR', label: 'Doctor' },
    { value: 'NURSE', label: 'Nurse' },
    { value: 'BOTH', label: 'Both' },
];

/**
 * FilesAndAccessCard - admin table controlling which forms are active and who
 * may edit each (Files & Access, Phase 1). Rows come from the backend registry;
 * each change PUTs an override.
 */
const FilesAndAccessCard = () => {
    const { success, error: toastError } = useToast();
    const [forms, setForms] = useState([]);
    const [loading, setLoading] = useState(true);
    const [savingKey, setSavingKey] = useState(null);

    const load = useCallback(async () => {
        setLoading(true);
        try {
            setForms(await formAccessService.list());
        } catch (e) {
            toastError(e?.response?.data?.error || 'Failed to load forms');
        } finally {
            setLoading(false);
        }
    }, [toastError]);

    useEffect(() => { load(); }, [load]);

    const save = async (form, patch) => {
        const next = { enabled: form.enabled, accessRole: form.accessRole, ...patch };
        setSavingKey(form.key);
        // optimistic
        setForms((prev) => prev.map((f) => (f.key === form.key ? { ...f, ...next } : f)));
        try {
            await formAccessService.update(form.key, next);
            success('Form access updated');
        } catch (e) {
            toastError(e?.response?.data?.error || 'Failed to update');
            load(); // revert to server truth
        } finally {
            setSavingKey(null);
        }
    };

    if (loading) return <div className="p-6 text-gray-500 text-sm">Loading forms…</div>;

    const groups = [
        { category: 'NURSING', title: 'Nursing Records' },
        { category: 'OT', title: 'OT / Surgery Forms' },
    ];

    return (
        <div className="bg-white rounded-2xl border border-gray-200/80 shadow-sm p-6 mt-6">
            <h3 className="text-lg font-bold text-gray-900 mb-1">Files &amp; Access</h3>
            <p className="text-sm text-gray-500 mb-5">Turn forms on or off for this hospital and choose who can edit each one. Off forms are hidden everywhere.</p>
            {groups.map((g) => {
                const rows = forms.filter((f) => f.category === g.category);
                if (rows.length === 0) return null;
                return (
                    <div key={g.category} className="mb-6 last:mb-0">
                        <h4 className="text-xs font-bold uppercase tracking-wider text-gray-400 mb-2">{g.title}</h4>
                        <div className="border border-gray-200 rounded-xl overflow-hidden">
                            <table className="w-full text-sm">
                                <thead className="bg-gray-50 border-b border-gray-200">
                                    <tr>
                                        <th className="px-4 py-2.5 text-left font-semibold text-gray-600">Form</th>
                                        <th className="px-4 py-2.5 text-left font-semibold text-gray-600">Accessed by</th>
                                        <th className="px-4 py-2.5 text-left font-semibold text-gray-600">Status</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {rows.map((f) => (
                                        <tr key={f.key} className={`border-b border-gray-100 last:border-0 ${!f.enabled ? 'bg-gray-50/60' : ''}`}>
                                            <td className={`px-4 py-3 font-medium ${f.enabled ? 'text-gray-900' : 'text-gray-400'}`}>{f.label}</td>
                                            <td className="px-4 py-3">
                                                <select
                                                    value={f.accessRole}
                                                    disabled={!f.enabled || savingKey === f.key}
                                                    onChange={(e) => save(f, { accessRole: e.target.value })}
                                                    className="px-3 py-1.5 text-sm border border-gray-300 rounded-lg disabled:bg-gray-100 disabled:text-gray-400"
                                                >
                                                    {ACCESS_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
                                                </select>
                                            </td>
                                            <td className="px-4 py-3">
                                                <button
                                                    type="button"
                                                    disabled={savingKey === f.key}
                                                    onClick={() => save(f, { enabled: !f.enabled })}
                                                    className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors ${f.enabled ? 'bg-gray-900' : 'bg-gray-300'}`}
                                                    aria-label={f.enabled ? 'On' : 'Off'}
                                                >
                                                    <span className={`inline-block h-4 w-4 transform rounded-full bg-white transition-transform ${f.enabled ? 'translate-x-6' : 'translate-x-1'}`} />
                                                </button>
                                            </td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        </div>
                    </div>
                );
            })}
        </div>
    );
};

export default FilesAndAccessCard;
```

- [ ] **Step 3: Wire into the Settings tab**

In `HospitalAdminDashboard.jsx`, add the import near the other page imports (e.g. next to `import TimeSlotsView from './TimeSlotsView';`):

```jsx
import FilesAndAccessCard from './FilesAndAccessCard';
```

Find the settings render block (`{activeTab === 'settings' && (` around line 2733). It renders a card `<div class="p-6 bg-white rounded-2xl ... max-w-4xl mx-auto my-4">…</div>`. Immediately **after** that closing `</div>` (still inside the `activeTab === 'settings'` block, before its closing `)}`), add — but only for non-pharmacy hospital tenants:

```jsx
                                        {!isPharmacyTenant && <FilesAndAccessCard />}
```

To place it precisely: locate the end of the settings card container and insert the line before the `)}` that closes the `activeTab === 'settings'` conditional. If the settings block returns a single card `<div>…</div>`, wrap both in a fragment:

```jsx
                                {activeTab === 'settings' && (
                                    <>
                                        <div className="p-6 bg-white rounded-2xl border border-gray-200/80 shadow-sm max-w-4xl mx-auto my-4">
                                            {/* …existing settings card unchanged… */}
                                        </div>
                                        {!isPharmacyTenant && (
                                            <div className="max-w-4xl mx-auto my-4">
                                                <FilesAndAccessCard />
                                            </div>
                                        )}
                                    </>
                                )}
```

(Read the block first; keep the existing card's inner JSX exactly as-is — only add the surrounding fragment and the `FilesAndAccessCard` block. `isPharmacyTenant` is already defined in this component.)

- [ ] **Step 4: Build** — `cd frontend && npx vite build --mode development` → `✓ built`.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/services/formAccessService.js frontend/src/pages/hospital/FilesAndAccessCard.jsx frontend/src/pages/hospital/HospitalAdminDashboard.jsx
git commit -m "feat(files-access): Files & Access settings table"
```

---

## Milestone P1-C — Nurse enforcement

### Task 5: Add `readOnly` support to the five nurse panels

**Files (each modified):**
- `frontend/src/pages/hospital/nurse/VitalsPanel.jsx`
- `frontend/src/pages/hospital/nurse/InitialAssessmentPanel.jsx`
- `frontend/src/pages/hospital/nurse/VulnerabilityAssessmentPanel.jsx`
- `frontend/src/pages/hospital/nurse/SugarChartPanel.jsx`
- `frontend/src/pages/hospital/nurse/ConsentFormsPanel.jsx`

For **each** of the four nursing panels (`VitalsPanel`, `InitialAssessmentPanel`, `VulnerabilityAssessmentPanel`, `SugarChartPanel`), apply these three edits (read the file first):

- [ ] **Step 1: Accept the prop.** Change the component signature from `({ admissionId })` to `({ admissionId, readOnly = false })`.

- [ ] **Step 2: Disable inputs in read-only.** Wrap the component's returned JSX in a fieldset that disables all descendant form controls without changing layout. I.e. change:

```jsx
    return (
        <div ...>
            ... panel body ...
        </div>
    );
```

to:

```jsx
    return (
        <fieldset disabled={readOnly} style={{ display: 'contents' }}>
            {readOnly && (
                <div className="mb-3 text-xs font-semibold text-amber-700 bg-amber-50 border border-amber-100 rounded-lg px-3 py-2">
                    Read-only — editing for this form is disabled for your role in Files &amp; Access.
                </div>
            )}
            <div ...>
                ... panel body ...
            </div>
        </fieldset>
    );
```

(The `disabled` fieldset disables every input/select/textarea/button inside it, including Save; `display: contents` keeps the existing layout. If the panel’s root is a Fragment `<>…</>`, put the fieldset just inside it.)

- [ ] **Step 3: For `ConsentFormsPanel`,** the panel lists forms and opens each in `SurgeryFormFrame`. Apply a per-form verdict instead of one flag:
  - Change signature to `({ admissionId, formVerdicts = {} })` where `formVerdicts` maps `formKey → 'EDITABLE'|'READ_ONLY'|'HIDDEN'`.
  - When rendering the list of forms from `surgeryFormsRegistry`, **filter out** any form whose `formVerdicts[form.type] === 'HIDDEN'`.
  - When opening a form, pass `readOnly={formVerdicts[form.type] === 'READ_ONLY'}` to `SurgeryFormFrame`.
  - In `frontend/src/pages/hospital/ot/SurgeryFormFrame.jsx`: add `readOnly = false` to its props; wrap its form body in `<fieldset disabled={readOnly} style={{ display: 'contents' }}>…</fieldset>`; keep the **Print** button outside the fieldset (or add `disabled={false}` explicitly) so printing still works; hide/disable the **Save** button when `readOnly`.

- [ ] **Step 4: Build** — `cd frontend && npx vite build --mode development` → `✓ built`.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/hospital/nurse/VitalsPanel.jsx \
        frontend/src/pages/hospital/nurse/InitialAssessmentPanel.jsx \
        frontend/src/pages/hospital/nurse/VulnerabilityAssessmentPanel.jsx \
        frontend/src/pages/hospital/nurse/SugarChartPanel.jsx \
        frontend/src/pages/hospital/nurse/ConsentFormsPanel.jsx \
        frontend/src/pages/hospital/ot/SurgeryFormFrame.jsx
git commit -m "feat(files-access): readOnly support in nurse form panels"
```

---

### Task 6: Enforce the effective map in `NursePatientDetail`

**Files:**
- Modify: `frontend/src/pages/hospital/nurse/NursePatientDetail.jsx`
- Modify: `frontend/src/services/nurseService.js` (only if not adding via formAccessService — use `formAccessService` directly instead)

- [ ] **Step 1: Fetch the effective verdict map.** In `NursePatientDetail.jsx`, add an import:

```jsx
import formAccessService from '../../../services/formAccessService';
```

Add state + load (near the other hooks):

```jsx
    const [formVerdicts, setFormVerdicts] = useState({});
    useEffect(() => {
        let active = true;
        formAccessService.effective()
            .then((v) => { if (active) setFormVerdicts(v || {}); })
            .catch(() => { if (active) setFormVerdicts({}); });
        return () => { active = false; };
    }, []);
```

- [ ] **Step 2: Map each nursing sub-tab to its form key and verdict.** Add a helper near the `tabs` definition:

```jsx
    const NURSING_FORM_KEY = {
        vitals: 'VITALS',
        assessment: 'INITIAL_ASSESSMENT',
        vulnerability: 'VULNERABILITY_ASSESSMENT',
        sugar: 'SUGAR_CHART',
    };
    const verdictFor = (tabId) => formVerdicts[NURSING_FORM_KEY[tabId]] || 'EDITABLE';
```

- [ ] **Step 3: Hide HIDDEN tabs.** Update the `tabs` array so the four nursing tabs are dropped when their verdict is `HIDDEN`. Replace the four static entries with conditional spreads, e.g.:

```jsx
    const tabs = [
        { id: 'overview', label: 'Overview' },
        ...(verdictFor('vitals') !== 'HIDDEN' ? [{ id: 'vitals', label: 'Vitals' }] : []),
        { id: 'medication', label: 'Medication' },
        { id: 'notes', label: 'Notes' },
        ...(verdictFor('assessment') !== 'HIDDEN' ? [{ id: 'assessment', label: 'Initial Assessment' }] : []),
        ...(verdictFor('vulnerability') !== 'HIDDEN' ? [{ id: 'vulnerability', label: 'Vulnerability Assessment' }] : []),
        ...(verdictFor('sugar') !== 'HIDDEN' ? [{ id: 'sugar', label: 'Sugar Chart' }] : []),
        ...(hasSurgery ? [{ id: 'consent', label: 'Consent Forms' }] : []),
    ];
```

- [ ] **Step 4: Pass `readOnly` / `formVerdicts` to the panels.** Update the render lines:

```jsx
            {tab === 'vitals' && <VitalsPanel admissionId={admissionId} readOnly={verdictFor('vitals') === 'READ_ONLY'} />}
            {tab === 'assessment' && <InitialAssessmentPanel admissionId={admissionId} readOnly={verdictFor('assessment') === 'READ_ONLY'} />}
            {tab === 'vulnerability' && <VulnerabilityAssessmentPanel admissionId={admissionId} readOnly={verdictFor('vulnerability') === 'READ_ONLY'} />}
            {tab === 'sugar' && <SugarChartPanel admissionId={admissionId} readOnly={verdictFor('sugar') === 'READ_ONLY'} />}
            {tab === 'consent' && <ConsentFormsPanel admissionId={admissionId} formVerdicts={formVerdicts} />}
```

(Medication, Notes, Overview lines unchanged. If `tab` currently points at a now-hidden tab, guard by defaulting: after computing `tabs`, add `useEffect(() => { if (!tabs.some(t => t.id === tab)) setTab('overview'); }, [tabs, tab]);`.)

- [ ] **Step 5: Build** — `cd frontend && npx vite build --mode development` → `✓ built`.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/pages/hospital/nurse/NursePatientDetail.jsx
git commit -m "feat(files-access): enforce form access on the nurse patient detail"
```

---

## Final verification

- [ ] `cd backend && mvn -o test` → `BUILD SUCCESS`, 228 tests, 0 failures.
- [ ] `cd frontend && npx vite build --mode development` → `✓ built`.
- [ ] Manual (backend restarted, logged in as admin then nurse):
  - Admin → Settings → Files & Access: toggle **Vitals** Off → as a nurse, the Vitals tab disappears from a patient.
  - Set **Vitals** access = Doctor → as a nurse, the Vitals tab shows but inputs are disabled and Save is gone (read-only banner shown).
  - Set a consent form Off → it no longer appears in the nurse's Consent Forms list; set it to Doctor → nurse can open it read-only and still Print.
  - Leave a form default (never touched) → nurse edits normally (enabled + BOTH).

## Notes for the implementer
- Do **not** run `git push`. Commit at the boundaries shown.
- Frontend builds run from `frontend/` only.
- The service returns plain `Map`/`List` (matching existing services); no extra response DTOs.
- Phase 2 (doctor IPD sub-tabs) will reuse `formAccessService.effective()` with the doctor's role and the same `readOnly` props added here — do not delete them.
```
