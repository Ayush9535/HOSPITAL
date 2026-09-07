# Nurse Module — Document 1: Business & Workflow Specification

**Phase 1 · Small Hospital (15–30 beds, 2–5 nurses, 2–3 doctors, single ward)**
Date: 2026-07-06 · Status: Draft for approval
Companion docs: `2026-07-06-nurse-module-2-technical-design.md`,
`2026-07-06-nurse-module-3-implementation-plan.md`

> Scope guard: Phase 1 only. No ICU / OT / Emergency / NICU / CCU / Dialysis.
> No departments, no shifts, no rosters, no automatic assignment, no task
> engine, no nurse-station terminal, no automation. Nurse exists **only** in
> the HOSPITAL tenant flavor, gated by a new `NURSING` plan module. Clinic and
> standalone Pharmacy are never touched.

---

## Locked architectural decisions (from brainstorming)

| # | Decision | Choice |
|---|---|---|
| 1 | How Nurse is gated | New `NURSING` plan module + `hospitalType === HOSPITAL` |
| 2 | Assignment anchor | Nurse is assigned to an **IPD admission** (auto-closes at discharge) |
| 3 | MAR medicine source | Existing `prescriptions` rows (doctor's ACTIVE Rx) |
| 4 | Vitals model | New IPD-scoped `VitalsRecord`; existing OPD vitals untouched |

---

## FIRST TASK — Reuse Analysis (what already exists)

Before designing anything new, this is what the current HMS gives us for free.
The Nurse module follows the **Receptionist/Pharmacist blueprint** exactly.

### 1. Existing entities that can be reused (no schema change)
- **`users`** — single credential store. Nurse is a new `role` string value
  (`NURSE`) with `hospital_id` set. No new column.
- **`patients`** — demographics, DOB/age. Read-only for Nurse.
- **`ipd_admission`** — the anchor for the entire nurse workflow (ward, bed,
  status ADMITTED/DISCHARGED, doctorId, patientId, primaryDiagnosis).
- **`prescriptions`** — doctor's medicine orders (dosage, frequency `1-0-1`,
  route, status ACTIVE/STOPPED). The MAR records administration against these.
- **`medical_records`** — symptoms, diagnosis, treatment notes, follow-up.
  Read-only for Nurse.
- **`wards`, `beds`, `ipd_bed_history`** — bed context shown on patient detail.
- **`billing` / `billing_items`** — read-only billing summary on patient detail.
- **`audit_logs`** — Nurse writes reuse `AuditLogService` as-is.

### 2. Existing APIs that can be reused (call, don't rebuild)
- `POST /login`, `GET /auth/me`, `PUT /auth/profile` — Nurse logs in through
  the identical hospital auth path; only a new role string flows through.
- `GET /hospital/ipd/{id}` and `GET /hospital/ipd/admissions` — admission read
  (Nurse gets a scoped variant, see Doc 2).
- `GET /hospital/patients/{publicId}` and `/consultation-details` — patient +
  clinical read.
- IPD prescription reads and `GET /hospital/ipd/{id}` clinical payloads.
- `GET /hospital/billing/patient/{publicId}` — read-only billing summary.

### 3. Controllers that can be extended (pattern to clone)
- **`ReceptionistController` / `PharmacistController`** → clone as
  `NurseController` for staff CRUD (create/list/get/update/delete/reset-password),
  class-level `@PreAuthorize("hasRole('HOSPITAL_ADMIN')")`.
- **`IpdAdmissionController`** — extended (not rewritten) with nurse-facing read
  endpoints and the assignment endpoints, OR a dedicated `NurseWorkspaceController`
  (Doc 2 recommends the latter to keep IPD controller focused).
- **`HospitalStatsController`** — add nurse dashboard stats method.

### 4. Services that can be extended
- **`ReceptionistService`** is the template for `NurseService` (creates `users`
  row + thin profile row, generates `publicId`/`customId`, reset-password).
- **`IpdAdmissionService`** — reused read-only for admission data; the
  discharge path gets a hook to auto-close nurse assignments.
- **`AuditLogService`** — reused verbatim.
- **`PatientService`** — reused read-only.

### 5. Repositories that already help
- `IpdAdmissionRepository`, `PrescriptionRepository`, `PatientRepository`,
  `BillingRepository`, `WardRepository`, `BedRepository`, `UserRepository`,
  `MedicalRecordRepository` — all reused for reads.

### 6. Frontend components that can be reused
- `HospitalLogin`, `ProtectedRoute`, `LandingRedirect`, `Sidebar`, `Navbar`,
  `PageHeader`, `PageMeta`, `DataTable`, `ActionMenu`, `StatusBadge`,
  `ConfirmationModal`, `EmptyState`, `LoadingSpinner`, `Skeleton`,
  `PatientDetailsModal` (read-only reuse), `StaffDetailsModal`,
  `ProfileModal`, `RefreshButton`, `ToastContext`, `useWebSocket`,
  `useModal`, `useDebounce`, `useFetch`.

### 7. Layouts that can be reused
- The dashboard shell composition (Sidebar + Navbar + content) used by every
  role dashboard. The `SIDEBAR_GROUPS` grouped-sidebar mechanism (HOSPITAL
  flavor) gains a "Nursing" group for the admin's assignment tab.

### 8. Permissions that already exist
- Role-string `@PreAuthorize` mechanism, the `/hospital/**` namespace rule in
  `SecurityConfig`, `@RequireModule` aspect, JWT `modules` claim, frontend
  route guards + module-gated tabs. **We plug into all of these; we invent no
  new permission mechanism.**

### 9. Things that must NEVER be modified
- **OPD vitals** on the `opd` row and the consultation code path.
- **Standalone Pharmacy ERP** (`/pharmacy/**` resource controllers, `entity/pharmacy`,
  `service/pharmacy`, ERP frontend) — nurse is hospital-only.
- **Clinic behavior** — no clinic tab, route, or module ever gains nursing.
- **Doctor's prescription authorship** — nurse never edits `prescriptions`.
- **Existing role annotations** — we only *add* `NURSE` where needed; we never
  remove or weaken an existing role from any endpoint.
- **Billing write paths** — nurse read-only.
- The `users` table shape, JWT claim structure, and login response contract
  (role is already a free string — no structural change).

---

## PART 1 — Real-World Workflow (hospital reality, not software)

A patient's journey through a small hospital, told from the ward floor:

1. **Admission.** A patient who needs to stay is admitted. The front desk
   creates the paperwork; a bed in the single ward is given. The patient is now
   "in the ward," physically present and under the hospital's care around the
   clock.

2. **Doctor's first examination.** The treating doctor sees the patient at the
   bedside, establishes the working diagnosis, and writes orders: which
   medicines, what dose, how often, and what to watch for. This is the doctor's
   plan of care.

3. **Nurse receives responsibility.** The charge/ward nurse is told "this
   patient is yours." From this moment the nurse is the continuous caretaker —
   the doctor visits periodically, but the nurse is there through the shift.
   The nurse reads the doctor's orders and the diagnosis so she knows the plan.

4. **Vitals.** At regular intervals (and whenever the patient looks unwell) the
   nurse measures temperature, pulse, blood pressure, breathing rate, oxygen
   saturation, sometimes weight and pain level. She writes each reading with the
   time. Over hours these readings form a picture — improving, stable, or
   deteriorating.

5. **Medicines.** Following the doctor's orders, the nurse gives each medicine
   at its scheduled time. For every dose she records what actually happened:
   given on time, given late, patient refused, patient was asleep/away, or the
   medicine wasn't available. She never decides *what* to give — only carries
   out and records the doctor's plan.

6. **Observation.** Between vitals and medicines the nurse watches the patient —
   appetite, pain, wound condition, mood, any complaint — and writes short notes
   on the chart. These notes are the running story of the patient's day and are
   what she hands over to the next nurse and reports to the doctor.

7. **Doctor's revisit.** The doctor returns (rounds), reads the vitals trend and
   the nurse's notes, examines the patient again, and adjusts orders — stops a
   medicine, adds another, changes a dose. The nurse then follows the updated
   plan.

8. **Discharge.** When the patient is well enough, the doctor decides to
   discharge. The nurse removes any lines, gives final instructions, ensures
   belongings and paperwork are in order, and the patient leaves. The nurse's
   responsibility for that patient ends.

Throughout, the **admin/ward-in-charge** decides which nurse looks after which
patient — in a small hospital this is a person saying "Nurse A takes beds 1–8."
There is no algorithm; it's a manual decision that can be changed at any time.

---

## PART 2 — System Workflow (HMS translation)

```
 [Receptionist/Admin]                [Doctor]                    [Hospital Admin]
        │                               │                               │
   Register Patient                     │                               │
        │                               │                               │
   Admit to IPD  ───────────────────────────────────────────────────►  │
   (ipd_admission: ADMITTED,            │                               │
    ward+bed, doctorId)                 │                               │
        │                               │                               │
        │                        Bedside exam +                         │
        │                        prescriptions (ACTIVE)                 │
        │                        medical_record (diagnosis)             │
        │                               │                               │
        │                               │                    Assign Nurse ──► PatientNurseAssignment
        │                               │                    (admission ↔ nurse, isActive)
        │                               │                               │
        ▼                                                               ▼
 ┌──────────────────────────────  NURSE  ──────────────────────────────────┐
 │  Sees "My Patients" = active admissions assigned to me                   │
 │      │                                                                   │
 │      ├─ Record Vitals ──► VitalsRecord (timeline, abnormal flags)        │
 │      │                                                                   │
 │      ├─ Record Medication ──► MedicationAdministration                   │
 │      │     against each ACTIVE prescription:                             │
 │      │     GIVEN / SKIPPED / DELAYED / REFUSED / NOT_AVAILABLE + time    │
 │      │                                                                   │
 │      ├─ Write Nursing Note ──► NursingNote (timeline, editable window)   │
 │      │                                                                   │
 │      └─ Complete Manual Tasks ──► ManualTask (PENDING→COMPLETED+remarks) │
 └──────────────────────────────────────────────────────────────────────────┘
        │                               ▲                               │
        │                               │                               │
   Vitals trend + notes  ──────────► Doctor Revisit (rounds)            │
        │                        reads vitals/notes,                    │
        │                        edits prescriptions                    │
        │                               │                               │
        ▼                               ▼                               │
   Doctor decides Discharge ──► IPD plan-discharge → confirm-discharge  │
        │                                                               │
   On confirm-discharge:  ipd_admission → DISCHARGED                    │
        └──► PatientNurseAssignment auto-closed (isActive=false)  ◄─────┘
             Patient drops off every nurse's "My Patients"

 In-app Notification fired to the nurse on: assignment created, manual task
 assigned, prescription changed for an assigned patient (Phase 1 triggers).
```

Every interaction above is either a **reuse** (admission, prescription,
discharge, patient read) or one of the **7 new writes** (assignment, vitals,
notes, MAR, tasks, notifications, nurse profile).

---

## PART 3 — Nurse Role Design

**Role string:** `NURSE`. **Tenant:** HOSPITAL only. **Module:** `NURSING`.
**Dual-hat:** none (unlike single-doctor admin / solo-pharmacist).

### Responsibilities
Record vitals, record medication administration against doctor's orders, write
nursing notes/observations, complete manual tasks assigned by admin, view the
clinical context of patients assigned to her.

### Accessible modules
IPD (read), Prescriptions (read), Patients (read), plus the new nurse features
(Vitals, Notes, MAR, Tasks, Notifications — all scoped to assigned admissions).

### Accessible pages
`/hospital/nurse` dashboard, My Patients, Patient Detail (read-only clinical +
nurse-writable vitals/notes/MAR sub-tabs), My Tasks, Notifications, Profile.

### Permission split (Read / Write / Update / Delete)

| Capability | Read | Create | Update | Delete |
|---|---|---|---|---|
| Own profile | ✔ | — | ✔ (limited: phone, password) | — |
| Assigned admissions ("My Patients") | ✔ (only assigned) | — | — | — |
| Patient demographics / diagnosis / prescriptions / doctor notes / billing | ✔ (only assigned) | — | — | — |
| Vitals | ✔ | ✔ | ✔ (own, within edit window) | Soft-delete own (within window) |
| Nursing notes | ✔ | ✔ | ✔ (own, within edit window) | Soft-delete own (within window) |
| Medication administration | ✔ | ✔ | ✔ (own, within window) | — (correction via new record) |
| Manual tasks | ✔ (assigned to me) | — | ✔ (status→completed + remarks) | — |
| Notifications | ✔ (own) | — | ✔ (mark read) | — |

### Restrictions (things Nurse can NEVER do)
- Cannot create/edit/delete prescriptions or diagnoses (doctor-only).
- Cannot admit, discharge, transfer, or change bed/ward.
- Cannot create/edit patients, appointments, or bills; billing is read-only.
- Cannot assign patients to herself or other nurses (admin-only).
- Cannot manage staff, settings, fees, WhatsApp, plans, inventory, pharmacy.
- Cannot see patients not currently assigned to her.
- Cannot access Clinic or Pharmacy tenants at all.

---

## PART 4 — Phase 1 Feature List

For each: Purpose · Workflow · User · Data stored · Future expansion · APIs · UI.

### F1 — Nurse Role & Authentication
- **Purpose:** let nurses log in and be recognized hospital-only.
- **Workflow:** admin creates nurse → nurse logs in at hospital portal → lands
  on nurse dashboard.
- **User:** Admin (create), Nurse (login).
- **Data:** `users` row (role=NURSE) + `NurseProfile`.
- **Future:** shift/department fields added to profile without contract change.
- **APIs:** existing `/login`, `/auth/me`; new `/hospital/nurses` CRUD.
- **UI:** login (reused), admin "Nurses" staff tab (clone of Receptionists).

### F2 — Nurse Profile Management
- **Purpose:** admin manages nurse staff records.
- **Workflow:** create / list / view / edit / deactivate / reset password.
- **User:** Hospital Admin.
- **Data:** `NurseProfile` (name via user, phone, optional license no.).
- **Future:** department, qualification, joining date columns.
- **APIs:** `/hospital/nurses` (POST/GET/GET{id}/PUT{id}/DELETE{id}/reset).
- **UI:** Nurses table + StaffDetailsModal + create/edit modal (reused shells).

### F3 — Patient (Admission) Assignment
- **Purpose:** admin decides which nurse cares for which admitted patient.
- **Workflow:** admin opens an active admission → assigns/reassigns a nurse.
- **User:** Hospital Admin (assign), Nurse (sees result).
- **Data:** `PatientNurseAssignment` (admission ↔ nurse, history preserved).
- **Future:** bed-range assignment, multi-nurse, auto-assignment engine.
- **APIs:** assign / reassign / unassign / list-by-admission / my-assignments.
- **UI:** admin "Nurse Assignments" tab; nurse "My Patients".

### F4 — Nurse Dashboard
- **Purpose:** at-a-glance start screen for the shift.
- **Workflow:** nurse logs in → sees assigned-patient count, pending tasks,
  recent vitals due, notifications.
- **User:** Nurse.
- **Data:** aggregates over assignments/vitals/tasks/notifications.
- **Future:** vitals-due schedule widget, handover widget.
- **APIs:** `/hospital/nurse/dashboard`.
- **UI:** stat cards + recent patients + pending tasks + empty/loading states.

### F5 — My Patients
- **Purpose:** the nurse's working list of assigned admitted patients.
- **Workflow:** browse/search assigned admissions → open a patient.
- **User:** Nurse.
- **Data:** read over `ipd_admission` filtered by active assignment.
- **Future:** filter by ward/bed once multi-ward exists.
- **APIs:** `/hospital/nurse/my-patients`.
- **UI:** table + patient cards + status + search + pagination.

### F6 — Patient Details (read-only clinical + nurse work sub-tabs)
- **Purpose:** single screen with everything the nurse needs bedside.
- **Workflow:** view demographics/diagnosis/prescriptions/notes/billing (RO) +
  act on Vitals/Notes/MAR tabs.
- **User:** Nurse.
- **Data:** reads across patient/admission/prescriptions/billing; writes to the
  3 nurse tables.
- **APIs:** `/hospital/nurse/patients/{admissionId}` composite read.
- **UI:** header + tabbed detail (Overview / Vitals / Medication / Notes).

### F7 — Vitals (new)
- **Purpose:** time-series vitals for admitted patients.
- **Workflow:** nurse records a reading → timeline + abnormal flags build up.
- **User:** Nurse (record), Doctor/Admin (read).
- **Data:** `VitalsRecord`.
- **Future:** device integration, configurable normal ranges, alerts.
- **APIs:** create / list-by-admission / (edit within window).
- **UI:** entry form + timeline + simple trend chart + normal/abnormal badges.

### F8 — Nursing Notes (new)
- **Purpose:** running observation log per admission.
- **Workflow:** nurse writes short notes; timeline accrues; limited edit window.
- **User:** Nurse (write), Doctor/Admin (read).
- **Data:** `NursingNote`.
- **Future:** categories, structured handover, note templates.
- **APIs:** create / list / update(own,window) / soft-delete(own,window).
- **UI:** note composer + timeline + search/filter.

### F9 — Medication Administration (new)
- **Purpose:** record what happened to each prescribed dose.
- **Workflow:** nurse sees ACTIVE prescriptions for the admission → records a
  status per dose (GIVEN/SKIPPED/DELAYED/REFUSED/NOT_AVAILABLE) + time + remark.
- **User:** Nurse (record), Doctor/Admin (read).
- **Data:** `MedicationAdministration` (FK to `prescriptions`).
- **Future:** recurring schedules from frequency, barcode scan, e-signature.
- **APIs:** create / list-by-admission / list-by-prescription.
- **UI:** medicine list from prescriptions + per-dose status entry + history.

### F10 — Manual Tasks
- **Purpose:** admin hands a nurse a to-do (no engine, no automation).
- **Workflow:** admin creates task + assigns nurse → nurse completes with remark.
- **User:** Admin (create/assign), Nurse (complete).
- **Data:** `ManualTask`.
- **Future:** clean migration path to a real Task Engine (see Part 22).
- **APIs:** create/list/assign/update-status; nurse my-tasks/complete.
- **UI:** admin task creator; nurse "My Tasks" with status + priority + due date.

### F11 — In-App Notifications
- **Purpose:** tell the nurse something changed (assignment, task, Rx change).
- **Workflow:** event fires → notification row → bell badge → nurse reads.
- **User:** Nurse (recipient).
- **Data:** `Notification`.
- **Future:** Email/SMS/WhatsApp channels behind the same table.
- **APIs:** list / unread-count / mark-read / mark-all-read.
- **UI:** navbar bell + dropdown + unread badge + empty state.

---

## PART 7 — Patient Assignment (business view)

- **Model:** one **active** `PatientNurseAssignment` links an `ipd_admission`
  to a nurse. Reassignment closes the old row (`isActive=false`,
  `unassignedAt`) and opens a new one — full **history preserved**.
- **Workflow:** admin opens an active admission, picks a nurse from a dropdown
  of hospital nurses, confirms. Reassign/unassign are the same action.
- **UI:** admin "Nurse Assignments" tab listing active admissions with their
  current nurse and an assign/reassign action; nurse sees the result in "My
  Patients".
- **Permissions:** create/reassign/unassign = Hospital Admin only. Nurse read
  own only. Doctor/Receptionist may view (read) but not assign.
- **Editing:** reassignment allowed anytime while admission is ADMITTED.
- **History:** every assignment row retained for audit; `audit_logs` entry on
  each change.
- **Validation:** target must be an active admission in the same hospital; nurse
  must be an active NURSE in the same hospital; block assigning a discharged
  admission.
- **Future upgrade:** add bed-range or multiple concurrent nurses without
  changing the row shape; an auto-assignment engine can write the same table.

---

## PART 8 — Nurse Dashboard (UX)

- **Widgets/stat cards:** Assigned Patients (active), Pending Tasks, Vitals
  Recorded Today, Unread Notifications.
- **Recent Patients:** last few assigned admissions with bed + primary
  diagnosis + quick "Record Vitals" action.
- **Pending Work:** open manual tasks sorted by priority/due date.
- **Quick Actions:** Record Vitals, Add Note, View My Patients.
- **Empty states:** "No patients assigned yet — your ward in-charge will assign
  patients to you." / "No pending tasks."
- **Loading states:** skeleton cards (reuse `Skeleton`).
- **Future widgets:** vitals-due timeline, shift handover summary.

---

## PART 9 — My Patients (UX)

- **Columns:** Patient name, Age/Sex, Bed, Admission date, Primary diagnosis,
  Treating doctor, Status, Actions.
- **Filters:** status (all active by default), search by name/bed.
- **Sorting:** by admission date, bed number, name.
- **Searching:** debounced name/bed search (reuse `useDebounce`).
- **Status:** admission status badge (ADMITTED); assignment always active here.
- **Pagination:** server-side (reuse `DataTable` pagination pattern).
- **Patient card:** compact card variant for smaller screens (name, bed, dx,
  quick actions).
- **Actions:** Open detail, Record Vitals, Add Note.
- **Permissions:** nurse sees only her active assignments.
- **Future:** ward/department filter, acuity sorting.

---

## PART 10 — Patient Details (read-only clinical, nurse-writable work tabs)

Sections (all **read-only** unless marked writable):
- **Demographics:** name, age/DOB, sex, contact (read).
- **Doctor:** treating doctor from admission (read).
- **Admission:** ward, bed, admission date/type, status (read).
- **Diagnosis:** primary diagnosis + latest medical record diagnosis (read).
- **Prescriptions / Current Medicines:** ACTIVE prescription rows —
  medicine, dose, frequency, route, status (read).
- **Allergies:** shown if present on patient record (read; Phase 1 shows
  whatever the patient record holds — no new allergy capture).
- **Doctor Notes:** treatment notes / follow-up from medical record (read).
- **Billing Summary:** total, paid, balance (read-only, reuse billing read).
- **Vitals (writable tab):** timeline + record form.
- **Medication (writable tab):** MAR against prescriptions.
- **Notes (writable tab):** nursing notes timeline + composer.

**No editing of any clinical/billing/demographic data.** The only writes on
this page are into `VitalsRecord`, `NursingNote`, `MedicationAdministration`.

---

## PART 14 — Manual Tasks (business view)

- **No task engine.** A task is a row an admin creates and assigns to one nurse.
- **Status:** PENDING → IN_PROGRESS → COMPLETED (or CANCELLED by admin).
- **Priority:** LOW / MEDIUM / HIGH.
- **Due date:** optional date/time.
- **Completion:** nurse marks complete with an optional remark + timestamp.
- **History:** status transitions + `audit_logs`; task rows retained.
- **Permissions:** create/assign/cancel = Admin; view+complete = assigned Nurse;
  Admin views all.
- **Future migration:** the `ManualTask` shape is a strict subset of a future
  Task Engine (add recurrence, auto-generation, dependencies, SLA) — Phase 1
  rows migrate untouched (see Part 22).

---

## PART 15 — Notifications (business view)

- **Channel:** in-app only. No Email, SMS, or WhatsApp in Phase 1.
- **Triggers (Phase 1):** (a) nurse assigned to an admission, (b) manual task
  assigned to nurse, (c) prescription added/stopped for an assigned admission.
- **States:** unread → read (with `readAt`); unread count drives the navbar bell
  badge.
- **UI:** bell dropdown listing recent notifications, mark-one / mark-all read.
- **Future:** the same `Notification` table feeds future Email/SMS/WhatsApp
  senders (reuse the existing `WhatsAppService`/scheduler pattern) — add a
  channel column + dispatcher; in-app rows are unaffected.

---

## PART 16 — Permission Matrix

Legend: **V**iew · **C**reate · **E**dit · **D**elete · **A**pprove ·
**As**sign · — = no access.

| Feature | Hospital Admin | Doctor | Receptionist | **Nurse** | Pharmacist |
|---|---|---|---|---|---|
| Nurse staff records | V C E D | — | — | V(self) E(self-ltd) | — |
| Nurse ↔ Admission assignment | V C E(reassign) D(unassign) As | V | V | V(own) | — |
| My Patients (assigned admissions) | V(all) | V(own) | V | **V(own)** | — |
| Patient demographics | V C E D | V | V C E | **V** | — |
| Diagnosis / medical record | V | V C E | — | **V** | — |
| Prescriptions | V | V C E D | — | **V** | V |
| Vitals (IPD) | V | V | V | **V C E(own,win)** | — |
| Nursing notes | V | V | — | **V C E(own,win) D(own,win)** | — |
| Medication administration | V | V | — | **V C E(own,win)** | — |
| Manual tasks | V C E D(cancel) As | — | — | **V(own) E(complete)** | — |
| Notifications | V(own) | V(own) | V(own) | **V(own) E(read)** | V(own) |
| Billing | V C E | V | V E | **V (read-only)** | — |
| IPD admit / discharge / bed | V C E | V(clinical) | V C E | **—** | — |
| Wards & beds | V C E D | — | V E | **V** | — |
| Settings / fees / WhatsApp / plans | V C E D | — | — | **—** | — |
| Pharmacy / inventory | V | — | — | **—** | V C E |

"win" = within a configurable edit window (default: same shift / N hours).

---

## PART 22 — Future Compatibility (business)

Each Phase-1 feature is a strict subset of its future self; none blocks growth:

- **Medium hospital (more beds/nurses):** assignment table already supports many
  nurses and many admissions; only UI paging scales.
- **Shift management:** add `shift`/`roster` tables + a `shift_id` on assignment
  and vitals; Phase-1 rows default to a single implicit shift — no rewrite.
- **Task engine:** `ManualTask` gains `recurrenceRule`, `sourceType`,
  `parentTaskId`; existing manual rows have these null and behave identically.
- **Automatic assignment:** an engine writes `PatientNurseAssignment` rows via
  the same service method the admin UI uses today.
- **Departments:** add `department_id` (nullable) to nurse profile + admission;
  Phase-1 single-ward hospitals leave it null.
- **ICU / OT / Emergency / Multi-speciality:** these become new admission
  *types* / new modules; nurse tables key off `ipd_admission`, so a
  differently-typed admission still gets vitals/notes/MAR for free. No existing
  API or column changes — only additive tables/columns and new module flags.

---

*End of Document 1. Technical schema, ER diagrams, API contracts, and
frontend/backend architecture are in Document 2. Milestones and test plan are in
Document 3.*
