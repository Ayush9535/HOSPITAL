# Nurse Module — Document 2: Technical Design Specification

**Phase 1 · Small Hospital** · Date: 2026-07-06 · Status: Draft for approval
Companion: Document 1 (Business & Workflow), Document 3 (Implementation Plan).

> Follows existing HMS conventions exactly (handoff §8): Lombok `@Data` +
> hand-written getters, IDENTITY ids, `publicId` UUID + prefixed `customId`,
> snake_case `@Column`, `@CreationTimestamp`, `isActive` soft-delete,
> `SecurityContextHelper` tenant scoping, `@RequireModule`, `@PreAuthorize`,
> `IllegalArgumentException`→400, best-effort `AuditLogService`,
> `@Transactional` on multi-write, `DatabaseMigrationRunner` idempotent
> `ensure*()` + matching `setup/schema-full.sql`. **No code in this document.**

---

## PART 5 — Database Design (new entities)

Seven new tables. Every one is hospital-scoped (`hospital_id`), soft-deletable,
and carries audit fields. All anchor (directly or via admission) to
`ipd_admission`. Nothing here modifies an existing table's shape; the only
touch to existing tables is a new **`role` value** (`NURSE`) — no DDL — and an
additive `NURSING` entry in the modules element-collection data.

### Common conventions applied to all 7 entities
- `id BIGINT PK` (IDENTITY).
- `public_id` (UUID, unique) generated in `@PrePersist` — for entities the UI
  addresses by id (NurseProfile, ManualTask, assignments, notes, vitals, MAR).
- `hospital_id BIGINT NOT NULL` — tenant mapping, set from
  `SecurityContextHelper.getCurrentHospitalId()`; every query scoped by it.
- **Audit fields:** `created_by_user_id BIGINT`, `updated_by_user_id BIGINT`,
  `created_at DATETIME` (`@CreationTimestamp`), `updated_at DATETIME`
  (`@UpdateTimestamp`).
- **Soft delete:** `is_active TINYINT(1) NOT NULL DEFAULT 1` (or `is_deleted`
  where a note/vitals is retracted; naming matches surrounding code — use
  `is_active` for consistency with the codebase).
- Cross-tenant reads throw "not found"; writes validate FK targets belong to
  the same `hospital_id`.

### 5.1 `nurse_profiles` — NurseProfile
- **Purpose:** thin staff profile for a NURSE user (clone of Receptionist/Pharmacist).
- **Fields:** `id`, `public_id`, `custom_id` (prefix `NRS`, sequential like
  receptionists), `user_id BIGINT NOT NULL` (FK→`users`), `hospital_id`,
  `phone VARCHAR(20)`, `license_number VARCHAR(50) NULL`, audit fields,
  `is_active`.
- **Relationships:** 1:1 with `users` (role=NURSE); N:1 hospital.
- **Indexes:** `UK(public_id)`, `UK(user_id)`, `KEY(hospital_id)`,
  `UK(hospital_id, custom_id)`.
- **Constraints/validation:** `user_id` unique; name/email/password live on
  `users`; license optional; phone format validated in service.

### 5.2 `patient_nurse_assignments` — PatientNurseAssignment
- **Purpose:** manual admin assignment of a nurse to an IPD admission (history-preserving).
- **Fields:** `id`, `public_id`, `hospital_id`, `ipd_admission_id BIGINT NOT NULL`
  (FK→`ipd_admission`), `patient_id BIGINT NOT NULL` (denormalized for fast
  "my patients"), `nurse_user_id BIGINT NOT NULL` (FK→`users`),
  `assigned_by_user_id BIGINT NOT NULL`, `assigned_at DATETIME`,
  `unassigned_at DATETIME NULL`, `notes VARCHAR(255) NULL`,
  `is_active TINYINT(1) NOT NULL DEFAULT 1`, audit fields.
- **Relationships:** N:1 admission, N:1 nurse (users), N:1 patient.
- **Indexes:** `KEY(hospital_id)`, `KEY(ipd_admission_id)`,
  `KEY(nurse_user_id, is_active)` (drives "my patients"),
  `KEY(hospital_id, is_active)`.
- **Constraints/validation:** at most **one active** assignment per admission
  (enforced in service: close existing active before opening new); admission
  must be ADMITTED and same-hospital; nurse must be active NURSE same-hospital.
- **Lifecycle:** auto-closed (`is_active=0`, `unassigned_at=now`) by the
  discharge hook in `IpdAdmissionService.confirmDischarge`.

### 5.3 `vitals_records` — VitalsRecord (NEW module)
- **Purpose:** time-series vitals for an admission.
- **Fields:** `id`, `public_id`, `hospital_id`, `ipd_admission_id BIGINT NOT NULL`,
  `patient_id BIGINT NOT NULL`, `recorded_by_user_id BIGINT NOT NULL`,
  `recorded_at DATETIME NOT NULL`, `temperature DECIMAL(4,1) NULL`,
  `pulse INT NULL`, `bp_systolic INT NULL`, `bp_diastolic INT NULL`,
  `respiratory_rate INT NULL`, `spo2 INT NULL`, `weight DECIMAL(5,2) NULL`,
  `pain_score INT NULL` (0–10), `remarks VARCHAR(500) NULL`,
  `is_active`, audit fields.
- **Relationships:** N:1 admission, N:1 patient, N:1 nurse (recorded_by).
- **Indexes:** `KEY(ipd_admission_id, recorded_at)` (timeline),
  `KEY(hospital_id)`, `KEY(patient_id)`.
- **Validation (service-level, plausibility ranges):** temp 30–45 °C, pulse
  20–250, systolic 40–300, diastolic 20–200, RR 5–60, SpO2 50–100, pain 0–10.
  At least one measurement present. `recorded_at` not in the future.
- **Note:** BP stored as two ints (not the OPD string) to enable trend charts;
  OPD's `opd.bp` string is left untouched.

### 5.4 `nursing_notes` — NursingNote (NEW module)
- **Purpose:** running observation log per admission.
- **Fields:** `id`, `public_id`, `hospital_id`, `ipd_admission_id BIGINT NOT NULL`,
  `patient_id BIGINT NOT NULL`, `nurse_user_id BIGINT NOT NULL`,
  `note_text TEXT NOT NULL`, `category VARCHAR(40) NULL` (reserved; free-form in
  Phase 1), `recorded_at DATETIME NOT NULL`, `is_active` (soft-delete), audit
  fields (`updated_at` supports the edit window).
- **Indexes:** `KEY(ipd_admission_id, recorded_at)`, `KEY(hospital_id)`,
  `KEY(nurse_user_id)`.
- **Validation:** `note_text` non-empty, ≤ 2000 chars; edit/soft-delete only by
  author within the edit window (default 12 h — a constant, configurable later).
- **Versioning (Phase 1):** lightweight — edits update in place and bump
  `updated_at`; every edit/delete writes an `audit_logs` entry capturing the
  change (full version history table is a Phase-2 upgrade, see Part 22).

### 5.5 `medication_administrations` — MedicationAdministration (NEW module)
- **Purpose:** record administration of each doctor-prescribed dose.
- **Fields:** `id`, `public_id`, `hospital_id`, `ipd_admission_id BIGINT NOT NULL`,
  `prescription_id BIGINT NOT NULL` (FK→`prescriptions`), `patient_id BIGINT`,
  `nurse_user_id BIGINT NOT NULL`, `scheduled_time DATETIME NULL`,
  `administered_time DATETIME NULL`,
  `status VARCHAR(20) NOT NULL` (`GIVEN|SKIPPED|DELAYED|REFUSED|NOT_AVAILABLE`),
  `remarks VARCHAR(500) NULL`, `is_active`, audit fields.
- **Relationships:** N:1 admission, **N:1 prescription** (source of truth for
  medicine/dose/route/frequency — never duplicated here), N:1 nurse.
- **Indexes:** `KEY(ipd_admission_id, administered_time)`,
  `KEY(prescription_id)`, `KEY(hospital_id)`.
- **Validation:** `prescription_id` must be an ACTIVE prescription for the same
  admission/hospital; `status` in enum; `administered_time` required when
  status=GIVEN/DELAYED; corrections are made by adding a new record (no
  destructive edit), matching the "audit-friendly" clinical convention.

### 5.6 `manual_tasks` — ManualTask
- **Purpose:** admin-created, nurse-completed to-do (no engine).
- **Fields:** `id`, `public_id`, `hospital_id`, `title VARCHAR(150) NOT NULL`,
  `description TEXT NULL`, `assigned_to_nurse_user_id BIGINT NOT NULL`,
  `assigned_by_user_id BIGINT NOT NULL`, `ipd_admission_id BIGINT NULL`
  (optional patient link), `priority VARCHAR(10) NOT NULL DEFAULT 'MEDIUM'`
  (`LOW|MEDIUM|HIGH`), `status VARCHAR(15) NOT NULL DEFAULT 'PENDING'`
  (`PENDING|IN_PROGRESS|COMPLETED|CANCELLED`), `due_date DATETIME NULL`,
  `completed_at DATETIME NULL`, `completion_remarks VARCHAR(500) NULL`,
  `is_active`, audit fields.
- **Indexes:** `KEY(assigned_to_nurse_user_id, status)` (my-tasks),
  `KEY(hospital_id, status)`, `KEY(ipd_admission_id)`.
- **Validation:** title non-empty; assignee active NURSE same-hospital; status
  transitions guarded (nurse may only move PENDING→IN_PROGRESS→COMPLETED; admin
  may CANCEL); completion sets `completed_at`.

### 5.7 `notifications` — Notification
- **Purpose:** in-app messages to a user (Phase 1: nurses).
- **Fields:** `id`, `public_id`, `hospital_id`, `recipient_user_id BIGINT NOT NULL`,
  `type VARCHAR(40) NOT NULL` (`ASSIGNMENT|TASK|PRESCRIPTION_CHANGE`),
  `title VARCHAR(150) NOT NULL`, `message VARCHAR(500) NULL`,
  `reference_type VARCHAR(40) NULL`, `reference_id BIGINT NULL`,
  `is_read TINYINT(1) NOT NULL DEFAULT 0`, `read_at DATETIME NULL`,
  `created_at DATETIME`.
- **Indexes:** `KEY(recipient_user_id, is_read)`, `KEY(hospital_id)`.
- **Validation:** recipient same-hospital; type in enum. Best-effort creation
  (wrapped in try/catch like audit logging — a failed notification never breaks
  the triggering write).

---

## PART 6 — Entity Relationships (ER)

```
                         ┌───────────────┐
                         │   hospitals   │ (tenant root; type=HOSPITAL, module NURSING)
                         └──────┬────────┘
                                │ hospital_id on every table below
        ┌───────────────────────┼───────────────────────────────┐
        │                       │                                │
   ┌────┴─────┐          ┌──────┴──────┐                  ┌──────┴───────┐
   │  users   │          │  patients   │                  │    wards     │
   │ role=    │          └──────┬──────┘                  │    beds      │
   │  NURSE   │                 │                          └──────┬───────┘
   └────┬─────┘                 │ patient_id                      │ ward/bed
        │ user_id               │                                 │
   ┌────┴──────────┐     ┌──────┴───────────────────┐             │
   │ nurse_profiles│     │      ipd_admission       │◄────────────┘
   └───────────────┘     │ (ADMITTED/DISCHARGED,    │
                         │  doctorId, patientId)    │
                         └──────┬───────────────────┘
                                │ ipd_admission_id (the anchor)
        ┌───────────────┬───────┼─────────────┬──────────────────┐
        │               │       │             │                  │
 ┌──────┴────────┐ ┌────┴────┐ ┌┴──────────┐ ┌┴───────────────┐ ┌┴──────────┐
 │ patient_nurse │ │ vitals_ │ │ nursing_  │ │ medication_    │ │ manual_   │
 │ _assignments  │ │ records │ │ notes     │ │ administrations│ │ tasks     │
 │ (admission ↔  │ │         │ │           │ │                │ │(admission │
 │  nurse)       │ └─────────┘ └───────────┘ └───┬────────────┘ │  optional)│
 └───────┬───────┘                               │ prescription_id└──────────┘
         │ nurse_user_id                          ▼
         │                                  ┌───────────────┐
         └──────────────► users ◄───────────│ prescriptions │ (doctor-authored,
                          (nurse)           │ status=ACTIVE │  status ACTIVE/STOPPED)
                                            └───────────────┘

 users ◄── recipient_user_id ── notifications  (in-app, hospital-scoped)
```

**Relationship narrative:**
- **Patient → Admission:** a patient may have admissions; the active admission
  is the unit of nurse care.
- **Admission → Doctor:** admission already carries `doctorId` (treating doctor).
- **Admission → Nurse:** via `patient_nurse_assignments` (one active row).
- **Admission → Vitals / Notes / MAR:** one-to-many; the admission is the
  timeline container. All auto-scope to the patient and end their relevance at
  discharge.
- **MAR → Prescription:** many administrations per prescription over the stay;
  the prescription owns *what* to give, the MAR owns *what happened*.
- **User(nurse) → Notification:** one-to-many, in-app.

---

## PART 11 — Vitals (technical)

- **Workflow:** nurse opens patient → Vitals tab → "Record Vitals" form →
  `POST` → timeline prepends the new reading → abnormal values badged.
- **APIs:** see Part 17 (`/hospital/nurse/vitals`).
- **Validation:** plausibility ranges (§5.3); ≥1 measurement; no future time.
- **Frontend:** entry form (numeric inputs with units), timeline list, a simple
  SVG/canvas trend chart (reuse the dataviz palette conventions if a chart lib
  is added; Phase 1 can ship a minimal inline trend without a new dependency).
- **Normal ranges (display constants, not stored):** Temp 36.1–37.2 °C, Pulse
  60–100, BP ~90–120/60–80, RR 12–20, SpO2 95–100, Pain 0. Out-of-range values
  render with a warning color + "abnormal" badge (client-side; no alerting).
- **History/timeline:** reverse-chronological, grouped by day.
- **Future:** device capture, per-hospital configurable ranges, threshold
  alerts feeding `notifications`.

---

## PART 12 — Nursing Notes (technical)

- **Workflow:** composer → `POST` → timeline; author edits/soft-deletes within
  the edit window.
- **DB:** §5.4. **Edit window:** constant (default 12 h) checked in service.
- **Versioning/audit:** in-place edit + `audit_logs` record per change (Phase 1);
  a dedicated `nursing_note_revisions` table is the documented Phase-2 upgrade.
- **UI:** timeline with author + timestamp + edited marker; composer with char
  count (reuse `CharCountInput`); search/filter by text/date (client-side in
  Phase 1, server-side later).
- **Permissions:** create/edit/delete = author nurse (window); read = nurse
  (assigned), doctor, admin.

---

## PART 13 — Medication Administration (technical)

- **Workflow:** MAR tab lists ACTIVE prescriptions for the admission (reused
  from `PrescriptionRepository`); for each, nurse records a status event with
  time + remark → `POST`. History shows all events per medicine.
- **Statuses:** `GIVEN, SKIPPED, DELAYED, REFUSED, NOT_AVAILABLE`.
- **DB:** §5.5 — FK to `prescriptions`; medicine details never copied.
- **Corrections:** append a new record (no destructive edit) — clinical audit
  safety.
- **Audit:** every record writes `audit_logs`.
- **UI:** medicine rows (name/dose/frequency/route from prescription) + a
  status selector + time picker + remark; per-medicine history drawer (reuse
  `HistoryDrawer`).
- **Future:** derive a dosing schedule from `prescriptions.frequency`
  (`1-0-1`), barcode/e-signature, missed-dose notifications.

---

## PART 17 — API Design

**Namespace:** all nurse endpoints under `/hospital/**` (HOSPITAL tenant only).
Staff CRUD is admin-facing; workspace endpoints are nurse-facing. Every
controller class annotated `@RequireModule("NURSING")`. Standard envelope:
`ResponseEntity<?>`, try/catch → `badRequest(message)`; validation via
`@Valid` DTOs + service checks; tenant scoping via `SecurityContextHelper`.

> Clinic/Pharmacy namespaces (`/clinic/**`, `/pharmacy/**`) are **deliberately
> NOT mapped** on nurse controllers — this is the mechanism that keeps nurse
> out of those tenants.

### A. Nurse Staff CRUD — `NurseController` (`/hospital/nurses`)
`@PreAuthorize("hasRole('HOSPITAL_ADMIN')")`

| Method | Route | Req DTO | Resp DTO | Validation | Errors |
|---|---|---|---|---|---|
| POST | `/hospital/nurses` | `CreateNurseRequest{name,email,password,phone,licenseNumber?}` | `NurseResponse` | email unique, required fields | 400 dup email / missing |
| GET | `/hospital/nurses` | — (query: search,page) | `Page<NurseResponse>` | — | — |
| GET | `/hospital/nurses/{id}` | — | `NurseResponse` | same-hospital | 404 |
| PUT | `/hospital/nurses/{id}` | `UpdateNurseRequest` | `NurseResponse` | same-hospital | 400/404 |
| DELETE | `/hospital/nurses/{id}` | (reason) | `ApiResponse` | soft-delete | 404 |
| POST | `/hospital/nurses/{id}/reset-password` | `{newPassword}` | `ApiResponse` | policy | 400/404 |

### B. Assignment — `NurseAssignmentController` (`/hospital/nurse-assignments`)
`@PreAuthorize("hasRole('HOSPITAL_ADMIN')")` (reads also allow DOCTOR/RECEPTIONIST)

| Method | Route | Req DTO | Resp DTO | Validation | Errors |
|---|---|---|---|---|---|
| POST | `/hospital/nurse-assignments` | `AssignNurseRequest{ipdAdmissionId,nurseUserId,notes?}` | `AssignmentResponse` | admission ADMITTED+same-hospital; nurse active NURSE; closes prior active | 400 discharged/invalid |
| PUT | `/hospital/nurse-assignments/{id}/reassign` | `{nurseUserId,notes?}` | `AssignmentResponse` | as above | 400/404 |
| DELETE | `/hospital/nurse-assignments/{id}` | — | `ApiResponse` | sets is_active=0 | 404 |
| GET | `/hospital/nurse-assignments/admission/{admissionId}` | — | `List<AssignmentResponse>` | same-hospital | 404 |
| GET | `/hospital/nurse-assignments/unassigned` | — | `List<AdmissionSummary>` | active admissions w/o nurse | — |

### C. Nurse Workspace — `NurseWorkspaceController` (`/hospital/nurse`)
`@PreAuthorize("hasRole('NURSE')")` unless noted

| Method | Route | Purpose | Resp | Validation/Errors |
|---|---|---|---|---|
| GET | `/hospital/nurse/dashboard` | dashboard aggregates | `NurseDashboardDTO` | scoped to caller |
| GET | `/hospital/nurse/my-patients` | assigned active admissions | `Page<MyPatientDTO>` | own only |
| GET | `/hospital/nurse/patients/{admissionId}` | composite patient detail | `NursePatientDetailDTO` | 403 if not assigned |

Access rule: workspace reads verify an **active assignment** links the caller to
the admission (else 403), except admin/doctor read variants.

### D. Vitals — `VitalsController` (`/hospital/nurse/vitals`)
`@PreAuthorize("hasAnyRole('NURSE','DOCTOR','HOSPITAL_ADMIN')")` (write NURSE)

| Method | Route | Req/Resp | Validation | Errors |
|---|---|---|---|---|
| POST | `/hospital/nurse/vitals` | `CreateVitalsRequest` / `VitalsResponse` | ranges §5.3; assigned admission | 400/403 |
| GET | `/hospital/nurse/vitals/admission/{admissionId}` | `List<VitalsResponse>` | same-hospital | 404 |
| PUT | `/hospital/nurse/vitals/{id}` | `UpdateVitalsRequest` | author + edit window | 400/403 |

### E. Nursing Notes — `NursingNoteController` (`/hospital/nurse/notes`)
Write NURSE; read NURSE/DOCTOR/ADMIN.

| Method | Route | Validation | Errors |
|---|---|---|---|
| POST | `/hospital/nurse/notes` | text ≤2000; assigned admission | 400/403 |
| GET | `/hospital/nurse/notes/admission/{admissionId}` | same-hospital | 404 |
| PUT | `/hospital/nurse/notes/{id}` | author + window | 400/403 |
| DELETE | `/hospital/nurse/notes/{id}` | author + window (soft) | 403/404 |

### F. Medication Administration — `MedicationAdminController` (`/hospital/nurse/medication`)
Write NURSE; read NURSE/DOCTOR/ADMIN.

| Method | Route | Validation | Errors |
|---|---|---|---|
| GET | `/hospital/nurse/medication/admission/{admissionId}/prescriptions` | ACTIVE Rx for admission | 404 |
| POST | `/hospital/nurse/medication` `CreateMarRequest{prescriptionId,status,administeredTime?,remarks?}` | Rx ACTIVE+same-admission; status enum; time rule | 400/403 |
| GET | `/hospital/nurse/medication/admission/{admissionId}` | history | 404 |

### G. Manual Tasks — `ManualTaskController` (`/hospital/nurse-tasks`)
Admin create/assign/cancel; nurse my-tasks/complete.

| Method | Route | Permission | Validation | Errors |
|---|---|---|---|---|
| POST | `/hospital/nurse-tasks` | ADMIN | title; assignee active NURSE | 400 |
| GET | `/hospital/nurse-tasks` | ADMIN | all hospital tasks (filters) | — |
| PUT | `/hospital/nurse-tasks/{id}/status` | ADMIN(cancel)/NURSE(progress,complete) | transition guard | 400/403 |
| GET | `/hospital/nurse-tasks/my` | NURSE | own tasks | — |

### H. Notifications — `NotificationController` (`/hospital/notifications`)
`@PreAuthorize("hasAnyRole('NURSE','DOCTOR','RECEPTIONIST','HOSPITAL_ADMIN','PHARMACIST')")`
(own-scoped; Phase 1 producers only target nurses)

| Method | Route | Purpose |
|---|---|---|
| GET | `/hospital/notifications` | own list (paged) |
| GET | `/hospital/notifications/unread-count` | badge |
| PUT | `/hospital/notifications/{id}/read` | mark one |
| PUT | `/hospital/notifications/read-all` | mark all |

### I. Extended existing endpoints
- `HospitalStatsController` — nurse dashboard stat method (or served by C).
- `IpdAdmissionService.confirmDischarge` — **hook** to auto-close active
  `patient_nurse_assignments` for the admission (no signature change).
- Prescription add/stop paths — **hook** to emit `PRESCRIPTION_CHANGE`
  notifications to the assigned nurse (best-effort).

---

## PART 18 — Frontend Design

### Pages
- `pages/hospital/NurseDashboard.jsx` — nurse landing (new, decomposed — NOT a
  monolith; follow the pharmacy-ERP per-view style, not the 5k-line admin file).
- Nurse sub-views under `pages/hospital/nurse/`:
  `MyPatientsView.jsx`, `NursePatientDetail.jsx`, `MyTasksView.jsx`,
  `VitalsPanel.jsx`, `MedicationPanel.jsx`, `NotesPanel.jsx`.
- Admin gains tabs inside existing `HospitalAdminDashboard.jsx`:
  **Nurses** (staff) and **Nurse Assignments**.

### Reusable components (no rebuild)
`Sidebar`, `Navbar`, `PageHeader`, `DataTable`, `ActionMenu`, `StatusBadge`,
`ConfirmationModal`, `EmptyState`, `LoadingSpinner`, `Skeleton`,
`StaffDetailsModal`, `ProfileModal`, `HistoryDrawer`, `CharCountInput`,
`RefreshButton`, `ToastContext`, `PatientDetailsModal` (read-only mode).

### New shared components
`NotificationBell.jsx` (navbar), `VitalsForm`, `VitalsTimeline`,
`MedicationStatusRow`, `NoteComposer`, `AssignNurseModal`, `TaskCard`.

### Folder structure
```
pages/hospital/NurseDashboard.jsx
pages/hospital/nurse/{MyPatientsView,NursePatientDetail,MyTasksView,
                     VitalsPanel,MedicationPanel,NotesPanel}.jsx
components/{NotificationBell,VitalsForm,VitalsTimeline,MedicationStatusRow,
           NoteComposer,AssignNurseModal,TaskCard}.jsx
services/nurseService.js         (all nurse API calls — one clean module)
hooks/useNotifications.js
```

### Navigation / Sidebar / Routing
- `App.jsx`: add route `/hospital/nurse` guarded
  `<ProtectedRoute allowedRoles={['NURSE']}>`; `LandingRedirect` maps
  `case 'NURSE' → /hospital/nurse`.
- `authService.getLoginUrl` unchanged (NURSE is a HOSPITAL user → `/login/hospital`).
- Nurse sidebar tabs (module + role gated): Dashboard, My Patients, My Tasks,
  Notifications, Profile.
- Admin sidebar: new "Nursing" group `{ tabIds: ['nurses','nurse-assignments'] }`
  added to `SIDEBAR_GROUPS`, shown only when `hospitalType==='HOSPITAL'` **and**
  `modules.includes('NURSING')`.

### API layer / state / hooks
- `services/nurseService.js` mirrors `hospitalService.js` axios-wrapper style
  (interceptors reused). Local component state + reload functions (existing
  pattern); `useNotifications` polls unread-count or subscribes via existing
  `useWebSocket` (reuse the per-hospital broadcast; Phase 1 may poll).
- No new global state library.

### Permission-based rendering
- Route guard + module-gated tabs (existing mechanism).
- Read-only clinical sections render without edit affordances for NURSE.
- Nurse never sees admin/doctor/pharmacy tabs.

---

## PART 19 — Backend Design

### Entities (7)
`NurseProfile`, `PatientNurseAssignment`, `VitalsRecord`, `NursingNote`,
`MedicationAdministration`, `ManualTask`, `Notification` — conventions per Part 5.

### Repositories (Spring Data, derived queries)
- `NurseProfileRepository` — `findByUserId`, `findByHospitalIdAndIsActiveTrue`,
  `findByHospitalIdAndCustomId`.
- `PatientNurseAssignmentRepository` — `findByIpdAdmissionIdAndIsActiveTrue`,
  `findByNurseUserIdAndIsActiveTrue`, `existsByIpdAdmissionIdAndNurseUserIdAndIsActiveTrue`.
- `VitalsRecordRepository` — `findByIpdAdmissionIdOrderByRecordedAtDesc`.
- `NursingNoteRepository` — `findByIpdAdmissionIdAndIsActiveTrueOrderByRecordedAtDesc`.
- `MedicationAdministrationRepository` — `findByIpdAdmissionIdOrderByAdministeredTimeDesc`,
  `findByPrescriptionId`.
- `ManualTaskRepository` — `findByAssignedToNurseUserIdAndStatus`,
  `findByHospitalId...`.
- `NotificationRepository` — `findByRecipientUserIdOrderByCreatedAtDesc`,
  `countByRecipientUserIdAndIsReadFalse`.

### Services
- `NurseService` (clone of `ReceptionistService`): create user+profile,
  list/get/update/delete/reset, `customId` generation.
- `NurseAssignmentService`: assign/reassign/unassign with single-active
  invariant; emits notification; `@Transactional`.
- `VitalsService`, `NursingNoteService`, `MedicationAdministrationService`:
  create/list/(edit-window); assignment-guard helper.
- `ManualTaskService`: create/assign/status-transition/my-tasks.
- `NotificationService`: create (best-effort), list, unread-count, mark-read.
- Shared `NurseAccessGuard` helper: `assertAssigned(admissionId, nurseUserId)`.
- Hooks: `IpdAdmissionService.confirmDischarge` → close assignments; prescription
  add/stop → notify assigned nurse.

### DTOs / Validators
- Requests: `CreateNurseRequest`, `UpdateNurseRequest`, `AssignNurseRequest`,
  `CreateVitalsRequest`, `UpdateVitalsRequest`, `CreateNoteRequest`,
  `CreateMarRequest`, `CreateTaskRequest`, `UpdateTaskStatusRequest`.
- Responses: `NurseResponse`, `AssignmentResponse`, `VitalsResponse`,
  `NoteResponse`, `MarResponse`, `TaskResponse`, `NotificationResponse`,
  `NurseDashboardDTO`, `MyPatientDTO`, `NursePatientDetailDTO`.
- `jakarta.validation` annotations on DTOs + service-level plausibility/scope
  checks (existing dual approach).

### Security / Module / Role / Permission registration
- **SecurityConfig:** add `NURSE` to `/hospital/**` and `/ws/**` `hasAnyRole`
  lists. (Critical — without this every nurse request 401s at the gate.)
- **Module:** add `NURSING` to `PlansTab.jsx` `AVAILABLE_MODULES` and to the
  HOSPITAL default/available plan module sets; controllers `@RequireModule("NURSING")`.
- **Role:** `NURSE` string used in `@PreAuthorize`; `User.generateIds` gets a
  `NRS` prefix branch; JWT unchanged (role is already free-form).
- **Migration:** `DatabaseMigrationRunner.ensureNurseTables()` (idempotent
  create-if-absent for all 7 tables) appended to `runMigrations()`; mirror in
  `setup/schema-full.sql`. Tables created wholesale → no ddl-auto/default race
  (handoff gotcha §6.1 avoided).
- **Audit:** all writes call `AuditLogService` (best-effort try/catch).

---

## Isolation guarantees (explicit)
1. **Clinic/Pharmacy untouched:** nurse controllers map only `/hospital/**`;
   `NURSING` module never added to clinic/pharmacy plans; no clinic/pharmacy
   sidebar or route references NURSE.
2. **OPD path untouched:** new `VitalsRecord` is separate from `opd.bp/...`.
3. **Doctor authorship untouched:** MAR references prescriptions read-only.
4. **Additive only:** no existing table column changed; no existing role removed
   from any endpoint; no existing API contract altered (only new endpoints +
   internal hooks).

---

*End of Document 2. Milestones, dependencies, and the full test plan are in
Document 3.*
