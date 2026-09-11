# Nurse Module — Document 3: Implementation Plan

**Phase 1 · Small Hospital** · Date: 2026-07-06 · Status: Draft for approval
Companion: Document 1 (Business & Workflow), Document 2 (Technical Design).

> This is the sequencing + testing plan. **No code yet.** Implementation
> (Document 4) proceeds one milestone at a time, each independently testable,
> each ending green on `mvn test` (backend) / `npx tsc --noEmit` +
> `npx vite build` (frontend) before commit. Follow the repo rules: never push,
> stage only the milestone's files, end commits with the required
> `Co-Authored-By` trailer.

---

## PART 20 — Implementation Order (milestones)

Each milestone is a vertical slice that compiles, passes tests, and can be
demoed on its own. Ordering guarantees the app keeps working at every commit
(Clinic/Pharmacy never break; nurse features light up incrementally).

### M0 — Foundations: role, module, security wiring
**Goal:** `NURSE` role + `NURSING` module exist and are gated correctly; no
features yet.
- SecurityConfig: add `NURSE` to `/hospital/**` and `/ws/**` role lists.
- `User.generateIds`: `NRS` prefix branch.
- `PlansTab.jsx` `AVAILABLE_MODULES += 'NURSING'`; HOSPITAL plan module sets.
- `App.jsx`: `/hospital/nurse` route stub + `LandingRedirect` NURSE case.
**Testable:** a hand-crafted NURSE JWT reaches `/hospital/**` (no 401 at gate);
clinic/pharmacy plans cannot receive `NURSING`; a NURSE lands on a stub page.
**Depends on:** nothing.

### M1 — Nurse profile & authentication (staff CRUD)
**Goal:** admin creates nurses; nurses log in.
- `NurseProfile` entity + repo + migration (`ensureNurseProfilesTable`) +
  schema-full.
- `NurseService` (clone `ReceptionistService`), `NurseController`
  (`/hospital/nurses`), DTOs.
- Admin "Nurses" tab (clone Receptionists table + modals).
**Testable:** create nurse → login → `/auth/me` returns role NURSE; admin lists
nurses; reset password works.
**Depends on:** M0.

### M2 — Patient (admission) assignment
**Goal:** admin assigns a nurse to an active admission; discharge auto-closes.
- `PatientNurseAssignment` entity/repo/migration/schema.
- `NurseAssignmentService` (single-active invariant) + `NurseAssignmentController`.
- Hook in `IpdAdmissionService.confirmDischarge` to close assignments.
- Admin "Nurse Assignments" tab + `AssignNurseModal`.
**Testable:** assign/reassign/unassign; discharge closes the row; history
retained; cannot assign a discharged admission.
**Depends on:** M1.

### M3 — Nurse dashboard + My Patients
**Goal:** nurse sees assigned patients and aggregate stats.
- `NurseWorkspaceController` (`/hospital/nurse/dashboard`, `/my-patients`,
  `/patients/{admissionId}`), `NurseAccessGuard`.
- `NurseDashboard.jsx`, `MyPatientsView.jsx`, `NursePatientDetail.jsx` (read-only
  clinical sections), `nurseService.js`.
**Testable:** nurse sees only assigned admissions; unassigned nurse sees empty
state; 403 opening a non-assigned admission.
**Depends on:** M2.

### M4 — Vitals (new module)
**Goal:** record + view vitals timeline.
- `VitalsRecord` entity/repo/migration/schema; `VitalsService`; `VitalsController`.
- `VitalsPanel` + `VitalsForm` + `VitalsTimeline` + abnormal badges.
**Testable:** record vitals (range validation), timeline builds, abnormal
flagged, edit-window enforced, cross-tenant blocked.
**Depends on:** M3.

### M5 — Nursing notes
**Goal:** write/read notes with edit window.
- `NursingNote` entity/repo/migration/schema; `NursingNoteService`;
  `NursingNoteController`.
- `NotesPanel` + `NoteComposer` (CharCountInput) + timeline.
**Testable:** create/edit/soft-delete within window; blocked after window/by
non-author; audit entries written.
**Depends on:** M3.

### M6 — Medication administration
**Goal:** record administration against doctor's ACTIVE prescriptions.
- `MedicationAdministration` entity/repo/migration/schema; service; controller.
- `MedicationPanel` + `MedicationStatusRow` + per-medicine `HistoryDrawer`.
**Testable:** ACTIVE prescriptions listed; status recorded with time rules;
correction via new record; STOPPED prescriptions excluded; audit written.
**Depends on:** M3 (and existing prescriptions data).

### M7 — Manual tasks
**Goal:** admin assigns tasks; nurse completes.
- `ManualTask` entity/repo/migration/schema; service (transition guard);
  controller.
- Admin task creator; nurse `MyTasksView` + `TaskCard`.
**Testable:** admin creates/assigns/cancels; nurse progresses/completes with
remark; illegal transitions rejected; my-tasks scoped.
**Depends on:** M1.

### M8 — In-app notifications
**Goal:** notify nurses on assignment / task / prescription change.
- `Notification` entity/repo/migration/schema; `NotificationService` (best-effort);
  `NotificationController`.
- Emit hooks: M2 assignment, M7 task assign, prescription add/stop.
- `NotificationBell` + `useNotifications`.
**Testable:** triggers create notifications; unread-count/badge; mark-read /
mark-all; a failed notification never breaks the triggering write.
**Depends on:** M2, M7 (hooks); can land after them.

### M9 — Hardening & full regression
**Goal:** production polish.
- Empty/loading states everywhere; consistent toasts; audit coverage check;
  permission matrix sweep; `schema-full.sql` reconciliation; docs update.
**Testable:** full backend suite + frontend build green; Clinic/Pharmacy
regression pass; permission matrix verified end-to-end.
**Depends on:** M1–M8.

**Dependency graph:**
```
M0 → M1 → M2 → M3 → {M4, M5, M6}
      └──────────→ M7
M2,M7 ───────────→ M8
all ─────────────→ M9
```

---

## PART 21 — Test Plan

Testing mirrors the existing suite: JUnit 5 + Mockito + AssertJ services;
`@WebMvcTest` + nested `@EnableMethodSecurity` config (handoff gotcha §5) for
controllers; frontend via `npx tsc --noEmit` + `npx vite build` + targeted
live checks (no E2E runner). Every milestone ships with its own tests.

### 21.1 Functional tests
- Nurse CRUD: create/list/get/update/delete/reset; `customId` `NRS####`.
- Assignment: assign, reassign (closes prior active), unassign, discharge
  auto-close, history retained.
- Dashboard/My Patients: aggregates correct; only assigned admissions.
- Vitals: create, list timeline, edit within window, range validation.
- Notes: create, edit, soft-delete, window enforcement.
- MAR: list ACTIVE prescriptions, record each status, exclude STOPPED,
  correction-by-append.
- Tasks: create/assign/cancel; progress/complete; transition guard.
- Notifications: trigger→row, unread-count, mark-read/all.

### 21.2 Permission tests (per endpoint, positive + negative)
- NURSE can hit workspace/vitals/notes/MAR/my-tasks/notifications; **cannot** hit
  staff CRUD, assignment write, billing write, IPD admit/discharge, settings,
  pharmacy, platform.
- ADMIN can do staff CRUD + assignment + task create; **cannot** record vitals
  as if a nurse where role-restricted (read-only where specified).
- DOCTOR/RECEPTIONIST/PHARMACIST get exactly the matrix rows in Doc 1 Part 16.
- SUPER_ADMIN has no nurse-tenant access (no hospitalId scope).

### 21.3 Security tests
- No-token / expired-token → 401 at gate.
- NURSE JWT without `NURSING` module → `@RequireModule` 403.
- NURSE accessing an admission with no active assignment → 403 (`NurseAccessGuard`).
- Role-escalation attempts (nurse calling admin routes) → 403.
- Notification/vitals/notes referencing another hospital's ids → "not found".

### 21.4 Tenant isolation tests
- Nurse in Hospital A cannot read/write any Hospital B admission, vitals, note,
  MAR, task, or notification (all queries `hospital_id`-scoped).
- Assignment/vitals/MAR creation validates every FK target is same-hospital.

### 21.5 Hospital-flavor isolation tests (critical, non-regression)
- CLINIC tenant: `NURSING` module cannot be enabled; no nurse tab/route; a
  crafted NURSE user under a CLINIC tenant is rejected/has no surface.
- PHARMACY tenant: same — nurse entirely absent; `/pharmacy/**` unaffected.
- Confirm nurse controllers expose **only** `/hospital/**` (no `/clinic`,
  `/pharmacy` mapping).

### 21.6 Validation tests
- Vitals out-of-range rejected; future `recorded_at` rejected; empty reading
  rejected.
- Note empty / >2000 chars rejected; edit after window rejected.
- MAR: non-ACTIVE prescription rejected; bad status rejected; GIVEN without time
  rejected.
- Task: empty title rejected; assignee not-a-nurse rejected; illegal transition
  rejected.
- Assignment: discharged admission rejected; non-nurse target rejected.

### 21.7 API tests
- Every endpoint: success shape, error envelope (`badRequest(message)`),
  status codes, DTO field coverage, pagination on list endpoints.

### 21.8 UI tests
- Route guard redirects unauthenticated → `/login/hospital`.
- Nurse sidebar shows only nurse tabs; admin sees Nursing group only when
  HOSPITAL + NURSING.
- Read-only clinical sections render no edit controls for NURSE.
- Empty/loading states for dashboard, My Patients, vitals, notes, tasks,
  notifications.
- Notification bell badge reflects unread-count.

### 21.9 Regression tests (run every milestone)
- Full backend `mvn test` stays green (baseline 115+).
- Frontend `npx tsc --noEmit` + `npx vite build` succeed.
- Smoke: OPD create + consultation + billing + hospital pharmacy dispense still
  work (nurse work must not perturb the busy consultation path).
- Clinic login + OPD flow; Pharmacy ERP sale flow — unchanged.

### 21.10 Test data hygiene
- Each milestone's live verification creates then deletes its test rows
  (API delete where available, SQL otherwise), per repo convention.

---

## Risks & mitigations
| Risk | Mitigation |
|---|---|
| Missing a role in one of ~120 `@PreAuthorize` sites | Permission-matrix sweep in M9; negative permission tests per endpoint |
| ddl-auto vs migration race (handoff §6.1) | All 7 tables created wholesale (no add-column-to-populated) — race N/A |
| `open-in-view=false` lazy proxies in composite reads | Use JOIN FETCH / projection DTOs in `NursePatientDetailDTO` reads |
| Nurse features leaking into Clinic/Pharmacy | Controllers map only `/hospital/**`; flavor-isolation tests §21.5 |
| Notification failure breaking a write | Best-effort try/catch (audit-log pattern) |
| Monolith dashboard debt | Nurse UI decomposed per-view (pharmacy-ERP style), not added to the 5k-line admin file |

---

## Definition of Done (Phase 1)
- All 9 milestones merged on branch; full backend suite + frontend build green.
- Permission matrix (Doc 1 Part 16) verified by tests.
- Clinic + Pharmacy regression pass; OPD/IPD/billing smoke pass.
- `setup/schema-full.sql` reconciled with all 7 new tables + migration runner.
- All three spec docs current; Document 4 (implementation log) records what
  shipped per milestone.

---

*End of Document 3. On approval, implementation proceeds milestone by milestone
(Document 4), starting with M0.*
