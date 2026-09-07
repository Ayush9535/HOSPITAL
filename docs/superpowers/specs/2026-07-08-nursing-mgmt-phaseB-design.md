# Nursing Management — Phase B Design (Time Slots + Nurse Scheduling)

**Date:** 2026-07-08
**Status:** Approved for planning
**Depends on:** Phase A (roles, `NurseInchargeGuard`, ward↔incharge, `NurseProfile.isIncharge`).
**Gate:** existing `NURSING` module; controllers under `/hospital/**`; tenant-scoped by `hospitalId`.

## Scope
1. **Time Slots** admin module with two areas: **Shift Templates** (CRUD) and **Appointment Slots** (CRUD).
2. **Nurse scheduling**: a Nurse Incharge assigns their ward nurses to shifts (per-date + range-fill); nurses view their own schedule.
3. **Replace `on_shift`** with schedule-derived on-shift status.

**Out of scope (later):** wiring Appointment Slots into the actual appointment-booking flow; attendance (D); Hospital Calendar (G).

## Decisions (locked)
- **Template change propagation:** each nurse-schedule row **snapshots** the shift's start/end at creation. Editing a shift template **bulk-updates snapshots on future-dated schedules only** (`shift_date >= today`); past rows keep their old times. No version tables.
- **Scheduling model:** per-date assignment `(nurseProfile, date, shiftTemplate)` with a **range-fill** helper (date range + optional days-of-week). One schedule per nurse per date.
- **Appointment Slots:** build management now; do **not** touch the booking flow this phase.
- **on_shift replacement:** the dashboard no longer uses the manual `on_shift` toggle; "On Shift / Off Shift" is derived from today's schedule (now ∈ [start, end], midnight-crossing aware). The `on_shift` column is left in place but ignored. To avoid lockouts, the nurse dashboard becomes **always accessible** with a schedule-derived status badge (replacing the hard Start-Shift gate and the End-Shift logout option).

---

## B1 — Time Slots admin module

### Data model
- **`ShiftTemplate`** (`shift_templates`): `id, public_id, hospital_id, name, start_time (TIME), end_time (TIME), is_active (default 1), created_at`. Night shifts may cross midnight (`end_time <= start_time` ⇒ crosses midnight — inferred, no extra column). Validation: name required, times required, start≠end.
- **`AppointmentSlot`** (`appointment_slots`): `id, public_id, hospital_id, start_time (TIME), end_time (TIME), is_active (default 1), created_at`. Validation: end after start.

Migrations via idempotent `DatabaseMigrationRunner.ensureXxx` + `schema-full.sql` mirror.

### Backend
- `ShiftTemplateService` (CRUD: create/list/update/deactivate), `AppointmentSlotService` (CRUD). Both tenant-scoped, audited (`SHIFT_TEMPLATE_*`, `APPOINTMENT_SLOT_*`).
- Controllers `/hospital/time-slots/shift-templates/**` and `/hospital/time-slots/appointment-slots/**`, `@RequireModule("NURSING")`, `@PreAuthorize("hasRole('HOSPITAL_ADMIN')")` for writes; reads open to `HOSPITAL_ADMIN`/`NURSE_INCHARGE` (incharge needs shift templates to schedule).
- `ShiftTemplateService.update(...)` triggers `NurseShiftScheduleService.applyTemplateChangeToFuture(templateId, newStart, newEnd)` (bulk snapshot update where `shift_date >= today`). (Defined in B2; in B1 the hook is a no-op placeholder if B2 not yet present — but B2 lands before this matters.)

### Frontend
- Admin **"Time Slots"** sidebar tab with two sub-tabs: **Shift Templates** and **Appointment Slots**, each a simple table + add/edit/deactivate modal. Reuse existing table/modal patterns.

---

## B2 — Nurse scheduling

### Data model
- **`NurseShiftSchedule`** (`nurse_shift_schedules`): `id, public_id, hospital_id, nurse_profile_id, ward_id (nullable), shift_date (DATE), shift_template_id, start_time (TIME snapshot), end_time (TIME snapshot), created_by_user_id, created_at`. Unique `(nurse_profile_id, shift_date)` (one shift per nurse per day). Index `(hospital_id, shift_date)` and `(nurse_profile_id, shift_date)`.

### Backend — `NurseShiftScheduleService`
- `assign(nurseProfileId, date, shiftTemplateId)` — ward-scoped: the nurse's ward must be one of the caller's wards (`NurseInchargeGuard`); admin unrestricted. Snapshots the template's current `start/end`. Upserts (replaces an existing row for that nurse+date). Audited `NURSE_SHIFT_ASSIGNED`.
- `rangeFill(nurseProfileId, startDate, endDate, shiftTemplateId, daysOfWeek?)` — creates/updates a row per matching date. Guard + snapshot as above.
- `remove(schedulePublicId)` — ward-scoped delete. Audited.
- `applyTemplateChangeToFuture(templateId, newStart, newEnd)` — `UPDATE nurse_shift_schedules SET start_time=?, end_time=? WHERE shift_template_id=? AND shift_date >= CURRENT_DATE`. Called from `ShiftTemplateService.update`.
- `getWardSchedule(wardId, fromDate, toDate)` — schedules for an incharge's ward over a range (grid). Ward-scoped.
- `getMySchedule(fromDate, toDate)` — the logged-in nurse's own schedule.
- `isOnShiftNow(nurseProfileId)` — true iff a schedule exists for today with `now` inside `[start,end]` (if `end<=start`, the window wraps past midnight).

### Backend — controller `/hospital/nurse-schedule/**`
- `POST /assign`, `POST /range-fill`, `DELETE /{publicId}`, `GET /ward?wardId=&from=&to=` — `hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')`.
- `GET /mine?from=&to=` — `hasAnyRole('NURSE','NURSE_INCHARGE','HOSPITAL_ADMIN')`.

### Frontend
- **Incharge** "Schedule" tab: a weekly grid (rows = the incharge's ward nurses, columns = dates); click a cell to assign/change a shift template; a **Range Fill** modal (nurse, date range, days-of-week, template). Uses shift-template list (B1) and ward nurses (Phase A endpoint).
- **Nurse** "My Shifts" view: the nurse's own upcoming schedule (from `GET /mine`).

---

## B3 — Replace `on_shift`

- `NurseWorkspaceService.getShiftStatus()` now returns `isOnShiftNow(currentNurseProfile)` (schedule-derived) plus today's shift window/label; `startShift()`/`endShift()` become **no-ops/removed** (kept as deprecated stubs returning current status if any caller remains, to avoid breakage — but the frontend stops calling them).
- **Frontend `NurseDashboard`**: remove the hard Start-Shift gate and the "End Shift & Logout" option. The dashboard is always accessible; the top shows a schedule-derived **On Shift / Off Shift** badge with the current/next shift time. Logout becomes a simple logout.
- `NurseProfile.on_shift` column stays (ignored); `NurseProfileRepository.findByWardIdAndIsActiveTrueAndOnShiftTrue` is left in place but unused (A2 already stopped using it for assignment).

---

## Testing & verification
- JUnit: `ShiftTemplateService`/`AppointmentSlotService` CRUD + validation + tenant; `NurseShiftScheduleService` (assign snapshots template times; range-fill day-of-week filter; `applyTemplateChangeToFuture` updates only `>= today`; ward-scope 403; `isOnShiftNow` incl. midnight-crossing). Each milestone: `mvn -o test` + `npx vite build`.
- Do not break existing OPD/IPD/pharmacy/OT/Phase-A flows.

## Milestones (for the plan)
- **B1** ShiftTemplate + AppointmentSlot entities/services/controllers/migrations + admin Time Slots UI.
- **B2** NurseShiftSchedule + scheduling service (assign/range-fill/bulk-future/reads) + controller + incharge scheduler UI + nurse "My Shifts".
- **B3** replace `on_shift` — schedule-derived status + NurseDashboard status badge (drop manual gate).

Standing constraints: commit at milestone boundaries; controllers under `/hospital/**`; NURSING-gated; clean up curl test data.
