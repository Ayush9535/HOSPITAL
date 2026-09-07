# Nursing Management — Phase G: Hospital Calendar (Design)

**Date:** 2026-07-09
**Status:** Approved design → plan
**Module gate:** `NURSING`
**Depends on:** Phases A–F (shift schedules, attendance, beds, incharge dashboard, coverage). This is the final phase of the Nursing Management module.

## Goal

A single month calendar that aggregates the hospital's day-to-day activity into one view for the Hospital Admin and Nurse Incharge, so they can see staffing, surgeries, and holidays/events at a glance and drill into any day.

## Scope (locked decisions)

- **Layers shown (v1):**
  1. **Nurse shifts** — from `nurse_shift_schedules` (Phase B).
  2. **Nurse attendance** — from `nurse_attendance` (Phase D).
  3. **OT surgeries** — from `surgeries` (OT module), on their `scheduledAt` date.
  4. **Holidays + hospital events** — a **new** `calendar_events` table with admin/incharge CRUD.
- **Appointments are intentionally excluded** (high volume; not useful at month granularity).
- **Audience:** `HOSPITAL_ADMIN` and `NURSE_INCHARGE` only.
- **View:** month grid with per-day badges + a day drill-down panel.
- **Scoping:**
  - Admin sees all wards.
  - Incharge's **shift** and **attendance** layers are limited to their wards (`NurseInchargeGuard.myWardIds()`).
  - Incharge's **surgeries** are limited to surgeries whose linked IPD admission's ward is in their wards (patient's home ward, i.e. `IpdAdmission.wardId` — **not** the OT ward). Admin sees all surgeries.
  - **Events** are hospital-wide for both roles.
- **Coverage (Phase F):** honored implicitly — the calendar reads shift/attendance rows as-is; temporary ward assignments already move a nurse's schedule rows, and attendance is per ward/date. No extra coverage logic in Phase G.

## Data model — one new table

`calendar_events`:

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT PK auto | |
| `public_id` | VARCHAR unique | UUID via `@PrePersist` |
| `hospital_id` | BIGINT NOT NULL | tenant scope; FK → hospitals (CASCADE) |
| `title` | VARCHAR(160) NOT NULL | |
| `event_type` | VARCHAR(20) NOT NULL | `HOLIDAY` \| `EVENT` \| `NOTICE` |
| `from_date` | DATE NOT NULL | inclusive |
| `to_date` | DATE NOT NULL | inclusive; single-day event ⇒ `to_date = from_date` |
| `description` | VARCHAR(500) NULL | |
| `created_by_user_id` | BIGINT NULL | |
| `created_at` | TIMESTAMP NOT NULL | `@CreationTimestamp` |

Indexes: `(hospital_id, from_date, to_date)`. Migration: idempotent `ensureCalendarEventsTable()` in `DatabaseMigrationRunner` + mirror in `setup/schema-full.sql` (Phase F pattern). Entity `CalendarEvent` + `CalendarEventRepository`.

Shifts, attendance, and surgeries are **read from existing tables** — no new storage.

## Backend

### `HospitalCalendarService`

Resolves the caller's effective ward set once (`admin ⇒ all hospital wards`, `incharge ⇒ myWardIds()`) and aggregates within a date window.

- **`monthSummary(int year, int month)` → per-day summary for the grid.**
  Computes the month's `[first, last]` date window, then for each day returns:
  `{ date, shiftCount, surgeryCount, events: [{ publicId, title, type }], hasHoliday }`.
  - `shiftCount` — nurse shift rows for that date within the ward set.
  - `surgeryCount` — scheduled surgeries on that date within scope.
  - `events` — `calendar_events` overlapping that date (`from_date <= date <= to_date`), hospital-wide. `hasHoliday = any event of type HOLIDAY`.
  Returns a list of day DTOs covering every day in the month (including empty days) so the grid renders uniformly.
- **`dayDetail(LocalDate date)` → drill-down.**
  `{ date,
     shifts: [{ nurseName, wardName, startTime, endTime }],
     attendance: { present, absent, onLeave },
     surgeries: [{ patientName, scheduledTime, surgeonName, otWardName, status }],
     events: [{ publicId, title, type, description, fromDate, toDate }] }`.
  Shift/attendance scoped to the ward set; surgeries scoped as above; events hospital-wide.
- **Event CRUD:** `createEvent`, `updateEvent(publicId, …)`, `deleteEvent(publicId)`, all hospital-scoped and validated (`title` required, valid `event_type`, `to_date >= from_date`). Best-effort audit via `AuditLogService` (`entityType = "CALENDAR_EVENT"`), matching Phase F.

**Surgery scoping detail:** load surgeries for the hospital with `scheduledAt` in the window and `status in (SCHEDULED, IN_PROGRESS, COMPLETED)`; for an incharge, keep only those whose `IpdAdmission.wardId ∈ myWardIds()`. (REQUESTED surgeries have no `scheduledAt` and are omitted from the calendar.)

### `HospitalCalendarController` — `/hospital/calendar/**`, `@RequireModule("NURSING")`

| Method | Path | Roles | Body/params |
|---|---|---|---|
| GET | `/calendar/month?year=&month=` | ADMIN, INCHARGE | — |
| GET | `/calendar/day?date=` | ADMIN, INCHARGE | ISO date |
| GET | `/calendar/events` | ADMIN, INCHARGE | list active/upcoming events |
| POST | `/calendar/events` | ADMIN, INCHARGE | `CalendarEventRequest` |
| PUT | `/calendar/events/{publicId}` | ADMIN, INCHARGE | `CalendarEventRequest` |
| DELETE | `/calendar/events/{publicId}` | ADMIN, INCHARGE | — |

`CalendarEventRequest` = `{ title, eventType, fromDate, toDate, description }`.

## Frontend

### `services/calendarService.js`
`getMonth(year, month)`, `getDay(dateStr)`, `getEvents()`, `createEvent(payload)`, `updateEvent(publicId, payload)`, `deleteEvent(publicId)`.

### `components` / `pages/hospital/HospitalCalendar.jsx` (shared)
- **Month grid**: header with `‹ Prev / Today / Next ›` and the month label; a 7-column grid of day cells. Each cell shows the day number, a shift-count badge (e.g. "3 shifts"), a small surgery indicator with count, and up to ~2 event pills (holiday pills visually distinct) with a "+N" overflow. Today is highlighted.
- **Day panel**: clicking a day opens a side panel (or modal) calling `getDay(date)` and listing the four sections (shifts, attendance summary, surgeries, events). Events in the panel have Edit/Delete (admin/incharge).
- **Add Event**: a button opens a small form (title, type dropdown, from/to dates, description) → `createEvent` → toast + refresh the month.
- Loading/empty/error states follow existing nurse views (`LoadingSpinner`, `useToast`, `e?.response?.data?.error`).

### Wiring
- **Nurse Incharge dashboard** (`NurseInchargeDashboard.jsx`): add a **Calendar** tab rendering `<HospitalCalendar />`, and enable the Phase E "View Calendar" quick action (currently disabled/"coming soon") to switch to it.
- **Hospital Admin dashboard** (`HospitalAdminDashboard.jsx`): add a **Calendar** section/tab rendering the same `<HospitalCalendar />`, shown only when the `NURSING` module is enabled.

## Testing
- `HospitalCalendarServiceTest` (Mockito): (1) `monthSummary` buckets shifts/surgeries/events onto the right days and applies ward scoping for an incharge; (2) `dayDetail` returns scoped shifts + attendance summary + scoped surgeries + hospital-wide events; (3) event CRUD validation (bad `event_type`, `to_date < from_date`). Keep to the existing unit-test style.
- Frontend: `npx vite build --mode development` must succeed.

## Out of scope (explicit)
- Appointments layer; doctor availability; staff/patient birthdays; leave management as a first-class object (leaves surface only via attendance `LEAVE`); recurring events; calendar export/print; drag-to-reschedule. These can be added later on top of the same aggregation service.

## Milestones
- **G1 — Backend:** `CalendarEvent` entity/repo, migration + schema mirror, `HospitalCalendarService`, `HospitalCalendarController`, `CalendarEventRequest`, `HospitalCalendarServiceTest`; `mvn -o test` green.
- **G2 — Frontend:** `calendarService.js`, `HospitalCalendar.jsx` (grid + day panel + event form), wire into incharge and admin dashboards; `npx vite build` green.
