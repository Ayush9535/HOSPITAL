# Nursing Management — Phase F Design (Temporary Ward Assignment + Substitution)

**Date:** 2026-07-09
**Status:** Approved for planning
**Depends on:** A (guard, profiles), B (schedule), D (attendance), E (dashboard).
**Gate:** `NURSING`; `/hospital/**`; tenant-scoped.

## Scope
Two date-ranged coverage records, both **auto-reverting** (no cron — coverage applies only while today ∈ [from,to]; the primary is never modified):
1. **Temporary ward assignment** — a nurse temporarily works another ward for a range; `NurseProfile.ward_id` (primary) is untouched.
2. **Nurse substitution** — a replacement nurse temporarily covers a primary nurse's patients for a range/reason.

## Decisions (locked)
- **Temp ward — functional:** a central "effective ward nurses on date D" set = primary-ward staff (minus those temporarily assigned *out*) ∪ those temporarily assigned *in*. It replaces the raw ward-nurse lookup in the incharge's consumers, so the temp ward's incharge fully manages the nurse for the window.
- **Substitution — functional:** the replacement's "My Patients" also includes the primary's active patients during the window, and `NurseAccessGuard` treats them as assigned so the replacement can record care. Primary assignment rows unchanged.
- **Managed by** `NURSE_INCHARGE` (own wards) + `HOSPITAL_ADMIN`.

## Data model
- **`nurse_ward_assignment`** (`nurse_ward_assignments`): `id, public_id, hospital_id, nurse_profile_id, temp_ward_id, from_date, to_date, reason, created_by_user_id, created_at`. Validation: `to>=from`; no *overlapping* active temp assignment for the same nurse.
- **`nurse_substitution`** (`nurse_substitutions`): `id, public_id, hospital_id, primary_nurse_profile_id, replacement_nurse_profile_id, from_date, to_date, reason, created_by_user_id, created_at`. Validation: `to>=from`; primary≠replacement; both active nurses in this hospital.
Idempotent migrations + `schema-full.sql` mirror.

## `NurseCoverageService` (new)
CRUD (ward-scoped via `NurseInchargeGuard` on the nurse's ward; audited) plus resolvers:
- `effectiveWardNurses(Long wardId, LocalDate date) -> List<NurseProfile>` — base `findByWardIdAndIsInchargeFalseAndIsActiveTrue(wardId)` minus nurses with an active temp assignment to a *different* ward, plus nurses with an active temp assignment *into* `wardId` (distinct). **With no temp records this returns exactly the base set** (backward compatible).
- `effectiveWardId(Long nurseProfileId, LocalDate date) -> Long` — active temp ward else primary.
- `coveredUserIds(Long replacementUserId, LocalDate date) -> Set<Long>` — for the replacement's profile, active substitutions → primary profiles → primary `user_id`s (the users whose patients they cover).
- `coversAdmission(Long userId, Long ipdAdmissionId, LocalDate date) -> boolean` — true if any covered user has an active `PatientNurseAssignment` to that admission.
- List helpers for the UI: temp assignments + substitutions for a ward (active + upcoming).

Repository finders use derived date-range queries, e.g. `findByNurseProfileIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(id, date, date)`, `findByTempWardId...`, `findByReplacementNurseProfileId...`.

## Wiring (Phase F2 — the delicate part)
- `NurseWorkspaceService.getWardStaffNurses(wardId)` → `effectiveWardNurses(wardId, today)`. This flows to: the incharge ward-nurses endpoint (assignment dropdown, attendance "add nurse"), scheduler nurse list, and the dashboard nurse count.
- `PatientAssignmentService.onAdmission` single-staff-nurse rule → count `effectiveWardNurses(wardId, today)` (non-incharge, with login).
- `NurseWorkspaceService.getMyPatients` → union in admissions of `coveredUserIds(currentUserId, today)` (dedup by admission id; mark them e.g. `coveredFor: <primaryName>`).
- `NurseAccessGuard.assertAssigned(admissionId)` → allow if directly assigned **or** `coverageService.coversAdmission(currentUserId, admissionId, today)`. (Additive OR — existing behavior unchanged when no substitution exists.) Inject `NurseCoverageService` into the guard.
- Backward-compat: all changes are no-ops when no coverage records exist, so Phases A–E tests keep passing (add mocks where a test now touches the new collaborator).

## Frontend (F3)
- **Incharge "Coverage" tab**: two sections — *Temporary Ward Assignments* (nurse, temp ward, from/to, reason; add/remove; shows Active/Upcoming) and *Substitutions* (primary, replacement, from/to, reason; add/remove). Nurse + ward pickers from existing endpoints (`getMyWards`, `getWardStaffNurses`).
- **Nurse dashboard banner**: if the logged-in nurse is an active replacement, show "You are covering <primary> until <date>"; covered patients already appear in My Patients (from the union).

## Testing & verification
- JUnit `NurseCoverageServiceTest`: `effectiveWardNurses` (temp-out excluded, temp-in included, empty→base); `coveredUserIds`/`coversAdmission` (active window only); no-overlap validation; ward-scope 403. Update any A–E test that now hits the new collaborator. `mvn -o test` + `npx vite build` per milestone.

## Milestones
- **F1** entities/repos/migrations + `NurseCoverageService` (CRUD + resolvers) + tests + `NurseCoverageController`.
- **F2** wire resolvers into getWardStaffNurses / dashboard / PatientAssignmentService / getMyPatients / NurseAccessGuard; fix tests.
- **F3** incharge Coverage tab + nurse covering banner.

Standing constraints: commit at milestone boundaries; `/hospital/**`; NURSING-gated.
