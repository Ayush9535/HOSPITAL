# OT — Implementation Specification

**Date:** 2026-07-10 · **Scope:** HOSPITAL tenants only
**Why these choices:** [OT ADR](2026-07-10-ot-adr.md) · **When:** [OT Roadmap](2026-07-10-ot-roadmap.md)

This document is the contract. A senior engineer should be able to implement from it without asking
"what did they mean". It states **what to build**, not why.

---

## 1. State machine

Nine states. `IN_THEATRE`, `WAITLISTED`, `RESCHEDULED`, `IN_RECOVERY` **do not exist** (ADR D5, D6).

```
REQUESTED ──approve──► APPROVED ──schedule──► SCHEDULED ──pre_op──► PRE_OP ──start──► IN_PROGRESS
                          ▲                       │ │                                      │
                          │                       │ └──reschedule──┐                       │
                          └───postpone────────────┘                │                       │
                                                   ◄───────────────┘                    complete
                                                                                           │
                                                                                           ▼
                                          CLOSED ◄──close── COMPLETED
CANCELLED  ◄── cancel ── {REQUESTED, APPROVED, SCHEDULED, PRE_OP}          (terminal)
```

### Transition table (the only place status is written)

| From | To | Permission | Policy precondition | Notes |
|---|---|---|---|---|
| `REQUESTED` | `APPROVED` | `OT_APPROVE` | `APPROVAL_MODE` | `NONE` ⇒ auto-transition, `actor=SYSTEM`, `reason=AUTO_APPROVED_BY_POLICY` |
| `REQUESTED` | `CANCELLED` | `OT_CANCEL` | `CANCELLATION_REASON` | reason from `cancellation_reasons` |
| `APPROVED` | `SCHEDULED` | `OT_SCHEDULE` | room free ∧ surgeon free | pessimistic lock on `ot_rooms` row |
| `APPROVED` | `CANCELLED` | `OT_CANCEL` | `CANCELLATION_REASON` | |
| `SCHEDULED` | `SCHEDULED` | `OT_RESCHEDULE` | room free ∧ surgeon free | **reschedule**; payload carries `{oldSlot,newSlot}` |
| `SCHEDULED` | `APPROVED` | `OT_RESCHEDULE` | — | **postpone**; returns to the waiting list |
| `SCHEDULED` | `PRE_OP` | `OT_PRE_OP` | `PRE_OP_CHECKLIST`, `ANAESTHESIA_CLEARANCE`, `FINANCIAL_CLEARANCE` (unordered set) | |
| `SCHEDULED` | `CANCELLED` | `OT_CANCEL` | `CANCELLATION_REASON` | |
| `PRE_OP` | `IN_PROGRESS` | `OT_START` | `WHO_CHECKLIST_MODE=BLOCKING` ⇒ Sign-In + Time-Out signed | frees nothing; room already held |
| `PRE_OP` | `CANCELLED` | `OT_CANCEL` | `CANCELLATION_REASON` | |
| `IN_PROGRESS` | `COMPLETED` | `OT_COMPLETE` | `WHO_CHECKLIST_MODE=BLOCKING` ⇒ Sign-Out signed | **releases the room** → `CLEANING` |
| `COMPLETED` | `CLOSED` | `OT_CLOSE` | `CLOSE_REQUIRES` ⊆ `{OPERATIVE_NOTE, WHO_SIGN_OUT, DISPOSITION}` | never billing, never discharge (ADR D7) |

**Every** transition writes exactly one `surgery_state_transitions` row, including auto-satisfied ones.
Order of validation, server-side, always: **permission → state legality → policy precondition.**

### Milestones (append-only facts, never states)

`PATIENT_ENTERED_OT`, `ANAESTHESIA_START`, `INCISION`, `CLOSURE`, `ANAESTHESIA_END`,
`LEFT_THEATRE`, `ARRIVED_RECOVERY`, `LEFT_RECOVERY`, `TRANSFERRED`.

### Waiting list (derived — ADR D6)

```sql
SELECT * FROM surgeries
WHERE hospital_id = ? AND status = 'APPROVED' AND scheduled_at IS NULL
ORDER BY waitlist_priority DESC, target_date ASC, approved_at ASC;
```

---

## 2. Permissions (15 codes)

`OT_VIEW`, `OT_CREATE`, `OT_APPROVE`, `OT_SCHEDULE`, `OT_RESCHEDULE`, `OT_CANCEL`,
`OT_ASSIGN_ROOM`, `OT_ASSIGN_TEAM`, `OT_PRE_OP`, `OT_TIME_OUT`, `OT_START`, `OT_COMPLETE`,
`OT_RECOVERY`, `OT_TRANSFER`, `OT_CLOSE`  (+ `OT_SETTINGS` for policy administration).

**Day-1 seed — reproduces current behaviour exactly. Do not "improve" it in Phase 2.**

| Role | Permissions |
|---|---|
| `DOCTOR` | `OT_VIEW`, `OT_CREATE` |
| `RECEPTIONIST` | `OT_VIEW`, `OT_APPROVE`, `OT_SCHEDULE`, `OT_RESCHEDULE`, `OT_CANCEL`, `OT_ASSIGN_ROOM`, `OT_START`, `OT_COMPLETE`, `OT_CLOSE` |
| `NURSE`, `NURSE_INCHARGE` | `OT_VIEW`, `OT_PRE_OP`, `OT_TIME_OUT` |
| `HOSPITAL_ADMIN` | all + `OT_SETTINGS` |

Minting (Phase 2), in `JwtAuthenticationFilter` — **additive**, `ROLE_*` untouched:

```java
List<GrantedAuthority> auths = new ArrayList<>();
auths.add(new SimpleGrantedAuthority("ROLE_" + role));           // unchanged — Clinic/Pharmacy rely on it
permissions.forEach(p -> auths.add(new SimpleGrantedAuthority(p))); // OT_SCHEDULE, …
```

Then `@PreAuthorize("hasAuthority('OT_SCHEDULE')")`. **No custom `PermissionEvaluator`.**
If the token carries no permission claim (issued before deploy), fall back to the seed map for one release.

---

## 3. Policy keys

Resolution: `(hospital_id, policy_key, priority_scope)` where `priority_scope ∈ {ANY, ELECTIVE, EMERGENCY}`.
Lookup order: exact `priority_scope` → `ANY` → **hardcoded default**. Absent row = default (no seeding).

| Key | Values | Default | Small | Medium | Large | Corporate |
|---|---|---|---|---|---|---|
| `APPROVAL_MODE` | `NONE\|SINGLE\|DUAL` | `NONE` | `NONE` | `SINGLE` | `SINGLE` | `DUAL` |
| `WHO_CHECKLIST_MODE` | `OFF\|ADVISORY\|BLOCKING` | `ADVISORY` | `ADVISORY` | `BLOCKING` | `BLOCKING` | `BLOCKING` |
| `PRE_OP_CHECKLIST` | `OFF\|REQUIRED` | `OFF` | `OFF` | `REQUIRED` | `REQUIRED` | `REQUIRED` |
| `ANAESTHESIA_CLEARANCE` | `OFF\|REQUIRED` | `OFF` | `OFF` | `REQUIRED` | `REQUIRED` | `REQUIRED` |
| `FINANCIAL_CLEARANCE` | `OFF\|CASH_ONLY\|ALL` | `OFF` | `OFF` | `CASH_ONLY` | `CASH_ONLY` | `ALL` |
| `RECOVERY_TRACKING` | `NONE\|MILESTONE\|PACU_EPISODE` | `NONE` | `NONE` | `MILESTONE` | `PACU_EPISODE` | `PACU_EPISODE` |
| `CLOSE_REQUIRES` | csv ⊆ `{OPERATIVE_NOTE,WHO_SIGN_OUT,DISPOSITION}` | `` (empty) | `` | `OPERATIVE_NOTE` | `OPERATIVE_NOTE,DISPOSITION` | all three |
| `TEAM_CAPTURE` | `SURGEON_ONLY\|SURGEON_ANAES\|FULL_TEAM` | `SURGEON_ONLY` | `SURGEON_ONLY` | `SURGEON_ANAES` | `FULL_TEAM` | `FULL_TEAM` |
| `CANCELLATION_REASON` | `OPTIONAL\|REQUIRED` | `OPTIONAL` | `OPTIONAL` | `REQUIRED` | `REQUIRED` | `REQUIRED` |

**Emergency overrides** (seeded as `priority_scope=EMERGENCY` rows by the presets):
`APPROVAL_MODE=NONE`, `WHO_CHECKLIST_MODE=ADVISORY`, `FINANCIAL_CLEARANCE=OFF`.

An **archetype preset** is a bulk write of these rows. It is **not stored on the hospital** — a hospital
that later diverges from its preset must not become a special case.

---

## 4. Schema

All tables: `hospital_id BIGINT NOT NULL` + FK to `hospitals(id) ON DELETE CASCADE`.
Migrations are idempotent `ensureXxx()` methods in `DatabaseMigrationRunner`, **mirrored in `setup/schema-full.sql`**.

### 4.1 Changes to `surgeries` (hospital-only table)

```sql
ALTER TABLE surgeries MODIFY ipd_admission_id BIGINT NULL;        -- D13 day-care
ALTER TABLE surgeries ADD COLUMN encounter_type VARCHAR(20) NOT NULL DEFAULT 'IPD'; -- IPD | DAY_CARE
ALTER TABLE surgeries ADD COLUMN ot_room_id BIGINT NULL;
ALTER TABLE surgeries ADD COLUMN waitlist_priority INT NOT NULL DEFAULT 0;
ALTER TABLE surgeries ADD COLUMN target_date DATE NULL;
ALTER TABLE surgeries ADD COLUMN approved_at DATETIME NULL;
ALTER TABLE surgeries ADD COLUMN estimated_duration_minutes INT NULL;
ALTER TABLE surgeries ADD COLUMN laterality VARCHAR(10) NULL;      -- LEFT|RIGHT|BILATERAL|NA
ALTER TABLE surgeries ADD COLUMN anaesthesia_type VARCHAR(30) NULL;
CREATE INDEX idx_surgery_hospital_scheduled ON surgeries(hospital_id, scheduled_at);
CREATE INDEX idx_surgery_room_scheduled     ON surgeries(ot_room_id, scheduled_at);
```

> **Guard:** every existing query assuming `ipd_admission_id IS NOT NULL` must be audited in Phase 1.
> `ot_ward_id` is retained and dual-read for one release, then dropped.

### 4.2 `surgery_forms` — the 3-step migration (order matters)

```sql
-- 1. backfill: attach each form to the admission's earliest non-cancelled surgery
UPDATE surgery_forms f JOIN (
  SELECT ipd_admission_id, MIN(id) AS sid FROM surgeries
  WHERE status <> 'CANCELLED' GROUP BY ipd_admission_id
) s ON s.ipd_admission_id = f.ipd_admission_id
SET f.surgery_id = s.sid WHERE f.surgery_id IS NULL;
-- emit a reconciliation report for admissions with >1 surgery. Never guess silently.

-- 2. re-key  (MySQL treats NULLs as distinct → step 1 must complete first)
ALTER TABLE surgery_forms DROP INDEX UK_surgery_form_admission_type;
ALTER TABLE surgery_forms ADD CONSTRAINT UK_surgery_form_surgery_type UNIQUE (surgery_id, form_type);

-- 3. tighten
ALTER TABLE surgery_forms MODIFY surgery_id BIGINT NOT NULL;

-- signature + versioning (D11, D12)
ALTER TABLE surgery_forms ADD COLUMN version INT NOT NULL DEFAULT 1;
ALTER TABLE surgery_forms ADD COLUMN signed_at DATETIME NULL;
ALTER TABLE surgery_forms ADD COLUMN signed_by_user_id BIGINT NULL;
ALTER TABLE surgery_forms ADD COLUMN recorded_by_user_id BIGINT NULL;
-- promoted, reportable/blocking fields (never query data_json)
ALTER TABLE surgery_forms ADD COLUMN sign_in_at DATETIME NULL;
ALTER TABLE surgery_forms ADD COLUMN time_out_at DATETIME NULL;
ALTER TABLE surgery_forms ADD COLUMN sign_out_at DATETIME NULL;
ALTER TABLE surgery_forms ADD COLUMN counts_correct BOOLEAN NULL;
ALTER TABLE surgery_forms ADD COLUMN site_marked BOOLEAN NULL;
```

Once `signed_at IS NOT NULL` the row is immutable: an edit inserts a new row with `version+1`.
The unique key therefore applies to the **current** version — enforce via `is_current BOOLEAN` in the key:
`UNIQUE (surgery_id, form_type, is_current)` with `is_current` NULL for superseded rows.

### 4.3 New tables (MVP — 8)

```sql
CREATE TABLE surgery_state_transitions (   -- Phase 3. The one table that cannot wait.
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  hospital_id BIGINT NOT NULL,
  surgery_id BIGINT NOT NULL,
  from_status VARCHAR(20) NULL,            -- NULL on creation
  to_status VARCHAR(20) NOT NULL,
  actor_user_id BIGINT NULL,               -- NULL ⇒ actor = SYSTEM
  actor_kind VARCHAR(10) NOT NULL,         -- USER | SYSTEM
  reason_code VARCHAR(60) NULL,
  reason_text VARCHAR(255) NULL,
  payload_json JSON NULL,                  -- reschedule: {oldSlot,newSlot}
  created_at DATETIME NOT NULL,
  KEY idx_sst_surgery (surgery_id, created_at),
  KEY idx_sst_hospital_to (hospital_id, to_status, created_at)   -- backs every metric
);

CREATE TABLE ot_rooms (                    -- Phase 4. Simple by decision (D9).
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  public_id VARCHAR(36) NOT NULL UNIQUE,
  hospital_id BIGINT NOT NULL,
  name VARCHAR(100) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',  -- AVAILABLE|OCCUPIED|CLEANING|MAINTENANCE
  current_surgery_id BIGINT NULL,
  turnover_minutes INT NOT NULL DEFAULT 15,
  is_active BOOLEAN NOT NULL DEFAULT TRUE,
  UNIQUE KEY uk_ot_room_name (hospital_id, name)
);

CREATE TABLE ot_workflow_policies (        -- Phase 5. Override rows only; absent = default.
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  hospital_id BIGINT NOT NULL,
  policy_key VARCHAR(40) NOT NULL,
  priority_scope VARCHAR(10) NOT NULL DEFAULT 'ANY',  -- ANY|ELECTIVE|EMERGENCY
  value VARCHAR(120) NOT NULL,
  UNIQUE KEY uk_policy (hospital_id, policy_key, priority_scope)
);

CREATE TABLE permissions (                 -- Phase 2. Platform master, not hospital-scoped.
  code VARCHAR(40) PRIMARY KEY,
  module VARCHAR(20) NOT NULL,             -- 'OT'
  description VARCHAR(255) NOT NULL
);

CREATE TABLE role_permissions (            -- Phase 2. Role STRING, not a designation FK (D2/F12).
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  hospital_id BIGINT NOT NULL,
  role VARCHAR(30) NOT NULL,
  permission_code VARCHAR(40) NOT NULL,
  UNIQUE KEY uk_role_perm (hospital_id, role, permission_code)
);

CREATE TABLE cancellation_reasons (        -- Phase 3. NABH: cancellation rate BY REASON.
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  hospital_id BIGINT NOT NULL,
  code VARCHAR(40) NOT NULL,
  label VARCHAR(120) NOT NULL,
  category VARCHAR(30) NOT NULL,           -- PATIENT|SURGEON|FACILITY|ADMINISTRATIVE|CLINICAL
  is_active BOOLEAN NOT NULL DEFAULT TRUE,
  UNIQUE KEY uk_cancel_reason (hospital_id, code)
);

CREATE TABLE surgery_milestones (          -- Phase 7. Append-only facts.
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  hospital_id BIGINT NOT NULL,
  surgery_id BIGINT NOT NULL,
  milestone VARCHAR(30) NOT NULL,
  occurred_at DATETIME NOT NULL,
  recorded_by_user_id BIGINT NULL,
  performed_by_nurse_id BIGINT NULL,       -- reuse PerformingNurseResolver
  KEY idx_milestone_surgery (surgery_id, occurred_at)
);

CREATE TABLE case_roles (                  -- Phase 6. Principle 3: a new role is a ROW.
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  hospital_id BIGINT NOT NULL,
  code VARCHAR(40) NOT NULL,               -- PRIMARY_SURGEON|ASSISTANT|ANAESTHETIST|SCRUB_NURSE|…
  label VARCHAR(120) NOT NULL,
  UNIQUE KEY uk_case_role (hospital_id, code)
);

CREATE TABLE surgery_team_members (        -- Phase 6. Replaces the two free-text name columns.
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  hospital_id BIGINT NOT NULL,
  surgery_id BIGINT NOT NULL,
  case_role_id BIGINT NOT NULL,
  user_id BIGINT NULL,                     -- internal staff
  external_name VARCHAR(255) NULL,         -- external operator: legitimate denormalisation
  KEY idx_team_surgery (surgery_id),
  CONSTRAINT chk_team_identity CHECK (user_id IS NOT NULL OR external_name IS NOT NULL)
);
```

`surgeries.surgeon_name` / `anaesthetist_name` stay **nullable** after Phase 6 as the external fallback.

---

## 5. API surface (all `@TenantType(HOSPITAL)` + `@RequireModule("OT")`, never `/clinic`-aliased)

| Method | Path | Permission | Phase |
|---|---|---|---|
| `POST` | `/hospital/ot/surgeries` | `OT_CREATE` | 1 |
| `GET` | `/hospital/ot/surgeries/{id}` | `OT_VIEW` | 1 |
| `GET` | `/hospital/ot/surgeries/{id}/allowed-transitions` | `OT_VIEW` | 3 |
| `POST` | `/hospital/ot/surgeries/{id}/transition` | *per transition table* | 3 |
| `GET` | `/hospital/ot/waiting-list` | `OT_VIEW` | 3 |
| `GET/POST/PUT/DELETE` | `/hospital/ot/rooms` | `OT_ASSIGN_ROOM` / `OT_SETTINGS` | 4 |
| `GET` | `/hospital/ot/board?date=` | `OT_VIEW` | 4 |
| `GET` | `/hospital/ot/list?date=` (print) | `OT_VIEW` | 4 |
| `GET/PUT` | `/hospital/ot/policies` | `OT_SETTINGS` | 5 |
| `POST` | `/hospital/ot/policies/preset/{archetype}` | `OT_SETTINGS` | 5 |
| `GET` | `/hospital/ot/analytics/summary` | `OT_VIEW` | 5 |
| `GET` | `/hospital/ot/permissions` (caller's effective set) | authenticated | 2 |
| `POST` | `/hospital/ot/surgeries/{id}/team` | `OT_ASSIGN_TEAM` | 6 |
| `POST` | `/hospital/ot/surgeries/{id}/checklist/{phase}/sign` | `OT_TIME_OUT` | 7 |
| `POST` | `/hospital/ot/surgeries/{id}/milestones` | `OT_START`/`OT_COMPLETE` | 7 |
| `POST` | `/hospital/ot/surgeries/{id}/operative-note` | `OT_COMPLETE` | 7 |

The legacy `/hospital/surgeries/**` and `/hospital/surgery-forms/**` routes remain as **deprecated shims**
delegating to the new ones until the frontend migrates; they are deleted at the end of Phase 4.

---

## 6. Package layout

```
service/hospital/ot/
  SurgeryStateMachine.java     // the ONLY writer of surgeries.status
  SurgeryCaseService.java      // create, read, waiting list
  OtRoomService.java           // rooms; pessimistic lock on booking
  OtSchedulingService.java     // room + surgeon overlap checks
  OtPolicyService.java         // lazy-default resolution (hospital, key, scope)
  PolicyGuard.java             // preconditions per transition
  SurgeryTeamService.java
  SurgeryFormService.java      // signed, versioned, surgery-scoped
  OtAnalyticsService.java      // read models over surgery_state_transitions
  OtBedFacade.java             // the ONLY caller of BedStatusService (clinic-aliased)
```

`SurgeryService` (existing) becomes a `@Deprecated` delegating shim in Phase 3. The build fails on new usage.

---

## 7. Concurrency

**Room booking** (Phase 4) — an overlap cannot be expressed as a unique index:

```java
@Transactional
public void schedule(Long surgeryId, Long roomId, LocalDateTime start, int durationMin) {
    OtRoom room = otRoomRepository.findByIdForUpdate(roomId);   // SELECT … FOR UPDATE
    LocalDateTime end = start.plusMinutes(durationMin + room.getTurnoverMinutes());
    if (surgeryRepository.existsOverlapInRoom(roomId, start, end))
        throw new IllegalArgumentException("That theatre is already booked for this slot");
    if (surgeryRepository.existsOverlapForSurgeon(surgeonId, start, end))   // ADR D10
        throw new IllegalArgumentException("This surgeon is already operating at that time");
    stateMachine.transition(surgery, SCHEDULED, actor, null, slotPayload());
}
```

Touching intervals (`prevEnd == nextStart`) **do not** conflict. Overlap is `start < existingEnd AND end > existingStart`.

---

## 8. Testing strategy (per phase)

| Phase | The test that proves the phase |
|---|---|
| 0A | A Clinic JWT gets **403** on `/hospital/surgeries`. A hospital without `OT` gets **403**. |
| 0B | Every `/clinic/**` and `/pharmacy/**` route passes, **unmodified**, on every OT PR. |
| 1 | Two surgeries in one admission, a consent signed on each → **both survive**. A day-care surgery with `ipd_admission_id = NULL` completes end-to-end. A signed form cannot be mutated by any code path. |
| 2 | Every OT endpoint: the pre-refactor role still passes; a role lacking the permission now fails. Granting `OT_SCHEDULE` to `DOCTOR` lets the surgeon schedule **with no code change**. |
| 3 | Exhaustive from×to matrix (legal and illegal). Concurrent double-transition. Reschedule writes `SCHEDULED→SCHEDULED` carrying both slots. |
| 4 | Two concurrent bookings on one room → exactly one wins. Touching intervals do not conflict. One surgeon, two rooms, overlapping → rejected. OT List matches a fixture. |
| 5 | **The policy matrix test:** 4 archetypes × the happy path. Each required step blocks; each disabled step auto-satisfies with a `SYSTEM` transition row. *This is the proof of the whole architecture.* |
| 6 | A transplant scenario adds `HARVEST_SURGEON` as a **master row** and passes with **no code change**. |
| 7 | Incision without a signed Time-Out is rejected **by the API**, not hidden in the UI. |
| 8 | The room is `AVAILABLE` **while** the case is `COMPLETED` and the patient is still in recovery. |
| 9 | Each board metric is one query and matches a hand-computed fixture. |

---

## 9. Definition of done for the whole programme

1. Four archetype hospitals run end-to-end on **one codebase**, with **zero** conditionals on hospital type or role string.
2. A new hospital archetype is a row in `ot_workflow_policies`; a new case role is a row in `case_roles`; a new designation is a role string + `role_permissions` rows. **None is a code change.**
3. The Clinic and Pharmacy suites have never been modified.
4. Every state change is attributable: actor, time, reason.
