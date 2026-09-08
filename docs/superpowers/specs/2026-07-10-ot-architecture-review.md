# OT — Expert Architecture Review (evidence record)

**Date:** 2026-07-10 · **Scope:** HOSPITAL tenants only
**Status:** Evidence. Superseded on every point of *decision* by the ADR.

This file records ten independent expert reviews of the existing OT module. It is deliberately
argumentative: each expert was instructed to disagree. **It contains no decisions.**

- Decisions and their reasons → [OT ADR](2026-07-10-ot-adr.md)
- Verified code findings (F1–F15) → [OT ADR §1](2026-07-10-ot-adr.md)
- Sequencing → [OT Roadmap](2026-07-10-ot-roadmap.md)
- Schema, endpoints, state machine → [OT Implementation Spec](2026-07-10-ot-implementation-spec.md)

Where a review below conflicts with the ADR, **the ADR wins** — several reviews were overruled
(notably: no `staff_designations` master, no `IN_THEATRE` state, no `WAITLISTED` state,
rooms without an occupancy timeline).

---

## The ten reviews

### Review 1 — Senior Enterprise Software Architect

**Verdict: the current OT is a transaction script, not a domain. It will not survive three more hospital types.**

- **Cohesion/coupling:** `SurgeryService` (362 lines) owns request, schedule, start, complete, cancel,
  ward lookup, tenant checks, and DTO mapping. Every new hospital variation adds an `if` to a method
  that already has four. Cyclomatic growth is linear in hospital types — the exact thing we are told
  to avoid.
- **SOLID:** OCP is violated at the method level (adding "approval" means editing `schedule()`), and
  DIP is violated because the workflow *depends on `hasRole('RECEPTIONIST')`* — a concretion of the
  staffing model — rather than on an abstraction ("someone with OT_SCHEDULE").
- **Multi-tenancy:** F3 is disqualifying. A SaaS product whose tenant isolation lives in an axios
  interceptor is one `curl` away from a cross-tenant-type incident. Isolation must be a server-side
  invariant.
- **Configuration strategy:** I *endorse* the lazy-default config pattern already proven twice in this
  codebase (`FormAccessService`, `VitalSettingsService`): store only overrides, absent row = default.
  It is backward compatible by construction and needs no seeding. Reuse it; do not invent a third.
- **DDD suitability:** Yes for a bounded context (`service/hospital/ot/`), **no** for aggregates,
  repositories-per-aggregate, and event sourcing. The team is Spring Data + JPA. Forcing tactical DDD
  raises maintenance cost without buying anything at this scale.
- **I reject a BPM engine (Camunda/Flowable).** The state space is ≤ 12 states. A workflow engine adds
  a second deployment artifact, a second data model, and a skills dependency for a problem solvable by
  a 40-line transition table. *Maintainable for years* means fewer moving parts, not more.
- **Future maintenance cost:** the biggest driver is F6. Until authorization is decoupled from
  designation, every new hospital archetype is a code change. That is the whole ballgame.

**Challenge to the proposed workflow:** "Doctor Request → Approval → …" bakes a corporate hospital's
org chart into the state machine. A 10-bed nursing home has no approver. Either the state is optional
(inconsistent reporting) or it is auto-satisfied (uniform reporting). Choose deliberately.

---

### Review 2 — Senior Hospital Administrator (Small Hospital, 10–50 beds)

**Verdict: the proposed 11-step workflow is unusable for me. I have one OT, two surgeons, and three nurses.**

- **My reality:** the surgeon decides at 10 am to operate at 2 pm. He tells the nurse. She writes it on
  a whiteboard. There is no coordinator, no approver, and often no separate anaesthetist for minor cases.
- **Approval is dead weight.** If the software forces an "Approval" click, staff will approve their own
  requests reflexively — which is *worse than no approval*, because it manufactures a false audit trail.
  If a step cannot be meaningfully performed, it must be **absent by policy**, not present-and-clicked.
- **Staffing:** the same person is receptionist and biller. The same nurse scrubs, circulates, and does
  recovery. **Any design that assigns one action to one designation locks me out** — I do not have those
  designations. This is F6 from the floor.
- **Unnecessary complexity:** PACU, equipment, CSSD, team allocation — I need none of it. If those
  screens ship enabled, my staff will stop using the module.
- **What I actually need:** request → schedule → done, a consent form, and a printable OT register.
- **Improvement I demand:** a "Simple OT" preset that turns everything else off in one click, and
  **defaults that match me**, because I am the most common tenant by count.

---

### Review 3 — Senior Hospital Administrator (Medium Hospital, 100–300 beds)

**Verdict: directionally right, but the scheduling model is the weak point, not the approvals.**

- **Operation flow:** approvals matter to me — but as *clearances*, not as a hierarchy. Before a case can
  go to theatre I need: **anaesthesia fitness**, **consent signed**, and **financial clearance** (for
  cash patients). These are three independent gates, evaluated in any order. Modelling them as a single
  linear "Approval" state is wrong — it is a **checklist of preconditions**, not a step.
- **Scheduling:** F7 is my daily pain. I have three theatres. "Ward whose name contains OT" means my
  "Post-Op Ward" and "Foot Clinic" appear in the theatre dropdown. And because capacity is a *bed*,
  I cannot book back-to-back cases with a turnover gap.
- **Staffing:** I have an OT Coordinator. I cannot represent her. She currently logs in as a receptionist.
  That destroys my audit trail — every scheduling action is attributed to "Reception".
- **Nursing:** the OT nurse and the ward nurse are different people with different competencies. The
  nurse module already has ward-scoped RBAC (`NurseInchargeGuard`); OT needs an equivalent *room* scope.
- **Improvement:** make cancellations record a **reason from a fixed list**. My NABH assessor asks for
  "% of elective surgeries cancelled, by reason" and I compute it in Excel today.
- **Improvement:** distinguish **postponed** from **cancelled**. A postponed case returns to my list.
  Today both are `CANCELLED` and the case is lost.

---

### Review 4 — Senior Corporate Hospital Administrator (500–1000 beds, chain)

**Verdict: the model is missing the two objects the whole business runs on — the room and the team.**

- **Multiple OT rooms:** utilisation (%), turnover time, first-case on-time start, and cancellation rate
  are my four board-level metrics. **None are computable** from the current schema. Room must be a
  first-class entity with an occupancy timeline, or every metric is a guess.
- **Multiple surgeons / team allocation:** `surgeon_name VARCHAR(255)` and `anaesthetist_name VARCHAR(255)`
  (F10) are unacceptable. I need a **team per case** with roles: primary surgeon, assistants,
  anaesthetist, scrub nurse, circulating nurse, perfusionist, technician. Cardiac and transplant cases
  are impossible without this — and the brief explicitly requires transplant/cardiac readiness.
- **Anaesthesia:** anaesthesia type, ASA grade, and the pre-anaesthesia evaluation must be structured
  data, not a JSON blob, because they drive my mortality/morbidity review.
- **Recovery/PACU:** PACU is a **physical unit with its own beds and its own nurses**, and a patient can
  be in PACU while the theatre has already been turned over for the next case. **If Recovery is a case
  state, my theatre stays "busy" until the patient reaches the ward.** That single modelling error would
  destroy my utilisation numbers. (See Decision D5.)
- **ICU transfer:** destination is not always a ward. It is Ward | ICU | HDU | PACU | Mortuary. Model the
  destination, not a boolean.
- **Analytics/audit:** I need every state change with actor, timestamp, and reason — immutable.
- **Legal compliance:** F8 is a **litigation event waiting to happen**. If a patient has two procedures in
  one admission and the second consent silently overwrites the first, I cannot produce the first consent
  in court. Fix this before any new feature.
- **NABH expectations:** COP 12–16 (surgical care) requires: documented pre-anaesthesia assessment, a
  signed informed consent *per procedure*, WHO surgical safety checklist compliance, an operative note
  written immediately post-op, and post-anaesthesia monitoring. Four of the five are unrepresentable today.

---

### Review 5 — Senior Surgeon

**Verdict: the flow is written from the administrator's chair. Clinically it inverts responsibility.**

- **Surgery request:** fine. But it must carry laterality (left/right), procedure code, planned
  anaesthesia type, and estimated duration — the last is what makes the list schedulable.
- **Consent:** consent belongs to the **procedure**, not the admission (F8), and must be **immutable once
  signed**. A consent that can be silently edited after the fact is not consent. Signed → append a new
  version, never mutate.
- **Anaesthesia:** the anaesthetist, not the surgeon, declares fitness. This is an independent clearance
  with its own author. It cannot be a checkbox on my request form.
- **WHO checklist:** three phases — **Sign-In** (before induction), **Time-Out** (before incision),
  **Sign-Out** (before the patient leaves theatre). The proposal collapses this into one "Time-Out" step.
  That is clinically wrong and would fail an NABH audit. Sign-Out is where the count of sponges and
  instruments is confirmed; omitting it is a retained-instrument risk.
- **Surgery execution / F10:** **Reception must never press "Start Surgery" or "Complete Surgery."**
  Those timestamps are clinical facts. Anaesthesia start, incision time, closure time, and anaesthesia
  end are four distinct events, and they are not the same as "case started/finished."
- **Operative note:** must be authored by the surgeon and recorded immediately after the procedure. It is
  absent from the design entirely.
- **Emergency:** I will not wait for a workflow. In an emergency, consent may be waived, approval is
  never sought, and the case starts before the paperwork exists. **The system must permit
  document-after-the-fact**, and mark it as such, rather than block me.

---

### Review 6 — Senior OT Nurse

**Verdict: the forms are the job, and the forms are the weakest part.**

- **Nursing workflow:** my day is receive patient → verify identity/site/consent → pre-op checklist →
  count instruments → assist → count again → hand over to recovery. The design has one "Pre-op" box.
- **OT notes / checklists:** 15 NABH forms as free JSON with layout on the frontend is convenient for
  developers and dangerous for us. **The fields the hospital is measured on** (WHO phases signed, sponge
  count correct, site marked) **must not live inside a JSON blob**, or nobody can report on them and
  nothing can block on them.
- **Handover:** the handover to recovery is a *signed* transfer of responsibility, with the patient's
  condition at that moment. It is not a status flip. Today there is no handover artifact at all.
- **Recovery:** we score patients (Aldrete) at intervals until they qualify for discharge from PACU.
  That is a small time-series, not a status.
- **Practicality:** the person who *performs* care and the person who *records* it are often different
  (night shift, no login). The nurse module already solved this — `PerformingNurseResolver` +
  `performed_by_nurse_id`. **OT must reuse that exact pattern**, not invent a second one.
- **Improvement:** every OT form must record *who performed* and *who recorded*, separately.

---

### Review 7 — Senior Java / Spring Boot Architect

**Verdict: three defects are latent bugs, not design opinions. Fix them first; they are cheap.**

- **F1 is a real, silent security defect.** `@Before("@annotation(x)")` binds method annotations. The fix
  is `@Before("@annotation(requireModule) || @within(requireModule)")` with the correct argument binding,
  or a dedicated pointcut per placement. **Adding `@within` will immediately begin rejecting requests
  that succeed today.** That is a behaviour change for live tenants and must be sequenced deliberately.
- **Entities:** `Surgery` uses `String` status with `public static final` constants. Use a JPA-mapped
  `enum` (`@Enumerated(STRING)`) so the compiler enumerates the state space and `switch` exhaustiveness
  protects transitions. String status + `if` chains is how F9 happened.
- **Transactions:** `schedule()` reads the ward, checks conflict, then writes — with no locking. Two
  concurrent schedulers can double-book a theatre. The uniqueness must be enforced by a **DB constraint
  or a pessimistic lock**, not by a read-then-write check. This is a real race today.
- **Validation:** ad-hoc `throw new IllegalArgumentException` inside the service. Move field validation to
  DTO bean-validation; keep the service for **invariants** (transition legality, tenant scope).
- **Security:** `@PreAuthorize("hasRole('RECEPTIONIST')")` must become
  `@PreAuthorize("hasAuthority('OT_SCHEDULE')")`. Spring supports this natively via authorities — no
  custom `PermissionEvaluator` is required if we mint permission authorities into the JWT alongside
  `ROLE_*`. **This is the cheapest correct implementation of Principle 2.**
- **Extensibility:** put OT behind a package boundary `service/hospital/ot/` with a facade. Keep the old
  `SurgeryService` signature as a delegating shim during migration (strangler fig) so no controller or
  test breaks in the same commit as the refactor.
- **Objection to the roadmap as briefed:** do not build the state machine before fixing F8. A schema whose
  unique key is wrong will have accumulated more corrupt rows by the time the engine lands.

---

### Review 8 — Senior React Architect

- **Screens:** OT logic is currently spread across `DoctorDashboard`, `ReceptionistDashboard`,
  `IpdDetails`, and `pages/hospital/ot/`. There is no OT owner screen. Corporate hospitals need an
  **OT Board** (rooms × time) as the primary surface; small hospitals need a list.
- **Navigation:** the same board serves both — a day view with one column is a list. Do not build two.
- **State management:** every OT screen re-derives permissions from `user.role`. This must become a single
  `useOtPermissions()` hook reading permission authorities, and `useOtPolicy()` reading the hospital's
  policy — mirroring `useEnabledVitals()` / `useModule()`, which already work.
- **The client-side ward filter (F7) must die.** The server must expose `GET /hospital/ot/rooms`. A UI
  filter is not a domain rule.
- **Scalability:** `HospitalAdminDashboard.jsx` is already ~3000 lines. **Do not add OT settings to it.**
  OT settings belong in their own route, following the `FilesAndAccessCard` / `VitalsSettingsCard` card
  pattern which is already the house style.
- **Usability caution:** policy-driven UI means a button may be absent for reasons the user cannot see.
  Every hidden action needs a discoverable reason ("Time-Out required before Start"), or support tickets
  will follow.

---

### Review 9 — Senior Database Architect

- **F8 is the headline.** `UNIQUE(ipd_admission_id, form_type)` must become `UNIQUE(surgery_id, form_type)`
  for procedure-scoped forms. Migration hazard: existing rows have `surgery_id = NULL`, and MySQL treats
  NULLs as distinct in unique keys, so the new key silently permits duplicates until backfilled. **Backfill
  `surgery_id` first, then add the constraint, then set `NOT NULL`.** Three separate migrations.
- **Normalization:** `surgeon_name` / `anaesthetist_name` are denormalised free text. Replace with
  `surgery_team_members`. Keep the strings as *fallback* columns for external operators who have no
  user row — that is legitimate denormalisation, not a smell.
- **Forms — hybrid, not either/or.** Fully normalising 15 NABH forms is premature (Review 7 agrees; Review 6
  disagrees). Resolution: keep `data_json` as the record of truth for layout-driven fields, but **project
  the ~8 fields that are queried or blocked on into real columns** (WHO phase signatures, counts correct,
  site marked, signed_at/by). Never report from JSON.
- **Indexing:** `surgeries` has `(hospital_id, status)` and `(ipd_admission_id)`. The board and calendar
  query by date. Add `(hospital_id, scheduled_at)` and, once rooms exist, `(ot_room_id, scheduled_at)` —
  the latter also backs the double-booking constraint.
- **Double-booking:** enforce with a unique index on `(ot_room_id, scheduled_at)` **only if** slots are
  discrete. For interval booking, a unique index cannot express overlap; use `SELECT … FOR UPDATE` on the
  room row plus an overlap query. Prefer the room-row lock: it is simple and correct.
- **Reporting/extensibility:** an append-only `surgery_state_transitions(surgery_id, from, to, actor_user_id,
  reason_code, at)` gives every metric the corporate reviewer asked for (turnover, on-time start,
  cancellation rate) as a query over one table. Build it in the same phase as the state machine.
- **Isolation:** all new tables carry `hospital_id NOT NULL` + FK. No new column may be added to a table
  Clinic reads (`patients`, `wards`, `beds`, `billing`) without a Clinic regression run.

---

### Review 10 — Senior Security Architect

- **F1 + F3 together mean the OT module has no server-side gate at all.** A Clinic tenant's DOCTOR user
  holds `ROLE_DOCTOR`, and `/hospital/**` authorises `ROLE_DOCTOR`. Only the *frontend* stops them. Fix:
  1. Add `hospitalType` to the JWT and to `UserAuthenticationDetails`.
  2. Introduce `@TenantType(HOSPITAL)` enforced by an aspect using **`@within` and `@annotation`**.
  3. Fix `ModuleAccessAspect` to honour class-level annotations.
- **F4 must be reverted:** strip `/clinic/**` and `/pharmacy/**` from `OtInchargeController`.
- **RBAC → PBAC.** Permissions (`OT_VIEW … OT_CLOSE`) are the authorization vocabulary. Designations map to
  permissions in a table. **Roles remain in the JWT for Clinic/Pharmacy compatibility**; permissions are
  *added* as extra authorities. Additive change, zero breakage.
- **Workflow security:** the state machine must be enforced server-side. Hiding the Start button is not
  access control. Every transition endpoint validates (a) permission, (b) current state, (c) policy
  preconditions.
- **Audit:** transitions must be append-only and must capture actor, reason, and — where a policy was
  auto-satisfied — `actor = SYSTEM` with `reason = AUTO_*`. A silent auto-approval with no row is
  indistinguishable from a bug.
- **Data isolation:** the tenant check must be in the service, not the controller, because 22 controllers
  are shared. `SecurityContextHelper.getCurrentHospitalId()` is the existing correct primitive — keep using it.
- **Immutability:** signed consents and completed checklists must be append-only versions. An `UPDATE` on a
  signed consent should be impossible by construction, not by convention.

---
