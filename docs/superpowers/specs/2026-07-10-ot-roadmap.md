# OT — Implementation Roadmap

**Date:** 2026-07-10 · **Scope:** HOSPITAL tenants only
**Decisions:** [OT ADR](2026-07-10-ot-adr.md) · **Schema/API:** [OT Implementation Spec](2026-07-10-ot-implementation-spec.md)

Ordered by **risk retired per unit of work**. Phase 0A is a days-long hard gate; **everything after it is
OT feature work and is not blocked by security work.** Phases 0B and 6+ can run in parallel with 1–5.

| Phase | Name | Blocks OT? | Size |
|---|---|---|---|
| **0A** | Critical fixes: module gate, tenant annotation, alias removal | **Yes (days)** | S |
| 0B | Clinic/Pharmacy regression suite | No — parallel | M |
| 1 | Case integrity + surgery-as-aggregate (day-care) | Yes | L |
| 2 | Permission layer (behaviour-identical) | Yes | M |
| 3 | Domain core: state machine + transition audit | Yes | M |
| 4 | Rooms, scheduling, OT List, OT Calendar | Yes | L |
| 5 | Policy engine **+ basic analytics** | Yes | L |
| 6 | Team & case roles | No | M |
| 7 | Pre-op, WHO 3-phase, intra-op, operative note | No | L |
| 8 | Recovery/PACU, transfer, designation master | No | M |
| 9 | NABH/JCI analytics, room occupancy timeline | No | M |
| — | **Backlog:** equipment/CSSD, blood bank, OT billing, doctor leave | No | — |

---

## Phase 0A — Critical Fixes (hard gate, days)

- **Objective:** make tenant-type and module isolation server-side invariants.
- **Business problem:** OT/NURSING are sold as plan modules but not enforced (F1/F2); tenant isolation lives in an axios interceptor (F3); a Clinic admin can reach an OT endpoint (F4).
- **Why required:** every later phase assumes a gate that does not exist. It is small; it must not become a project.
- **Dependencies:** none. Q1 and Q2 are answered — enforce outright.
- **Backend:** `hospitalType` → JWT claim + `UserAuthenticationDetails`; new `@TenantType` + `TenantTypeAspect`; fix `ModuleAccessAspect` to `@annotation(...) || @within(...)` **and scope it to `HospitalType.HOSPITAL` (F16 — mandatory, see ADR D1)**; strip `/clinic`,`/pharmacy` from `OtInchargeController`.
- **Frontend:** none — the axios rewrite stays as defence in depth.
- **Database / API / UI:** none. Some **hospital** calls begin returning 403 **by design**. **No Clinic or Pharmacy call changes.**
- **Security:** this *is* the phase. Tokens issued before deploy lack `hospitalType`; treat an absent claim as unrestricted for one release, then require it.
- **Testing:** a matrix test (tenant type × module-gated controller → expected 200/403). Assert a Clinic JWT gets 403 on `/hospital/surgeries`. **Assert Clinic still gets 200 on `/clinic/hospital-inventory` and Pharmacy on `/pharmacy/appointments`** — the F16 regression.
- **Risks:** *(Critical, F16)* a naive `@within` fix permanently 403s Clinic on `/clinic/hospital-inventory` and Pharmacy on `/pharmacy/{appointments, hospital-inventory, settings/fees/custom}`, because those modules **cannot be granted** to those plan types. Mitigation: **the tenant-type scope in ADR D1 is not optional.** *(Medium)* enforcing `@RequireModule` revokes hospital access where a plan lacks the module. Mitigation: audit `plan_modules` for every active hospital before merge. Hospital is pre-production, so no grandfathering.
- **Deliverables:** working tenant + module gate; plan audit report.
- **Exit criteria:** Clinic JWT → 403 on every OT/nursing endpoint; a hospital without `OT` → 403; Clinic + Pharmacy suites green **and unmodified**.

## Phase 0B — Clinic/Pharmacy Regression Suite (parallel, non-blocking)

- **Objective:** a permanent guard proving OT work never touches Clinic or Pharmacy.
- **Why required:** 22 controllers are shared (F11). Without this suite, invariant #6 is unverifiable.
- **Backend/Testing:** endpoint-level tests for every `/clinic/**` and `/pharmacy/**` route: auth, happy path, tenant scoping. Wire into CI as a required check.
- **Risks:** *(Low)* slow suite. Mitigation: `@DataJpaTest` slices + one full-context smoke test.
- **Exit criteria:** the suite is required on every PR touching `service/hospital/` or `controller/hospital/`.

---

## Phase 1 — Case Integrity & Surgery-as-Aggregate

- **Objective:** one signed form per **procedure**; a surgery may exist **without** an IPD admission.
- **Business problem:** F8 (a second surgery silently overwrites the first's consent — legal risk) and Q4 (day-care cataract/endoscopy cases cannot be represented).
- **Why required:** both are schema truths about the aggregate root. Changing them after Phase 4 means migrating every OT table and every UI that assumes `IpdDetails` is the entry point. **Cheapest now, by a wide margin.**
- **Dependencies:** Phase 0A.
- **Backend:** `SurgeryFormService` resolves and stamps `surgery_id`; reject writes to a signed form (append a version); `SurgeryService` accepts a `patientId` with a **null** `ipdAdmissionId` when `encounter_type=DAY_CARE`.
- **Frontend:** `SurgeryFormFrame` shows signature state + version history; signed forms render read-only. A new reception entry point: "Book day-care surgery" for a registered patient. `IpdDetails` remains **an** entry point, not the only one.
- **Database:** `surgeries.ipd_admission_id` → **nullable**; add `encounter_type`. Then 3 ordered migrations on `surgery_forms`: (1) backfill `surgery_id`; (2) add `UNIQUE(surgery_id, form_type)` and drop the admission key; (3) `surgery_id NOT NULL`. Add `signed_at`, `signed_by_user_id`, `version`, `recorded_by_user_id`.
- **API:** `POST /hospital/surgery-forms` takes `surgeryId`; forms are fetched by surgery, not admission; `GET …/versions`.
- **Security:** an `UPDATE` on a signed row is rejected in the service **and** unreachable via the API. Day-care surgeries are tenant-scoped by `hospital_id` exactly as IPD ones.
- **UI:** signed badge; version drawer; "performed by" vs "recorded by".
- **Testing:** (a) two surgeries in one admission, sign a consent on each → **both survive**; (b) a day-care surgery with `ipd_admission_id = NULL` completes end-to-end; (c) a signed form cannot be mutated by any code path.
- **Risks:** *(Medium)* backfill ambiguity where an admission already has two surgeries. Mitigation: attach to the earliest non-cancelled surgery, emit a reconciliation report, **never guess silently**. *(Medium)* nullable FK weakens a previously-guaranteed join — audit every query that assumes `ipd_admission_id IS NOT NULL`.
- **Deliverables:** procedure-scoped signed forms; day-care-capable surgery aggregate.
- **Exit criteria:** the two-surgery test passes; no form row has a null `surgery_id`; a day-care case is created, scheduled and completed with no admission.

## Phase 2 — Permission Layer (behaviour-identical)

- **Objective:** decouple authorization from role (Principles 2 & 4) with **zero** day-1 behaviour change.
- **Business problem:** F6 — a small hospital cannot let its surgeon schedule; a medium hospital's OT Coordinator must log in as "Reception", destroying the audit trail.
- **Why required:** it is the precondition for "one codebase, many hospital types". Nothing else unblocks it.
- **Dependencies:** Phase 0A.
- **Backend:** `permissions` master (15 `OT_*` codes) + `role_permissions`; `JwtAuthenticationFilter` mints permission authorities **alongside** `ROLE_*`; OT `@PreAuthorize` moves from `hasRole` to `hasAuthority`. **No designation table** (ADR D2/F12).
- **Frontend:** `useOtPermissions()`; OT components render by permission, never by role.
- **Database:** 2 new hospital-scoped tables + a seed that reproduces today's mapping exactly.
- **API:** `GET /hospital/ot/permissions` (caller's effective set); admin CRUD on the role→permission matrix.
- **Security:** authorities are **additive**; Clinic/Pharmacy `hasAnyRole` checks are untouched and never read a permission.
- **UI:** a permission matrix screen (role × permission) in **its own route**, following the `FilesAndAccessCard` pattern.
- **Testing:** for every OT endpoint, the pre-refactor role still passes and a role lacking the permission now fails. Golden-master the seed.
- **Risks:** *(Medium)* a token issued before deploy has no permission claim → fall back to the role map for one release. *(Low)* JWT growth — OT permissions only; measure the header.
- **Exit criteria:** every OT endpoint authorises on `hasAuthority`; day-1 access is byte-identical for every existing user; an admin can grant `OT_SCHEDULE` to `DOCTOR` and the surgeon schedules — **with no code change**.

## Phase 3 — Domain Core: State Machine + Transition Audit

- **Objective:** replace `String` status and `if` guards with an explicit, audited machine.
- **Business problem:** F9 — transitions are unaudited and unenforceable; NABH cannot be evidenced; reschedule history is lost.
- **Why required:** every later phase hangs a guard, a milestone, or a metric off a transition. **This is the one table that cannot wait.**
- **Dependencies:** Phases 0A, 1, 2.
- **Backend:** `service/hospital/ot/` bounded context; `SurgeryStatus` enum (9 states, **no** `IN_THEATRE`); declarative transition table; `SurgeryStateMachine.transition(surgery, to, actor, reason, payload)`; append-only `surgery_state_transitions`; `POSTPONED` + `cancellation_reasons` master; `SurgeryService` becomes a `@Deprecated` delegating shim.
- **Frontend:** actions rendered from a server-provided `allowedTransitions` list — the UI stops guessing.
- **Database:** `surgery_state_transitions`, `cancellation_reasons`; `surgeries.status` enum-backed (values unchanged → no data migration); waitlist attributes (`waitlist_priority`, `target_date`, `approved_at`); index `(hospital_id, scheduled_at)`.
- **API:** `GET /hospital/ot/surgeries/{id}/allowed-transitions`; `POST …/transition`; `GET /hospital/ot/waiting-list` (**derived** from `APPROVED` + no schedule — ADR D6).
- **Security:** every transition validates **permission → state legality → policy precondition**, in that order, server-side. Hiding a button is not access control.
- **UI:** a case timeline (who moved it, when, why) and a waiting list ordered by priority/target date.
- **Testing:** exhaustive from×to transition table (legal and illegal); a concurrency test on double-transition; reschedule writes a `SCHEDULED→SCHEDULED` row carrying old and new slots.
- **Risks:** *(Medium)* the shim hides a missed call site. Mitigation: `@Deprecated` + fail the build on new usage.
- **Exit criteria:** no OT status is written outside the state machine (enforced by an ArchUnit-style test); every transition has an audit row; the waiting list needs **no** new state.

## Phase 4 — Rooms, Scheduling, OT List & OT Calendar

- **Objective:** make the theatre a real, conflict-safe resource, and give every hospital its morning list.
- **Business problem:** F7 (`"FOOT WARD"` is a theatre; client-side; unvalidated); a genuine double-booking race; the same surgeon in three rooms at once (F13); **no printable OT List** — the one artefact every hospital produces daily.
- **Why required:** utilisation, turnover and on-time start are otherwise uncomputable, and the OT List is the module's daily proof of value.
- **Dependencies:** Phase 3.
- **Backend:** `OtRoomService` — **simple rooms only**: `name`, `status`, `current_surgery_id`, `turnover_minutes` (ADR D9; **no occupancy timeline**). Interval-overlap booking under a **pessimistic lock on the room row**. Surgeon-overlap rejection as a **query** (ADR D10). OT List + Calendar read models.
- **Frontend:** `GET /hospital/ot/rooms` replaces the client-side name filter. An **OT Board** (rooms × time) that degrades to a list at one room. An **OT Calendar** extending `HospitalCalendarService` (F15) with a room axis — **not** a second calendar. A print view for the OT List.
- **Database:** `ot_rooms`; `surgeries.ot_room_id` (nullable; dual-read with `ot_ward_id` for one release, then drop); index `(ot_room_id, scheduled_at)`.
- **API:** room CRUD; `GET /hospital/ot/board?date=`; `GET /hospital/ot/list?date=` (print); calendar extension.
- **Security:** `OT_ASSIGN_ROOM`. Room-scoped reads for OT nurses, mirroring `NurseInchargeGuard`'s ward scope.
- **UI:** the OT Board becomes the module's primary surface; the OT List prints via `PdfLayoutHelper` (patient, age, procedure, room, time, surgeon).
- **Testing:** two concurrent schedules on one room → exactly one succeeds; touching intervals do **not** conflict; the same surgeon in two rooms at overlapping times is rejected; the OT List matches a fixture.
- **Risks:** *(Medium)* migrating OT-named wards mis-identifies rooms. Mitigation: present a **proposed** list an admin confirms; never auto-convert. *(Low)* bed coupling — call `BedStatusService` through a hospital-only facade, **unchanged** (it is clinic-aliased).
- **Exit criteria:** no code references ward-name-contains-`"OT"`; the double-booking and surgeon-overlap tests pass; a hospital prints today's OT List.

## Phase 5 — Policy Engine + Basic Analytics

- **Objective:** make hospital variation configuration, not code — and pay the owner immediately.
- **Business problem:** small hospitals drown in steps they never perform; corporate hospitals cannot enforce the steps they must. Owners ask for today's numbers on day one.
- **Why required:** this is the phase the whole brief is about. Everything before it made it safe to build.
- **Dependencies:** Phases 3–4.
- **Backend:** `OtPolicyService` (lazy default, keyed `(hospital, key, priority_scope)`); `PolicyGuard` on every transition; auto-satisfied steps write a transition row with `actor=SYSTEM, reason=AUTO_*`; clearances are **unordered preconditions**, not a linear step. `OtAnalyticsService` — today's surgeries, completed, cancelled by reason, utilisation — all queries over `surgery_state_transitions` (ADR D14).
- **Frontend:** `useOtPolicy()`. **Every blocked action states its reason** ("Time-Out required before Start"), or support tickets follow.
- **Database:** `ot_workflow_policies` — override rows only, **no seeding**.
- **API:** `GET/PUT /hospital/ot/policies`; archetype presets as a bulk write; `GET /hospital/ot/analytics/summary`.
- **Security:** `OT_SETTINGS`; policy changes are audited via `AuditLogService`.
- **UI:** an OT Settings card + a one-click archetype preset (Small / Medium / Large / Corporate); an analytics strip on the OT Board.
- **Testing:** the **policy matrix test** — 4 archetypes × the full happy path, asserting each required step blocks and each disabled step auto-satisfies with a `SYSTEM` row. This test is the proof of the entire thesis.
- **Risks:** *(High)* a mis-set policy blocks a live theatre. Mitigation: `ADVISORY` before `BLOCKING`; an explicit, audited, permissioned break-glass override.
- **Exit criteria:** all four archetypes run end-to-end on **one codebase with zero conditionals on hospital type or role**; the owner sees today's four numbers.

## Phase 6 — Team & Case Roles *(parallel-safe)*

- **Objective:** record who was in the room.
- **Business problem:** F10 — free-text `surgeon_name`/`anaesthetist_name` make cardiac/transplant impossible and the audit unattributable.
- **Dependencies:** Phase 2.
- **Backend:** `case_roles` master; `SurgeryTeamService`. Rename the `OT_INCHARGE` role to `OT_COORDINATOR` and drop the literal from `SecurityConfig` (Q3). This is a **migration, not a deletion** — the role backs the live `ot_incharge_enabled` toggle and OT Incharge staff record, so it survives until permissions (Phase 2) and case roles (here) can replace it.
- **Database:** `case_roles`, `surgery_team_members`; keep the two name columns **nullable** as an external-operator fallback (legitimate denormalisation).
- **API/Security:** team assign/remove; `OT_ASSIGN_TEAM`.
- **Testing:** a transplant scenario adds `HARVEST_SURGEON` **as a master row** and passes with **no code change** — the Principle-3 proof.
- **Risks:** *(Medium)* renaming `OT_INCHARGE` orphans users and breaks the `ot_incharge_enabled` toggle. Mitigation: migrate users, the setting and the staff record to `OT_COORDINATOR` + permissions in the same release; never delete before the replacement is live.
- **Exit criteria:** no OT code references a role string; a new case role is data.

## Phase 7 — Pre-op, WHO 3-Phase, Intra-op & Operative Note

- **Objective:** clinically correct execution with real safety gates.
- **Business problem:** the brief collapsed WHO into one "Time-Out"; Reception writes clinical timestamps (F10); the operative note is absent entirely.
- **Dependencies:** Phases 1, 3, 5.
- **Backend:** WHO Sign-In / Time-Out / Sign-Out, independently signed; promoted columns (ADR D12); clinical milestones `PATIENT_ENTERED_OT`, `ANAESTHESIA_START`, `INCISION`, `CLOSURE`, `ANAESTHESIA_END`; operative note authored by the surgeon.
- **Database:** promoted checklist columns + `surgery_milestones`.
- **API:** `POST …/checklist/{phase}/sign`; `POST …/milestones`; `POST …/operative-note`.
- **Security:** `OT_TIME_OUT`, `OT_START`, `OT_COMPLETE`. With `WHO_CHECKLIST_MODE=BLOCKING`, `PRE_OP → IN_PROGRESS` is refused **server-side** without a signed Time-Out.
- **Testing:** incision without a signed Time-Out is rejected **by the API**, not merely hidden in the UI. Sign-Out captures the instrument/sponge count.
- **Risks:** *(High)* a blocking gate stops an emergency. Mitigation: the `EMERGENCY` priority scope resolves `ADVISORY`, plus an audited break-glass — the surgeon documents after the fact.
- **Exit criteria:** WHO compliance is a SQL query over columns; no clinical timestamp is written by a non-clinical permission.

## Phase 8 — Recovery (PACU), Transfer & Designation Master

- **Objective:** track the patient after theatre **without holding the theatre** (ADR D8).
- **Dependencies:** Phases 4–5, 7.
- **Backend:** `ot_recovery_episodes` (+ Aldrete series) when `RECOVERY_TRACKING=PACU_EPISODE`; milestones otherwise. Transfer destination `WARD|ICU|HDU|PACU|MORTUARY` (enum first, table later). `COMPLETED → CLOSED` enforces `CLOSE_REQUIRES` (ADR D7). Promote `role` → `staff_designations` — a **rename**, since only `role_permissions` references it (ADR D2).
- **Testing:** the room returns to `AVAILABLE` **while** the case is `COMPLETED` and the patient is still in recovery — the explicit regression protecting utilisation.
- **Risks:** *(Medium)* bed-state coupling — `BedStatusService` is called through a facade and **not modified**.
- **Exit criteria:** a small hospital records nothing; a corporate hospital records an Aldrete series; **one schema**. Room availability is independent of patient location.

## Phase 9 — NABH/JCI Analytics & Occupancy Timeline

- **Objective:** the board-level metrics and the audit evidence.
- **Backend:** `ot_room_occupancy` (deferred from D9); read models for turnover, first-case on-time start, elective cancellation rate by reason, WHO compliance %, unplanned return to OT.
- **Risks:** *(Low)* report queries on the OLTP path. Mitigation: date-bounded queries, covering indexes.
- **Exit criteria:** each metric is one query and matches a hand-computed fixture.

---

## Backlog (speced, not scheduled)

| Item | Why deferred | Entry condition |
|---|---|---|
| **Equipment & CSSD** | **No equipment entity exists** (F14). A sterilisation lifecycle is its own module. | A hospital asks for set tracking. |
| **Blood bank** | Only `BLOOD_CONSENT` exists (F14). Cross-references a blood inventory we do not have. | Blood inventory module exists. |
| **OT billing** (package / consumables / implants) | `billing` is clinic-aliased — OT may **never** add a column to it (D15). Needs `surgery_charges` + posting through the existing billing API. | Phase 5 complete; billing owner signs off. |
| **Doctor leave management** | No leave entity (F13). Phase 4's overlap query prevents the actual harm. | Rostering is requested. |
| `ot_room_occupancy` | Analytics nicety; Phase 3's transition table is accurate enough. | Phase 9. |
