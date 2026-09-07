# Nursing Management — Phase D Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Nurse Incharge records daily nurse attendance (Present/Absent/Half Day/Leave/Holiday/Late) with the scheduled shift window snapshotted, optional check-in/out and remarks; every mark and correction is audited; nurses view their own attendance.

**Architecture:** One `nurse_attendance` row per `(nurse, date)` (upsert). The daily sheet is built by unioning nurses scheduled that date (from `NurseShiftSchedule`) with nurses already marked, so an incharge can also add an unrostered nurse. Ward scoping reuses `NurseInchargeGuard`; corrections write an `AuditLogService` entry with previous → new status.

**Tech Stack:** Spring Boot 3.3.5 / Java 17 / Maven / MySQL (JdbcTemplate migrations), JUnit 5 + Mockito + AssertJ. React 18 / Vite, axios (`apiClient`).

---

## Conventions
- Tenant scope via `SecurityContextHelper.getCurrentHospitalId()`. Gate `@RequireModule("NURSING")`.
- Errors: `IllegalArgumentException`→400, `UnauthorizedException`→401, `AccessDeniedException`→403.
- Migrations: add `ensureXxxTable()` to `DatabaseMigrationRunner` (copy `ensureNurseShiftSchedulesTable` shape; logger `log`, field `jdbcTemplate`), call from `runMigrations()`, mirror in `setup/schema-full.sql`.
- Audit: `auditLogService.logAction(action, details, securityHelper.getCurrentUserEmail(), hospitalId, entityType, entityId, reason)` best-effort in try/catch.
- Build/test: `cd backend && mvn -o test`; `cd frontend && npx vite build --mode development` (never vite from backend).
- **Commit at milestone boundaries** (D1, D2).
- Existing facts: `NurseProfile` (`getId/getName/getHospitalId/getWardId/getIsActive/getIsIncharge`), `NurseProfileRepository.findById/findByUserId/findByWardIdAndIsInchargeFalseAndIsActiveTrue(Long)`. `NurseShiftSchedule` (`getNurseProfileId/getShiftDate/getShiftTemplateId/getStartTime/getEndTime`), `NurseShiftScheduleRepository.findByWardIdAndShiftDateBetweenOrderByShiftDateAsc(wardId, from, to)`. `NurseInchargeGuard.assertWardAccess(Long)`. `SecurityContextHelper.getCurrentUserId()/getCurrentUserEmail()/getCurrentHospitalId()`.

---

## File Structure
**Backend new:** `entity/AttendanceStatus.java`, `entity/NurseAttendance.java`, `repository/NurseAttendanceRepository.java`, `dto/MarkAttendanceRequest.java`, `dto/AttendanceSheetRow.java`, `dto/AttendanceSummary.java`, `service/hospital/NurseAttendanceService.java`, `controller/hospital/NurseAttendanceController.java`, `test/.../NurseAttendanceServiceTest.java`.
**Backend modified:** `DatabaseMigrationRunner`, `setup/schema-full.sql`.
**Frontend new:** `services/attendanceService.js`, `pages/hospital/nurse-incharge/AttendanceView.jsx`, `pages/hospital/nurse/MyAttendanceView.jsx`.
**Frontend modified:** `NurseInchargeDashboard.jsx` (Attendance tab), `NurseDashboard.jsx` (My Attendance tab).

---

# Milestone D1 — Backend

### Task 1: `AttendanceStatus` + `NurseAttendance` entity

**Files:** Create `entity/AttendanceStatus.java`, `entity/NurseAttendance.java`

- [ ] **Step 1: Status constants**
```java
package com.hms.entity;

/** Nurse attendance statuses (stored uppercase). */
public final class AttendanceStatus {
    public static final String PRESENT = "PRESENT";
    public static final String ABSENT = "ABSENT";
    public static final String HALF_DAY = "HALF_DAY";
    public static final String LEAVE = "LEAVE";
    public static final String HOLIDAY = "HOLIDAY";
    public static final String LATE = "LATE";
    private AttendanceStatus() {}

    public static boolean isValid(String s) {
        return PRESENT.equals(s) || ABSENT.equals(s) || HALF_DAY.equals(s)
                || LEAVE.equals(s) || HOLIDAY.equals(s) || LATE.equals(s);
    }
}
```
- [ ] **Step 2: Entity**
```java
package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor; import lombok.Data; import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp; import org.hibernate.annotations.UpdateTimestamp;
import java.time.LocalDate; import java.time.LocalDateTime; import java.time.LocalTime;

/**
 * One nurse's attendance for one date. Shift fields are SNAPSHOTS of the
 * nurse's schedule for that date, so later roster edits never rewrite history.
 */
@Entity
@Table(name = "nurse_attendance",
       uniqueConstraints = @UniqueConstraint(name = "UK_na_nurse_date", columnNames = {"nurse_profile_id","attendance_date"}))
@Data @NoArgsConstructor @AllArgsConstructor
public class NurseAttendance {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true) private String publicId;
    @Column(name = "hospital_id", nullable = false) private Long hospitalId;
    @Column(name = "nurse_profile_id", nullable = false) private Long nurseProfileId;
    @Column(name = "ward_id") private Long wardId;
    @Column(name = "attendance_date", nullable = false) private LocalDate attendanceDate;
    @Column(nullable = false, length = 20) private String status;
    @Column(name = "shift_template_id") private Long shiftTemplateId;
    @Column(name = "shift_start_time") private LocalTime shiftStartTime;
    @Column(name = "shift_end_time") private LocalTime shiftEndTime;
    @Column(name = "check_in_time") private LocalTime checkInTime;
    @Column(name = "check_out_time") private LocalTime checkOutTime;
    @Column(length = 255) private String remarks;
    @Column(name = "marked_by_user_id") private Long markedByUserId;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private LocalDateTime updatedAt;
    @PrePersist public void pre() { if (publicId == null) publicId = java.util.UUID.randomUUID().toString(); }
}
```
- [ ] **Step 3:** `cd backend && mvn -o -q compile` → BUILD SUCCESS.

---

### Task 2: Repository + migration

**Files:** Create `repository/NurseAttendanceRepository.java`; modify `DatabaseMigrationRunner`, `setup/schema-full.sql`

- [ ] **Step 1: Repository**
```java
package com.hms.repository;
import com.hms.entity.NurseAttendance;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate; import java.util.List; import java.util.Optional;
public interface NurseAttendanceRepository extends JpaRepository<NurseAttendance, Long> {
    Optional<NurseAttendance> findByPublicId(String publicId);
    Optional<NurseAttendance> findByNurseProfileIdAndAttendanceDate(Long nurseProfileId, LocalDate date);
    List<NurseAttendance> findByWardIdAndAttendanceDate(Long wardId, LocalDate date);
    List<NurseAttendance> findByNurseProfileIdAndAttendanceDateBetweenOrderByAttendanceDateDesc(Long nurseProfileId, LocalDate from, LocalDate to);
}
```
- [ ] **Step 2: Migration** — add to `DatabaseMigrationRunner`, call from `runMigrations()`:
```java
    private void ensureNurseAttendanceTable() {
        try {
            Integer c = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nurse_attendance'", Integer.class);
            if (c != null && c == 0) {
                jdbcTemplate.execute("CREATE TABLE nurse_attendance (" +
                    "id BIGINT NOT NULL AUTO_INCREMENT, public_id VARCHAR(255) NOT NULL, hospital_id BIGINT NOT NULL," +
                    "nurse_profile_id BIGINT NOT NULL, ward_id BIGINT, attendance_date DATE NOT NULL, status VARCHAR(20) NOT NULL," +
                    "shift_template_id BIGINT, shift_start_time TIME, shift_end_time TIME," +
                    "check_in_time TIME, check_out_time TIME, remarks VARCHAR(255), marked_by_user_id BIGINT," +
                    "created_at DATETIME(6) NOT NULL, updated_at DATETIME(6)," +
                    "PRIMARY KEY (id), UNIQUE KEY UK_na_public (public_id)," +
                    "UNIQUE KEY UK_na_nurse_date (nurse_profile_id, attendance_date)," +
                    "KEY idx_na_hospital_date (hospital_id, attendance_date), KEY idx_na_ward_date (ward_id, attendance_date)," +
                    "FOREIGN KEY (hospital_id) REFERENCES hospitals(id) ON DELETE CASCADE)");
                log.info("DB migration applied: nurse_attendance table created");
            }
        } catch (Exception e) { log.warn("DB migration skipped (nurse_attendance): {}", e.getMessage()); }
    }
```
Mirror in `setup/schema-full.sql`.
- [ ] **Step 3:** `mvn -o -q compile` → BUILD SUCCESS.

---

### Task 3: DTOs

**Files:** Create `dto/MarkAttendanceRequest.java`, `dto/AttendanceSheetRow.java`, `dto/AttendanceSummary.java`

- [ ] **Step 1**
```java
package com.hms.dto; import lombok.Data; import java.time.LocalDate; import java.time.LocalTime;
@Data public class MarkAttendanceRequest {
    private Long nurseProfileId;   // required
    private LocalDate date;        // required
    private String status;         // required: PRESENT/ABSENT/HALF_DAY/LEAVE/HOLIDAY/LATE
    private LocalTime checkInTime; // optional
    private LocalTime checkOutTime;// optional
    private String remarks;        // optional
}
```
```java
package com.hms.dto; import lombok.Data; import java.time.LocalTime;
/** One row of the incharge's daily attendance sheet (marked or not). */
@Data public class AttendanceSheetRow {
    private Long nurseProfileId; private String nurseName;
    private Long shiftTemplateId; private LocalTime shiftStartTime; private LocalTime shiftEndTime;
    private String attendancePublicId; private String status;
    private LocalTime checkInTime; private LocalTime checkOutTime; private String remarks;
}
```
```java
package com.hms.dto; import lombok.Data;
@Data public class AttendanceSummary {
    private int present; private int absent; private int halfDay;
    private int leave; private int holiday; private int late; private int unmarked;
}
```
- [ ] **Step 2:** `mvn -o -q compile` → BUILD SUCCESS.

---

### Task 4: `NurseAttendanceService` (TDD)

**Files:** Create `service/hospital/NurseAttendanceService.java`; Test `NurseAttendanceServiceTest`

- [ ] **Step 1: Failing test** `backend/src/test/java/com/hms/service/hospital/NurseAttendanceServiceTest.java`
```java
package com.hms.service.hospital;

import com.hms.dto.MarkAttendanceRequest;
import com.hms.entity.AttendanceStatus;
import com.hms.entity.NurseAttendance;
import com.hms.entity.NurseProfile;
import com.hms.entity.NurseShiftSchedule;
import com.hms.repository.NurseAttendanceRepository;
import com.hms.repository.NurseProfileRepository;
import com.hms.repository.NurseShiftScheduleRepository;
import com.hms.security.NurseInchargeGuard;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks; import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate; import java.time.LocalTime; import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NurseAttendanceServiceTest {
    @Mock NurseAttendanceRepository attendanceRepository;
    @Mock NurseProfileRepository nurseProfileRepository;
    @Mock NurseShiftScheduleRepository scheduleRepository;
    @Mock NurseInchargeGuard nurseInchargeGuard;
    @Mock SecurityContextHelper securityHelper;
    @Mock AuditLogService auditLogService;
    @InjectMocks NurseAttendanceService service;

    private static final LocalDate D = LocalDate.of(2026, 7, 10);

    private NurseProfile nurse() {
        NurseProfile p = new NurseProfile();
        p.setId(11L); p.setHospitalId(7L); p.setWardId(3L); p.setName("Priya"); p.setIsActive(true);
        return p;
    }
    private MarkAttendanceRequest req(String status) {
        MarkAttendanceRequest r = new MarkAttendanceRequest();
        r.setNurseProfileId(11L); r.setDate(D); r.setStatus(status);
        return r;
    }

    @Test void mark_createsRow_andSnapshotsScheduledShift() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(securityHelper.getCurrentUserId()).thenReturn(20L);
        when(nurseProfileRepository.findById(11L)).thenReturn(Optional.of(nurse()));
        NurseShiftSchedule s = new NurseShiftSchedule();
        s.setShiftTemplateId(5L); s.setStartTime(LocalTime.of(8,0)); s.setEndTime(LocalTime.of(16,0));
        when(scheduleRepository.findByNurseProfileIdAndShiftDate(11L, D)).thenReturn(Optional.of(s));
        when(attendanceRepository.findByNurseProfileIdAndAttendanceDate(11L, D)).thenReturn(Optional.empty());
        when(attendanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        NurseAttendance a = service.mark(req(AttendanceStatus.PRESENT));

        verify(nurseInchargeGuard).assertWardAccess(3L);
        assertThat(a.getStatus()).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(a.getShiftTemplateId()).isEqualTo(5L);
        assertThat(a.getShiftStartTime()).isEqualTo(LocalTime.of(8,0));
        assertThat(a.getMarkedByUserId()).isEqualTo(20L);
    }

    @Test void mark_upsertsExistingRow() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(securityHelper.getCurrentUserId()).thenReturn(20L);
        when(nurseProfileRepository.findById(11L)).thenReturn(Optional.of(nurse()));
        when(scheduleRepository.findByNurseProfileIdAndShiftDate(11L, D)).thenReturn(Optional.empty());
        NurseAttendance existing = new NurseAttendance();
        existing.setId(99L); existing.setHospitalId(7L); existing.setNurseProfileId(11L);
        existing.setAttendanceDate(D); existing.setStatus(AttendanceStatus.ABSENT);
        when(attendanceRepository.findByNurseProfileIdAndAttendanceDate(11L, D)).thenReturn(Optional.of(existing));
        when(attendanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        NurseAttendance a = service.mark(req(AttendanceStatus.PRESENT));

        assertThat(a.getId()).isEqualTo(99L);              // same row, upserted
        assertThat(a.getStatus()).isEqualTo(AttendanceStatus.PRESENT);
    }

    @Test void mark_rejectsInvalidStatus() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(nurseProfileRepository.findById(11L)).thenReturn(Optional.of(nurse()));
        assertThatThrownBy(() -> service.mark(req("NAPPING")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```
Run — expect FAIL.

- [ ] **Step 2: Implement**
```java
package com.hms.service.hospital;

import com.hms.dto.AttendanceSheetRow;
import com.hms.dto.AttendanceSummary;
import com.hms.dto.MarkAttendanceRequest;
import com.hms.entity.AttendanceStatus;
import com.hms.entity.NurseAttendance;
import com.hms.entity.NurseProfile;
import com.hms.exception.UnauthorizedException;
import com.hms.repository.NurseAttendanceRepository;
import com.hms.repository.NurseProfileRepository;
import com.hms.repository.NurseShiftScheduleRepository;
import com.hms.security.NurseInchargeGuard;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

/**
 * NurseAttendanceService - daily nurse attendance (Nursing Mgmt Phase D).
 * One row per (nurse, date), upserted. Shift fields are snapshots of the
 * nurse's schedule for that date. Ward-scoped via NurseInchargeGuard; every
 * mark/correction is audited.
 */
@Service
public class NurseAttendanceService {

    @Autowired private NurseAttendanceRepository attendanceRepository;
    @Autowired private NurseProfileRepository nurseProfileRepository;
    @Autowired private NurseShiftScheduleRepository scheduleRepository;
    @Autowired private NurseInchargeGuard nurseInchargeGuard;
    @Autowired private SecurityContextHelper securityHelper;
    @Autowired private AuditLogService auditLogService;

    @Transactional
    public NurseAttendance mark(MarkAttendanceRequest req) {
        Long hospitalId = requireHospitalId();
        if (req.getNurseProfileId() == null || req.getDate() == null) {
            throw new IllegalArgumentException("nurseProfileId and date are required");
        }
        NurseProfile p = nurseProfileRepository.findById(req.getNurseProfileId())
                .orElseThrow(() -> new IllegalArgumentException("Nurse not found"));
        if (!hospitalId.equals(p.getHospitalId())) throw new UnauthorizedException("Nurse belongs to another hospital");
        if (!AttendanceStatus.isValid(req.getStatus())) throw new IllegalArgumentException("Invalid attendance status");
        nurseInchargeGuard.assertWardAccess(p.getWardId());

        NurseAttendance a = attendanceRepository
                .findByNurseProfileIdAndAttendanceDate(p.getId(), req.getDate())
                .orElseGet(NurseAttendance::new);
        String previous = a.getStatus();

        a.setHospitalId(hospitalId);
        a.setNurseProfileId(p.getId());
        a.setWardId(p.getWardId());
        a.setAttendanceDate(req.getDate());
        a.setStatus(req.getStatus());
        a.setCheckInTime(req.getCheckInTime());
        a.setCheckOutTime(req.getCheckOutTime());
        a.setRemarks(req.getRemarks());
        a.setMarkedByUserId(securityHelper.getCurrentUserId());
        // Snapshot the scheduled shift window for that date, if rostered.
        scheduleRepository.findByNurseProfileIdAndShiftDate(p.getId(), req.getDate()).ifPresent(s -> {
            a.setShiftTemplateId(s.getShiftTemplateId());
            a.setShiftStartTime(s.getStartTime());
            a.setShiftEndTime(s.getEndTime());
        });
        NurseAttendance saved = attendanceRepository.save(a);

        String action = (previous == null) ? "ATTENDANCE_MARKED" : "ATTENDANCE_MODIFIED";
        String details = p.getName() + " " + req.getDate() + " : " + (previous == null ? req.getStatus() : previous + " -> " + req.getStatus());
        audit(action, details, hospitalId, saved.getId(), req.getRemarks());
        return saved;
    }

    public List<AttendanceSheetRow> getSheet(Long wardId, LocalDate date) {
        nurseInchargeGuard.assertWardAccess(wardId);
        Map<Long, AttendanceSheetRow> rows = new LinkedHashMap<>();

        // 1. Nurses rostered for that date in this ward.
        scheduleRepository.findByWardIdAndShiftDateBetweenOrderByShiftDateAsc(wardId, date, date).forEach(s -> {
            AttendanceSheetRow r = new AttendanceSheetRow();
            r.setNurseProfileId(s.getNurseProfileId());
            r.setShiftTemplateId(s.getShiftTemplateId());
            r.setShiftStartTime(s.getStartTime());
            r.setShiftEndTime(s.getEndTime());
            rows.put(s.getNurseProfileId(), r);
        });

        // 2. Nurses already marked that date (covers unrostered additions) + fill in marks.
        for (NurseAttendance a : attendanceRepository.findByWardIdAndAttendanceDate(wardId, date)) {
            AttendanceSheetRow r = rows.computeIfAbsent(a.getNurseProfileId(), k -> {
                AttendanceSheetRow n = new AttendanceSheetRow();
                n.setNurseProfileId(k);
                return n;
            });
            r.setAttendancePublicId(a.getPublicId());
            r.setStatus(a.getStatus());
            r.setCheckInTime(a.getCheckInTime());
            r.setCheckOutTime(a.getCheckOutTime());
            r.setRemarks(a.getRemarks());
            if (r.getShiftStartTime() == null) {
                r.setShiftTemplateId(a.getShiftTemplateId());
                r.setShiftStartTime(a.getShiftStartTime());
                r.setShiftEndTime(a.getShiftEndTime());
            }
        }

        rows.values().forEach(r -> nurseProfileRepository.findById(r.getNurseProfileId())
                .ifPresent(p -> r.setNurseName(p.getName())));
        return new ArrayList<>(rows.values());
    }

    public AttendanceSummary summary(Long wardId, LocalDate date) {
        List<AttendanceSheetRow> sheet = getSheet(wardId, date); // ward-scoped inside
        AttendanceSummary s = new AttendanceSummary();
        for (AttendanceSheetRow r : sheet) {
            if (r.getStatus() == null) { s.setUnmarked(s.getUnmarked() + 1); continue; }
            switch (r.getStatus()) {
                case AttendanceStatus.PRESENT -> s.setPresent(s.getPresent() + 1);
                case AttendanceStatus.ABSENT -> s.setAbsent(s.getAbsent() + 1);
                case AttendanceStatus.HALF_DAY -> s.setHalfDay(s.getHalfDay() + 1);
                case AttendanceStatus.LEAVE -> s.setLeave(s.getLeave() + 1);
                case AttendanceStatus.HOLIDAY -> s.setHoliday(s.getHoliday() + 1);
                case AttendanceStatus.LATE -> s.setLate(s.getLate() + 1);
                default -> s.setUnmarked(s.getUnmarked() + 1);
            }
        }
        return s;
    }

    public List<NurseAttendance> getMyAttendance(LocalDate from, LocalDate to) {
        requireHospitalId();
        Long profileId = nurseProfileRepository.findByUserId(securityHelper.getCurrentUserId())
                .map(NurseProfile::getId).orElse(null);
        if (profileId == null) return List.of();
        return attendanceRepository.findByNurseProfileIdAndAttendanceDateBetweenOrderByAttendanceDateDesc(profileId, from, to);
    }

    private Long requireHospitalId() {
        Long h = securityHelper.getCurrentHospitalId();
        if (h == null) throw new UnauthorizedException("Hospital ID not found");
        return h;
    }
    private void audit(String action, String details, Long hospitalId, Long id, String reason) {
        try {
            auditLogService.logAction(action, details, securityHelper.getCurrentUserEmail(), hospitalId,
                    "NURSE_ATTENDANCE", String.valueOf(id), reason);
        } catch (Exception e) { /* best-effort */ }
    }
}
```
NOTE: `mark_rejectsInvalidStatus` expects validation AFTER the nurse is loaded (the test stubs `nurseProfileRepository.findById`) and BEFORE `assertWardAccess`. Keep that order: load nurse → tenant check → validate status → ward guard. Do not reorder.
NOTE: `switch` with `case AttendanceStatus.PRESENT ->` requires constant expressions — Java allows `case` on `String` constants only in a `switch` on String with `case "PRESENT"`. If the compiler rejects `case AttendanceStatus.PRESENT`, use string literals (`case "PRESENT" ->`) or if/else. Fix as needed and report.
- [ ] **Step 3:** `mvn -o -q -Dtest=NurseAttendanceServiceTest test` → 3/3 PASS. Then `mvn -o test` → BUILD SUCCESS.

---

### Task 5: Controller

**Files:** Create `controller/hospital/NurseAttendanceController.java`

- [ ] **Step 1**
```java
package com.hms.controller.hospital;

import com.hms.dto.MarkAttendanceRequest;
import com.hms.security.RequireModule;
import com.hms.service.hospital.NurseAttendanceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/hospital/nurse-attendance")
@RequireModule("NURSING")
public class NurseAttendanceController {

    @Autowired private NurseAttendanceService service;

    @GetMapping("/sheet")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')")
    public ResponseEntity<?> sheet(@RequestParam Long wardId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(service.getSheet(wardId, date));
    }

    @PostMapping("/mark")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')")
    public ResponseEntity<?> mark(@RequestBody MarkAttendanceRequest req) {
        return ResponseEntity.ok(service.mark(req));
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')")
    public ResponseEntity<?> summary(@RequestParam Long wardId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(service.summary(wardId, date));
    }

    @GetMapping("/mine")
    @PreAuthorize("hasAnyRole('NURSE','NURSE_INCHARGE','HOSPITAL_ADMIN')")
    public ResponseEntity<?> mine(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(service.getMyAttendance(from, to));
    }
}
```
- [ ] **Step 2:** `mvn -o test` → BUILD SUCCESS.
- [ ] **Step 3: Commit D1** — `git add -A && git commit -m "feat(nurse-mgmt): Phase D1 — nurse attendance backend"`

---

# Milestone D2 — Frontend

### Task 6: Service + incharge Attendance tab + nurse My Attendance

**Files:** Create `services/attendanceService.js`, `pages/hospital/nurse-incharge/AttendanceView.jsx`, `pages/hospital/nurse/MyAttendanceView.jsx`; modify `NurseInchargeDashboard.jsx`, `NurseDashboard.jsx`

- [ ] **Step 1: Service**
```javascript
import apiClient from './apiService';

/** attendanceService - nurse attendance (Nursing Mgmt Phase D). */
const attendanceService = {
    getSheet: async (wardId, date) =>
        (await apiClient.get(`/hospital/nurse-attendance/sheet?wardId=${wardId}&date=${date}`)).data,
    mark: async (payload) => (await apiClient.post('/hospital/nurse-attendance/mark', payload)).data,
    getSummary: async (wardId, date) =>
        (await apiClient.get(`/hospital/nurse-attendance/summary?wardId=${wardId}&date=${date}`)).data,
    getMine: async (from, to) => (await apiClient.get(`/hospital/nurse-attendance/mine?from=${from}&to=${to}`)).data,
};

export default attendanceService;
```
- [ ] **Step 2: `AttendanceView.jsx`** (incharge)
   - Ward selector from `nurseService.getMyWards()`; date input defaulting to today (`YYYY-MM-DD`).
   - Load `attendanceService.getSheet(wardId, date)` and `getSummary(wardId, date)`.
   - Summary strip: Present / Absent / Half Day / Leave / Holiday / Late / Unmarked counts.
   - Table: Nurse | Scheduled shift (`HH:mm–HH:mm` or "—") | Status `<select>` (PRESENT, ABSENT, HALF_DAY, LEAVE, HOLIDAY, LATE) | Check-in (`<input type="time">`) | Check-out | Remarks (text) | **Save** button per row → `attendanceService.mark({ nurseProfileId, date, status, checkInTime, checkOutTime, remarks })`, toast, reload sheet + summary.
   - **Add nurse**: a `<select>` of `nurseService.getWardStaffNurses(wardId)` filtered to nurses not already on the sheet; picking one appends an unmarked row locally so it can be marked (e.g. Leave).
   - Times sent as `"HH:mm"` (backend `LocalTime` parses it); send `null` for empty.
   - Errors: `e?.response?.data?.error`; use `useToast()`.
- [ ] **Step 3: Add an "Attendance" tab** to `NurseInchargeDashboard.jsx` rendering `<AttendanceView />` (match its existing tab pattern: My Nurses / My Ward Patients / Schedule / Beds).
- [ ] **Step 4: `MyAttendanceView.jsx`** (nurse) — lists the last 30 days from `attendanceService.getMine(from, to)`: Date | Status badge | Shift window | Check-in/out | Remarks. Empty state "No attendance recorded."
- [ ] **Step 5: Add a "My Attendance" tab** to `NurseDashboard.jsx` (match its `sidebarTabs` / `renderContent()` / `titleFor()` pattern).
- [ ] **Step 6:** `cd frontend && npx vite build --mode development` → `✓ built`.
- [ ] **Step 7: Commit D2** — `git add -A && git commit -m "feat(nurse-mgmt): Phase D2 — attendance UI (incharge sheet + nurse view)"`

---

### Task 7: End-to-end verification
- [ ] `cd backend && mvn -o test` → BUILD SUCCESS.
- [ ] `cd frontend && npx vite build --mode development` → `✓ built`.
- [ ] Manual smoke: roster a nurse for today (Phase B Schedule), open the incharge **Attendance** tab → the nurse appears with their shift window pre-filled; mark Present with a check-in time → summary updates. Change it to Late → the audit log shows `PRESENT -> LATE`. Add an unrostered ward nurse and mark them Leave. The nurse sees both in **My Attendance**. Clean up test data.

---

## Self-Review (completed during authoring)
- **Spec coverage:** statuses → Task 1. Entity + unique `(nurse,date)` + snapshots → Tasks 1–2. Sheet = rostered ∪ marked, plus add-any-ward-nurse → Task 4 `getSheet` + Task 6 Step 2. Check-in/out + remarks → Tasks 1, 3, 4. Upsert + audit prev→new → Task 4 `mark`. Ward scope → `assertWardAccess` in `mark`/`getSheet`. Summary for Phase E tiles → Task 4 `summary` + Task 5. Nurse self-view → `getMyAttendance` + Task 6 Step 4. Verification → Task 7.
- **Placeholder scan:** none. Two explicit compiler caveats are called out with fixes (String `switch` constants; validation ordering required by the tests).
- **Type consistency:** `AttendanceStatus.*`, `NurseAttendance` field names, `MarkAttendanceRequest`/`AttendanceSheetRow`/`AttendanceSummary` fields, repository finder names, and the four controller routes are consistent across tasks.
- **Flagged for the implementer:** `scheduleRepository.findByNurseProfileIdAndShiftDate` must already exist (it does, from Phase B); `AttendanceSummary.setLeave/getLeave` — `leave` is not a Java keyword, so Lombok generates them fine.
