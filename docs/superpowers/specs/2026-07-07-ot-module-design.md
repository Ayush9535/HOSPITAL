# Operation Theatre (OT) Module — Phase 2 Design

**Date:** 2026-07-07
**Status:** Approved for planning
**Scope:** Hospital-tenant OT surgery workflow (request → schedule → live → complete), gated by the existing `OT` plan module.

---

## 1. Background & Phase 1 (already present — no code)

The `OT` plan module already exists and gates an "Operation Theatre" tab for the Hospital Admin (currently a "coming soon" placeholder). Phase 2 builds the real workflow on top of the existing IPD, ward/bed, nurse-assignment, and notification infrastructure.

Two Phase‑1 conventions this design relies on (no new code needed):

- **Surgeon detection:** a doctor is a "surgeon" when their free-text `Doctor.specialization` contains "surgeon" (case-insensitive). No schema change.
- **OT room = a ward named "OT" with a single bed.** The admin creates a ward whose `ward_name` is (or contains) "OT", with exactly one bed. OT wards are identified by this name convention. The single bed enforces one-surgery-at-a-time through the existing `Bed.status` occupancy mechanism.

Non-goals for Phase 2: anaesthetist scheduling, surgeon free-date calendars, and anaesthesia checks are handled **manually/offline** and are not modelled. The Hospital Admin OT tab stays a placeholder.

---

## 2. Roles & Visibility

| Actor | Capability |
|---|---|
| **Any doctor** | Create a Surgery Request from the IPD case view (OT module gated). See the request's status there. |
| **Surgeon doctors only** | Additionally get the **"Operation Theatre" tab** — a board of *their own* scheduled + live surgeries (where they are the assigned surgeon or the requester). No "Requests" filter. |
| **Reception** | **"Operation Theatre" tab** with two filters: **Requests** (schedule / cancel) and **Scheduled/Live** (start / complete). |
| **Nurse** | Notified when a surgery is scheduled for their patient; records OT notes during the surgery from the patient detail view. |
| **Hospital Admin** | OT tab remains the existing placeholder (out of scope). |

All OT controllers map under `/hospital/**` and are gated with `@RequireModule("OT")`. Tenant isolation by `hospitalId` on every query.

---

## 3. Data Model

### 3.1 New entity — `Surgery` (`surgeries` table)

One active surgery per IPD admission (see §5). Fields:

| Field | Type | Set by / when |
|---|---|---|
| `id` / `publicId` | PK / unique string | system |
| `hospitalId` | Long, not null | system (tenant) |
| `ipdAdmissionId` | Long FK → IpdAdmission, not null | on request |
| `patientId` | Long, not null (denormalised for lists) | on request |
| `procedureName` | String | doctor |
| `clinicalNotes` | text | doctor (diagnosis / pre-op notes) |
| `priority` | String — `ELECTIVE` \| `EMERGENCY` | doctor |
| `preferredDate` | LocalDate, nullable | doctor (target date) |
| `requestedByUserId` | Long | doctor |
| `requestedAt` | LocalDateTime | on request |
| `status` | String — `REQUESTED` → `SCHEDULED` → `IN_PROGRESS` → `COMPLETED` (+ `CANCELLED`) | lifecycle |
| `surgeonUserId` | Long, nullable | reception, at schedule |
| `scheduledAt` | LocalDateTime, nullable | reception, at schedule (date + time) |
| `otWardId` | Long, nullable | reception, at schedule |
| `scheduledByUserId` | Long, nullable | reception |
| `startedAt` | LocalDateTime, nullable | reception, at start |
| `completedAt` | LocalDateTime, nullable | reception, at complete |
| `createdAt` / `updatedAt` | timestamps | system |

Migration via `DatabaseMigrationRunner.ensureSurgeriesTable()` (idempotent, try/catch, appended to `runMigrations()`) and mirrored in `setup/schema-full.sql`.

### 3.2 Extend `NursingNote` (Option A — OT notes)

Add one nullable column:

- `surgeryId` — Long, nullable, FK → Surgery.

The existing `category` column already exists; OT notes use `category = "OT"` and set `surgeryId`. Migration via `DatabaseMigrationRunner.ensureNursingNoteSurgeryId()` + schema mirror. No new entity/panel — reuses the nurse's existing Notes infrastructure.

---

## 4. Lifecycle & Data Flow

1. **Request (doctor).** From the IPD case view, any doctor submits `procedureName`, `clinicalNotes`, `priority`, `preferredDate` → `Surgery` created with `status = REQUESTED`. The patient's `IpdAdmission.status` stays `ADMITTED`. Appears in reception's **Requests** filter.

2. **Schedule (reception).** From the **Requests** filter, reception opens a Schedule modal: choose a **surgeon** (from the surgeon-doctor list), `scheduledAt` (date + time), and the **OT ward** → `status = SCHEDULED`. Side effects:
   - **Notify the patient's assigned nurse** — `PatientNurseAssignmentRepository.findByIpdAdmissionIdAndIsActiveTrue(ipdAdmissionId)` → `NotificationService.create(nurseUserId, hospitalId, "OT_SCHEDULED", title, body, …)`.
   - **Notify the assigned surgeon** similarly (`"OT_ASSIGNED"`).
   Now visible in the **Scheduled/Live** filter and (for the surgeon) on their OT tab.

3. **Start (reception).** From **Scheduled/Live**, mark a scheduled surgery live → `status = IN_PROGRESS`, `startedAt` set, and the OT ward's single **bed marked occupied**. Blocked if that OT bed is already occupied by another live surgery.

4. **During surgery (nurse).** While the surgery is `SCHEDULED`/`IN_PROGRESS`, an **"OT Notes"** section appears in the nurse's patient detail. Notes created there are timestamped (`recordedAt`) and saved as `NursingNote` with `surgeryId` set and `category = "OT"`. These are the operative nursing record. (A printable "Operative Nursing Record" form pulling these notes is a small later addition, not in Phase 2.)

5. **Complete (reception).** Mark live surgery done → `status = COMPLETED`, `completedAt` set, OT **bed freed**. The patient never left their IPD ward/bed, so they are simply back in IPD. No separate completion-notes field (the nurse's OT notes are the record).

`CANCELLED`: reception may cancel a `REQUESTED`/`SCHEDULED` surgery (frees the OT bed if it was reserved). This resolves the "one active surgery per admission" guard so a new request can be made.

---

## 5. Backend Components

- `entity/Surgery.java`
- `repository/SurgeryRepository.java` — finders: by `hospitalId` + status set (board), by `hospitalId` + `REQUESTED` (requests), by `surgeonUserId` (surgeon board), by `ipdAdmissionId` (active-guard & IPD-case status), by `publicId`.
- DTOs: `CreateSurgeryRequest` (doctor), `ScheduleSurgeryRequest` (reception: surgeonUserId, scheduledAt, otWardId), `SurgeryView` (list/detail projection incl. patient name/age/sex, IPD number, bed, surgeon name, ward name).
- `service/hospital/SurgeryService.java`: `createRequest`, `listRequests`, `schedule` (assign + notify nurse & surgeon), `start`, `complete`, `cancel`, `listBoard` (role-scoped: reception = all scheduled+live; surgeon = own), `listMyCaseSurgery(admissionId)` (for the IPD case view), `listSurgeons`.
- `controller/hospital/SurgeryController.java` under `/hospital/surgeries/**`, `@RequireModule("OT")`, role checks (doctor creates; reception schedules/starts/completes/cancels).
- Reuse `NursingNoteService` for OT notes: accept optional `surgeryId` + `category` on note creation; add a finder for `surgeryId`.

**Guards & errors** (via `GlobalExceptionHandler` → 400/403):
- One active (non-`COMPLETED`/`CANCELLED`) surgery per IPD admission.
- Schedule requires a surgeon and an existing OT ward; if no OT ward exists, return a clear message ("Create a ward named 'OT' first").
- Start blocked if the OT bed is already occupied by another live surgery.
- Role + module + tenant enforcement on every endpoint.

---

## 6. Frontend Components

- **Doctor IPD case view** ([IpdDetails.jsx](../../../frontend/src/pages/hospital/IpdDetails.jsx)): in the slot where "Billing" appears for billing-handlers (doctors don't bill at hospital level), add **Create Surgery Request** (OT-module gated). Form: procedure name, clinical notes, priority, preferred date. If an active surgery already exists for the admission, show its status/detail instead of the form.
- **Receptionist dashboard — new "Operation Theatre" tab** (OT gated): top-right two-filter pattern mirroring the IPD tab —
  - **Scheduled/Live** (default): `SCHEDULED` + `IN_PROGRESS`; actions **Start**, **Complete**.
  - **Requests**: `REQUESTED`; actions **Schedule** (modal: surgeon dropdown, date/time, OT ward), **Cancel**.
- **Doctor dashboard — new "Operation Theatre" tab**, shown **only to surgeon doctors**: their scheduled + live board (assigned surgeon or requester). Read-only, no requests filter.
- **Nurse patient detail** — an **OT Notes** section that appears while the patient has a scheduled/live surgery, posting notes with `surgeryId` + `category = "OT"` through the existing notes flow.
- **Services:** `surgeryService.js` (or extend `hospitalService.js`) for the endpoints; nurse note create extended to pass `surgeryId`/`category`.

---

## 7. Testing & Verification

- `SurgeryServiceTest`: create request; schedule (status transition + nurse & surgeon notifications); start/complete (OT bed occupancy toggling); one-active-per-admission guard; no-OT-ward error; tenant isolation; role/module gating.
- Nurse OT-note path covered in the nursing-note service test (surgeryId + category persisted).
- Each milestone verified with `mvn -o test` (backend) and `npx vite build --mode development` (frontend, run from `frontend/`).

---

## 8. Milestones (for the implementation plan)

- **M1 — Backend:** `Surgery` entity/repo/migration, `NursingNote.surgeryId` migration, `SurgeryService` (all transitions + notifications + guards), `SurgeryController`, surgeon-list endpoint, tests.
- **M2 — Doctor Create Surgery Request** in the IPD case view (+ status display when one exists).
- **M3 — Reception OT tab:** Requests + Scheduled/Live filters; Schedule / Cancel / Start / Complete.
- **M4 — Doctor OT board** (surgeon-only tab) + **nurse OT Notes** section.

Standing constraints: do not commit/push unless explicitly asked; clean up any curl test data; nurse/OT controllers map only `/hospital/**`.
