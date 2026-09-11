# ICU Phase 6 — Continuous Infusions

**Checkpoint:** ICU-6 (AUDIT → PLAN) · **Date:** 2026-08-26 · **Branch:** `icu` · **Base:** `b89b403`
**Status:** Audit only — **no implementation, no migration, no code changed.**
**Roadmap source:** [ICU_SYSTEM_DESIGN.md](ICU_SYSTEM_DESIGN.md) §12.3 item 1

---

## 1. ICU-6 name

**ICU-6 — Continuous Infusions.**

Determined, not invented. §12.3 lists the remaining ICU scope in order: **(1) continuous
infusions**, (2) ventilator, (3) severity scores, (4) alert thresholds, (5) append-only semantics
(now an established pattern), (6) multiple consultants. ICU-4 took §12.1, ICU-5 took §12.2, so
§12.3 item 1 is next. [ICU_PHASE5_PLAN.md](ICU_PHASE5_PLAN.md) §5 independently confirms it,
calling medication/infusion records "those do not exist yet (ICU-6+)".

Items 2–4 and 6 remain ICU-7+.

---

## 2. Purpose in simple words

A ward drug is given and finished — one event. An ICU drug often **runs continuously** at a rate
that is changed repeatedly: noradrenaline at 5 mL/h, raised to 8, lowered to 6, stopped. The chart
must show what is running _right now_, and what the rate was at any earlier moment.

The system has no concept of a rate. ICU-6 records infusions as running spans with a rate history.

---

## 3. Exact requirements from the existing design

§12.3 item 1, verbatim: _"Continuous infusions — rate over time. No existing entity carries
rate/units/titration."_ Plus §12.3 item 5: append-only semantics, per §12.1's supersede pattern.

That is the whole brief. **No clinical field beyond rate, unit, time and the drug is authorised**,
and nothing in the design asks for dose calculation, weight-based dosing, or infusion limits.

---

## 4. What already exists (verified)

| Component                    | State | Note                                                                                                                                                                           |
| ---------------------------- | ----- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `Prescription`               | ✅    | `medicineName, dosage, frequency, duration, durationDays, startDate, instructions, status, type, route`. **`type` already includes `IV_FLUID`; `route` already includes `IV`** |
| `MedicationAdministration`   | ✅    | `prescriptionId, scheduledTime, administeredTime, status, remarks, performedByNurseId`. Statuses `GIVEN/SKIPPED/DELAYED/REFUSED/NOT_AVAILABLE`                                 |
| `MedicationChartItemDTO`     | ✅    | course view: `dayOfCourse`, `courseActive`, `administeredToday`                                                                                                                |
| `MedicationPanel.jsx`        | ✅    | the existing chart, with a `readOnly` prop                                                                                                                                     |
| `IcuIoEntry` (ICU-5)         | ✅    | fluid events incl. `IV_FLUIDS` intake                                                                                                                                          |
| `IcuStay` (ICU-3)            | ✅    | the stay window, read-only for clinical phases                                                                                                                                 |
| **Rate / units / titration** | ❌    | `grep` for `rate                                                                                                                                                               | infus | titrat` across the entity layer returns **nothing** |
| **A running span**           | ❌    | `MedicationAdministration` is a discrete point: `administeredTime` is one instant                                                                                              |

**Confirmed:** the codebase contains **zero** references to "infusion" in Java.

### Why the existing model cannot carry this

`MedicationAdministration` records _"this dose was given at 14:05"_. An infusion is _"running from
14:05 at 5 mL/h, changed to 8 mL/h at 16:20, stopped at 22:00"_. Two structural mismatches:

1. **A point vs a span.** One `administeredTime` cannot express start-and-still-running.
2. **No rate, and no rate history.** Even adding a rate column would store only the latest value,
   and _"what was it running at when the BP dropped?"_ — the question the chart exists to answer —
   would be unanswerable.

Forcing it would mean one `MedicationAdministration` row per rate change, each pretending to be a
discrete dose, which would corrupt the existing MAR's meaning and its `administeredToday` logic.

---

## 5. What can be reused

- **`Prescription`** — the _order_ ("noradrenaline, IV"). ICU-6 adds no second order model; an
  infusion **references an existing prescription**, exactly as `MedicationAdministration` does.
- **`IcuStay`** — read-only window, as ICU-4/ICU-5 use it. No lifecycle change.
- **`FormAccessService`** — gating, see D-3.
- **`PerformingNurseResolver` / `NurseWriteAccess` / `NurseAccessGuard`** — attribution and
  patient scoping, verbatim.
- **`AuditLogService`, `RealtimeNotifier`** — best-effort, after commit.
- **The append-only supersede pattern** — now proven twice (ICU-4 vitals, ICU-5 I/O).
- **`MedicationPanel`** — the infusion section belongs beside the MAR in the same workspace.

---

## 6. Missing database / schema work

**Two new tables. No change to any existing table.**

**`icu_infusion`** — one running (or finished) infusion:

| Column                                        | Note                                                          |
| --------------------------------------------- | ------------------------------------------------------------- |
| `id`, `public_id`, `hospital_id`              | house convention; `hospital_id` NOT NULL                      |
| `ipd_admission_id`, `patient_id`              | the admission is the key, as everywhere else                  |
| `prescription_id`                             | **nullable** — see D-2                                        |
| `medicine_name`                               | denormalised for display, as `Prescription` already stores it |
| `started_at`                                  | when the infusion began                                       |
| `stopped_at`                                  | NULL while running                                            |
| `stop_reason`                                 | free text, nullable                                           |
| `started_by_user_id`, `performed_by_nurse_id` | same attribution as vitals/IO                                 |
| `is_active`, `created_at`                     | house convention                                              |

**`icu_infusion_rate`** — the rate history (this is the "rate over time" the design asks for):

| Column                                         | Note                                                            |
| ---------------------------------------------- | --------------------------------------------------------------- |
| `id`, `public_id`, `hospital_id`               |                                                                 |
| `icu_infusion_id`                              | parent                                                          |
| `rate_value`                                   | DECIMAL — rates are fractional (0.05 mcg/kg/min)                |
| `rate_unit`                                    | VARCHAR — `ML_HR`, `MCG_MIN`, `MCG_KG_MIN`, `UNITS_HR`. **D-4** |
| `effective_from`                               | when this rate started applying                                 |
| `recorded_by_user_id`, `performed_by_nurse_id` |                                                                 |
| `supersedes_rate_id`                           | append-only correction, mirroring ICU-4/5                       |
| `is_active`, `created_at`                      |                                                                 |

**Why two tables, not one:** the whole requirement is _rate over time_. A rate column on the
infusion would hold only the current value; the history — the thing that answers "what was it
running at 16:00?" — needs its own rows. A titration is a new rate row, **not** an edit.

**Not required:** no change to `prescriptions`, `medication_administrations`, `vitals_records`,
`icu_io_entry`, `icu_stay`, `beds`, `wards` or `ipd_admission`.

---

## 7. Missing backend work

- `IcuInfusion` + `IcuInfusionRate` entities, tenant-scoped repositories.
- `IcuInfusionService` — `start`, `changeRate` (titrate), `stop`, `listForAdmission`,
  `rateAt(instant)`, `correctRate` (append-only).
- `IcuInfusionController` under `/hospital/nurse/infusions`, mirroring the ICU-5 I/O controller's
  placement, and **declared in `ControllerModules`** (an undeclared controller reads as allowed).
- **No interpretation:** no maximum rate, no dose calculation, no weight-based conversion, no
  "rate too high" flag. The chart records what was set. Converting `MCG_KG_MIN` to `ML_HR` needs a
  concentration and a body weight the system does not hold, and inventing that arithmetic would be
  clinical calculation — explicitly out of scope.

---

## 8. Missing frontend work

- An **Infusions** section inside the existing `MedicationPanel` (or a sibling tab beside it) —
  running infusions with their current rate, a titrate action, a stop action, and rate history.
- Superseded rate rows struck through, as ICU-4/5 already render.
- `icuService.js` additions.

**No new page and no new route** — the ICU chart stays one workspace.

---

## 9. API changes

New, additive only:

| Method | Path                                                   |
| ------ | ------------------------------------------------------ |
| POST   | `/hospital/nurse/infusions` (start)                    |
| GET    | `/hospital/nurse/infusions/admission/{id}`             |
| POST   | `/hospital/nurse/infusions/{publicId}/rate` (titrate)  |
| POST   | `/hospital/nurse/infusions/{publicId}/stop`            |
| POST   | `/hospital/nurse/infusions/rate/{publicId}/correction` |

**No existing endpoint changes shape.** The MAR, vitals, I/O, board and stay APIs are untouched.

---

## 10. Security / tenant requirements

- `hospital_id NOT NULL` on both tables; every finder tenant-scoped; foreign → **404**.
- `prescription_id` (when given) must resolve to the **same admission and tenant**.
- Correction keeps the ICU-4/5 rule: **same recorder, same edit window. No widening.**
- **No new role and no new permission** — D-3 settles which existing gate applies.
- Use scoped finders; a new lookup-by-id means a deliberate `TenantScopingArchTest` allowlist edit.

---

## 11. Transaction requirements

- Each write is its own `@Transactional`.
- **ICU-6 does not join the IPD movement transaction and never calls `IcuStayService`'s
  `MANDATORY` methods.** A failed infusion write must never roll back an admission, bed move or
  stay — the rule ICU-4 set and ICU-5 kept.
- Reading the stay window is a plain read, no lock.
- **No concurrency test required.** Two nurses titrating contend for nothing: a titration appends
  a rate row rather than mutating shared state. Stated explicitly, per the E1 lesson.

---

## 12. Relationship with IcuStay

**Read-only, exactly as ICU-4 and ICU-5.** ICU-6 reads the stay window to decide whether the
append-only correction guard applies. It writes no stay, changes no lifecycle, and adds no
`MANDATORY` call. `IcuStay` remains the episode authority.

---

## 13. Relationship with existing IPD/ICU functionality

- **IPD movement** — untouched. Infusions are keyed to the **admission**, so a bed or ward move
  carries them automatically (the ICU-5 lesson).
- **MAR** — untouched. An infusion is a _different clinical object_ from a discrete dose. Both may
  reference the same `Prescription`; neither writes the other's rows.
- **ICU Dashboard / Bed Board** — **no change required.** Showing "on 2 infusions" per bed is a
  future nicety and is _not_ in §12.3. **Not proposed.**
- **⚠ ICU-5 I/O — the one real overlap, and it needs your decision (D-1).** An IV infusion delivers
  fluid, and `icu_io_entry` already has an `IV_FLUIDS` intake route. Left unresolved this becomes
  exactly the duplicate-source-of-truth problem D-2 settled for urine.

---

## 14. Automated tests required

**Unit** — start an infusion; current rate = latest rate row; titration appends rather than
edits; `rateAt(t)` returns the rate in force at `t`; stop sets `stopped_at` and the infusion
leaves the running list; a stopped infusion cannot be titrated; correction appends with
`supersedes_rate_id` and the original stays readable; correction respects the recorder +
edit-window rules; rate must be positive; unknown unit rejected.

**Integration** — full lifecycle start → titrate ×2 → stop, history intact; infusions survive a
bed/ward move (admission-keyed); a non-ICU admission is unaffected (**backward-compat gate**).

**Tenant/security** — foreign infusion → 404; foreign `prescription_id` rejected.

**Rollback** — a failed titration writes nothing and leaves the prior rate in force.

**D-1 boundary** — whichever D-1 option is chosen, a test must pin it: either an infusion creates
no `icu_io_entry` (option A) or it creates exactly one and is never double-counted (option B).

**Frontend** — running infusions with current rate; titrate flow; stop flow; superseded rate
struck through; **no derived judgement** (assert absence of any "high/critical" styling).

**E1 standard:** every new guard verified to **fail** with its fix reverted.

**Regression gates:** `MedicationAdministrationServiceTest`, `IcuIoServiceTest`,
`IcuVitalsCorrectionTest`, `IcuStayLifecycleTest`, `IcuBoardServiceTest`, full backend (576) and
frontend (112).

---

## 15. Manual test checklist

| ID   | Scenario                          | Expected                                                                            |
| ---- | --------------------------------- | ----------------------------------------------------------------------------------- |
| M-1  | Start an infusion at 5 mL/h       | Appears as running, rate 5                                                          |
| M-2  | Titrate to 8, then 6              | Current rate 6; history shows 5 → 8 → 6 with times                                  |
| M-3  | Stop the infusion                 | Leaves the running list; history retained                                           |
| M-4  | Correct a mistyped rate           | New rate row; **original visible, struck through**                                  |
| M-5  | Titrate a stopped infusion        | Refused                                                                             |
| M-6  | Move the patient's bed/ward       | Infusions still listed                                                              |
| M-7  | Ward (non-ICU) patient            | Existing MAR behaviour unchanged                                                    |
| M-8  | Cross-tenant infusion             | 404, nothing written                                                                |
| M-9  | Files & Access gate set read-only | Start/titrate/stop refused                                                          |
| M-10 | **D-1 boundary**                  | Infusion volume and I/O intake behave exactly as D-1 decided — never double-counted |
| M-11 | ICU board, vitals, I/O, MAR       | All unchanged                                                                       |

---

## 16. Decisions / blockers

| #       | Decision                                                                                                                                                                                                                                                                                                                               | Recommendation                                                                                                                                                                                                                                                                                                                                                          |
| ------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **D-1** | **An IV infusion delivers fluid, and `icu_io_entry` has an `IV_FLUIDS` intake route. Does an infusion feed the fluid balance?** **(A)** it does not — the nurse records IV intake separately, infusions stay a drug-delivery record; **(B)** stopping/titrating derives an `icu_io_entry`; **(C)** the balance sums infusions directly | **(A).** It mirrors D-2 exactly: separate meanings, no synchronisation, no double counting. (B) needs a volume the system cannot compute without concentration and duration arithmetic, and (C) creates a second balance source — the precise failure D-2 was written to prevent. **This is the one genuine duplicate-source risk in ICU-6 and it is yours to settle.** |
| **D-2** | Must an infusion reference a `Prescription`, or may it stand alone?                                                                                                                                                                                                                                                                    | **Nullable reference.** An ICU drip is often started on a verbal order before the prescription is entered; requiring it would block recording care that happened. Reuses `Prescription` when present, never duplicates it.                                                                                                                                              |
| **D-3** | Which Files & Access key gates infusions? `MEDICATION` exists in `FormRegistry`                                                                                                                                                                                                                                                        | **Reuse `MEDICATION`.** An infusion is medication administration. A new key means a new toggle hospitals must discover; **no new permission or role either way.**                                                                                                                                                                                                       |
| **D-4** | Rate units: fixed list or free text?                                                                                                                                                                                                                                                                                                   | **Fixed list** (`ML_HR`, `MCG_MIN`, `MCG_KG_MIN`, `UNITS_HR`) in a Java registry, like `CareUnitRegistry`. Free text makes the history unreadable; **no conversion between units is performed.**                                                                                                                                                                        |
| **D-5** | Show infusions on the ICU bed board?                                                                                                                                                                                                                                                                                                   | **No.** Not in §12.3, and it would widen the board's read model. Revisit later if asked.                                                                                                                                                                                                                                                                                |

**No blocker.** Nothing here requires a change to shared IPD architecture, `IcuStay`, IPD
movement, the MAR, the board, or any existing table. D-1 must be answered before ICU-6.0.

---

## 17. Recommended implementation order

```
ICU-6.0  entities + repositories + migration + schema mirror   (no behaviour)
ICU-6.1  IcuInfusionService: start / stop / current rate       (TDD, nothing calls it)
ICU-6.2  titration = append a rate row; rateAt(t)
ICU-6.3  append-only correction of a rate, mirroring ICU-4/5
ICU-6.4  controller + tenancy tests + ControllerModules
ICU-6.5  frontend infusion section beside the MAR
ICU-6.6  tests, regression, manual checklist
```

6.1 before 6.2 so the span lifecycle is settled before rate history hangs off it; correction last
among the writes, as in ICU-5.

---

**NEXT PHASE:**
ICU-6 — Continuous Infusions (`ICU_SYSTEM_DESIGN.md` §12.3 item 1)

**BLOCKERS:**
None.

**DECISIONS REQUIRED:**

1. **D-1** — infusion ↔ `icu_io_entry` fluid-balance boundary (recommend A: no synchronisation)
2. **D-2** — `prescription_id` nullable (recommend yes)
3. **D-3** — gate on the existing `MEDICATION` key (recommend yes)
4. **D-4** — fixed rate-unit registry, no conversion (recommend yes)
5. **D-5** — infusions on the bed board (recommend no)

**RECOMMENDED ORDER:**

1. Entities, repositories, migration, schema mirror
2. `IcuInfusionService` — start / stop / current rate
3. Titration as an appended rate row + `rateAt(t)`
4. Append-only rate correction
5. Controller, tenancy tests, `ControllerModules` declaration
6. Frontend infusion section beside the MAR
7. Tests, regression, manual checklist
