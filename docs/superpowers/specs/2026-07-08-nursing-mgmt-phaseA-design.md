# Nursing Management Module — Phase A Design (Roles & Nurse Incharge Foundation)

**Date:** 2026-07-08
**Status:** Approved for planning
**Scope:** The foundational layer of the Nursing Management Module: the `NURSE_INCHARGE` role, ward↔incharge assignment, admin nurse management (create/promote/demote/activate), a ward-scope RBAC guard, the new incharge-mediated patient-assignment flow (replacing least-loaded auto-assign), and the Separate Nurse Login toggle with "Performed By" attribution.

**Gate:** Reuses the existing `NURSING` plan module. All controllers map under `/hospital/**`. Multi-tenant isolation by `hospitalId` on every query (existing `SecurityContextHelper`).

**Out of scope (later phases):** Shift Templates & scheduling (B), Appointment Slots (B), bed cleaning status/workflow (C), attendance (D), full Nurse Incharge dashboard (E), temporary ward assignment & nurse substitution (F), Hospital Calendar (G). The existing `on_shift` boolean and Start/End-Shift gate are left untouched in Phase A and are replaced by template-based schedules in Phase B.

---

## 0. Decisions (locked)

- **RBAC:** pragmatic — keep the existing role model (`@PreAuthorize` + `@RequireModule("NURSING")` + tenant scope) and add a **ward-scope guard**. No DB-driven permission matrix (YAGNI; new roles remain easy to add).
- **Incharge is not an assignable caregiver.** For the single/multiple-nurse rule, only **non-incharge** active staff nurses count. An incharge always *supervises* (sees all ward patients) but is never auto-assigned patients.
- **Admission to an incharge-less ward is blocked.** Reception cannot pick a ward that has no incharge; the admission service rejects it.
- **Incharge always has a login**, regardless of the Separate Nurse Login setting (that toggle governs *staff* nurses only).

---

## A1 — Role, Ward↔Incharge, Admin nurse management, RBAC guard

### A1.1 Data model

**Role**
- Add `NURSE_INCHARGE` to the role set. Update: `JwtUtil` (role claim is already generic), `SecurityConfig` (grant `ROLE_NURSE_INCHARGE` authority; add to any role lists used for `/hospital/**`), and any place that enumerates hospital roles (e.g., staff-creation validation, login routing). Staff Nurse remains the existing `NURSE` role. `OT_INCHARGE` is **not** added in Phase A (actor only, deferred).

**`NurseProfile`** (add columns; migration + `schema-full.sql`):
- `is_incharge TINYINT(1) NOT NULL DEFAULT 0`
- `gender VARCHAR(10)`
- `qualification VARCHAR(120)`
- `registration_number VARCHAR(60)` (distinct from existing `license_number`)
- `joining_date DATE`

**`Ward`** (add column):
- `incharge_nurse_id BIGINT NULL` (FK → `nurse_profiles.id`). One incharge per ward. An incharge holds many wards (multiple ward rows share the same `incharge_nurse_id`). "Transfer" re-points ward rows.

**`hospital_settings`** (add column):
- `separate_nurse_login TINYINT(1) NOT NULL DEFAULT 0` (OFF by default).

All migrations follow the existing idempotent `DatabaseMigrationRunner.ensureXxx()` pattern (information_schema COLUMN_NAME check + `ALTER TABLE`), appended to `runMigrations()`, and mirrored in `setup/schema-full.sql`.

### A1.2 Backend

**Ward-scope guard** — `com.hms.security.NurseInchargeGuard` (mirrors `NurseAccessGuard`):
- `assertWardAccess(Long wardId)`: `HOSPITAL_ADMIN` → allow; `NURSE_INCHARGE` → allow iff `ward.inchargeNurseId` == the caller's `NurseProfile.id` (resolved via `nurseProfileRepository.findByUserId(currentUserId)`); else `AccessDeniedException`.
- `assertAdmissionInMyWard(Long ipdAdmissionId)`: resolves the admission's `wardId` then delegates to `assertWardAccess`.
- `myWardIds()`: for the current incharge, the list of `wardId`s they hold (used by list endpoints). Admin → all hospital wards.

**`NurseService`** (extend existing):
- `createNurse(req)` — extend to persist `gender`, `qualification`, `registrationNumber`, `joiningDate`; `primaryWard` mandatory. Login created only if the request asks *and* Separate Nurse Login is ON (else no `User`).
- `createIncharge(req)` — create `NurseProfile` (`is_incharge=true`) + a `User` with role `NURSE_INCHARGE` (login always). Ward assignment optional at creation, settable after.
- `promote(nurseProfileId)` — set `is_incharge=true`; ensure a `User` with role `NURSE_INCHARGE` exists (create login if missing, or elevate existing `NURSE` user's role). Audit `NURSE_PROMOTED`.
- `demote(nurseProfileId)` — **blocked** if the nurse is still `incharge_nurse_id` of any ward (`400` "reassign wards first"); otherwise `is_incharge=false`, user role → `NURSE`. Audit `NURSE_DEMOTED`.
- `setActive(nurseProfileId, boolean)` — activate/deactivate; deactivating an incharge is blocked while they hold wards. Audit.

**`WardService`** (extend):
- `setIncharge(wardId, inchargeNurseProfileId)` / `clearIncharge(wardId)` / `transferInchargeWards(...)` — admin-only, tenant-scoped, audited (`WARD_INCHARGE_SET`, previous/new value). Validate the target profile is an active incharge in the same hospital.
- `WardRepository.findByHospitalIdAndInchargeNurseId(...)`.

**Block incharge-less admission**:
- Reception's ward/bed-selection endpoint returns only wards where `incharge_nurse_id IS NOT NULL` **and** there is ≥1 Available bed.
- `IpdAdmissionService` admission path rejects (`400`) a ward whose `incharge_nurse_id` is null, with a clear message.

**Controllers** (under `/hospital/**`, `@RequireModule("NURSING")`):
- Admin nurse/incharge management endpoints (create/promote/demote/activate, ward-incharge set/transfer) — `@PreAuthorize("hasRole('HOSPITAL_ADMIN')")`.
- Guard is applied in incharge-facing services (A2).

### A1.3 Frontend
- **Admin** ([HospitalAdminDashboard] nurses area): add Create Incharge, Promote/Demote and Activate toggles (row actions), a Ward → Incharge assignment/transfer UI (in Wards & Beds or the nurses tab), and the **Separate Nurse Login** setting toggle (Settings).
- **Nurse Incharge portal shell** (new): minimal landing routed for `NURSE_INCHARGE` — tabs for *My Nurses* (manage ward staff nurses) and *My Ward Patients* (A2). Full dashboard is Phase E. Login routing sends `NURSE_INCHARGE` here.

---

## A2 — Patient assignment flow (replaces least-loaded auto-assign)

### A2.1 Rule
On IPD admission to a ward (which now always has an incharge):
1. The patient is visible to **that ward's incharge** — derived from ward membership (all admissions whose `ward_id ∈ myWardIds()`); **no assignment row needed** for incharge visibility.
2. **Separate Nurse Login OFF** → **no** `PatientNurseAssignment`; the incharge handles the patient.
3. **Separate Nurse Login ON** → let `N` = count of active, **non-incharge** `NurseProfile`s whose primary ward = this ward:
   - `N == 1` → auto-create a `PatientNurseAssignment` to that nurse (source `AUTO_SINGLE`); the incharge still sees it.
   - `N > 1` → **no** auto-assign; the incharge assigns via endpoint (source `INCHARGE`).
   - `N == 0` → incharge only.

### A2.2 Backend
- Replace the current `NurseAssignmentService.autoAssign` (least-loaded on-shift) call in the admit/confirm path with `PatientAssignmentService.onAdmission(admission)` implementing A2.1. Keep `PatientNurseAssignment` as the assignment record; add nullable `assignment_source VARCHAR(20)`.
- Endpoints:
  - `GET /hospital/nurse-incharge/patients` — admissions in `myWardIds()` (incharge/admin), with per-patient assigned-nurse (if any).
  - `POST /hospital/nurse-incharge/assign` `{ ipdAdmissionId, nurseProfileId }` — assign a staff nurse; ward-scoped via `assertAdmissionInMyWard` + validate the nurse belongs to that ward and is non-incharge/active. Audited (`PATIENT_NURSE_ASSIGNED`). Reassignment deactivates the prior active assignment.
- Notification to the assigned staff nurse reuses the existing `NotificationService` (only meaningful when they have a login).

### A2.3 Frontend
- Incharge "My Ward Patients": list + an **Assign Nurse** action (dropdown of that ward's staff nurses) shown when unassigned or to reassign; hidden when Separate Login is OFF (nothing to assign to).
- Existing Staff-Nurse "My Patients" continues to show only rows assigned to them.

---

## A3 — Separate Nurse Login + "Performed By"

### A3.1 Model
- With Separate Login **OFF**, staff nurses have no `User`, so a care record's caregiver must reference the **`NurseProfile`**, not a `User`.
- Add `performed_by_nurse_id BIGINT NULL` (FK → `nurse_profiles.id`) to the nursing write tables: `vitals_records`, `nursing_notes`, `medication_administrations`, `sugar_chart_entries`, `surgery_forms` (and any other nurse-authored record). Semantics:
  - `performed_by_nurse_id` = **who did the care** (the credited nurse).
  - existing `nurse_user_id` / created-by = **who keyed it in** (the incharge when OFF; the nurse themselves when ON).
- Reads/print (e.g., nurse name on forms) prefer `performed_by_nurse_id` → NurseProfile name; fall back to the keying user.

### A3.2 Resolver
- Shared `PerformingNurseResolver.resolve(Long requestedNurseProfileId)`:
  - Separate Login **ON** → the credited nurse = the logged-in nurse's `NurseProfile` (ignore any requested id).
  - Separate Login **OFF** → require `requestedNurseProfileId` (the "Performed By" selection); validate it is an active nurse in the relevant ward/hospital; else `400`.
- Each nursing write DTO gains optional `performedByNurseId`; each write service calls the resolver and sets `performed_by_nurse_id`. When ON, the field is ignored.

### A3.3 Frontend
- Nursing-entry forms (vitals, notes, medication, sugar, surgery forms, I/O) show a **"Performed By Nurse"** dropdown **only when** Separate Login is OFF, populated from the ward's active staff nurses. When ON, no dropdown (logged-in nurse is recorded automatically).
- A small `GET /hospital/settings/separate-nurse-login` (or include in the existing settings/login payload) tells the frontend which mode is active.

---

## Audit logging (cross-cutting)
Every state change writes `AuditLogService.logAction(...)`: `NURSE_PROMOTED`, `NURSE_DEMOTED`, `NURSE_ACTIVATED/DEACTIVATED`, `WARD_INCHARGE_SET` (prev/new), `PATIENT_NURSE_ASSIGNED` (prev/new nurse), `SEPARATE_NURSE_LOGIN_CHANGED`. Fields captured: entity, action, previous/new value, performed-by email, hospitalId, timestamp, reason (best-effort, wrapped in try/catch per existing pattern).

## Testing & verification
- JUnit (Mockito): `NurseInchargeGuard` (admin vs incharge vs cross-ward 403); promote/demote (demote blocked while holding wards); `PatientAssignmentService` (OFF → no assignment; ON+1 → auto; ON+>1 → none; ON+0 → none; incharge excluded from count); block-incharge-less admission; `PerformingNurseResolver` (ON ignores id; OFF requires+validates id); tenant isolation on new endpoints.
- Each sub-phase: `cd backend && mvn -o test` and `cd frontend && npx vite build --mode development`.
- Do not break existing OPD/IPD/pharmacy/clinic/OT flows; the existing Staff-Nurse dashboard keeps working.

## Milestones (for the plan)
- **A1** role + migrations + guard + ward-incharge + admin nurse management + block admission + incharge portal shell.
- **A2** patient assignment flow + incharge patient list/assign + replace auto-assign.
- **A3** separate-login setting + `performed_by_nurse_id` + resolver + "Performed By" UI.

Standing constraints: never commit/push unless explicitly asked; clean up any curl test data; keep controllers under `/hospital/**`; NURSING-gated.
