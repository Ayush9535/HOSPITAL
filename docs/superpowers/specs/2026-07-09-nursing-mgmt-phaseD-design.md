# Nursing Management — Phase D Design (Attendance)

**Date:** 2026-07-09
**Status:** Approved for planning
**Depends on:** Phase A (`NurseInchargeGuard`, ward↔incharge), Phase B (`NurseShiftSchedule` with snapshot shift times).
**Gate:** `NURSING` module; controllers under `/hospital/**`; tenant-scoped by `hospitalId`.

## Scope
The Nurse Incharge records daily attendance for the nurses in their wards. Statuses: **Present, Absent, Half Day, Leave, Holiday, Late**. The scheduled shift window is recorded on the attendance row, with optional actual check-in / check-out and remarks. Every mark and every later correction is audited. Nurses can view their own attendance.

**Out of scope:** the incharge dashboard's Present/Absent/On-Leave tiles (Phase E consumes the `summary` endpoint added here); the Hospital Calendar (G).

## Decisions (locked)
- **Sheet source:** the daily sheet pre-lists nurses **scheduled** that date (shift window pre-filled from the schedule snapshot), and the incharge may **add any other active ward nurse** to the sheet (to record Leave/Holiday/etc. for an unrostered nurse).
- **Record fields:** status + **shift time snapshot** (from the schedule) + optional **check-in / check-out** + **remarks**.
- **Edit policy:** upsert per `(nurse, date)`. The incharge (own wards) and admin may correct it at any time; **every change is audited** with previous → new status. No dedicated audit table — reuse `AuditLogService` (`ATTENDANCE_MARKED` / `ATTENDANCE_MODIFIED`, prev→new in details, remarks as `reason`).

---

## Data model

**`AttendanceStatus`** constants (stored uppercase): `PRESENT, ABSENT, HALF_DAY, LEAVE, HOLIDAY, LATE`, with `isValid(String)`.

**`NurseAttendance`** (`nurse_attendance`):
`id, public_id, hospital_id, nurse_profile_id, ward_id (nullable), attendance_date (DATE), status (VARCHAR 20), shift_template_id (nullable), shift_start_time (TIME, nullable), shift_end_time (TIME, nullable), check_in_time (TIME, nullable), check_out_time (TIME, nullable), remarks (VARCHAR 255, nullable), marked_by_user_id, created_at, updated_at`.
- **Unique** `(nurse_profile_id, attendance_date)` — one record per nurse per day (upsert).
- Indexes `(hospital_id, attendance_date)`, `(ward_id, attendance_date)`.
- Idempotent migration + `setup/schema-full.sql` mirror.

Shift fields are **snapshots** taken from that nurse's `NurseShiftSchedule` for the date (if one exists); they stay put even if the schedule later changes — consistent with Phase B's history-preservation rule.

---

## Backend — `NurseAttendanceService`

- `getSheet(wardId, date)` — ward-scoped (`NurseInchargeGuard.assertWardAccess`). Returns one row per nurse to display:
  - nurses with a `NurseShiftSchedule` for `(wardId, date)` (shift window from the schedule), **union**
  - nurses who already have a `NurseAttendance` row for that ward/date (covers previously-added unrostered nurses).
  Each row: `nurseProfileId, nurseName, shiftTemplateId, shiftStartTime, shiftEndTime, status, checkInTime, checkOutTime, remarks, attendancePublicId` (status/etc. null when not yet marked).
- `getWardStaffNurses(wardId)` already exists (Phase A) and powers the "add a nurse to the sheet" dropdown.
- `mark(MarkAttendanceRequest)` — upsert by `(nurseProfileId, date)`. Validates: nurse is active + in this hospital; ward access via the nurse's ward; status is a valid `AttendanceStatus`. Snapshots `shiftTemplateId/shiftStartTime/shiftEndTime` from the nurse's schedule for that date when present. Sets `markedByUserId`. Audits `ATTENDANCE_MARKED` (new row) or `ATTENDANCE_MODIFIED` (`previousStatus -> newStatus`), remarks as reason.
- `getMyAttendance(from, to)` — the logged-in nurse's own rows.
- `summary(wardId, date)` — counts by status for the ward/date (feeds the Phase E dashboard tiles): `{ present, absent, halfDay, leave, holiday, late, unmarked }`.

## Backend — controller `/hospital/nurse-attendance/**`, `@RequireModule("NURSING")`
- `GET /sheet?wardId=&date=` — `hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')`
- `POST /mark` — `hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')`
- `GET /summary?wardId=&date=` — `hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')`
- `GET /mine?from=&to=` — `hasAnyRole('NURSE','NURSE_INCHARGE','HOSPITAL_ADMIN')`

DTOs: `MarkAttendanceRequest { nurseProfileId, date, status, checkInTime?, checkOutTime?, remarks? }`, `AttendanceSheetRow`, `AttendanceSummary`.

---

## Frontend
- **Incharge "Attendance" tab**: ward selector (`getMyWards`) + date picker (defaults today). Renders the sheet: Nurse | Scheduled shift (`HH:mm–HH:mm` or "—") | Status (`<select>` of the six statuses) | Check-in | Check-out | Remarks | Save. Saving a row calls `mark` and reloads. An **"Add nurse"** dropdown (ward staff nurses not already on the sheet) appends an unmarked row. A small summary strip shows the counts from `summary`.
- **Nurse "My Attendance"**: a tab in `NurseDashboard` listing the last ~30 days from `GET /mine` (date, status badge, shift window, check-in/out, remarks).

## Testing & verification
- JUnit `NurseAttendanceServiceTest`: `mark` creates a row and snapshots the schedule's shift times; re-`mark` updates the same row (upsert) and audits `ATTENDANCE_MODIFIED` with previous→new; invalid status rejected (400); ward-scope 403 for a foreign ward; `summary` counts by status.
- Each milestone: `cd backend && mvn -o test` + `cd frontend && npx vite build --mode development`.
- Don't break Phases A–C.

## Milestones (for the plan)
- **D1** `AttendanceStatus`, `NurseAttendance` entity/repo/migration, `NurseAttendanceService` (+ tests), DTOs, controller.
- **D2** incharge Attendance tab + nurse My Attendance tab.

Standing constraints: commit at milestone boundaries; controllers under `/hospital/**`; NURSING-gated.
