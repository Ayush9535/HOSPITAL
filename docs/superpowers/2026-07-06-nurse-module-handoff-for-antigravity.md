# Nurse Module — Cold-Start Handoff for Antigravity — 2026-07-06

Read this top to bottom before touching anything. It contains everything needed
to finish the Phase 1 Nurse Module without prior context: the design, what is
already done and verified, the exact remaining work (finish M6 → M9), the
file-by-file recipe used for every milestone, and how to verify. Follow the
existing patterns exactly — the module is deliberately a clone of the
Receptionist/Pharmacist + IPD patterns.

**Branch: `nurse-module`** (created off `pharmacy`). **Nothing is committed** —
the owner commits/pushes themselves. Never push, never touch `main`.

---

## 0. Authoritative source documents (read these too)

Everything below is derived from three specs already written and reviewed. When
in doubt, they are the source of truth:

- `docs/superpowers/specs/2026-07-06-nurse-module-1-business-workflow.md`
  (workflow, roles, features, permission matrix, future compatibility)
- `docs/superpowers/specs/2026-07-06-nurse-module-2-technical-design.md`
  (**database schema for ALL 7 entities**, ER diagram, **every API endpoint**,
  frontend + backend architecture) — Parts 5, 17, 18, 19 are the ones you need.
- `docs/superpowers/specs/2026-07-06-nurse-module-3-implementation-plan.md`
  (milestones M0–M9, full test plan)

Also useful for environment/conventions/gotchas (older but still accurate):
`docs/superpowers/2026-07-04-antigravity-handoff-3.md`.

---

## 1. The 4 locked design decisions (do not revisit)

| # | Decision | Choice |
|---|---|---|
| 1 | How Nurse is gated | New `NURSING` plan module + `hospitalType === HOSPITAL`. Clinic/Pharmacy never get it. |
| 2 | Assignment anchor | Nurse is assigned to an **IPD admission** (auto-closes at discharge). |
| 3 | MAR medicine source | Existing `prescriptions` rows (doctor's ACTIVE Rx). Nurse only records what happened. |
| 4 | Vitals model | New IPD-scoped `VitalsRecord`; the OPD vitals snapshot (`opd.bp/…`) is untouched. |

Nurse is **HOSPITAL-tenant only**. Every nurse controller is mapped **only**
under `/hospital/**` (never `/clinic/**` or `/pharmacy/**`). This is the
isolation guarantee — keep it.

---

## 2. Environment

- OS Windows; shells: Git Bash (POSIX) + PowerShell. Repo root: `e:\Projects\HOSPITAL`.
- Backend: Spring Boot / Java 17 / Maven, from `backend/`.
  - Build+test: `cd backend && mvn -o test` (offline; ~1–2 min).
  - Compile only: `mvn -o -q clean compile`.
  - Run: `mvn spring-boot:run` (port 8080). No hot reload — restart JVM for Java changes.
- Frontend: React 18 / Vite, from `frontend/`.
  - Verify: `npx vite build --mode development` (there is **no** frontend test runner).
  - Run: `npm run dev` (port 5173).
- DB: MySQL 8. Creds + JWT secret are in `backend/.env`. `spring.jpa.hibernate.ddl-auto=update`
  and `spring.jpa.open-in-view=false` (both matter — see Gotchas).
- For a test JWT and Playwright recipe, see handoff #3 §7 (unchanged).

---

## 3. Hard rules

1. **Never push. Never touch `main`.** Stay on `nurse-module`. Do not commit
   unless the owner asks.
2. **Don't break Clinic or Pharmacy, or OPD/consultation/billing.** Every change
   is additive. Nurse controllers map only `/hospital/**`. `NURSING` is only in
   the HOSPITAL plan module list.
3. **Stage only the files a task touches** if the owner ever asks you to commit
   (`git add <paths>`, never `git add -A`).
4. Follow the superpowers workflow already in motion: each milestone is a
   vertical slice that compiles, passes `mvn -o test` (all green), and builds on
   the frontend, before you move to the next.
5. **Verify with evidence.** After each milestone run `mvn -o test` and
   `npx vite build` and paste the pass counts. Do not claim done without them.

---

## 4. Architecture cheat-sheet (how this codebase works)

Multi-tenant SaaS HMS. `users` is the single credential store; `role` is a free
string. Tenant identity travels in JWT claims (`userId`, `role`, `hospitalId`,
`modules`, `branchId`). `SecurityContextHelper` reads them:
`getCurrentHospitalId()`, `getCurrentUserId()`, `getCurrentUserRole()`,
`getCurrentUserEmail()`. Every service scopes queries by `hospital_id`.

Three authorization layers: (a) URL namespace in `SecurityConfig`
(`/hospital/**` allows the hospital roles incl. `NURSE`), (b) method-level
`@PreAuthorize("hasRole('…')")`, (c) module gating `@RequireModule("NURSING")`
enforced by `ModuleAccessAspect` against the JWT `modules` claim.

Nurse-specific access rule: `NurseAccessGuard.assertAssigned(admissionId)`
throws `AccessDeniedException` (403) unless the current nurse has an **active**
assignment to that admission. Reuse it for every nurse write.

`AuditLogService.logAction(action, details, performedByEmail, hospitalId,
entityType, entityId, reason)` — 7 args, wrap calls in try/catch (best-effort).

Real-time refresh: `webSocketHandler.broadcast(hospitalId, "{\"type\":\"REFRESH_DATA\"}")`.

---

## 5. What is DONE and VERIFIED (M0–M5)

All of the following compiles, and the backend suite is green (was **158/158**
at the end of M5; each nurse service has its own unit test). Frontend builds.

### M0 — role/module/security wiring
- `SecurityConfig.java`: `NURSE` added to `/ws/**`; the `/hospital/**` matcher
  **split out** so `NURSE` is authorized on `/hospital/**` only (not clinic/pharmacy).
- `User.java` `generateIds()`: `NURSE` excluded from random-id branch (sequential
  `NRS` set by `NurseService`, like Receptionist).
- `PlansTab.jsx`: `NURSING` added to `AVAILABLE_MODULES` (HOSPITAL-only by construction).
- `App.jsx`: `/hospital/nurse` route (`allowedRoles={['NURSE']}`) + `NURSE` case
  in `LandingRedirect`.

### M1 — nurse staff CRUD (cloned from Receptionist)
- `entity/NurseProfile.java`, `repository/NurseProfileRepository.java`
- `UserRepository.java`: added `searchNurses(...)` + `findMaxNurseSequence()`
- `service/hospital/NurseService.java`, `controller/hospital/NurseController.java`
  (`/hospital/nurses` ONLY, `@RequireModule("NURSING")`, `@PreAuthorize("hasRole('HOSPITAL_ADMIN')")`)
- Migration `ensureNurseProfilesTable()` + `setup/schema-full.sql`
- `test/.../NurseServiceTest.java` (7 tests)
- Frontend: nurse APIs in `hospitalService.js`; **Nurses** admin tab wired into
  `HospitalAdminDashboard.jsx` (state, loadData branch, delete/reset handlers,
  tab entry `requiredModule: 'NURSING'`, `group-nursing` sidebar group, add-label,
  DataTable gating, render block, `NursesTable` component, modal fields).

### M2 — patient (admission) assignment
- `entity/PatientNurseAssignment.java`, `repository/PatientNurseAssignmentRepository.java`
- `dto/AssignNurseRequest.java`, `dto/NurseAssignmentDTO.java`
- `service/hospital/NurseAssignmentService.java` (single-active invariant,
  assign/reassign/unassign/overview/history/closeActiveAssignmentsForAdmission)
- `controller/hospital/NurseAssignmentController.java` (`/hospital/nurse-assignments`)
- **Discharge hook**: `IpdAdmissionService.confirmDischarge` closes the active
  assignment (best-effort try/catch) after setting status DISCHARGED. A field
  `patientNurseAssignmentRepository` was added to `IpdAdmissionService`.
- Migration `ensurePatientNurseAssignmentsTable()` + schema
- `test/.../NurseAssignmentServiceTest.java` (6 tests)
- Frontend: assignment APIs in `hospitalService.js`; **Nurse Assignments** admin
  tab (`group-nursing` extended to `['nurses','nurse-assignments']`),
  `NurseAssignmentsTable` + `AssignNurseModal` components + handlers/state.

### M3 — nurse workspace (dashboard / my patients / patient detail)
- `security/NurseAccessGuard.java` (reused everywhere after)
- `dto/MyPatientDTO.java`, `dto/NurseDashboardDTO.java`, `dto/NursePatientDetailDTO.java`
- `service/hospital/NurseWorkspaceService.java`, `controller/hospital/NurseWorkspaceController.java`
  (`/hospital/nurse/{dashboard,my-patients,patients/{admissionId}}`, `@PreAuthorize("hasRole('NURSE')")`)
- `test/.../NurseWorkspaceServiceTest.java` (4 tests)
- Frontend: `services/nurseService.js`; `NurseDashboard.jsx` rewritten as a
  Sidebar+Navbar shell (mirrors `PharmacyDashboard.jsx`); views in
  `pages/hospital/nurse/`: `NurseOverviewView`, `MyPatientsView`, `NursePatientDetail`.

### M4 — vitals
- `entity/VitalsRecord.java`, `repository/VitalsRecordRepository.java`,
  `dto/VitalsRequest.java`, `service/hospital/VitalsService.java`,
  `controller/hospital/VitalsController.java` (`/hospital/nurse/vitals`)
- Migration `ensureVitalsRecordsTable()` + schema; `test/.../VitalsServiceTest.java` (7)
- Frontend: vitals calls in `nurseService.js`; `nurse/VitalsPanel.jsx`;
  **Vitals** tab in `NursePatientDetail`.

### M5 — nursing notes
- `entity/NursingNote.java`, `repository/NursingNoteRepository.java`,
  `dto/NursingNoteRequest.java`, `service/hospital/NursingNoteService.java`,
  `controller/hospital/NursingNoteController.java` (`/hospital/nurse/notes`)
- Migration `ensureNursingNotesTable()` + schema; `test/.../NursingNoteServiceTest.java` (7)
- Frontend: note calls in `nurseService.js`; `nurse/NotesPanel.jsx`;
  **Notes** tab in `NursePatientDetail`.

---

## 6. THE UNIVERSAL RECIPE (follow this for every remaining module)

Each nurse clinical module was built as these 8–10 steps. Copy an existing
milestone (Vitals M4 or Notes M5 are the cleanest templates) and adapt:

1. **Entity** `entity/Xxx.java`: Lombok `@Data @NoArgsConstructor @AllArgsConstructor`,
   `@Table(name="…")`, IDENTITY id, `publicId` (UUID, unique) generated in
   `@PrePersist`, `hospital_id` NOT NULL, `is_active` boolean, `@CreationTimestamp created_at`
   (+ `@UpdateTimestamp updated_at` if editable). snake_case `@Column(name=…)`.
2. **Repository** `repository/XxxRepository.java`: `extends JpaRepository<Xxx,Long>`,
   derived queries (`findByPublicId`, `findByIpdAdmissionIdAndIsActiveTrueOrderBy…`).
3. **Request DTO** `dto/XxxRequest.java`: Lombok `@Data`.
4. **Service** `service/hospital/XxxService.java`: `@Autowired` fields
   (`SecurityContextHelper securityHelper`, `NurseAccessGuard nurseAccessGuard`,
   `AuditLogService auditLogService`, `IpdAdmissionRepository`, your repo).
   Pattern: `requireHospitalId()` → load admission via `requireAdmission(id, hospitalId)`
   (checks same-hospital) → `nurseAccessGuard.assertAssigned(admissionId)` for nurse
   writes → validate → save → `audit(...)`. Reads: nurse `assertAssigned`, doctor/admin
   just hospital scope (branch on `securityHelper.getCurrentUserRole()`). Throw
   `IllegalArgumentException` (→400) for validation, `UnauthorizedException` for cross-tenant.
   `@Transactional` on writes.
5. **Controller** `controller/hospital/XxxController.java`:
   `@RequestMapping("/hospital/…")` **only** (never clinic/pharmacy),
   `@RequireModule("NURSING")` at class level, method-level `@PreAuthorize`
   (`hasRole('NURSE')` for writes; `hasAnyRole('NURSE','DOCTOR','HOSPITAL_ADMIN')`
   for reads; admin-only actions `hasRole('HOSPITAL_ADMIN')`). Return `ResponseEntity<?>`.
6. **Migration** in `config/DatabaseMigrationRunner.java`: add
   `ensureXxxTable()` (copy `ensureVitalsRecordsTable`), and append the call to
   `runMigrations()`.
7. **schema-full.sql**: append the matching `CREATE TABLE` (copy an existing nurse
   block; utf8mb4, FK to `hospitals(id) ON DELETE CASCADE`).
8. **Unit test** `test/.../XxxServiceTest.java`: `@ExtendWith(MockitoExtension.class)`,
   `@Mock` repos + guard + securityHelper + auditLogService, `@InjectMocks service`.
   Cover: happy path, validation reject, not-assigned 403, cross-hospital denial.
9. **Frontend service**: add calls to `services/nurseService.js` (nurse-facing) or
   `services/hospitalService.js` (admin-facing), matching the existing axios-wrapper style.
10. **Frontend UI**: nurse-facing → a new panel in `pages/hospital/nurse/` wired as
    a tab in `NursePatientDetail.jsx` or a sidebar tab in `NurseDashboard.jsx`.
    Admin-facing → a tab in `HospitalAdminDashboard.jsx` (see M1/M2 for the exact
    wiring points).
11. **Verify**: `cd backend && mvn -o test` (all green) + `cd frontend && npx vite build`.

---

## 7. REMAINING WORK

### 7A. FINISH M6 — Medication Administration (backend DONE + VERIFIED; frontend NOT done)

The backend is **written and verified green: `mvn -o test` = 163/163 tests pass,
BUILD SUCCESS** (158 from M0–M5 + 5 new MAR tests). Only the **frontend** for M6
remains. (Re-run `mvn -o test` once to reconfirm before you start if you like.)

Files already created and passing:
- `entity/MedicationAdministration.java` (FK-by-value `prescription_id`,
  `status` GIVEN/SKIPPED/DELAYED/REFUSED/NOT_AVAILABLE, correction-by-append —
  no destructive edit)
- `repository/MedicationAdministrationRepository.java`
- `dto/MedicationAdminRequest.java`
- `service/hospital/MedicationAdministrationService.java` — key logic: a
  prescription is valid only if it is in the admission's ACTIVE list
  (`prescriptionRepository.findByIpdAdmissionIdAndStatus(admissionId,"ACTIVE")`);
  `administeredTime` defaults to now for GIVEN/DELAYED; assignment-gated.
  (Note: `Prescription` has **no** direct `ipdAdmissionId` field — it links via
  `medicalRecordId → MedicalRecord.ipdAdmissionId`. That's why validation uses
  the ACTIVE-list membership check, not a field compare.)
- `controller/hospital/MedicationAdminController.java`
  (`/hospital/nurse/medication`: GET `/admission/{id}/prescriptions`,
  POST ``, GET `/admission/{id}`)
- Migration `ensureMedicationAdministrationsTable()` (already appended to `runMigrations()`)
- `setup/schema-full.sql` (medication_administrations block already appended)
- `test/.../MedicationAdministrationServiceTest.java` (5 tests)

**Remaining M6 work (frontend):**
1. Add to `services/nurseService.js`:
   - `getMedicationPrescriptions(admissionId)` → GET `/hospital/nurse/medication/admission/${admissionId}/prescriptions`
   - `recordMedication(payload)` → POST `/hospital/nurse/medication`
   - `getMedicationHistory(admissionId)` → GET `/hospital/nurse/medication/admission/${admissionId}`
2. Create `pages/hospital/nurse/MedicationPanel.jsx` (clone `VitalsPanel.jsx`
   structure): list the ACTIVE prescriptions (medicine/dosage/frequency/route);
   for each, a status selector (GIVEN/SKIPPED/DELAYED/REFUSED/NOT_AVAILABLE) +
   time (default now for GIVEN/DELAYED) + remarks → POST. Below, a history list
   from `getMedicationHistory` (status + medicine + time + who). Corrections =
   record again (no edit).
3. Add a **Medication** tab to `pages/hospital/nurse/NursePatientDetail.jsx`
   (add `{ id:'medication', label:'Medication' }` to `tabs`, import
   `MedicationPanel`, add `{tab === 'medication' && <MedicationPanel admissionId={admissionId} />}`).
4. Verify: `npx vite build`.

### 7B. M7 — Manual Tasks (spec Doc 2 §5.6 + Doc 1 Part 14)

Admin creates a task and assigns one nurse; nurse completes it. No engine.

**Backend (universal recipe):**
- `entity/ManualTask.java`: `id, publicId, hospitalId, title (VARCHAR 150, NOT NULL),
  description (TEXT), assignedToNurseUserId (NOT NULL), assignedByUserId (NOT NULL),
  ipdAdmissionId (nullable), priority (VARCHAR 10, default 'MEDIUM': LOW/MEDIUM/HIGH),
  status (VARCHAR 15, default 'PENDING': PENDING/IN_PROGRESS/COMPLETED/CANCELLED),
  dueDate (DATETIME null), completedAt (DATETIME null), completionRemarks (VARCHAR 500),
  isActive, createdAt, updatedAt`.
- `repository/ManualTaskRepository.java`: `findByPublicId`,
  `findByAssignedToNurseUserIdAndIsActiveTrueOrderByCreatedAtDesc`,
  `findByHospitalIdAndIsActiveTrueOrderByCreatedAtDesc`.
- `dto/CreateTaskRequest.java` (title, description, assignedToNurseUserId,
  ipdAdmissionId?, priority, dueDate) and `dto/UpdateTaskStatusRequest.java`
  (status, completionRemarks?).
- `service/hospital/ManualTaskService.java`:
  - `createTask(req)` — HOSPITAL_ADMIN: validate title non-empty; assignee must
    be an **active NURSE in the same hospital** (`userRepository.findById` →
    role NURSE + isActive + hospitalId match); set assignedByUserId =
    `securityHelper.getCurrentUserId()`; save; audit; **(M8) notify the nurse**.
  - `listForHospital()` — admin, all tasks for hospital.
  - `listMyTasks()` — nurse, `assignedToNurseUserId == getCurrentUserId()`.
  - `updateStatus(publicId, req)` — transition guard: NURSE may only move
    PENDING→IN_PROGRESS→COMPLETED (and only their own tasks); ADMIN may CANCEL.
    On COMPLETED set `completedAt=now` + `completionRemarks`. Reject illegal
    transitions with `IllegalArgumentException`.
- `controller/hospital/ManualTaskController.java` (`/hospital/nurse-tasks`,
  `@RequireModule("NURSING")`): POST (`hasRole('HOSPITAL_ADMIN')`),
  GET `` (admin, all), GET `/my` (`hasRole('NURSE')`),
  PUT `/{publicId}/status` (`hasAnyRole('HOSPITAL_ADMIN','NURSE')` — service enforces who-can-do-what).
- Migration `ensureManualTasksTable()` + schema-full block.
- `test/.../ManualTaskServiceTest.java`: create validates assignee is nurse;
  reject non-nurse assignee; nurse completes own task; illegal transition rejected;
  cross-hospital denial.

**Frontend:**
- Admin side (in `HospitalAdminDashboard.jsx`, same wiring points as M1/M2): a
  new **Nurse Tasks** tab (add to `group-nursing` tabIds, tab entry
  `requiredModule: 'NURSING'`, state, loadData branch calling a
  `hospitalService.getNurseTasks()`, a create-task modal, a tasks table, and a
  cancel action). Add task APIs to `services/hospitalService.js`
  (`getNurseTasks`, `createNurseTask`, `updateNurseTaskStatus`).
- Nurse side: add a **My Tasks** sidebar tab in `NurseDashboard.jsx`
  (`sidebarTabs` += `{ id:'my-tasks', label:'My Tasks' }`), a new
  `pages/hospital/nurse/MyTasksView.jsx` (list own tasks with priority/due/status,
  a "Start"/"Complete" button that calls `updateTaskStatus`), and task calls in
  `services/nurseService.js` (`getMyTasks`, `updateTaskStatus`).
- Verify.

### 7C. M8 — In-app Notifications (spec Doc 2 §5.7 + Doc 1 Part 15)

In-app only. No email/SMS/WhatsApp.

**Backend:**
- `entity/Notification.java`: `id, publicId, hospitalId, recipientUserId (NOT NULL),
  type (VARCHAR 40: ASSIGNMENT/TASK/PRESCRIPTION_CHANGE), title (VARCHAR 150),
  message (VARCHAR 500), referenceType (VARCHAR 40), referenceId (BIGINT),
  isRead (default 0), readAt, createdAt`. (No is_active needed; no updated_at.)
- `repository/NotificationRepository.java`:
  `findByRecipientUserIdOrderByCreatedAtDesc(Long, Pageable)`,
  `countByRecipientUserIdAndIsReadFalse(Long)`, `findByPublicId`.
- `service/hospital/NotificationService.java`:
  - `create(recipientUserId, hospitalId, type, title, message, referenceType, referenceId)`
    — **best-effort**: wrap the whole body in try/catch and log on failure; a
    failed notification must NEVER break the triggering write.
  - `listMine(pageable)`, `unreadCount()`, `markRead(publicId)` (own only),
    `markAllRead()` (own only). Scope everything to `getCurrentUserId()`.
- `controller/hospital/NotificationController.java` (`/hospital/notifications`,
  `@RequireModule("NURSING")` — or leave ungated since it's own-scoped; prefer
  gating to stay consistent). `@PreAuthorize("hasAnyRole('NURSE','DOCTOR','RECEPTIONIST','HOSPITAL_ADMIN','PHARMACIST')")`:
  GET `` (own list, paged), GET `/unread-count`, PUT `/{publicId}/read`, PUT `/read-all`.
- Migration `ensureNotificationsTable()` + schema-full block.
- **Emit hooks (best-effort, all wrapped so they never throw into the caller):**
  - `NurseAssignmentService.assignNurse(...)` → after save, `notificationService.create(
    nurseUserId, hospitalId, "ASSIGNMENT", "New patient assigned", "<patient/IPD>",
    "IPD", admissionId)`. Inject `NotificationService` (no cycle: NotificationService
    does not depend on NurseAssignmentService).
  - `ManualTaskService.createTask(...)` → notify assignee, type "TASK".
  - Prescription add/stop for an assigned admission → notify the assigned nurse,
    type "PRESCRIPTION_CHANGE". Find the add/stop paths: IPD prescriptions are
    added in `IpdAdmissionService` (`addIpdPrescription`/similar) and stopped via
    `PUT /hospital/ipd/prescriptions/{id}/stop`. In those methods, look up the
    active assignment (`patientNurseAssignmentRepository.findByIpdAdmissionIdAndIsActiveTrue`)
    and notify that nurse. Best-effort try/catch. If wiring this cleanly is risky,
    it is acceptable to ship ASSIGNMENT + TASK triggers in Phase 1 and note
    PRESCRIPTION_CHANGE as a follow-up — but prefer to include it.
- `test/.../NotificationServiceTest.java`: create sets fields + isRead false;
  markRead sets readAt; markRead/markAll only affect own; a thrown repo error in
  `create` is swallowed (does not propagate).

**Frontend:**
- `services/notificationService.js` (or add to `nurseService.js`):
  `list`, `unreadCount`, `markRead(publicId)`, `markAllRead`.
- `components/NotificationBell.jsx`: a bell with an unread badge + dropdown of
  recent notifications, mark-one / mark-all-read. Add it to the `NurseDashboard.jsx`
  header area (next to the Navbar) or pass into `Navbar`. A `hooks/useNotifications.js`
  that polls `unreadCount` every ~30–60s (Phase 1 may poll; the WebSocket
  `REFRESH_DATA` broadcast can also trigger a refetch).
- Verify.

### 7D. M9 — Hardening + live verification (spec Doc 3 Part 21)

- **Live end-to-end run (never done yet — do it):** start backend + DB +
  frontend. As HOSPITAL_ADMIN (a hospital that has the `NURSING` module):
  create a nurse; admit a patient (IPD); assign the nurse; log in as the nurse;
  confirm My Patients shows only the assigned patient; record vitals (try an
  out-of-range value → rejected) and a note (edit/delete it); record medication
  against an ACTIVE prescription; as admin create a task → nurse completes it;
  confirm notifications appear; discharge the patient → confirm the assignment
  auto-closes and the patient drops off My Patients. Delete all test data after.
- **Isolation regression:** confirm a CLINIC and a PHARMACY tenant show no nurse
  tab/route and cannot be granted `NURSING`; confirm OPD/consultation/billing and
  the Pharmacy ERP still work.
- **Permission sweep:** verify the matrix in Doc 1 Part 16 (nurse cannot hit
  staff CRUD, assignment writes, billing writes, IPD admit/discharge, settings,
  pharmacy, platform). Consider adding `@WebMvcTest` controller tests
  (use the nested `@EnableMethodSecurity` config pattern from handoff #3 §5).
- **Reconcile** `setup/schema-full.sql` with all 7 new tables (nurse_profiles,
  patient_nurse_assignments, vitals_records, nursing_notes,
  medication_administrations, manual_tasks, notifications).
- Empty/loading states present on every new view. Full `mvn -o test` +
  `npx vite build` green.

---

## 8. Gotchas that will bite you (carried from this build)

1. `ddl-auto=update` auto-creates new tables from entities on startup. The
   `ensureXxxTable()` migrations are belt-and-suspenders + keep `schema-full.sql`
   authoritative. Because every nurse table is created **wholesale** (no
   add-column-to-populated-table), the default-backfill race from handoff #3 §6.1
   does NOT apply here.
2. `open-in-view=false`: don't touch lazy `@ManyToOne` proxies after a service
   returns. The nurse reads use explicit `findById` lookups and DTOs, so there
   are no lazy proxies leaking — keep it that way.
3. `Prescription` links to an admission via `MedicalRecord`
   (`p.medicalRecordId → m.ipdAdmissionId`), NOT a direct column. Use
   `prescriptionRepository.findByIpdAdmissionIdAndStatus(admissionId, "ACTIVE")`.
4. `AuditLogService.logAction` takes 7 args (see §4). Wrap in try/catch.
5. Mockito strict stubbing fails on unused stubs — only stub what the path uses.
   In service unit tests the `logAction` audit call may log an error when there's
   no SecurityContext; that's the best-effort swallow, harmless (you'll see an
   ERROR line but the test passes).
6. The admin dashboard `HospitalAdminDashboard.jsx` is a ~5,900-line monolith.
   Admin-facing tabs (Nurses, Assignments, Tasks) must be wired there across
   several spots — copy exactly how M1/M2 did it (state, loadData branch,
   handlers, tab entry, sidebar group, render block, table component, modal).
   Nurse-facing UI stays decomposed in `pages/hospital/nurse/` — do NOT put it
   in the monolith.
7. The login `user` object (sessionStorage) has `userId, role, hospitalId,
   modules, hospitalType, name`. Use `userId` to show own-note/own-task actions.
8. Nurse controllers must map ONLY `/hospital/**`. Never add `/clinic` or
   `/pharmacy` mappings to them.

---

## 9. Exact next actions (in order)

1. `cd backend && mvn -o test` — sanity check (should be **163/163 green**; M6
   backend is already verified done).
2. Finish M6 frontend (§7A): nurseService medication calls → `MedicationPanel.jsx`
   → Medication tab in `NursePatientDetail.jsx` → `npx vite build`.
3. M7 Manual Tasks (§7B) end to end, verify.
4. M8 Notifications (§7C) end to end (including emit hooks), verify.
5. M9 hardening + live verification (§7D), clean up test data.
6. Keep the branch as-is (`nurse-module`). Do NOT commit or push unless the owner
   asks. Report `mvn -o test` and `npx vite build` results at each step.

Everything you need is in the three spec docs + this map. Good luck.
