# Nursing Management — Phase B Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the Time Slots admin module (Shift Templates + Appointment Slots), nurse shift scheduling (per-date + range-fill, snapshot-based), and replace the manual `on_shift` toggle with schedule-derived on-shift status.

**Architecture:** Extends the Phase A nurse module. Shift/appointment definitions are simple tenant-scoped CRUD entities. A nurse's schedule row snapshots its shift template's times at creation; editing a template bulk-updates only future-dated snapshots. "On shift now" is computed from today's schedule (midnight-crossing aware), replacing the `on_shift` boolean.

**Tech Stack:** Spring Boot 3.3.5 / Java 17 / Maven / MySQL (JdbcTemplate migrations), JUnit 5 + Mockito + AssertJ. React 18 / Vite, axios (`apiClient`).

---

## Conventions (read once)
- Tenant scope via `SecurityContextHelper.getCurrentHospitalId()`. Module gate `@RequireModule("NURSING")`.
- Admin writes: `@PreAuthorize("hasRole('HOSPITAL_ADMIN')")`. Scheduling: `@PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')")` + `NurseInchargeGuard` for ward scope. Nurse reads: `hasAnyRole('NURSE','NURSE_INCHARGE','HOSPITAL_ADMIN')`.
- Errors: `IllegalArgumentException`→400, `UnauthorizedException`→401, `AccessDeniedException`→403.
- Migrations: reuse `DatabaseMigrationRunner.addColumnIfMissing(table,col,def)` (exists) for columns; for new tables add an `ensureXxxTable()` (COUNT information_schema.TABLES then CREATE) — copy the shape of `ensureSurgeryFormsTable`. Mirror in `setup/schema-full.sql`.
- Audit: `auditLogService.logAction(action, details, securityHelper.getCurrentUserEmail(), hospitalId, entityType, entityId, reason)` best-effort.
- Build/test: `cd backend && mvn -o test`; `cd frontend && npx vite build --mode development` (never vite from backend).
- Existing facts: `NurseInchargeGuard.assertWardAccess(Long)/myWardIds()`. `NurseProfileRepository.findByUserId`, `findById`, `findByWardIdAndIsInchargeFalseAndIsActiveTrue(Long)`. `NurseProfile.getWardId()/getId()/getHospitalId()`. `NurseWorkspaceService` has `getShiftStatus()/startShift()/endShift()` used by `NurseWorkspaceController` + frontend `NurseDashboard.jsx`/`nurseService.js`. `HospitalSettingRepository.findByHospital_Id`.
- **Commit at milestone boundaries** (B1, B2, B3), not per task, unless told otherwise.

---

## File Structure
**Backend new:** `entity/ShiftTemplate.java`, `entity/AppointmentSlot.java`, `entity/NurseShiftSchedule.java`; repos `ShiftTemplateRepository`, `AppointmentSlotRepository`, `NurseShiftScheduleRepository`; services `ShiftTemplateService`, `AppointmentSlotService`, `NurseShiftScheduleService`; controllers `TimeSlotController`, `NurseScheduleController`; DTOs `ShiftTemplateRequest`, `AppointmentSlotRequest`, `AssignShiftRequest`, `RangeFillShiftRequest`, `NurseShiftScheduleView`; tests for the three services.
**Backend modified:** `DatabaseMigrationRunner`, `schema-full.sql`, `NurseWorkspaceService` (getShiftStatus), `NurseWorkspaceController` (maybe drop start/end).
**Frontend new:** `services/timeSlotService.js`, `services/nurseScheduleService.js`; admin `pages/hospital/TimeSlots.jsx` (or a tab in admin dashboard); incharge schedule tab in `NurseInchargeDashboard.jsx`; nurse "My Shifts" in `NurseDashboard.jsx`.
**Frontend modified:** admin dashboard (Time Slots tab), `NurseDashboard.jsx` (status badge, drop gate), `nurseService.js`.

---

# Milestone B1 — Time Slots (Shift Templates + Appointment Slots)

### Task 1: `ShiftTemplate` + `AppointmentSlot` entities

**Files:** Create `entity/ShiftTemplate.java`, `entity/AppointmentSlot.java`

- [ ] **Step 1: ShiftTemplate**
```java
package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.time.LocalTime;

/** A reusable nurse shift definition (e.g. Morning 08:00-16:00). end<=start crosses midnight. */
@Entity
@Table(name = "shift_templates")
@Data @NoArgsConstructor @AllArgsConstructor
public class ShiftTemplate {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true)
    private String publicId;
    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;
    @Column(nullable = false, length = 60)
    private String name;
    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;
    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    @PrePersist public void pre() { if (publicId == null) publicId = java.util.UUID.randomUUID().toString(); }
}
```

- [ ] **Step 2: AppointmentSlot** (same shape, no `name`)
```java
package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.time.LocalTime;

/** A bookable appointment slot definition (e.g. 09:00-09:15). */
@Entity
@Table(name = "appointment_slots")
@Data @NoArgsConstructor @AllArgsConstructor
public class AppointmentSlot {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true)
    private String publicId;
    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;
    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;
    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    @PrePersist public void pre() { if (publicId == null) publicId = java.util.UUID.randomUUID().toString(); }
}
```

- [ ] **Step 3: Compile** `cd backend && mvn -o -q compile` → BUILD SUCCESS.

---

### Task 2: Repositories + migrations

**Files:** Create `ShiftTemplateRepository`, `AppointmentSlotRepository`; modify `DatabaseMigrationRunner`, `schema-full.sql`

- [ ] **Step 1: Repositories**
```java
package com.hms.repository;
import com.hms.entity.ShiftTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List; import java.util.Optional;
public interface ShiftTemplateRepository extends JpaRepository<ShiftTemplate, Long> {
    Optional<ShiftTemplate> findByPublicId(String publicId);
    List<ShiftTemplate> findByHospitalIdOrderByStartTimeAsc(Long hospitalId);
    List<ShiftTemplate> findByHospitalIdAndIsActiveTrueOrderByStartTimeAsc(Long hospitalId);
}
```
```java
package com.hms.repository;
import com.hms.entity.AppointmentSlot;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List; import java.util.Optional;
public interface AppointmentSlotRepository extends JpaRepository<AppointmentSlot, Long> {
    Optional<AppointmentSlot> findByPublicId(String publicId);
    List<AppointmentSlot> findByHospitalIdOrderByStartTimeAsc(Long hospitalId);
    List<AppointmentSlot> findByHospitalIdAndIsActiveTrueOrderByStartTimeAsc(Long hospitalId);
}
```

- [ ] **Step 2: Migrations.** Add ensure-table methods to `DatabaseMigrationRunner` (copy the shape of `ensureSurgeryFormsTable`; logger is `log`, field `jdbcTemplate`):
```java
    private void ensureShiftTemplatesTable() {
        try {
            Integer c = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'shift_templates'", Integer.class);
            if (c != null && c == 0) {
                jdbcTemplate.execute("CREATE TABLE shift_templates (" +
                    "id BIGINT NOT NULL AUTO_INCREMENT, public_id VARCHAR(255) NOT NULL, hospital_id BIGINT NOT NULL," +
                    "name VARCHAR(60) NOT NULL, start_time TIME NOT NULL, end_time TIME NOT NULL," +
                    "is_active TINYINT(1) NOT NULL DEFAULT 1, created_at DATETIME(6) NOT NULL," +
                    "PRIMARY KEY (id), UNIQUE KEY UK_shift_template_public (public_id), KEY idx_shift_template_hospital (hospital_id)," +
                    "FOREIGN KEY (hospital_id) REFERENCES hospitals(id) ON DELETE CASCADE)");
                log.info("DB migration applied: shift_templates table created");
            }
        } catch (Exception e) { log.warn("DB migration skipped (shift_templates): {}", e.getMessage()); }
    }
    private void ensureAppointmentSlotsTable() {
        try {
            Integer c = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'appointment_slots'", Integer.class);
            if (c != null && c == 0) {
                jdbcTemplate.execute("CREATE TABLE appointment_slots (" +
                    "id BIGINT NOT NULL AUTO_INCREMENT, public_id VARCHAR(255) NOT NULL, hospital_id BIGINT NOT NULL," +
                    "start_time TIME NOT NULL, end_time TIME NOT NULL, is_active TINYINT(1) NOT NULL DEFAULT 1, created_at DATETIME(6) NOT NULL," +
                    "PRIMARY KEY (id), UNIQUE KEY UK_appt_slot_public (public_id), KEY idx_appt_slot_hospital (hospital_id)," +
                    "FOREIGN KEY (hospital_id) REFERENCES hospitals(id) ON DELETE CASCADE)");
                log.info("DB migration applied: appointment_slots table created");
            }
        } catch (Exception e) { log.warn("DB migration skipped (appointment_slots): {}", e.getMessage()); }
    }
```
Call both from `runMigrations()`. Mirror both CREATE TABLEs in `setup/schema-full.sql`.

- [ ] **Step 3: Compile & commit-checkpoint** — `mvn -o -q compile` → BUILD SUCCESS.

---

### Task 3: Services + DTOs (TDD for validation)

**Files:** Create `dto/ShiftTemplateRequest.java`, `dto/AppointmentSlotRequest.java`, `service/hospital/ShiftTemplateService.java`, `service/hospital/AppointmentSlotService.java`; Test `ShiftTemplateServiceTest`

- [ ] **Step 1: DTOs**
```java
package com.hms.dto; import lombok.Data; import java.time.LocalTime;
@Data public class ShiftTemplateRequest { private String name; private LocalTime startTime; private LocalTime endTime; }
```
```java
package com.hms.dto; import lombok.Data; import java.time.LocalTime;
@Data public class AppointmentSlotRequest { private LocalTime startTime; private LocalTime endTime; }
```

- [ ] **Step 2: Failing test** `backend/src/test/java/com/hms/service/hospital/ShiftTemplateServiceTest.java`:
```java
package com.hms.service.hospital;

import com.hms.dto.ShiftTemplateRequest;
import com.hms.entity.ShiftTemplate;
import com.hms.repository.ShiftTemplateRepository;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks; import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShiftTemplateServiceTest {
    @Mock ShiftTemplateRepository repository;
    @Mock SecurityContextHelper securityHelper;
    @Mock AuditLogService auditLogService;
    @Mock NurseShiftScheduleService nurseShiftScheduleService;
    @InjectMocks ShiftTemplateService service;

    @Test void create_savesWithHospitalAndTimes() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        ShiftTemplateRequest r = new ShiftTemplateRequest();
        r.setName("Morning"); r.setStartTime(LocalTime.of(8,0)); r.setEndTime(LocalTime.of(16,0));
        ShiftTemplate t = service.create(r);
        assertThat(t.getHospitalId()).isEqualTo(7L);
        assertThat(t.getName()).isEqualTo("Morning");
        assertThat(t.getStartTime()).isEqualTo(LocalTime.of(8,0));
    }

    @Test void create_rejectsEqualStartEnd() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        ShiftTemplateRequest r = new ShiftTemplateRequest();
        r.setName("Bad"); r.setStartTime(LocalTime.of(8,0)); r.setEndTime(LocalTime.of(8,0));
        assertThatThrownBy(() -> service.create(r)).isInstanceOf(IllegalArgumentException.class);
    }
}
```
Run — expect FAIL.

- [ ] **Step 3: Implement `ShiftTemplateService`** (note: it holds a reference to `NurseShiftScheduleService` for the future-propagation hook, injected `@org.springframework.context.annotation.Lazy` to avoid a cycle; the method is defined in B2 — for B1, guard the call with a null/try so B1 compiles/tests standalone):
```java
package com.hms.service.hospital;

import com.hms.dto.ShiftTemplateRequest;
import com.hms.entity.ShiftTemplate;
import com.hms.exception.UnauthorizedException;
import com.hms.repository.ShiftTemplateRepository;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.List;

@Service
public class ShiftTemplateService {
    @Autowired private ShiftTemplateRepository repository;
    @Autowired private SecurityContextHelper securityHelper;
    @Autowired private AuditLogService auditLogService;
    @Autowired（required = false) @Lazy private NurseShiftScheduleService nurseShiftScheduleService;

    @Transactional
    public ShiftTemplate create(ShiftTemplateRequest req) {
        Long hospitalId = requireHospitalId();
        validate(req);
        ShiftTemplate t = new ShiftTemplate();
        t.setHospitalId(hospitalId);
        t.setName(req.getName().trim());
        t.setStartTime(req.getStartTime());
        t.setEndTime(req.getEndTime());
        t.setIsActive(true);
        ShiftTemplate saved = repository.save(t);
        audit("SHIFT_TEMPLATE_CREATED", saved.getName(), hospitalId, saved.getId());
        return saved;
    }

    public List<ShiftTemplate> list(boolean activeOnly) {
        Long hospitalId = requireHospitalId();
        return activeOnly ? repository.findByHospitalIdAndIsActiveTrueOrderByStartTimeAsc(hospitalId)
                          : repository.findByHospitalIdOrderByStartTimeAsc(hospitalId);
    }

    @Transactional
    public ShiftTemplate update(String publicId, ShiftTemplateRequest req) {
        Long hospitalId = requireHospitalId();
        ShiftTemplate t = require(publicId, hospitalId);
        validate(req);
        t.setName(req.getName().trim());
        t.setStartTime(req.getStartTime());
        t.setEndTime(req.getEndTime());
        ShiftTemplate saved = repository.save(t);
        // Future schedules using this template pick up the new times; past unchanged.
        if (nurseShiftScheduleService != null) {
            try { nurseShiftScheduleService.applyTemplateChangeToFuture(saved.getId(), saved.getStartTime(), saved.getEndTime()); }
            catch (Exception ignored) { }
        }
        audit("SHIFT_TEMPLATE_UPDATED", saved.getName(), hospitalId, saved.getId());
        return saved;
    }

    @Transactional
    public void deactivate(String publicId) {
        Long hospitalId = requireHospitalId();
        ShiftTemplate t = require(publicId, hospitalId);
        t.setIsActive(false);
        repository.save(t);
        audit("SHIFT_TEMPLATE_DEACTIVATED", t.getName(), hospitalId, t.getId());
    }

    private void validate(ShiftTemplateRequest req) {
        if (req.getName() == null || req.getName().trim().isEmpty()) throw new IllegalArgumentException("Name is required");
        LocalTime s = req.getStartTime(), e = req.getEndTime();
        if (s == null || e == null) throw new IllegalArgumentException("Start and end time are required");
        if (s.equals(e)) throw new IllegalArgumentException("Start and end time must differ");
    }
    private ShiftTemplate require(String publicId, Long hospitalId) {
        ShiftTemplate t = repository.findByPublicId(publicId).orElseThrow(() -> new IllegalArgumentException("Shift template not found"));
        if (!hospitalId.equals(t.getHospitalId())) throw new UnauthorizedException("Belongs to another hospital");
        return t;
    }
    private Long requireHospitalId() {
        Long h = securityHelper.getCurrentHospitalId();
        if (h == null) throw new UnauthorizedException("Hospital ID not found");
        return h;
    }
    private void audit(String a, String d, Long h, Long id) {
        try { auditLogService.logAction(a, d, securityHelper.getCurrentUserEmail(), h, "SHIFT_TEMPLATE", String.valueOf(id), null); } catch (Exception e) {}
    }
}
```
IMPORTANT: the `@Autowired（required = false)` above contains a full-width parenthesis by mistake — write it as `@Autowired(required = false)`. Ensure the import for `Lazy` is present. If Spring complains about the optional lazy dependency, instead inject `NurseShiftScheduleService` normally (it exists after B2) and remove the null check — but for B1-before-B2 ordering, keep it optional.

- [ ] **Step 4: `AppointmentSlotService`** — analogous (no name; validate end after start; audit `APPOINTMENT_SLOT_*`), methods `create/list/update/deactivate`.
```java
package com.hms.service.hospital;

import com.hms.dto.AppointmentSlotRequest;
import com.hms.entity.AppointmentSlot;
import com.hms.exception.UnauthorizedException;
import com.hms.repository.AppointmentSlotRepository;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AppointmentSlotService {
    @Autowired private AppointmentSlotRepository repository;
    @Autowired private SecurityContextHelper securityHelper;
    @Autowired private AuditLogService auditLogService;

    @Transactional public AppointmentSlot create(AppointmentSlotRequest req) {
        Long hospitalId = requireHospitalId(); validate(req);
        AppointmentSlot s = new AppointmentSlot();
        s.setHospitalId(hospitalId); s.setStartTime(req.getStartTime()); s.setEndTime(req.getEndTime()); s.setIsActive(true);
        AppointmentSlot saved = repository.save(s);
        audit("APPOINTMENT_SLOT_CREATED", saved.getStartTime() + "-" + saved.getEndTime(), hospitalId, saved.getId());
        return saved;
    }
    public List<AppointmentSlot> list(boolean activeOnly) {
        Long h = requireHospitalId();
        return activeOnly ? repository.findByHospitalIdAndIsActiveTrueOrderByStartTimeAsc(h) : repository.findByHospitalIdOrderByStartTimeAsc(h);
    }
    @Transactional public AppointmentSlot update(String publicId, AppointmentSlotRequest req) {
        Long h = requireHospitalId(); AppointmentSlot s = require(publicId, h); validate(req);
        s.setStartTime(req.getStartTime()); s.setEndTime(req.getEndTime());
        AppointmentSlot saved = repository.save(s);
        audit("APPOINTMENT_SLOT_UPDATED", saved.getStartTime() + "-" + saved.getEndTime(), h, saved.getId());
        return saved;
    }
    @Transactional public void deactivate(String publicId) {
        Long h = requireHospitalId(); AppointmentSlot s = require(publicId, h);
        s.setIsActive(false); repository.save(s);
        audit("APPOINTMENT_SLOT_DEACTIVATED", s.getStartTime() + "-" + s.getEndTime(), h, s.getId());
    }
    private void validate(AppointmentSlotRequest req) {
        if (req.getStartTime() == null || req.getEndTime() == null) throw new IllegalArgumentException("Start and end time are required");
        if (!req.getEndTime().isAfter(req.getStartTime())) throw new IllegalArgumentException("End time must be after start time");
    }
    private AppointmentSlot require(String publicId, Long h) {
        AppointmentSlot s = repository.findByPublicId(publicId).orElseThrow(() -> new IllegalArgumentException("Slot not found"));
        if (!h.equals(s.getHospitalId())) throw new UnauthorizedException("Belongs to another hospital");
        return s;
    }
    private Long requireHospitalId() { Long h = securityHelper.getCurrentHospitalId(); if (h == null) throw new UnauthorizedException("Hospital ID not found"); return h; }
    private void audit(String a, String d, Long h, Long id) { try { auditLogService.logAction(a, d, securityHelper.getCurrentUserEmail(), h, "APPOINTMENT_SLOT", String.valueOf(id), null); } catch (Exception e) {} }
}
```
NOTE: `ShiftTemplateServiceTest` mocks `NurseShiftScheduleService` — that class is created in B2. For B1 to build/test standalone, create a MINIMAL stub `NurseShiftScheduleService` now with just `public void applyTemplateChangeToFuture(Long templateId, java.time.LocalTime start, java.time.LocalTime end) {}` (B2 fills it in), OR make the test not mock it and make the field truly optional. Simplest: create the full `NurseShiftScheduleService` in B2 and, for B1, add a temporary no-op `applyTemplateChangeToFuture` method to an otherwise-empty `@Service NurseShiftScheduleService`. Report which you did.

- [ ] **Step 5: Run tests** `mvn -o -q -Dtest=ShiftTemplateServiceTest test` → PASS. Then `mvn -o test` → BUILD SUCCESS.

---

### Task 4: Time Slots controller

**Files:** Create `controller/hospital/TimeSlotController.java`

- [ ] **Step 1:**
```java
package com.hms.controller.hospital;

import com.hms.dto.AppointmentSlotRequest;
import com.hms.dto.ShiftTemplateRequest;
import com.hms.security.RequireModule;
import com.hms.service.hospital.AppointmentSlotService;
import com.hms.service.hospital.ShiftTemplateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/hospital/time-slots")
@RequireModule("NURSING")
public class TimeSlotController {
    @Autowired private ShiftTemplateService shiftTemplateService;
    @Autowired private AppointmentSlotService appointmentSlotService;

    // Shift templates
    @GetMapping("/shift-templates")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')")
    public ResponseEntity<?> listShiftTemplates(@RequestParam(defaultValue = "false") boolean activeOnly) {
        return ResponseEntity.ok(shiftTemplateService.list(activeOnly));
    }
    @PostMapping("/shift-templates")
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<?> createShiftTemplate(@RequestBody ShiftTemplateRequest req) {
        return ResponseEntity.ok(shiftTemplateService.create(req));
    }
    @PutMapping("/shift-templates/{publicId}")
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<?> updateShiftTemplate(@PathVariable String publicId, @RequestBody ShiftTemplateRequest req) {
        return ResponseEntity.ok(shiftTemplateService.update(publicId, req));
    }
    @DeleteMapping("/shift-templates/{publicId}")
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<?> deactivateShiftTemplate(@PathVariable String publicId) {
        shiftTemplateService.deactivate(publicId);
        return ResponseEntity.ok(Map.of("message", "Shift template deactivated"));
    }

    // Appointment slots
    @GetMapping("/appointment-slots")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','RECEPTIONIST','NURSE_INCHARGE')")
    public ResponseEntity<?> listSlots(@RequestParam(defaultValue = "false") boolean activeOnly) {
        return ResponseEntity.ok(appointmentSlotService.list(activeOnly));
    }
    @PostMapping("/appointment-slots")
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<?> createSlot(@RequestBody AppointmentSlotRequest req) {
        return ResponseEntity.ok(appointmentSlotService.create(req));
    }
    @PutMapping("/appointment-slots/{publicId}")
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<?> updateSlot(@PathVariable String publicId, @RequestBody AppointmentSlotRequest req) {
        return ResponseEntity.ok(appointmentSlotService.update(publicId, req));
    }
    @DeleteMapping("/appointment-slots/{publicId}")
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<?> deactivateSlot(@PathVariable String publicId) {
        appointmentSlotService.deactivate(publicId);
        return ResponseEntity.ok(Map.of("message", "Slot deactivated"));
    }
}
```
- [ ] **Step 2: Compile + full test** — `mvn -o test` → BUILD SUCCESS.

---

### Task 5: Frontend — admin Time Slots UI

**Files:** Create `frontend/src/services/timeSlotService.js`; add a "Time Slots" tab to the admin dashboard.

- [ ] **Step 1: Service**
```javascript
import apiClient from './apiService';
const timeSlotService = {
    listShiftTemplates: async (activeOnly = false) => (await apiClient.get(`/hospital/time-slots/shift-templates?activeOnly=${activeOnly}`)).data,
    createShiftTemplate: async (p) => (await apiClient.post('/hospital/time-slots/shift-templates', p)).data,
    updateShiftTemplate: async (id, p) => (await apiClient.put(`/hospital/time-slots/shift-templates/${id}`, p)).data,
    deactivateShiftTemplate: async (id) => (await apiClient.delete(`/hospital/time-slots/shift-templates/${id}`)).data,
    listAppointmentSlots: async (activeOnly = false) => (await apiClient.get(`/hospital/time-slots/appointment-slots?activeOnly=${activeOnly}`)).data,
    createAppointmentSlot: async (p) => (await apiClient.post('/hospital/time-slots/appointment-slots', p)).data,
    updateAppointmentSlot: async (id, p) => (await apiClient.put(`/hospital/time-slots/appointment-slots/${id}`, p)).data,
    deactivateAppointmentSlot: async (id) => (await apiClient.delete(`/hospital/time-slots/appointment-slots/${id}`)).data,
};
export default timeSlotService;
```
- [ ] **Step 2: Admin tab.** Add a "Time Slots" sidebar tab in `HospitalAdminDashboard.jsx` (gated by the `NURSING` module — match how other module-gated admin tabs are added). Render two sub-tabs: **Shift Templates** (table: Name, Start, End, Active; add/edit modal with name + two `<input type="time">` + save; deactivate action) and **Appointment Slots** (table: Start, End, Active; add/edit modal with two time inputs). Times are sent as `"HH:mm"` strings — the backend `LocalTime` binds from `HH:mm`. Toast + refresh on success; error at `e?.response?.data?.error`.
- [ ] **Step 3: Build** `cd frontend && npx vite build --mode development` → `✓ built`.
- [ ] **Step 4: Commit B1**
```bash
git add -A && git commit -m "feat(nurse-mgmt): Phase B1 — Time Slots (shift templates + appointment slots)"
```

---

# Milestone B2 — Nurse scheduling

### Task 6: `NurseShiftSchedule` entity + repo + migration

**Files:** Create `entity/NurseShiftSchedule.java`, `repository/NurseShiftScheduleRepository.java`; migration.

- [ ] **Step 1: Entity**
```java
package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor; import lombok.Data; import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDate; import java.time.LocalDateTime; import java.time.LocalTime;

/** One nurse's shift on one date. start/end are SNAPSHOTS of the template at assign time. */
@Entity
@Table(name = "nurse_shift_schedules",
       uniqueConstraints = @UniqueConstraint(name = "UK_nss_nurse_date", columnNames = {"nurse_profile_id","shift_date"}))
@Data @NoArgsConstructor @AllArgsConstructor
public class NurseShiftSchedule {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true) private String publicId;
    @Column(name = "hospital_id", nullable = false) private Long hospitalId;
    @Column(name = "nurse_profile_id", nullable = false) private Long nurseProfileId;
    @Column(name = "ward_id") private Long wardId;
    @Column(name = "shift_date", nullable = false) private LocalDate shiftDate;
    @Column(name = "shift_template_id", nullable = false) private Long shiftTemplateId;
    @Column(name = "start_time", nullable = false) private LocalTime startTime;
    @Column(name = "end_time", nullable = false) private LocalTime endTime;
    @Column(name = "created_by_user_id") private Long createdByUserId;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
    @PrePersist public void pre() { if (publicId == null) publicId = java.util.UUID.randomUUID().toString(); }
}
```
- [ ] **Step 2: Repository**
```java
package com.hms.repository;
import com.hms.entity.NurseShiftSchedule;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate; import java.time.LocalTime; import java.util.List; import java.util.Optional;
public interface NurseShiftScheduleRepository extends JpaRepository<NurseShiftSchedule, Long> {
    Optional<NurseShiftSchedule> findByPublicId(String publicId);
    Optional<NurseShiftSchedule> findByNurseProfileIdAndShiftDate(Long nurseProfileId, LocalDate shiftDate);
    List<NurseShiftSchedule> findByNurseProfileIdAndShiftDateBetweenOrderByShiftDateAsc(Long nurseProfileId, LocalDate from, LocalDate to);
    List<NurseShiftSchedule> findByWardIdAndShiftDateBetweenOrderByShiftDateAsc(Long wardId, LocalDate from, LocalDate to);
    @Modifying
    @Query("UPDATE NurseShiftSchedule s SET s.startTime = :start, s.endTime = :end WHERE s.shiftTemplateId = :templateId AND s.shiftDate >= :today")
    int applyTemplateChangeToFuture(@Param("templateId") Long templateId, @Param("start") LocalTime start, @Param("end") LocalTime end, @Param("today") LocalDate today);
}
```
- [ ] **Step 3: Migration** `ensureNurseShiftSchedulesTable()` (CREATE TABLE with the columns above; UNIQUE `(nurse_profile_id, shift_date)`, KEY `(hospital_id, shift_date)`, KEY `(nurse_profile_id, shift_date)`, FK hospital_id→hospitals CASCADE). Call from `runMigrations()`; mirror in `schema-full.sql`.
- [ ] **Step 4: Compile** → BUILD SUCCESS.

---

### Task 7: `NurseShiftScheduleService` (TDD)

**Files:** Replace the B1 stub `service/hospital/NurseShiftScheduleService.java` with the full service; Test `NurseShiftScheduleServiceTest`; DTOs `AssignShiftRequest`, `RangeFillShiftRequest`, `NurseShiftScheduleView`.

- [ ] **Step 1: DTOs**
```java
package com.hms.dto; import lombok.Data; import java.time.LocalDate;
@Data public class AssignShiftRequest { private Long nurseProfileId; private LocalDate date; private String shiftTemplatePublicId; }
```
```java
package com.hms.dto; import lombok.Data; import java.time.LocalDate; import java.util.List;
@Data public class RangeFillShiftRequest {
    private Long nurseProfileId; private LocalDate fromDate; private LocalDate toDate;
    private String shiftTemplatePublicId; private List<Integer> daysOfWeek; // 1=Mon..7=Sun; null/empty = all days
}
```
```java
package com.hms.dto; import com.hms.entity.NurseShiftSchedule; import lombok.Data;
import java.time.LocalDate; import java.time.LocalTime;
@Data public class NurseShiftScheduleView {
    private String publicId; private Long nurseProfileId; private String nurseName; private Long wardId;
    private LocalDate shiftDate; private Long shiftTemplateId; private LocalTime startTime; private LocalTime endTime;
    public static NurseShiftScheduleView of(NurseShiftSchedule s) {
        NurseShiftScheduleView v = new NurseShiftScheduleView();
        v.publicId = s.getPublicId(); v.nurseProfileId = s.getNurseProfileId(); v.wardId = s.getWardId();
        v.shiftDate = s.getShiftDate(); v.shiftTemplateId = s.getShiftTemplateId();
        v.startTime = s.getStartTime(); v.endTime = s.getEndTime(); return v;
    }
}
```

- [ ] **Step 2: Failing test** `NurseShiftScheduleServiceTest`:
```java
package com.hms.service.hospital;

import com.hms.dto.AssignShiftRequest;
import com.hms.entity.NurseProfile;
import com.hms.entity.NurseShiftSchedule;
import com.hms.entity.ShiftTemplate;
import com.hms.repository.NurseProfileRepository;
import com.hms.repository.NurseShiftScheduleRepository;
import com.hms.repository.ShiftTemplateRepository;
import com.hms.security.NurseInchargeGuard;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks; import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate; import java.time.LocalTime; import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NurseShiftScheduleServiceTest {
    @Mock NurseShiftScheduleRepository scheduleRepository;
    @Mock ShiftTemplateRepository shiftTemplateRepository;
    @Mock NurseProfileRepository nurseProfileRepository;
    @Mock NurseInchargeGuard nurseInchargeGuard;
    @Mock SecurityContextHelper securityHelper;
    @Mock AuditLogService auditLogService;
    @InjectMocks NurseShiftScheduleService service;

    @Test void assign_snapshotsTemplateTimes_andWardScopes() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        NurseProfile p = new NurseProfile(); p.setId(11L); p.setHospitalId(7L); p.setWardId(3L);
        when(nurseProfileRepository.findById(11L)).thenReturn(Optional.of(p));
        ShiftTemplate t = new ShiftTemplate(); t.setId(5L); t.setHospitalId(7L);
        t.setStartTime(LocalTime.of(8,0)); t.setEndTime(LocalTime.of(16,0));
        when(shiftTemplateRepository.findByPublicId("st-1")).thenReturn(Optional.of(t));
        when(scheduleRepository.findByNurseProfileIdAndShiftDate(11L, LocalDate.of(2026,7,10))).thenReturn(Optional.empty());
        when(scheduleRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        AssignShiftRequest req = new AssignShiftRequest();
        req.setNurseProfileId(11L); req.setDate(LocalDate.of(2026,7,10)); req.setShiftTemplatePublicId("st-1");
        NurseShiftSchedule s = service.assign(req);

        verify(nurseInchargeGuard).assertWardAccess(3L);
        assertThat(s.getStartTime()).isEqualTo(LocalTime.of(8,0));
        assertThat(s.getEndTime()).isEqualTo(LocalTime.of(16,0));
        assertThat(s.getShiftTemplateId()).isEqualTo(5L);
    }

    @Test void isOnShiftNow_trueWithinWindow() {
        LocalTime now = LocalTime.now();
        NurseShiftSchedule s = new NurseShiftSchedule();
        s.setStartTime(now.minusHours(1)); s.setEndTime(now.plusHours(1));
        when(scheduleRepository.findByNurseProfileIdAndShiftDate(eq(11L), any())).thenReturn(Optional.of(s));
        assertThat(service.isOnShiftNow(11L)).isTrue();
    }
}
```
Run — expect FAIL.

- [ ] **Step 3: Implement `NurseShiftScheduleService`** (replaces the B1 stub):
```java
package com.hms.service.hospital;

import com.hms.dto.AssignShiftRequest;
import com.hms.dto.NurseShiftScheduleView;
import com.hms.dto.RangeFillShiftRequest;
import com.hms.entity.NurseProfile;
import com.hms.entity.NurseShiftSchedule;
import com.hms.entity.ShiftTemplate;
import com.hms.exception.UnauthorizedException;
import com.hms.repository.NurseProfileRepository;
import com.hms.repository.NurseShiftScheduleRepository;
import com.hms.repository.ShiftTemplateRepository;
import com.hms.security.NurseInchargeGuard;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class NurseShiftScheduleService {
    @Autowired private NurseShiftScheduleRepository scheduleRepository;
    @Autowired private ShiftTemplateRepository shiftTemplateRepository;
    @Autowired private NurseProfileRepository nurseProfileRepository;
    @Autowired private NurseInchargeGuard nurseInchargeGuard;
    @Autowired private SecurityContextHelper securityHelper;
    @Autowired private AuditLogService auditLogService;

    @Transactional
    public NurseShiftSchedule assign(AssignShiftRequest req) {
        Long hospitalId = requireHospitalId();
        NurseProfile p = requireNurse(req.getNurseProfileId(), hospitalId);
        nurseInchargeGuard.assertWardAccess(p.getWardId());
        ShiftTemplate t = requireTemplate(req.getShiftTemplatePublicId(), hospitalId);
        if (req.getDate() == null) throw new IllegalArgumentException("Date is required");
        NurseShiftSchedule s = scheduleRepository.findByNurseProfileIdAndShiftDate(p.getId(), req.getDate())
                .orElseGet(NurseShiftSchedule::new);
        s.setHospitalId(hospitalId);
        s.setNurseProfileId(p.getId());
        s.setWardId(p.getWardId());
        s.setShiftDate(req.getDate());
        s.setShiftTemplateId(t.getId());
        s.setStartTime(t.getStartTime());
        s.setEndTime(t.getEndTime());
        s.setCreatedByUserId(securityHelper.getCurrentUserId());
        NurseShiftSchedule saved = scheduleRepository.save(s);
        audit("NURSE_SHIFT_ASSIGNED", p.getName() + " " + req.getDate() + " " + t.getName(), hospitalId, saved.getId());
        return saved;
    }

    @Transactional
    public int rangeFill(RangeFillShiftRequest req) {
        Long hospitalId = requireHospitalId();
        NurseProfile p = requireNurse(req.getNurseProfileId(), hospitalId);
        nurseInchargeGuard.assertWardAccess(p.getWardId());
        ShiftTemplate t = requireTemplate(req.getShiftTemplatePublicId(), hospitalId);
        if (req.getFromDate() == null || req.getToDate() == null || req.getToDate().isBefore(req.getFromDate())) {
            throw new IllegalArgumentException("Valid from/to dates are required");
        }
        int count = 0;
        for (LocalDate d = req.getFromDate(); !d.isAfter(req.getToDate()); d = d.plusDays(1)) {
            if (req.getDaysOfWeek() != null && !req.getDaysOfWeek().isEmpty()
                    && !req.getDaysOfWeek().contains(d.getDayOfWeek().getValue())) continue;
            NurseShiftSchedule s = scheduleRepository.findByNurseProfileIdAndShiftDate(p.getId(), d)
                    .orElseGet(NurseShiftSchedule::new);
            s.setHospitalId(hospitalId); s.setNurseProfileId(p.getId()); s.setWardId(p.getWardId());
            s.setShiftDate(d); s.setShiftTemplateId(t.getId());
            s.setStartTime(t.getStartTime()); s.setEndTime(t.getEndTime());
            s.setCreatedByUserId(securityHelper.getCurrentUserId());
            scheduleRepository.save(s); count++;
        }
        audit("NURSE_SHIFT_RANGE_FILLED", p.getName() + " " + req.getFromDate() + ".." + req.getToDate() + " x" + count, hospitalId, p.getId());
        return count;
    }

    @Transactional
    public void remove(String publicId) {
        Long hospitalId = requireHospitalId();
        NurseShiftSchedule s = scheduleRepository.findByPublicId(publicId)
                .orElseThrow(() -> new IllegalArgumentException("Schedule not found"));
        if (!hospitalId.equals(s.getHospitalId())) throw new UnauthorizedException("Belongs to another hospital");
        nurseInchargeGuard.assertWardAccess(s.getWardId());
        scheduleRepository.delete(s);
        audit("NURSE_SHIFT_REMOVED", publicId, hospitalId, s.getId());
    }

    @Transactional
    public int applyTemplateChangeToFuture(Long templateId, LocalTime start, LocalTime end) {
        return scheduleRepository.applyTemplateChangeToFuture(templateId, start, end, LocalDate.now());
    }

    public List<NurseShiftScheduleView> getWardSchedule(Long wardId, LocalDate from, LocalDate to) {
        nurseInchargeGuard.assertWardAccess(wardId);
        return decorate(scheduleRepository.findByWardIdAndShiftDateBetweenOrderByShiftDateAsc(wardId, from, to));
    }

    public List<NurseShiftScheduleView> getMySchedule(LocalDate from, LocalDate to) {
        requireHospitalId();
        Long profileId = nurseProfileRepository.findByUserId(securityHelper.getCurrentUserId())
                .map(NurseProfile::getId).orElse(null);
        if (profileId == null) return List.of();
        return decorate(scheduleRepository.findByNurseProfileIdAndShiftDateBetweenOrderByShiftDateAsc(profileId, from, to));
    }

    /** True iff the nurse has a shift today whose window contains 'now' (wraps past midnight if end<=start). */
    public boolean isOnShiftNow(Long nurseProfileId) {
        return scheduleRepository.findByNurseProfileIdAndShiftDate(nurseProfileId, LocalDate.now())
                .map(s -> withinWindow(s.getStartTime(), s.getEndTime(), LocalTime.now()))
                .orElse(false);
    }

    static boolean withinWindow(LocalTime start, LocalTime end, LocalTime now) {
        if (end.isAfter(start)) return !now.isBefore(start) && now.isBefore(end);
        // crosses midnight
        return !now.isBefore(start) || now.isBefore(end);
    }

    private List<NurseShiftScheduleView> decorate(List<NurseShiftSchedule> list) {
        List<NurseShiftScheduleView> out = new ArrayList<>();
        for (NurseShiftSchedule s : list) {
            NurseShiftScheduleView v = NurseShiftScheduleView.of(s);
            nurseProfileRepository.findById(s.getNurseProfileId()).ifPresent(p -> v.setNurseName(p.getName()));
            out.add(v);
        }
        return out;
    }

    private NurseProfile requireNurse(Long id, Long hospitalId) {
        if (id == null) throw new IllegalArgumentException("nurseProfileId is required");
        NurseProfile p = nurseProfileRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Nurse not found"));
        if (!hospitalId.equals(p.getHospitalId())) throw new UnauthorizedException("Nurse belongs to another hospital");
        return p;
    }
    private ShiftTemplate requireTemplate(String publicId, Long hospitalId) {
        ShiftTemplate t = shiftTemplateRepository.findByPublicId(publicId).orElseThrow(() -> new IllegalArgumentException("Shift template not found"));
        if (!hospitalId.equals(t.getHospitalId())) throw new UnauthorizedException("Template belongs to another hospital");
        return t;
    }
    private Long requireHospitalId() { Long h = securityHelper.getCurrentHospitalId(); if (h == null) throw new UnauthorizedException("Hospital ID not found"); return h; }
    private void audit(String a, String d, Long h, Long id) { try { auditLogService.logAction(a, d, securityHelper.getCurrentUserEmail(), h, "NURSE_SHIFT", String.valueOf(id), null); } catch (Exception e) {} }
}
```
- [ ] **Step 4: Run tests** `mvn -o -q -Dtest=NurseShiftScheduleServiceTest test` → PASS; then `mvn -o test` → BUILD SUCCESS.

---

### Task 8: Schedule controller

**Files:** Create `controller/hospital/NurseScheduleController.java`

- [ ] **Step 1:**
```java
package com.hms.controller.hospital;

import com.hms.dto.AssignShiftRequest;
import com.hms.dto.RangeFillShiftRequest;
import com.hms.security.RequireModule;
import com.hms.service.hospital.NurseShiftScheduleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/hospital/nurse-schedule")
@RequireModule("NURSING")
public class NurseScheduleController {
    @Autowired private NurseShiftScheduleService service;

    @PostMapping("/assign")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')")
    public ResponseEntity<?> assign(@RequestBody AssignShiftRequest req) { return ResponseEntity.ok(service.assign(req)); }

    @PostMapping("/range-fill")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')")
    public ResponseEntity<?> rangeFill(@RequestBody RangeFillShiftRequest req) {
        return ResponseEntity.ok(Map.of("created", service.rangeFill(req)));
    }

    @DeleteMapping("/{publicId}")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')")
    public ResponseEntity<?> remove(@PathVariable String publicId) { service.remove(publicId); return ResponseEntity.ok(Map.of("message","Removed")); }

    @GetMapping("/ward")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')")
    public ResponseEntity<?> ward(@RequestParam Long wardId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(service.getWardSchedule(wardId, from, to));
    }

    @GetMapping("/mine")
    @PreAuthorize("hasAnyRole('NURSE','NURSE_INCHARGE','HOSPITAL_ADMIN')")
    public ResponseEntity<?> mine(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(service.getMySchedule(from, to));
    }
}
```
- [ ] **Step 2: Compile + full test** → BUILD SUCCESS.

---

### Task 9: Frontend — incharge scheduler + nurse My Shifts

**Files:** Create `frontend/src/services/nurseScheduleService.js`; add a "Schedule" tab to `NurseInchargeDashboard.jsx`; add "My Shifts" to `NurseDashboard.jsx`.

- [ ] **Step 1: Service**
```javascript
import apiClient from './apiService';
const nurseScheduleService = {
    assign: async (nurseProfileId, date, shiftTemplatePublicId) =>
        (await apiClient.post('/hospital/nurse-schedule/assign', { nurseProfileId, date, shiftTemplatePublicId })).data,
    rangeFill: async (payload) => (await apiClient.post('/hospital/nurse-schedule/range-fill', payload)).data,
    remove: async (publicId) => (await apiClient.delete(`/hospital/nurse-schedule/${publicId}`)).data,
    getWard: async (wardId, from, to) => (await apiClient.get(`/hospital/nurse-schedule/ward?wardId=${wardId}&from=${from}&to=${to}`)).data,
    getMine: async (from, to) => (await apiClient.get(`/hospital/nurse-schedule/mine?from=${from}&to=${to}`)).data,
};
export default nurseScheduleService;
```
- [ ] **Step 2: Incharge Schedule tab.** In `NurseInchargeDashboard.jsx`, add a "Schedule" tab: pick a ward (the incharge's wards) + a week (from/to); render a grid (rows = ward staff nurses via the Phase A `getWardStaffNurses`, columns = the 7 dates); each cell shows the assigned shift (if any) and clicking opens a small picker of active shift templates (`timeSlotService.listShiftTemplates(true)`) → `nurseScheduleService.assign(nurseProfileId, dateStr, templatePublicId)`; a "Remove" clears it. Add a **Range Fill** button → modal (nurse, from, to, days-of-week checkboxes, template) → `rangeFill`. Dates sent as `YYYY-MM-DD`.
- [ ] **Step 3: Nurse My Shifts.** In `NurseDashboard.jsx`, add a small "My Shifts" section/tab listing `nurseScheduleService.getMine(from, to)` for the next ~14 days (date, shift time).
- [ ] **Step 4: Build & commit B2** — `npx vite build --mode development` → `✓ built`;
```bash
git add -A && git commit -m "feat(nurse-mgmt): Phase B2 — nurse shift scheduling (assign/range-fill/snapshots)"
```

---

# Milestone B3 — Replace on_shift with schedule-derived status

### Task 10: `getShiftStatus` from schedule

**Files:** Modify `service/hospital/NurseWorkspaceService.java`

- [ ] **Step 1:** Inject `@Autowired private NurseShiftScheduleService nurseShiftScheduleService;`. Change `getShiftStatus()` to derive from the schedule (keep the method signature returning a boolean, but base it on the schedule):
```java
    /** Whether the current nurse is on shift NOW, derived from today's schedule. */
    public boolean getShiftStatus() {
        Long profileId = currentProfile().getId();
        return nurseShiftScheduleService.isOnShiftNow(profileId);
    }
```
Leave `startShift()`/`endShift()` as deprecated no-ops that just return the current status (so any lingering caller does not error), or remove them + their controller endpoints if nothing else uses them. Confirm `NurseWorkspaceController` mappings for `/shift/start` `/shift/end` `/shift/status` — keep `/shift/status`; make start/end return the current schedule-derived status.
- [ ] **Step 2: Compile + full test** → BUILD SUCCESS (add `@Mock NurseShiftScheduleService` to `NurseWorkspaceServiceTest` if the shift-status path is exercised there; stub `isOnShiftNow` as needed).

---

### Task 11: Frontend — NurseDashboard status badge (drop manual gate)

**Files:** Modify `frontend/src/pages/hospital/NurseDashboard.jsx`, `frontend/src/services/nurseService.js`

- [ ] **Step 1:** Remove the hard Start-Shift gate: the dashboard is always accessible. Replace it with a header **status badge** reading `nurseService.getShiftStatus()` → "On Shift" (green) if true, else "Off Shift" (grey), optionally showing today's shift time from `getMine(today, today)`.
- [ ] **Step 2:** Remove the "End Shift & Log out" option — logout is a plain logout. Remove/stop calling `startShift`/`endShift` in `nurseService.js` and the dashboard (leave the service methods if referenced elsewhere, but the dashboard no longer calls them).
- [ ] **Step 3: Build & commit B3** — `npx vite build --mode development` → `✓ built`;
```bash
git add -A && git commit -m "feat(nurse-mgmt): Phase B3 — schedule-derived on-shift status (drop manual gate)"
```

---

### Task 12: End-to-end verification
- [ ] `cd backend && mvn -o test` → BUILD SUCCESS.
- [ ] `cd frontend && npx vite build --mode development` → `✓ built`.
- [ ] Manual smoke (NURSING enabled): admin creates shift templates + appointment slots under Time Slots; incharge assigns a ward nurse to a Morning shift for a date + range-fills a week; editing the Morning template's end time updates that nurse's FUTURE shifts but not past ones; the nurse sees "My Shifts" and an On/Off Shift badge that flips based on the current time vs their shift. Clean up test data.

---

## Self-Review (completed during authoring)
- **Spec coverage:** B1 shift templates + appointment slots CRUD + admin UI → Tasks 1–5. B2 NurseShiftSchedule + assign/range-fill/bulk-future/reads + controller + incharge scheduler + My Shifts → Tasks 6–9. B3 replace on_shift → Tasks 10–11. Snapshot + future-only propagation → `applyTemplateChangeToFuture` (Task 7) called from `ShiftTemplateService.update` (Task 3). Midnight-crossing → `withinWindow` (Task 7). Verification → Task 12.
- **Placeholder scan:** none. The one ordering wrinkle (ShiftTemplateService references NurseShiftScheduleService before B2) is handled explicitly: create a minimal `NurseShiftScheduleService` stub in B1 (Task 3 Step 4) with a no-op `applyTemplateChangeToFuture`, replaced by the full service in Task 7. FIX the typo `@Autowired（required = false)` → `@Autowired(required = false)`.
- **Type consistency:** `ShiftTemplate.getStartTime/getEndTime`, `NurseShiftSchedule` snapshot fields, `applyTemplateChangeToFuture(Long,LocalTime,LocalTime[,LocalDate])`, `isOnShiftNow(Long)`, `withinWindow`, DTO field names, and controller routes are consistent across tasks.
- **Flagged for implementer:** confirm `NurseWorkspaceController` shift endpoints (Task 10); add mocks to `NurseWorkspaceServiceTest` if needed; how the admin dashboard adds a module-gated tab (Task 5 Step 2); `LocalTime` binds from `"HH:mm"` request strings (Jackson default) — verify and, if needed, accept strings and parse.
