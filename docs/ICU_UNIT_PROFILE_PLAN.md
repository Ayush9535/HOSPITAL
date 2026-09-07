# `icu_unit_profile` — readiness audit

**Status:** AUDIT ONLY. Nothing implemented, no migration, no commit.
**Question asked:** is `icu_unit_profile` ready to become the next ICU capability, or should it
stay deferred?
**Answer:** **stay deferred (option D).** The design's own precondition has not been met.
**Baselines:** backend 729 tests, frontend 174 tests. ICU-1 … ICU-10 complete.

---

## 1. What §5.4 designed

`ICU_SYSTEM_DESIGN.md` §5.4, verbatim:

> **Decision (r2):** not built in ICU-2. **It is created only in the later phase that implements
> nurse-ratio enforcement.** Until then it does not exist, and **no ICU-2 or ICU-3 code may read
> it.**
>
> Rationale: nothing in the stay lifecycle, the ICU chart or patient movement depends on it, and
> a table that exists before its enforcement logic is a table that gets populated with values
> nothing honours — which is worse than its absence.

| Column                | Note                              | Provenance  |
| --------------------- | --------------------------------- | ----------- |
| `ward_id`             | UNIQUE — one profile per ICU ward | design §5.4 |
| `hospital_id`         | tenant ownership — I12            | design §5.4 |
| `nurse_patient_ratio` | e.g. `1` for 1:1, `2` for 1:2     | design §5.4 |
| `ventilator_capacity` | integer, nullable                 | design §5.4 |
| `notes`               |                                   | design §5.4 |

§5.4 also states the extension point: _"`PUT /hospital/icu/units/{wardId}` sets `unit_type`
**only** in ICU-2. Profile fields are added to that endpoint when the profile ships."_

**The design names no behaviour for these fields beyond "nurse-ratio enforcement".** It does not
say what enforcement blocks, when it fires, or what happens when a ward is over ratio.

---

## 2. Current state — verified

| Check                                                                                                                | Result                                                                                                                                                        |
| -------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `grep -rn "icu_unit_profile\|nurse_patient_ratio\|ventilator_capacity"` over `backend/src`, `frontend/src`, `setup/` | **zero hits**                                                                                                                                                 |
| Enforcement logic of any kind                                                                                        | **none exists**                                                                                                                                               |
| `PUT /hospital/icu/units/{wardId}` — the endpoint §5.4 says to extend                                                | **never built.** `unit_type` is set through `WardService` (the ward form), not an ICU units endpoint                                                          |
| Ratio-adjacent data that DOES exist                                                                                  | `PatientNurseAssignmentRepository.countByNurseUserIdAndIsActiveTrue(nurseUserId)` — _"Active patient load for a nurse (drives least-loaded auto-assignment)"_ |
| Shift-aware ward staffing                                                                                            | `NurseCoverageService.effectiveWardNurses(wardId, date)`, used by `PatientAssignmentService.onAdmission`                                                      |
| Any per-nurse patient limit today                                                                                    | **none.** `NurseAssignmentService` picks the _least loaded_ nurse; it never refuses one for being full                                                        |

So: the table has no writer, no reader, no enforcement, and the endpoint the design planned to
extend does not exist either.

---

## 3. What the profile is supposed to do — separated

The brief asked these to be distinguished. Only one is named by the design.

| Capability                             | Named by §5.4?                                                     |
| -------------------------------------- | ------------------------------------------------------------------ |
| Storing configuration                  | Implied — it is a table                                            |
| Displaying configuration               | **No**                                                             |
| **Enforcing nurse ratios**             | **Yes — and it is the stated precondition for creating the table** |
| Enforcing ventilator capacity          | **No**                                                             |
| Affecting admission / bed availability | **No**                                                             |
| Affecting the ICU dashboard            | **No**                                                             |

Everything except nurse-ratio enforcement would be a **new proposal requiring approval**.

---

## 4. Can `nurse_patient_ratio` actually be enforced? — three findings

### 4.1 The data exists, at one enforcement point only

Enforcing "this nurse is at her limit" needs the nurse's active patient count and the ward's
ratio. The count exists (`countByNurseUserIdAndIsActiveTrue`). So **assignment-time enforcement
is technically possible today** with no new data.

### 4.2 But it is inert for half your tenants — the real dependency

`PatientAssignmentService.onAdmission`: when **Separate Nurse Login is OFF**, staff nurses have
no login, the incharge records care, and **no staff assignment row is written at all**.

For those hospitals `patient_nurse_assignments` is empty, so a per-nurse patient count is
always zero and a ratio can never be breached. Enforcement would be silently meaningless.

Your own tenants are split: hospital 1 has it **ON**, hospital 3 has it **OFF**.

**This is the missing prerequisite, and it is a product decision, not a coding gap:** what does a
nurse:patient ratio mean in a hospital that does not track which nurse has which patient? The
design does not answer it, and I will not answer it from general practice.

### 4.3 The enforcement that would actually matter touches IPD movement

The version of ratio enforcement a hospital would care about is _"do not admit a 9th patient to a
4-bed ICU staffed by two nurses at 1:2"_ — that is a **refusal at admission or transfer**, inside
`IpdAdmissionService.admitFromOpd` / `changeBed`.

That is the standing **stop-and-escalate boundary**: the scope rule forbids modifying IPD
sequencing architecture inside an ICU phase. E1 hardened those paths (transaction boundary,
`lockForClaim`, C1–C4) so a new check _could_ be added safely, but **E1 being sufficient
technically does not make the change in scope.** It needs its own approval.

---

## 5. Does `ventilator_capacity` have a consumer? — no

**Zero references.** ICU-7 deliberately did not read it, and §5.4 forbade it.

Could one be derived? Yes — "ventilators in use" is countable as the admissions whose latest
non-superseded `icu_ventilator_setting.ventilation_status` is not `OFF`. **But nothing in the
design asks for that**, and adding it would be inventing a capacity-management feature.

**Recommendation: do not ship `ventilator_capacity` at all** unless a stated requirement appears.
Shipping it would create precisely the "column with no reader" the design warned against — and
would do it _inside the phase that quotes that warning_.

---

## 6. Existing nursing architecture reviewed

| Component                                                        | Relevance                                                                        |
| ---------------------------------------------------------------- | -------------------------------------------------------------------------------- |
| `PatientNurseAssignment` (+ `countByNurseUserIdAndIsActiveTrue`) | the load counter a ratio needs                                                   |
| `NurseAssignmentService.assignNurse` / `autoAssignForAdmission`  | least-loaded pick; **no limit** — the natural place for an assignment-time check |
| `PatientAssignmentService.onAdmission`                           | the Separate-Nurse-Login fork of §4.2                                            |
| `NurseCoverageService.effectiveWardNurses(wardId, date)`         | shift- and substitution-aware ward staffing                                      |
| `NurseInchargeGuard.myWardIds()` / `assertWardAccess`            | ward-scoped RBAC, already correct for a per-ward profile                         |
| `NurseWriteAccess` / `NurseAccessGuard`                          | clinical write access — **unrelated to ratios**, no change needed                |
| `Ward.unitType` + `CareUnitRegistry`                             | how an ICU ward is identified today                                              |

Nothing here needs modifying to _store_ a ratio. Everything needed to _enforce_ one at assignment
time already exists.

---

## 7. Interaction with existing ICU features

| Feature                       | Interaction if built                                                                                                                       |
| ----------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ |
| ICU-2 bed board               | **none required.** Showing ratio compliance is a new proposal, and the D-4/D-5 precedent from ICU-7/8 says the board stays a capacity view |
| ICU-3 `IcuStay`               | **none.** §5.4: nothing in the stay lifecycle depends on the profile                                                                       |
| IPD admission / movement      | **none unless §4.3 enforcement is approved** — and that is out of scope by the standing rule                                               |
| Nurse assignment              | the one real integration point                                                                                                             |
| ICU-6 infusions, ICU-8 scores | **none**                                                                                                                                   |
| ICU-7 ventilator              | **none** — and §5.4 explicitly forbids reading the profile from it                                                                         |
| ICU-9 alerts                  | **none.** An "over ratio" alert would be a staffing alert, not a vitals threshold; ICU-9's scope is settled                                |

---

## 8. Security and tenancy (if built)

Nothing new is needed — every piece exists:

- `hospital_id` on the row; `ward_id` UNIQUE per I12.
- Configuration write: `HOSPITAL_ADMIN`, matching every other ICU settings surface.
  Ward-scoped read for `NURSE_INCHARGE` via `NurseInchargeGuard.assertWardAccess(wardId)`.
- Foreign tenant → `ResourceNotFoundException` → **404**, the established convention.
- **No new role, no new permission, no Files & Access key** — a ward setting is not a clinical form.
- Cross-tenant risk is low: `ward_id` is the only caller-supplied id, and `WardService` already
  resolves wards tenant-scoped.

---

## 9. Transactions and concurrency

- **Storing** a profile is an ordinary admin write. One `@Transactional`, no locking, **no race**.
- **Assignment-time enforcement** (§4.1) would be check-then-act on a count: two incharges could
  each assign a patient to the same nurse simultaneously and both pass. That is a **real race**,
  and if that enforcement is approved it needs a real MySQL proof, not a latch test that can
  pass for the wrong reason. It is also _low consequence_ — the outcome is one patient over
  ratio, not a double-booked bed.
- **Admission-time enforcement** (§4.3) would sit inside the bed-claim transaction. E1 already
  made that path transactional with `lockForClaim`, so it is technically safe to extend —
  **but it is out of scope**, per §4.3.

No concurrency test is proposed here, because nothing is proposed to be built here.

---

## 10. Recommendation — D: remain deferred

**The design's precondition has not been met.** §5.4 says the table is created _"only in the
later phase that implements nurse-ratio enforcement"_. That phase has not been requested,
specified or justified:

1. **No operational requirement exists.** Nobody has asked for ratio enforcement; no code, ticket
   or document references it beyond §5.4's own placeholder.
2. **The design does not specify the behaviour.** What enforcement blocks, when it fires, and
   what happens to a ward already over ratio are all unanswered — and answering them from
   general ICU practice is exactly what the project rules forbid.
3. **The meaningful form is out of scope.** Refusing an admission touches IPD movement (§4.3).
4. **It is inert for tenants with Separate Nurse Login OFF** (§4.2) — an unresolved product
   question, not a technical gap.
5. **`ventilator_capacity` has no consumer and none is required** (§5).

Building it now would produce a table populated with values nothing honours — the precise
failure §5.4 was written to prevent. **Deferring is following the design, not avoiding work.**

### If you want it anyway — option C, the smallest honest slice

Two steps, in this order, and **only step 1 without a stated requirement for step 2**:

- **Step 1 — store and display `nurse_patient_ratio` only.** One table (no
  `ventilator_capacity`, no `notes` until something reads them), admin-configured on the existing
  ward form, shown on the incharge's ward view beside the live patient count. No enforcement, no
  refusal, no IPD change. Honest and inert-by-design, but it still creates a field nothing
  enforces — so I would only do this if you intend step 2.
- **Step 2 — enforce at assignment.** `NurseAssignmentService` refuses an assignment that would
  put a nurse over her ward's ratio, with an incharge override. Needs the §4.2 answer first.

**Admission-time refusal is not offered** — it requires a separate escalation.

---

## 11. Decisions required from you

**D-1 — accept deferral, or proceed with option C step 1?**
_Recommendation: accept deferral_, and record it in `ICU_SYSTEM_DESIGN.md` §5.4 so the item reads
as decided rather than pending.

**D-2 — what does a nurse:patient ratio mean when Separate Nurse Login is OFF?** (§4.2)
Blocks any enforcement. Options: enforcement applies only to ON tenants; or ratio is measured
against the incharge's whole ward load; or the setting is hidden for OFF tenants.
**I have no recommendation — this is a product question about your users.**

**D-3 — is `ventilator_capacity` wanted at all?** _Recommendation: no_, until something reads it.

**D-4 — if built, where is it configured?** The ward form (where `unit_type` already lives), or a
new `PUT /hospital/icu/units/{wardId}` as §5.4 assumed. _Recommendation: the ward form_ — the
endpoint §5.4 named was never built, and one ICU field already lives on that form.

---

## 12. Dependencies and blockers

| Item                                                      | Status                                      |
| --------------------------------------------------------- | ------------------------------------------- |
| **Separate Nurse Login semantics for ratios** (D-2)       | **BLOCKER for any enforcement**             |
| **IPD movement change** for admission-time refusal        | **Out of scope** — standing escalation rule |
| A stated operational requirement for ratio enforcement    | **MISSING — this is the §5.4 precondition** |
| Lab results (E-1/E-2), consultants (A), alert history (D) | **Unrelated.** Not touched by this audit    |

Nothing else blocks it. The nursing data model is ready; the _requirement_ is not.

---

## 13. Testing and manual scope — only if approved

**Not written now**, since nothing is proposed. If option C step 1 proceeds: tenancy (404 for a
foreign ward), admin-only write, one profile per ward enforced by the unique index, ward-scoped
read for the incharge, and a guard-revert proof on the tenant check. Manual: set a ratio on an
ICU ward, see it on the incharge ward view, confirm a second hospital's ward is untouched, and
confirm the bed board, ICU stays and every ICU-4…ICU-10 surface are unchanged.

If step 2 proceeds, add the real MySQL concurrency proof described in §9.

---

## 14. Definition of done

**For this checkpoint:** this document exists, `icu_unit_profile` remains uncreated, no
production code changed, and D-1 … D-4 are answered.

**If deferral is accepted:** §5.4 is annotated with the decision and the date, so the next reader
finds "deferred, and here is why the precondition still is not met" rather than an open item.

---

**STOP.** Audit only. No table, no migration, no code, no commit. Awaiting D-1 … D-4.
