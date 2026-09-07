# Nursing Management — Phase C Design (Bed Management + Cleaning Workflow)

**Date:** 2026-07-09
**Status:** Approved for planning
**Depends on:** Phase A (`NURSE_INCHARGE`, `NurseInchargeGuard`, ward↔incharge).
**Gate:** existing `NURSING` module; controllers under `/hospital/**`; tenant-scoped by `hospitalId`.

## Scope
1. Four bed statuses: **Available, Occupied, Cleaning Required, Under Maintenance**.
2. **Cleaning workflow**: vacating a bed marks it Cleaning Required; the Nurse Incharge verifies and Marks Cleaned → Available.
3. **Every bed status change is audited** in a dedicated `bed_status_audits` table (previous status, new status, changed by, changed at, remarks) + a per-bed **Bed History** view.
4. Only **Available** beds appear in admission selection.
5. Bonus (closes a Phase B gap): a **`GET /hospital/nurse-incharge/wards`** ("my wards") endpoint, needed by the bed screen and the shift scheduler.

## Decisions (locked)
- **Audit:** dedicated `bed_status_audits` table (structured) **plus** a general `AuditLogService` entry.
- **Cleaning trigger:** confirmDischarge, bed transfer (the vacated old bed), **and the OT theatre bed** when a surgery completes/cancels. Consequence: `SurgeryService.start` (requires an Available bed) blocks the next surgery until the theatre is marked cleaned. Intentional.
- **Who may change status:** `NURSE_INCHARGE` (own wards only, via `NurseInchargeGuard`) and `HOSPITAL_ADMIN` (any ward). Staff nurses may not.

## Status values
Existing DB values are lowercase strings (`"available"`, `"occupied"`, `"maintenance"`). Keep that convention and add `"cleaning"`. Introduce a constants holder `com.hms.entity.BedStatus`:
```
AVAILABLE = "available"; OCCUPIED = "occupied"; CLEANING = "cleaning"; MAINTENANCE = "maintenance";
```
No data migration needed (new value only). Existing `WardService` "hasOccupied" ward-delete check (`!"available".equals(status)`) continues to treat cleaning/maintenance as not-free — acceptable.

---

## C1 — Status audit + central status service

### Data model
**`BedStatusAudit`** (`bed_status_audits`): `id, public_id, hospital_id, bed_id, ward_id (nullable), previous_status, new_status, changed_by_user_id, changed_at, remarks (nullable)`. Indexes on `(bed_id, changed_at)` and `hospital_id`. Idempotent migration + `schema-full.sql` mirror.

### `BedStatusService` (new, the single place bed status changes)
- `change(Long bedId, String newStatus, String remarks)` — loads the bed (tenant-checked), records `previous → new`, saves the bed, writes a `BedStatusAudit` row, and a best-effort `AuditLogService.logAction("BED_STATUS_CHANGED", ...)` with the remarks as `reason`. Returns the updated bed.
- `history(Long bedId)` — audit rows for a bed, newest first (tenant-checked).
- Internal system transitions (admit→occupied, discharge→cleaning, transfer, OT) call `change(...)` so **every** change is audited, with an auto remark (e.g. `"IPD discharge"`, `"Bed transfer"`, `"Surgery completed"`).

### Guard
Status changes initiated by a **user** (mark cleaned / maintenance) go through `NurseInchargeGuard.assertWardAccess(bed.getWardId())`. System transitions (discharge/admit/OT) bypass the guard (they run as the acting clinician/receptionist) — they still audit.

---

## C2 — Transitions & admission filter

- `IpdAdmissionService.confirmDischarge` → bed `occupied → cleaning` (was `available`), remark `"IPD discharge"`; clears `currentIpdAdmissionId`.
- `IpdAdmissionService` bed transfer → old bed `→ cleaning` (remark `"Bed transfer (vacated)"`), new bed `→ occupied`.
- `SurgeryService.complete` / `cancel` (freeing the OT bed) → `→ cleaning` (remark `"Surgery completed"` / `"Surgery cancelled"`). `SurgeryService.start` still requires an `available` bed, so the theatre must be marked cleaned first.
- Admission bed selection (`BedController /available` / whatever the admit modal calls) must return only `status = "available"` beds. Verify and fix if it returns anything else.
- **Test impact:** `SurgeryServiceTest.complete_freesOtBed` currently asserts the bed becomes `"available"` — update to `"cleaning"`.

---

## C3 — Endpoints, incharge Beds screen, "my wards"

### Endpoints (`/hospital/beds/**`, `@RequireModule("NURSING")`)
- `POST /{bedId}/cleaned` `{ remarks? }` — allowed only from `cleaning`; → `available`. `hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')` + ward guard.
- `POST /{bedId}/maintenance` `{ remarks? }` — from any non-`occupied` status → `maintenance`. Same roles/guard.
- `POST /{bedId}/available` `{ remarks? }` — from `maintenance` → `available`. Same roles/guard.
- `GET /{bedId}/history` — the bed's audit trail. Same roles/guard.
- Reject illegal transitions with 400 (e.g. marking an `occupied` bed cleaned).

### `GET /hospital/nurse-incharge/wards`
Returns the caller's wards (`NurseInchargeGuard.myWardIds()` → ward id/name/bed counts). `hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')`. Fixes the Phase B workaround where the scheduler derived wards from ward patients.

### Frontend
- **Incharge "Beds" tab**: ward selector (from the new my-wards endpoint) → bed grid showing each bed's status as a coloured badge, with **Mark Cleaned** (when `cleaning`), **Under Maintenance**, **Back to Available** (when `maintenance`) actions taking optional remarks, and a **History** popover per bed (`GET /{bedId}/history`).
- **Shift scheduler**: switch its ward selector to the new my-wards endpoint.
- Admin already sees beds in Wards & Beds; surface the new statuses' badges there too (no new actions required).

---

## Testing & verification
- JUnit: `BedStatusService` (records previous→new + audit row; tenant check), illegal-transition rejection, ward-scope 403 for a foreign ward, discharge→cleaning, OT complete→cleaning. Update `SurgeryServiceTest.complete_freesOtBed` to expect `cleaning`.
- Each milestone: `cd backend && mvn -o test` + `cd frontend && npx vite build --mode development`.
- Don't break existing admission/discharge/transfer/OT flows.

## Milestones (for the plan)
- **C1** `BedStatus` constants, `BedStatusAudit` entity/repo/migration, `BedStatusService` (+ tests).
- **C2** wire transitions (discharge, transfer, OT) through `BedStatusService`; enforce Available-only admission selection; fix the OT test.
- **C3** bed endpoints + `my wards` endpoint + incharge Beds screen + scheduler ward-selector switch.

Standing constraints: commit at milestone boundaries; controllers under `/hospital/**`; NURSING-gated.
