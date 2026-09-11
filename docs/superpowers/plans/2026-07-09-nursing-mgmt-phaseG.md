# Nursing Management — Phase G: Hospital Calendar Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A month calendar for Hospital Admin + Nurse Incharge that aggregates nurse shifts, nurse attendance, OT surgeries, and a new holidays/events store, with a per-day drill-down.

**Architecture:** One new `calendar_events` table (admin/incharge CRUD); everything else is read from existing tables. A `HospitalCalendarService` resolves the caller's effective ward set (`admin ⇒ all wards`, `incharge ⇒ myWardIds()`) and returns per-day summaries (grid) + a day detail. Surgeries are hospital-wide for admin, ward-scoped (by the patient's admission ward) for an incharge. A shared React `HospitalCalendar.jsx` renders a month grid + day panel + event form, wired into both dashboards.

**Tech Stack:** Spring Boot 3 / JPA / Maven (backend), React 18 / Vite / axios (frontend). Follows the Phase F pattern exactly (`NurseCoverageService`/`CoverageView`).

**Reference:** Design spec `docs/superpowers/specs/2026-07-09-nursing-mgmt-phaseG-design.md`.

**Conventions (already verified against the codebase):**
- Migrations: idempotent `ensureXxxTable()` in `DatabaseMigrationRunner`, wired into `runMigrations()`, mirrored in `setup/schema-full.sql`.
- Errors flow through `GlobalExceptionHandler` (throw `IllegalArgumentException`/`UnauthorizedException`; no try/catch in controllers).
- Audit: `auditLogService.logAction(action, details, email, hospitalId, entityType, entityId, reason)` wrapped in try/catch.
- `nurseInchargeGuard.myWardIds()` returns **all** hospital wards for `HOSPITAL_ADMIN`, the incharge's wards otherwise.
- `securityHelper.getCurrentUserRole()` returns the role string; `getCurrentHospitalId()`, `getCurrentUserId()`, `getCurrentUserEmail()` exist.
- Build frontend from the frontend dir only: `cd frontend && npx vite build --mode development`.

---

## Milestone G1 — Backend

### Task 1: `CalendarEvent` entity, repository, request DTO, migration, schema mirror

**Files:**
- Create: `backend/src/main/java/com/hms/entity/CalendarEvent.java`
- Create: `backend/src/main/java/com/hms/repository/CalendarEventRepository.java`
- Create: `backend/src/main/java/com/hms/dto/CalendarEventRequest.java`
- Modify: `backend/src/main/java/com/hms/config/DatabaseMigrationRunner.java`
- Modify: `setup/schema-full.sql`

- [ ] **Step 1: Create the entity**

`backend/src/main/java/com/hms/entity/CalendarEvent.java`:

```java
package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** A hospital calendar entry: holiday, event, or notice (Nursing Mgmt Phase G). */
@Entity
@Table(name = "calendar_events")
@Data @NoArgsConstructor @AllArgsConstructor
public class CalendarEvent {
    public static final String HOLIDAY = "HOLIDAY";
    public static final String EVENT = "EVENT";
    public static final String NOTICE = "NOTICE";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "public_id", nullable = false, unique = true)
    private String publicId;
    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;
    @Column(nullable = false, length = 160)
    private String title;
    @Column(name = "event_type", nullable = false, length = 20)
    private String eventType;
    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;
    @Column(name = "to_date", nullable = false)
    private LocalDate toDate;
    @Column(length = 500)
    private String description;
    @Column(name = "created_by_user_id")
    private Long createdByUserId;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist public void pre() { if (publicId == null) publicId = java.util.UUID.randomUUID().toString(); }
}
```

- [ ] **Step 2: Create the repository**

`backend/src/main/java/com/hms/repository/CalendarEventRepository.java`:

```java
package com.hms.repository;

import com.hms.entity.CalendarEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CalendarEventRepository extends JpaRepository<CalendarEvent, Long> {
    Optional<CalendarEvent> findByPublicId(String publicId);

    // Events overlapping [from,to]: fromDate <= to AND toDate >= from.
    List<CalendarEvent> findByHospitalIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(
            Long hospitalId, LocalDate to, LocalDate from);

    // Active + upcoming events for the management list.
    List<CalendarEvent> findByHospitalIdAndToDateGreaterThanEqualOrderByFromDateAsc(Long hospitalId, LocalDate today);
}
```

- [ ] **Step 3: Create the request DTO**

`backend/src/main/java/com/hms/dto/CalendarEventRequest.java`:

```java
package com.hms.dto;

import java.time.LocalDate;

public class CalendarEventRequest {
    private String title;
    private String eventType;
    private LocalDate fromDate;
    private LocalDate toDate;
    private String description;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public LocalDate getFromDate() { return fromDate; }
    public void setFromDate(LocalDate fromDate) { this.fromDate = fromDate; }
    public LocalDate getToDate() { return toDate; }
    public void setToDate(LocalDate toDate) { this.toDate = toDate; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
```

- [ ] **Step 4: Add the migration**

In `backend/src/main/java/com/hms/config/DatabaseMigrationRunner.java`, add a call inside `runMigrations()` right after the `ensureNurseSubstitutionsTable();` line:

```java
        ensureCalendarEventsTable();
```

Then add this method next to the other `ensureXxxTable()` methods:

```java
    private void ensureCalendarEventsTable() {
        try {
            Integer exists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'calendar_events'",
                    Integer.class);
            if (exists == null || exists == 0) {
                jdbcTemplate.execute(
                        "CREATE TABLE calendar_events (" +
                        "  id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                        "  public_id VARCHAR(64) NOT NULL UNIQUE," +
                        "  hospital_id BIGINT NOT NULL," +
                        "  title VARCHAR(160) NOT NULL," +
                        "  event_type VARCHAR(20) NOT NULL," +
                        "  from_date DATE NOT NULL," +
                        "  to_date DATE NOT NULL," +
                        "  description VARCHAR(500)," +
                        "  created_by_user_id BIGINT," +
                        "  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                        "  INDEX idx_calevent_hosp_dates (hospital_id, from_date, to_date)," +
                        "  CONSTRAINT fk_calevent_hospital FOREIGN KEY (hospital_id) REFERENCES hospitals(id) ON DELETE CASCADE" +
                        ")");
                log.info("Created calendar_events table");
            }
        } catch (Exception e) {
            log.warn("ensureCalendarEventsTable failed: {}", e.getMessage());
        }
    }
```

- [ ] **Step 5: Mirror the DDL in `setup/schema-full.sql`**

Add after the `nurse_substitutions` table block:

```sql
-- Hospital calendar events (Nursing Mgmt Phase G)
CREATE TABLE IF NOT EXISTS calendar_events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    public_id VARCHAR(64) NOT NULL UNIQUE,
    hospital_id BIGINT NOT NULL,
    title VARCHAR(160) NOT NULL,
    event_type VARCHAR(20) NOT NULL,
    from_date DATE NOT NULL,
    to_date DATE NOT NULL,
    description VARCHAR(500),
    created_by_user_id BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_calevent_hosp_dates (hospital_id, from_date, to_date),
    CONSTRAINT fk_calevent_hospital FOREIGN KEY (hospital_id) REFERENCES hospitals(id) ON DELETE CASCADE
);
```

- [ ] **Step 6: Compile**

Run: `cd backend && mvn -o -q -DskipTests compile`
Expected: BUILD SUCCESS (no output on success).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/hms/entity/CalendarEvent.java \
        backend/src/main/java/com/hms/repository/CalendarEventRepository.java \
        backend/src/main/java/com/hms/dto/CalendarEventRequest.java \
        backend/src/main/java/com/hms/config/DatabaseMigrationRunner.java \
        setup/schema-full.sql
git commit -m "feat(nurse-mgmt): Phase G — calendar_events entity/repo/migration"
```

---

### Task 2: Repository helpers for ward-set reads

**Files:**
- Modify: `backend/src/main/java/com/hms/repository/NurseShiftScheduleRepository.java`
- Modify: `backend/src/main/java/com/hms/repository/NurseAttendanceRepository.java`

- [ ] **Step 1: Add the shift-schedule finder**

In `NurseShiftScheduleRepository` (interface body), add:

```java
    List<NurseShiftSchedule> findByWardIdInAndShiftDateBetween(java.util.Collection<Long> wardIds, LocalDate from, LocalDate to);
```

- [ ] **Step 2: Add the attendance finder**

In `NurseAttendanceRepository` (interface body), add:

```java
    List<NurseAttendance> findByWardIdInAndAttendanceDateBetween(java.util.Collection<Long> wardIds, LocalDate from, LocalDate to);
```

- [ ] **Step 3: Compile**

Run: `cd backend && mvn -o -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/hms/repository/NurseShiftScheduleRepository.java \
        backend/src/main/java/com/hms/repository/NurseAttendanceRepository.java
git commit -m "feat(nurse-mgmt): Phase G — ward-set finders for shifts + attendance"
```

---

### Task 3: `HospitalCalendarService` (TDD)

**Files:**
- Create: `backend/src/main/java/com/hms/service/hospital/HospitalCalendarService.java`
- Test: `backend/src/test/java/com/hms/service/hospital/HospitalCalendarServiceTest.java`

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/hms/service/hospital/HospitalCalendarServiceTest.java`:

```java
package com.hms.service.hospital;

import com.hms.entity.CalendarEvent;
import com.hms.entity.NurseShiftSchedule;
import com.hms.entity.Surgery;
import com.hms.repository.*;
import com.hms.security.NurseInchargeGuard;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HospitalCalendarServiceTest {
    @Mock SecurityContextHelper securityHelper;
    @Mock NurseInchargeGuard nurseInchargeGuard;
    @Mock WardRepository wardRepository;
    @Mock NurseShiftScheduleRepository shiftScheduleRepository;
    @Mock NurseAttendanceRepository attendanceRepository;
    @Mock SurgeryRepository surgeryRepository;
    @Mock IpdAdmissionRepository ipdAdmissionRepository;
    @Mock PatientRepository patientRepository;
    @Mock NurseProfileRepository nurseProfileRepository;
    @Mock CalendarEventRepository calendarEventRepository;
    @Mock AuditLogService auditLogService;
    @InjectMocks HospitalCalendarService service;

    private NurseShiftSchedule shift(Long wardId, LocalDate date) {
        NurseShiftSchedule s = new NurseShiftSchedule();
        s.setWardId(wardId); s.setShiftDate(date);
        s.setStartTime(LocalTime.of(8, 0)); s.setEndTime(LocalTime.of(16, 0));
        s.setNurseProfileId(1L);
        return s;
    }

    @Test void monthSummary_bucketsShiftsAndEventsOntoDays() {
        LocalDate anchor = LocalDate.of(2026, 7, 1);
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(nurseInchargeGuard.myWardIds()).thenReturn(List.of(3L));
        when(shiftScheduleRepository.findByWardIdInAndShiftDateBetween(any(), any(), any()))
                .thenReturn(List.of(shift(3L, LocalDate.of(2026, 7, 2)), shift(3L, LocalDate.of(2026, 7, 2))));
        when(surgeryRepository.findByHospitalIdAndStatusInOrderByScheduledAtAsc(any(), any()))
                .thenReturn(List.of());
        CalendarEvent holiday = new CalendarEvent();
        holiday.setPublicId("evt-1"); holiday.setTitle("Founder's Day"); holiday.setEventType(CalendarEvent.HOLIDAY);
        holiday.setFromDate(LocalDate.of(2026, 7, 2)); holiday.setToDate(LocalDate.of(2026, 7, 2));
        when(calendarEventRepository.findByHospitalIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(any(), any(), any()))
                .thenReturn(List.of(holiday));

        List<Map<String, Object>> days = service.monthSummary(2026, 7);

        assertThat(days).hasSize(31);
        Map<String, Object> jul2 = days.stream()
                .filter(d -> "2026-07-02".equals(String.valueOf(d.get("date")))).findFirst().orElseThrow();
        assertThat(jul2.get("shiftCount")).isEqualTo(2);
        assertThat(jul2.get("hasHoliday")).isEqualTo(true);
        assertThat((List<?>) jul2.get("events")).hasSize(1);
    }

    @Test void monthSummary_incharge_excludesSurgeryOutsideTheirWards() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(securityHelper.getCurrentUserRole()).thenReturn("NURSE_INCHARGE");
        when(nurseInchargeGuard.myWardIds()).thenReturn(List.of(3L));
        when(shiftScheduleRepository.findByWardIdInAndShiftDateBetween(any(), any(), any())).thenReturn(List.of());
        when(calendarEventRepository.findByHospitalIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(any(), any(), any()))
                .thenReturn(List.of());

        Surgery inWard = new Surgery();
        inWard.setIpdAdmissionId(100L); inWard.setStatus(Surgery.SCHEDULED);
        inWard.setScheduledAt(LocalDate.of(2026, 7, 5).atTime(10, 0));
        Surgery outWard = new Surgery();
        outWard.setIpdAdmissionId(200L); outWard.setStatus(Surgery.SCHEDULED);
        outWard.setScheduledAt(LocalDate.of(2026, 7, 5).atTime(12, 0));
        when(surgeryRepository.findByHospitalIdAndStatusInOrderByScheduledAtAsc(any(), any()))
                .thenReturn(List.of(inWard, outWard));

        com.hms.entity.IpdAdmission a1 = new com.hms.entity.IpdAdmission(); a1.setWardId(3L);
        com.hms.entity.IpdAdmission a2 = new com.hms.entity.IpdAdmission(); a2.setWardId(9L);
        when(ipdAdmissionRepository.findById(100L)).thenReturn(java.util.Optional.of(a1));
        when(ipdAdmissionRepository.findById(200L)).thenReturn(java.util.Optional.of(a2));

        List<Map<String, Object>> days = service.monthSummary(2026, 7);
        Map<String, Object> jul5 = days.stream()
                .filter(d -> "2026-07-05".equals(String.valueOf(d.get("date")))).findFirst().orElseThrow();
        assertThat(jul5.get("surgeryCount")).isEqualTo(1);
    }

    @Test void createEvent_rejectsBadType() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        com.hms.dto.CalendarEventRequest req = new com.hms.dto.CalendarEventRequest();
        req.setTitle("X"); req.setEventType("PARTY");
        req.setFromDate(LocalDate.of(2026, 7, 1)); req.setToDate(LocalDate.of(2026, 7, 1));
        assertThatThrownBy(() -> service.createEvent(req)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void createEvent_rejectsToBeforeFrom() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        com.hms.dto.CalendarEventRequest req = new com.hms.dto.CalendarEventRequest();
        req.setTitle("X"); req.setEventType(CalendarEvent.EVENT);
        req.setFromDate(LocalDate.of(2026, 7, 5)); req.setToDate(LocalDate.of(2026, 7, 1));
        assertThatThrownBy(() -> service.createEvent(req)).isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && mvn -o -q -Dtest=HospitalCalendarServiceTest test`
Expected: FAIL — `HospitalCalendarService` does not exist / does not compile.

- [ ] **Step 3: Implement the service**

`backend/src/main/java/com/hms/service/hospital/HospitalCalendarService.java`:

```java
package com.hms.service.hospital;

import com.hms.dto.CalendarEventRequest;
import com.hms.entity.CalendarEvent;
import com.hms.entity.NurseAttendance;
import com.hms.entity.NurseShiftSchedule;
import com.hms.entity.Surgery;
import com.hms.entity.Ward;
import com.hms.exception.UnauthorizedException;
import com.hms.repository.*;
import com.hms.security.NurseInchargeGuard;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * HospitalCalendarService - aggregates nurse shifts, attendance, OT surgeries,
 * and calendar_events into a month grid + day detail (Nursing Mgmt Phase G).
 * Ward-scoped for an incharge (myWardIds); admin sees all. Surgeries are
 * hospital-wide for admin, and for an incharge limited to surgeries whose IPD
 * admission is in one of their wards.
 */
@Service
public class HospitalCalendarService {
    @Autowired private SecurityContextHelper securityHelper;
    @Autowired private NurseInchargeGuard nurseInchargeGuard;
    @Autowired private WardRepository wardRepository;
    @Autowired private NurseShiftScheduleRepository shiftScheduleRepository;
    @Autowired private NurseAttendanceRepository attendanceRepository;
    @Autowired private SurgeryRepository surgeryRepository;
    @Autowired private IpdAdmissionRepository ipdAdmissionRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private NurseProfileRepository nurseProfileRepository;
    @Autowired private CalendarEventRepository calendarEventRepository;
    @Autowired private AuditLogService auditLogService;

    private static final List<String> CAL_SURGERY_STATUSES =
            List.of(Surgery.SCHEDULED, Surgery.IN_PROGRESS, Surgery.COMPLETED);
    private static final Set<String> EVENT_TYPES =
            Set.of(CalendarEvent.HOLIDAY, CalendarEvent.EVENT, CalendarEvent.NOTICE);

    // ---- month grid ----

    public List<Map<String, Object>> monthSummary(int year, int month) {
        Long hospitalId = requireHospitalId();
        Set<Long> wardIds = new HashSet<>(nurseInchargeGuard.myWardIds());
        YearMonth ym = YearMonth.of(year, month);
        LocalDate first = ym.atDay(1), last = ym.atEndOfMonth();

        // shifts per day
        Map<LocalDate, Integer> shiftCounts = new HashMap<>();
        if (!wardIds.isEmpty()) {
            for (NurseShiftSchedule s : shiftScheduleRepository.findByWardIdInAndShiftDateBetween(wardIds, first, last)) {
                shiftCounts.merge(s.getShiftDate(), 1, Integer::sum);
            }
        }
        // surgeries per day (scoped)
        Map<LocalDate, Integer> surgeryCounts = new HashMap<>();
        for (Surgery sg : scopedSurgeries(hospitalId, wardIds, first, last)) {
            surgeryCounts.merge(sg.getScheduledAt().toLocalDate(), 1, Integer::sum);
        }
        // events per day
        List<CalendarEvent> events = calendarEventRepository
                .findByHospitalIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(hospitalId, last, first);

        List<Map<String, Object>> days = new ArrayList<>();
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
            List<Map<String, Object>> dayEvents = new ArrayList<>();
            boolean hasHoliday = false;
            for (CalendarEvent e : events) {
                if (!d.isBefore(e.getFromDate()) && !d.isAfter(e.getToDate())) {
                    dayEvents.add(Map.of("publicId", e.getPublicId(), "title", e.getTitle(), "type", e.getEventType()));
                    if (CalendarEvent.HOLIDAY.equals(e.getEventType())) hasHoliday = true;
                }
            }
            Map<String, Object> day = new HashMap<>();
            day.put("date", d.toString());
            day.put("shiftCount", shiftCounts.getOrDefault(d, 0));
            day.put("surgeryCount", surgeryCounts.getOrDefault(d, 0));
            day.put("events", dayEvents);
            day.put("hasHoliday", hasHoliday);
            days.add(day);
        }
        return days;
    }

    // ---- day detail ----

    public Map<String, Object> dayDetail(LocalDate date) {
        Long hospitalId = requireHospitalId();
        Set<Long> wardIds = new HashSet<>(nurseInchargeGuard.myWardIds());
        Map<Long, String> wardNames = wardNameMap(hospitalId);

        // shifts
        List<Map<String, Object>> shifts = new ArrayList<>();
        if (!wardIds.isEmpty()) {
            for (NurseShiftSchedule s : shiftScheduleRepository.findByWardIdInAndShiftDateBetween(wardIds, date, date)) {
                Map<String, Object> m = new HashMap<>();
                m.put("nurseName", nurseProfileRepository.findById(s.getNurseProfileId())
                        .map(com.hms.entity.NurseProfile::getName).orElse("Nurse #" + s.getNurseProfileId()));
                m.put("wardName", wardNames.getOrDefault(s.getWardId(), ""));
                m.put("startTime", String.valueOf(s.getStartTime()));
                m.put("endTime", String.valueOf(s.getEndTime()));
                shifts.add(m);
            }
        }
        // attendance summary
        int present = 0, absent = 0, onLeave = 0;
        if (!wardIds.isEmpty()) {
            for (NurseAttendance a : attendanceRepository.findByWardIdInAndAttendanceDateBetween(wardIds, date, date)) {
                switch (a.getStatus() == null ? "" : a.getStatus()) {
                    case "PRESENT", "LATE", "HALF_DAY" -> present++;
                    case "ABSENT" -> absent++;
                    case "LEAVE" -> onLeave++;
                    default -> { }
                }
            }
        }
        // surgeries
        List<Map<String, Object>> surgeries = new ArrayList<>();
        for (Surgery sg : scopedSurgeries(hospitalId, wardIds, date, date)) {
            Map<String, Object> m = new HashMap<>();
            m.put("patientName", patientRepository.findById(sg.getPatientId())
                    .map(com.hms.entity.Patient::getName).orElse("Patient #" + sg.getPatientId()));
            m.put("scheduledTime", String.valueOf(sg.getScheduledAt().toLocalTime()));
            m.put("surgeonName", sg.getSurgeonName() != null ? sg.getSurgeonName() : "");
            m.put("otWardName", sg.getOtWardId() != null ? wardNames.getOrDefault(sg.getOtWardId(), "") : "");
            m.put("status", sg.getStatus());
            surgeries.add(m);
        }
        // events
        List<Map<String, Object>> events = new ArrayList<>();
        for (CalendarEvent e : calendarEventRepository
                .findByHospitalIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(hospitalId, date, date)) {
            Map<String, Object> m = new HashMap<>();
            m.put("publicId", e.getPublicId());
            m.put("title", e.getTitle());
            m.put("type", e.getEventType());
            m.put("description", e.getDescription());
            m.put("fromDate", e.getFromDate().toString());
            m.put("toDate", e.getToDate().toString());
            events.add(m);
        }

        Map<String, Object> out = new HashMap<>();
        out.put("date", date.toString());
        out.put("shifts", shifts);
        out.put("attendance", Map.of("present", present, "absent", absent, "onLeave", onLeave));
        out.put("surgeries", surgeries);
        out.put("events", events);
        return out;
    }

    // ---- events CRUD ----

    public List<CalendarEvent> listEvents() {
        return calendarEventRepository
                .findByHospitalIdAndToDateGreaterThanEqualOrderByFromDateAsc(requireHospitalId(), LocalDate.now());
    }

    @Transactional
    public CalendarEvent createEvent(CalendarEventRequest req) {
        Long hospitalId = requireHospitalId();
        validate(req);
        CalendarEvent e = new CalendarEvent();
        e.setHospitalId(hospitalId);
        apply(e, req);
        e.setCreatedByUserId(securityHelper.getCurrentUserId());
        CalendarEvent saved = calendarEventRepository.save(e);
        audit("CALENDAR_EVENT_CREATED", saved.getTitle(), hospitalId, saved.getId());
        return saved;
    }

    @Transactional
    public CalendarEvent updateEvent(String publicId, CalendarEventRequest req) {
        Long hospitalId = requireHospitalId();
        validate(req);
        CalendarEvent e = requireEvent(publicId, hospitalId);
        apply(e, req);
        CalendarEvent saved = calendarEventRepository.save(e);
        audit("CALENDAR_EVENT_UPDATED", saved.getTitle(), hospitalId, saved.getId());
        return saved;
    }

    @Transactional
    public void deleteEvent(String publicId) {
        Long hospitalId = requireHospitalId();
        CalendarEvent e = requireEvent(publicId, hospitalId);
        calendarEventRepository.delete(e);
        audit("CALENDAR_EVENT_DELETED", publicId, hospitalId, e.getId());
    }

    // ---- helpers ----

    private List<Surgery> scopedSurgeries(Long hospitalId, Set<Long> wardIds, LocalDate from, LocalDate to) {
        boolean isAdmin = "HOSPITAL_ADMIN".equals(securityHelper.getCurrentUserRole());
        List<Surgery> out = new ArrayList<>();
        for (Surgery sg : surgeryRepository.findByHospitalIdAndStatusInOrderByScheduledAtAsc(hospitalId, CAL_SURGERY_STATUSES)) {
            if (sg.getScheduledAt() == null) continue;
            LocalDate d = sg.getScheduledAt().toLocalDate();
            if (d.isBefore(from) || d.isAfter(to)) continue;
            if (!isAdmin) {
                Long admWard = ipdAdmissionRepository.findById(sg.getIpdAdmissionId())
                        .map(com.hms.entity.IpdAdmission::getWardId).orElse(null);
                if (admWard == null || !wardIds.contains(admWard)) continue;
            }
            out.add(sg);
        }
        return out;
    }

    private Map<Long, String> wardNameMap(Long hospitalId) {
        Map<Long, String> m = new HashMap<>();
        for (Ward w : wardRepository.findByHospitalId(hospitalId)) m.put(w.getWardId(), w.getWardName());
        return m;
    }

    private void validate(CalendarEventRequest req) {
        if (req.getTitle() == null || req.getTitle().trim().isEmpty()) throw new IllegalArgumentException("Title is required");
        if (req.getEventType() == null || !EVENT_TYPES.contains(req.getEventType()))
            throw new IllegalArgumentException("Invalid event type");
        if (req.getFromDate() == null || req.getToDate() == null || req.getToDate().isBefore(req.getFromDate()))
            throw new IllegalArgumentException("Valid from/to dates are required");
    }

    private void apply(CalendarEvent e, CalendarEventRequest req) {
        e.setTitle(req.getTitle().trim());
        e.setEventType(req.getEventType());
        e.setFromDate(req.getFromDate());
        e.setToDate(req.getToDate());
        e.setDescription(req.getDescription());
    }

    private CalendarEvent requireEvent(String publicId, Long hospitalId) {
        CalendarEvent e = calendarEventRepository.findByPublicId(publicId)
                .orElseThrow(() -> new IllegalArgumentException("Calendar event not found"));
        if (!hospitalId.equals(e.getHospitalId())) throw new UnauthorizedException("Belongs to another hospital");
        return e;
    }

    private Long requireHospitalId() {
        Long h = securityHelper.getCurrentHospitalId();
        if (h == null) throw new UnauthorizedException("Hospital ID not found");
        return h;
    }

    private void audit(String a, String d, Long h, Long id) {
        try {
            auditLogService.logAction(a, d, securityHelper.getCurrentUserEmail(), h, "CALENDAR_EVENT", String.valueOf(id), null);
        } catch (Exception e) { /* best-effort */ }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && mvn -o -q -Dtest=HospitalCalendarServiceTest test`
Expected: `Tests run: 4, Failures: 0, Errors: 0`.

> Note: `monthSummary_bucketsShiftsAndEventsOntoDays` does not stub `getCurrentUserRole()`; Mockito returns `null`, so `isAdmin` is false and the (empty) surgery list needs no admission lookups. If strict-stub errors appear on unused mocks, wrap the offending stub in `lenient()` as shown in the test imports.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/hms/service/hospital/HospitalCalendarService.java \
        backend/src/test/java/com/hms/service/hospital/HospitalCalendarServiceTest.java
git commit -m "feat(nurse-mgmt): Phase G — HospitalCalendarService (month/day aggregation + event CRUD)"
```

---

### Task 4: `HospitalCalendarController`

**Files:**
- Create: `backend/src/main/java/com/hms/controller/hospital/HospitalCalendarController.java`

- [ ] **Step 1: Create the controller**

`backend/src/main/java/com/hms/controller/hospital/HospitalCalendarController.java`:

```java
package com.hms.controller.hospital;

import com.hms.dto.CalendarEventRequest;
import com.hms.security.RequireModule;
import com.hms.service.hospital.HospitalCalendarService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

/**
 * HospitalCalendarController - month grid, day detail, and holiday/event CRUD
 * for Admin + Incharge (Nursing Mgmt Phase G). NURSING-gated.
 */
@RestController
@RequestMapping("/hospital/calendar")
@RequireModule("NURSING")
@PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')")
public class HospitalCalendarController {

    @Autowired private HospitalCalendarService calendarService;

    @GetMapping("/month")
    public ResponseEntity<?> month(@RequestParam int year, @RequestParam int month) {
        return ResponseEntity.ok(calendarService.monthSummary(year, month));
    }

    @GetMapping("/day")
    public ResponseEntity<?> day(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(calendarService.dayDetail(date));
    }

    @GetMapping("/events")
    public ResponseEntity<?> listEvents() {
        return ResponseEntity.ok(calendarService.listEvents());
    }

    @PostMapping("/events")
    public ResponseEntity<?> createEvent(@RequestBody CalendarEventRequest req) {
        return ResponseEntity.ok(calendarService.createEvent(req));
    }

    @PutMapping("/events/{publicId}")
    public ResponseEntity<?> updateEvent(@PathVariable String publicId, @RequestBody CalendarEventRequest req) {
        return ResponseEntity.ok(calendarService.updateEvent(publicId, req));
    }

    @DeleteMapping("/events/{publicId}")
    public ResponseEntity<?> deleteEvent(@PathVariable String publicId) {
        calendarService.deleteEvent(publicId);
        return ResponseEntity.ok(Map.of("message", "Calendar event deleted"));
    }
}
```

- [ ] **Step 2: Run the full backend suite**

Run: `cd backend && mvn -o test`
Expected: `BUILD SUCCESS`, `Tests run: 215` (211 prior + 4 new), `Failures: 0, Errors: 0`.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/hms/controller/hospital/HospitalCalendarController.java
git commit -m "feat(nurse-mgmt): Phase G — HospitalCalendarController (/hospital/calendar)"
```

---

## Milestone G2 — Frontend

### Task 5: `calendarService.js` + `HospitalCalendar.jsx`

**Files:**
- Create: `frontend/src/services/calendarService.js`
- Create: `frontend/src/pages/hospital/HospitalCalendar.jsx`

- [ ] **Step 1: Create the service**

`frontend/src/services/calendarService.js`:

```javascript
import apiClient from './apiService';

/** calendarService - Hospital Calendar (Nursing Mgmt Phase G). */
const calendarService = {
    getMonth: async (year, month) =>
        (await apiClient.get(`/hospital/calendar/month?year=${year}&month=${month}`)).data,
    getDay: async (dateStr) =>
        (await apiClient.get(`/hospital/calendar/day?date=${dateStr}`)).data,
    getEvents: async () => (await apiClient.get('/hospital/calendar/events')).data,
    createEvent: async (payload) => (await apiClient.post('/hospital/calendar/events', payload)).data,
    updateEvent: async (publicId, payload) => (await apiClient.put(`/hospital/calendar/events/${publicId}`, payload)).data,
    deleteEvent: async (publicId) => (await apiClient.delete(`/hospital/calendar/events/${publicId}`)).data,
};

export default calendarService;
```

- [ ] **Step 2: Create the calendar component**

`frontend/src/pages/hospital/HospitalCalendar.jsx`:

```jsx
import React, { useState, useEffect, useCallback, useMemo } from 'react';
import calendarService from '../../services/calendarService';
import { useToast } from '../../context/ToastContext';

const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June',
    'July', 'August', 'September', 'October', 'November', 'December'];
const DOW = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
const EVENT_TYPES = ['HOLIDAY', 'EVENT', 'NOTICE'];

const typePill = (type) => {
    if (type === 'HOLIDAY') return 'bg-red-50 text-red-700 border-red-100';
    if (type === 'EVENT') return 'bg-indigo-50 text-indigo-700 border-indigo-100';
    return 'bg-amber-50 text-amber-700 border-amber-100';
};

const hhmm = (t) => (t ? String(t).slice(0, 5) : '');

/**
 * HospitalCalendar - month grid + day drill-down + holiday/event CRUD
 * (Nursing Mgmt Phase G). Shared by the incharge and admin dashboards.
 */
const HospitalCalendar = () => {
    const { success, error: toastError } = useToast();
    const today = new Date();
    const [year, setYear] = useState(today.getFullYear());
    const [month, setMonth] = useState(today.getMonth() + 1); // 1..12
    const [days, setDays] = useState([]);
    const [loading, setLoading] = useState(false);

    const [selectedDate, setSelectedDate] = useState(null);
    const [dayDetail, setDayDetail] = useState(null);
    const [dayLoading, setDayLoading] = useState(false);

    const [eventFormOpen, setEventFormOpen] = useState(false);

    const loadMonth = useCallback(async () => {
        setLoading(true);
        try {
            setDays(await calendarService.getMonth(year, month));
        } catch (e) {
            toastError(e?.response?.data?.error || 'Failed to load calendar');
        } finally {
            setLoading(false);
        }
    }, [year, month, toastError]);

    useEffect(() => { loadMonth(); }, [loadMonth]);

    const openDay = async (dateStr) => {
        setSelectedDate(dateStr);
        setDayLoading(true);
        setDayDetail(null);
        try {
            setDayDetail(await calendarService.getDay(dateStr));
        } catch (e) {
            toastError(e?.response?.data?.error || 'Failed to load day');
        } finally {
            setDayLoading(false);
        }
    };

    // Leading blanks so day 1 lands under the right weekday (Mon-first).
    const leadingBlanks = useMemo(() => {
        const firstDow = new Date(year, month - 1, 1).getDay(); // 0=Sun..6=Sat
        return firstDow === 0 ? 6 : firstDow - 1;
    }, [year, month]);

    const prevMonth = () => {
        if (month === 1) { setMonth(12); setYear((y) => y - 1); } else setMonth((m) => m - 1);
    };
    const nextMonth = () => {
        if (month === 12) { setMonth(1); setYear((y) => y + 1); } else setMonth((m) => m + 1);
    };
    const goToday = () => { setYear(today.getFullYear()); setMonth(today.getMonth() + 1); };

    const todayStr = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`;

    return (
        <div className="p-4 space-y-4">
            <div className="flex items-center justify-between flex-wrap gap-3">
                <div className="flex items-center gap-2">
                    <button onClick={prevMonth} className="px-2.5 py-1.5 text-sm rounded-lg border border-gray-300 hover:bg-gray-50">‹ Prev</button>
                    <button onClick={goToday} className="px-2.5 py-1.5 text-sm rounded-lg border border-gray-300 hover:bg-gray-50">Today</button>
                    <button onClick={nextMonth} className="px-2.5 py-1.5 text-sm rounded-lg border border-gray-300 hover:bg-gray-50">Next ›</button>
                    <span className="ml-2 text-lg font-bold text-gray-900">{MONTHS[month - 1]} {year}</span>
                </div>
                <button onClick={() => setEventFormOpen(true)}
                    className="px-4 py-2 bg-gray-900 text-white text-sm font-semibold rounded-lg hover:bg-gray-800">
                    + Add Event
                </button>
            </div>

            <div className="bg-white border border-gray-200 rounded-2xl overflow-hidden">
                <div className="grid grid-cols-7 bg-gray-50 border-b border-gray-200">
                    {DOW.map((d) => (
                        <div key={d} className="px-2 py-2 text-xs font-semibold text-gray-500 text-center">{d}</div>
                    ))}
                </div>
                {loading ? (
                    <div className="p-8 text-center text-gray-500">Loading…</div>
                ) : (
                    <div className="grid grid-cols-7">
                        {Array.from({ length: leadingBlanks }).map((_, i) => (
                            <div key={`b${i}`} className="min-h-[92px] border-b border-r border-gray-100 bg-gray-50/40" />
                        ))}
                        {days.map((day) => {
                            const dateStr = String(day.date);
                            const dayNum = Number(dateStr.slice(8, 10));
                            const isToday = dateStr === todayStr;
                            return (
                                <button key={dateStr} onClick={() => openDay(dateStr)}
                                    className={`min-h-[92px] border-b border-r border-gray-100 p-1.5 text-left align-top hover:bg-gray-50 transition-colors ${day.hasHoliday ? 'bg-red-50/40' : ''}`}>
                                    <div className={`text-xs font-semibold mb-1 ${isToday ? 'text-white bg-gray-900 rounded-full w-5 h-5 flex items-center justify-center' : 'text-gray-700'}`}>{dayNum}</div>
                                    {day.shiftCount > 0 && (
                                        <div className="text-[10px] text-gray-600 mb-0.5">{day.shiftCount} shift{day.shiftCount > 1 ? 's' : ''}</div>
                                    )}
                                    {day.surgeryCount > 0 && (
                                        <div className="text-[10px] text-purple-700 mb-0.5">🔪 {day.surgeryCount} surgery</div>
                                    )}
                                    {(day.events || []).slice(0, 2).map((e) => (
                                        <div key={e.publicId} className={`text-[10px] px-1 py-0.5 mb-0.5 rounded border truncate ${typePill(e.type)}`}>{e.title}</div>
                                    ))}
                                    {(day.events || []).length > 2 && (
                                        <div className="text-[10px] text-gray-400">+{day.events.length - 2} more</div>
                                    )}
                                </button>
                            );
                        })}
                    </div>
                )}
            </div>

            {selectedDate && (
                <DayPanel
                    date={selectedDate}
                    detail={dayDetail}
                    loading={dayLoading}
                    onClose={() => { setSelectedDate(null); setDayDetail(null); }}
                    onChanged={() => { loadMonth(); openDay(selectedDate); }}
                />
            )}

            {eventFormOpen && (
                <EventForm
                    initialDate={selectedDate}
                    onClose={() => setEventFormOpen(false)}
                    onSaved={() => { setEventFormOpen(false); loadMonth(); if (selectedDate) openDay(selectedDate); success('Event saved'); }}
                    onError={(msg) => toastError(msg)}
                />
            )}
        </div>
    );
};

const DayPanel = ({ date, detail, loading, onClose, onChanged }) => {
    const { success, error: toastError } = useToast();
    const del = async (publicId) => {
        try {
            await calendarService.deleteEvent(publicId);
            success('Event deleted');
            onChanged();
        } catch (e) {
            toastError(e?.response?.data?.error || 'Failed to delete');
        }
    };
    return (
        <div className="fixed inset-0 bg-black bg-opacity-40 flex justify-end z-50" onClick={onClose}>
            <div className="bg-white w-full max-w-md h-full overflow-y-auto p-5" onClick={(e) => e.stopPropagation()}>
                <div className="flex items-center justify-between mb-4">
                    <h2 className="text-lg font-bold text-gray-900">{date}</h2>
                    <button onClick={onClose} className="text-gray-400 hover:text-gray-700 text-xl">✕</button>
                </div>
                {loading || !detail ? (
                    <div className="text-gray-500">Loading…</div>
                ) : (
                    <div className="space-y-5 text-sm">
                        <section>
                            <h3 className="font-semibold text-gray-700 mb-1">Nurses on shift</h3>
                            {detail.shifts.length === 0 ? <p className="text-gray-400">None</p> :
                                detail.shifts.map((s, i) => (
                                    <div key={i} className="text-gray-700">{s.nurseName} — {s.wardName} ({hhmm(s.startTime)}–{hhmm(s.endTime)})</div>
                                ))}
                        </section>
                        <section>
                            <h3 className="font-semibold text-gray-700 mb-1">Attendance</h3>
                            <p className="text-gray-700">Present {detail.attendance.present} · Absent {detail.attendance.absent} · Leave {detail.attendance.onLeave}</p>
                        </section>
                        <section>
                            <h3 className="font-semibold text-gray-700 mb-1">Surgeries</h3>
                            {detail.surgeries.length === 0 ? <p className="text-gray-400">None</p> :
                                detail.surgeries.map((s, i) => (
                                    <div key={i} className="text-gray-700">{hhmm(s.scheduledTime)} — {s.patientName} ({s.surgeonName || '—'}) · {s.otWardName} · {s.status}</div>
                                ))}
                        </section>
                        <section>
                            <h3 className="font-semibold text-gray-700 mb-1">Events</h3>
                            {detail.events.length === 0 ? <p className="text-gray-400">None</p> :
                                detail.events.map((e) => (
                                    <div key={e.publicId} className="flex items-center justify-between">
                                        <span className="text-gray-700">{e.title} <span className="text-xs text-gray-400">({e.type})</span></span>
                                        <button onClick={() => del(e.publicId)} className="text-red-600 text-xs hover:text-red-700">Delete</button>
                                    </div>
                                ))}
                        </section>
                    </div>
                )}
            </div>
        </div>
    );
};

const EventForm = ({ initialDate, onClose, onSaved, onError }) => {
    const [title, setTitle] = useState('');
    const [eventType, setEventType] = useState('HOLIDAY');
    const [fromDate, setFromDate] = useState(initialDate || '');
    const [toDate, setToDate] = useState(initialDate || '');
    const [description, setDescription] = useState('');
    const [saving, setSaving] = useState(false);

    const submit = async () => {
        if (!title.trim() || !fromDate || !toDate) { onError('Title and dates are required'); return; }
        setSaving(true);
        try {
            await calendarService.createEvent({ title: title.trim(), eventType, fromDate, toDate, description });
            onSaved();
        } catch (e) {
            onError(e?.response?.data?.error || 'Failed to save event');
        } finally {
            setSaving(false);
        }
    };

    return (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50 p-4" onClick={onClose}>
            <div className="bg-white rounded-2xl w-full max-w-md p-6" onClick={(e) => e.stopPropagation()}>
                <h2 className="text-lg font-bold text-gray-900 mb-4">Add Calendar Event</h2>
                <div className="space-y-3">
                    <input value={title} onChange={(e) => setTitle(e.target.value)} placeholder="Title *"
                        className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm" />
                    <select value={eventType} onChange={(e) => setEventType(e.target.value)}
                        className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm">
                        {EVENT_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
                    </select>
                    <div className="grid grid-cols-2 gap-3">
                        <div>
                            <label className="block text-xs text-gray-500 mb-1">From</label>
                            <input type="date" value={fromDate} onChange={(e) => setFromDate(e.target.value)}
                                className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm" />
                        </div>
                        <div>
                            <label className="block text-xs text-gray-500 mb-1">To</label>
                            <input type="date" value={toDate} onChange={(e) => setToDate(e.target.value)}
                                className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm" />
                        </div>
                    </div>
                    <textarea value={description} onChange={(e) => setDescription(e.target.value)} placeholder="Description (optional)" rows={2}
                        className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm" />
                </div>
                <div className="mt-5 flex justify-end gap-3">
                    <button onClick={onClose} disabled={saving} className="px-4 py-2 text-sm border border-gray-300 rounded-lg hover:bg-gray-50">Cancel</button>
                    <button onClick={submit} disabled={saving}
                        className="px-4 py-2 text-sm bg-gray-900 text-white rounded-lg hover:bg-gray-800 disabled:opacity-50">
                        {saving ? 'Saving…' : 'Save'}
                    </button>
                </div>
            </div>
        </div>
    );
};

export default HospitalCalendar;
```

- [ ] **Step 3: Build to verify it compiles**

Run: `cd frontend && npx vite build --mode development`
Expected: `✓ built in …` (no errors).

- [ ] **Step 4: Commit**

```bash
git add frontend/src/services/calendarService.js frontend/src/pages/hospital/HospitalCalendar.jsx
git commit -m "feat(nurse-mgmt): Phase G — calendarService + HospitalCalendar component"
```

---

### Task 6: Wire the calendar into both dashboards

**Files:**
- Modify: `frontend/src/pages/hospital/NurseInchargeDashboard.jsx`
- Modify: `frontend/src/pages/hospital/nurse-incharge/InchargeOverview.jsx`
- Modify: `frontend/src/pages/hospital/HospitalAdminDashboard.jsx`

- [ ] **Step 1: Incharge dashboard — import**

In `NurseInchargeDashboard.jsx`, next to `import CoverageView from './nurse-incharge/CoverageView';` add:

```jsx
import HospitalCalendar from './HospitalCalendar';
```

- [ ] **Step 2: Incharge dashboard — add the tab**

In the `sidebarTabs` array, after the `{ id: 'coverage', label: 'Coverage' }` entry add:

```jsx
        { id: 'calendar', label: 'Calendar' },
```

- [ ] **Step 3: Incharge dashboard — title + render**

In `titleFor()` (the `if (activeTab === 'coverage') return 'Coverage';` block), add before the fallback:

```jsx
        if (activeTab === 'calendar') return 'Calendar';
```

In `renderContent()`'s `switch`, after `case 'coverage': return <CoverageView />;` add:

```jsx
            case 'calendar':
                return <HospitalCalendar />;
```

- [ ] **Step 4: Enable the "View Calendar" quick action**

In `InchargeOverview.jsx`, find the entire disabled calendar button element — it begins `<button disabled title="Coming in a later phase"` and ends with its closing `</button>` after the `View Calendar` label — and replace that whole element with:

```jsx
                    <button onClick={() => onNavigate && onNavigate('calendar')}
                        className="px-4 py-2 text-sm font-semibold rounded-lg border border-gray-300 text-gray-700 hover:bg-gray-50">
                        View Calendar
                    </button>
```

(If the existing button spans multiple attributes/lines, delete all of them through the closing tag; the snippet above is the complete replacement.)

- [ ] **Step 5: Admin dashboard — import**

In `HospitalAdminDashboard.jsx`, next to `import TimeSlotsView from './TimeSlotsView';` add:

```jsx
import HospitalCalendar from './HospitalCalendar';
```

- [ ] **Step 6: Admin dashboard — sidebar item + group**

In the sidebar item list (near `{ id: 'time-slots', label: 'Time Slots', icon: null, requiredModule: 'NURSING' }`), add after it:

```jsx
        { id: 'calendar', label: 'Calendar', icon: null, requiredModule: 'NURSING' },
```

In the `group-nursing` group definition, add `'calendar'` to its `tabIds`:

```jsx
        { id: 'group-nursing', label: 'Nursing', tabIds: ['nurses', 'nurse-assignments', 'nurse-tasks', 'time-slots', 'calendar'] },
```

- [ ] **Step 7: Admin dashboard — render branch + Add-button exclusion**

Near the `{activeTab === 'time-slots' && (<TimeSlotsView />)}` render block, add:

```jsx
                                {activeTab === 'calendar' && (
                                    <HospitalCalendar />
                                )}
```

In the `PageHeader onAdd={...}` ternary that already excludes `'time-slots'`, also exclude `'calendar'` so no generic Add button shows (the calendar has its own Add Event button). Change the condition `activeTab !== 'time-slots'` to `activeTab !== 'time-slots' && activeTab !== 'calendar'`.

- [ ] **Step 8: Build**

Run: `cd frontend && npx vite build --mode development`
Expected: `✓ built in …` (no errors).

- [ ] **Step 9: Commit**

```bash
git add frontend/src/pages/hospital/NurseInchargeDashboard.jsx \
        frontend/src/pages/hospital/nurse-incharge/InchargeOverview.jsx \
        frontend/src/pages/hospital/HospitalAdminDashboard.jsx
git commit -m "feat(nurse-mgmt): Phase G — wire Hospital Calendar into incharge + admin dashboards"
```

---

## Final verification

- [ ] `cd backend && mvn -o test` → `BUILD SUCCESS`, 215 tests, 0 failures.
- [ ] `cd frontend && npx vite build --mode development` → `✓ built`.
- [ ] Manual (backend running, NURSING module enabled):
  - As **admin**: open Calendar tab → month grid renders; add a HOLIDAY on a date → red pill appears and the day cell tints; click the day → panel shows the event, shifts, attendance, surgeries; delete the event → it disappears.
  - As **incharge**: Dashboard → "View Calendar" quick action opens the Calendar tab; shifts/attendance reflect only their wards; a surgery for a patient outside their wards does **not** appear, one inside does.
  - Confirm a scheduled OT surgery shows on its `scheduledAt` date with patient + surgeon + OT ward.

---

## Notes for the implementer
- Do **not** run `git push`. Commit at the boundaries shown.
- Frontend builds run from `frontend/` only.
- If `mvn -o` fails for missing artifacts, drop `-o` once to let Maven fetch, then resume offline.
- The service returns plain `Map`/`List` structures (matching `NurseWorkspaceService.getMyWards()`), so no extra response DTOs are needed.
