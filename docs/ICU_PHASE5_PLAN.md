# ICU Phase 5 — ICU Intake & Output

**Checkpoint:** ICU-5 (AUDIT → DESIGN → PLAN)
**Date:** 2026-08-26 · **Branch:** `icu` · **Base SHA:** `0ccae94`
**Status:** Audit + plan — **awaiting approval. No implementation, no migration, no code changed.**
**Roadmap source:** [ICU_SYSTEM_DESIGN.md](ICU_SYSTEM_DESIGN.md) §12.2, deferred from ICU-4 by
[ICU_PHASE4_PLAN.md](ICU_PHASE4_PLAN.md) D-1

---

## 1. Phase name

**ICU-5 — ICU Intake & Output.**

---

## 2. Purpose in simple words

An ICU patient's fluid balance — what went in, what came out, and the running difference — is
one of the numbers critical care is steered by. Today the hospital prints a NABH chart whose
INPUT and OUTPUT columns come out **blank** and are completed by hand, so no balance can be
totalled, trended or read back.

ICU-5 records those entries as data, so the running balance is computed instead of hand-added.

---

## 3. Current-state audit

### 3.1 The §12.2 premise is largely WRONG and must be corrected

`ICU_SYSTEM_DESIGN.md` §12.2 described the existing `IO_CHART` as a surgery-keyed form storing
opaque JSON in `surgery_forms`. **Verified against the code, that is not what it is.**

| §12.2 claim                                                     | Reality                                                  | Evidence                                                                                                             |
| --------------------------------------------------------------- | -------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------- |
| "surgery-keyed, an ICU patient with no surgery cannot have one" | **False.** It takes `admissionId`                        | `IoChartForm({ admissionId })`; `SurgeryForm.surgeryId` is **nullable** and the entity also carries `ipdAdmissionId` |
| "stores opaque JSON"                                            | **False. It stores nothing at all**                      | `IoChartForm` javadoc: _"derived from the patient's recorded vitals (no fill/save step), so it simply prints"_       |
| "a document, not a time series"                                 | **False.** It is a _rendering of_ the vitals time series | `buildIoChartHtml(rows …)` is exported from `VitalsPanel` and fed by `nurseService.getVitals(admissionId)`           |

So the duplicate-model risk §12.2 feared **does not exist**: there is no second store to reconcile
with, and no OT-owned I/O data to migrate. The real gap is narrower and clearer.

### 3.2 What the chart actually prints

`buildIoChartHtml` emits a 10-column NABH sheet:

```
TIME | TEMP | PULSE | RESP. | B.P. || INPUT: I.V. FLUIDS | ORAL || OUTPUT: RYLES TUBE ASPIRATION | URINE O/P | VOMITING MONITION
```

The first five columns are filled from `vitals_records`. **The five I/O columns are emitted
empty** (`<td></td>` ×5 per row, plus ~26 blank rows) for manual completion.

**These five column names are the phase's field list, taken from the hospital's own NABH sheet —
no clinical field is invented in this plan.**

### 3.3 What ICU-4 already changed

`vitals_records.urine_output_ml` exists (ICU-4). **One of the five I/O columns is therefore
already captured as data**, and the printed URINE O/P column could already be filled today. That
is a genuine overlap ICU-5 must resolve rather than duplicate — see D-2.

### 3.4 Summary

| Component                    | State                                                   |
| ---------------------------- | ------------------------------------------------------- |
| `FormRegistry.IO_CHART`      | ✅ exists, category `OT`, gated by Files & Access       |
| `IoChartForm.jsx` (83 lines) | ✅ print-only, admission-keyed, stores nothing          |
| `buildIoChartHtml`           | ✅ in `VitalsPanel`, exported                           |
| `surgery_forms` store        | ✅ exists but is **not used** for I/O                   |
| Intake/output data           | ❌ **nothing is stored**                                |
| Running balance              | ❌ does not exist                                       |
| `urine_output_ml`            | ⚠️ exists on `vitals_records` (ICU-4) — overlaps OUTPUT |

---

## 4. Reusable components

- **`IcuStay`** (ICU-3) — the stay window, read-only. ICU-5 reads it to scope entries to a
  critical-care period exactly as ICU-4 does. **No IcuStay change.**
- **`buildIoChartHtml`** — the NABH sheet exists; ICU-5 fills columns it already renders.
- **`IoChartForm.jsx`** — the print entry point stays; it gains real data.
- **`FormRegistry.IO_CHART` + `FormAccessService.assertCanEdit`** — the per-hospital gate is
  already there. **No new form key, no new permission, no new role.**
- **`PerformingNurseResolver` / `performedByNurseId`** — the Separate-Nurse-Login attribution
  rule, reused verbatim.
- **`NurseWriteAccess` / `NurseAccessGuard`** — patient scoping, unchanged.
- **`AuditLogService`, `RealtimeNotifier`** — best-effort, after commit.
- **`VitalsPanel`** — the I/O tab sits beside it in the same workspace; no new page.

---

## 5. Required database changes

**One new table, `icu_io_entry`.** Justified below.

| Column                                          | Type                 | Note                                                                             |
| ----------------------------------------------- | -------------------- | -------------------------------------------------------------------------------- |
| `id` / `public_id` / `hospital_id`              | —                    | house convention; `hospital_id` NOT NULL                                         |
| `ipd_admission_id`                              | BIGINT NOT NULL      | the key everything else in the chart uses                                        |
| `patient_id`                                    | BIGINT NOT NULL      | denormalised, as every clinical table does                                       |
| `direction`                                     | VARCHAR(6) NOT NULL  | `INTAKE` \| `OUTPUT`                                                             |
| `route`                                         | VARCHAR(30) NOT NULL | the NABH columns only: `IV_FLUIDS`, `ORAL`, `RYLES_ASPIRATION`, `URINE`, `VOMIT` |
| `volume_ml`                                     | INT NOT NULL         | the measured amount                                                              |
| `occurred_at`                                   | DATETIME NOT NULL    | when it happened, not when it was typed                                          |
| `notes`                                         | VARCHAR(255) NULL    | e.g. the fluid name                                                              |
| `recorded_by_user_id` / `performed_by_nurse_id` | BIGINT               | same attribution as vitals                                                       |
| `supersedes_io_entry_id`                        | BIGINT NULL          | correction chain, mirroring ICU-4                                                |
| `is_active`, `created_at`                       | —                    | house convention                                                                 |

**Why a new table is genuinely required** — the three alternatives fail:

1. **Extend `vitals_records`** (the ICU-4 approach). Fails: a vitals row is _one observation at
   one instant_, but a patient can have several intakes and outputs between two observations. One
   row per reading cannot hold many events, and forcing it would either lose entries or invent
   fake observation times.
2. **Reuse `surgery_forms` JSON.** Fails on ICU-5's whole purpose: a running balance must be
   summed and trended, and opaque JSON cannot be aggregated by the database. (Note this is a
   _different_ reason from §12.2's — the form is not currently used for I/O at all.)
3. **Derive from medication/infusion records.** Fails: those do not exist yet (ICU-6+), and oral
   intake and vomit have no medication record by definition.

A running balance is `SUM(volume_ml)` grouped by direction over a time range — an aggregate the
database does trivially and a JSON blob cannot do at all.

**No change to any existing table.** All eight ICU-4 columns stay as they are.

---

## 6. Backend changes

- **`IcuIoEntry`** entity + **`IcuIoEntryRepository`**, every finder tenant-scoped.
- **`IcuIoService`** — `record`, `list(admissionId, from, to)`, `balance(admissionId, from, to)`,
  `correct(publicId, …)`. Same `assertCanEdit("IO_CHART")` gate; same recording-nurse +
  edit-window amendment rules ICU-4 settled on (D-6 there), so authorization does not widen.
- **`IcuIoController`** — under the existing `/hospital/nurse/**` namespace beside vitals, or
  `/hospital/icu/**`; see D-4. Declared in `ControllerModules` if a new controller class is added
  — the `FacilityAccessAspect` null-means-allowed trap applies.
- **Balance is arithmetic only**: intake total, output total, net. **No target, no threshold, no
  "positive balance" warning, no colour by value.** Deciding what a balance means is clinical
  interpretation and is explicitly out of scope.
- **Append-only correction**, mirroring ICU-4 exactly: an entry inside an ICU stay window is never
  edited in place; a correction writes a new row carrying `supersedes_io_entry_id`, and the
  original stays readable.

---

## 7. Frontend changes

- **`IoChartPanel.jsx`** — a tab beside Vitals in the existing workspace (`NursePatientDetail`
  and the doctor's `IpdDetails`). Entry form: direction, route, volume, time, optional note; plus
  a list and the running totals. **No new page, no new route.**
- **`buildIoChartHtml`** — fill the five columns it already emits blank, from the recorded
  entries. This is where the printed NABH sheet stops being hand-completed.
- **`icuService.js`** — the read/write calls.
- Superseded entries render struck-through, as ICU-4's vitals do.

---

## 8. Security / tenant requirements

- `hospital_id` NOT NULL on the new table; every finder tenant-scoped; foreign → **404**.
- `assertCanEdit("IO_CHART")` is the only permission gate. The key **already exists** in
  `FormRegistry`, so hospitals control it in Files & Access on day one.
- `supersedes_io_entry_id` must resolve to the **same admission and tenant** — the ICU-4 rule.
- No new role, no new module, no `SecurityConfig` change.
- Use scoped finders so `TenantScopingArchTest` needs no allowlist edit.

---

## 9. Transaction requirements

- Each write is its own `@Transactional`. **ICU-5 does not join the IPD movement transaction and
  never calls `IcuStayService`'s `MANDATORY` methods** — recording a fluid entry must never roll
  back an admission, a bed move or a stay. Same rule ICU-4 established.
- Reading the stay window is a plain read; no lock.
- **No concurrency test required.** Two nurses recording separate I/O entries contend for
  nothing — there is no shared mutable resource, unlike E1's bed claim. Stating this explicitly,
  per the E1 lesson that a green concurrency test proving nothing is worse than none.
- Balance is computed on read, never stored, so it cannot drift from its entries.
- Audit and realtime stay best-effort, after commit.

---

## 10. Test plan

**Unit**

1. Record an intake; record an output; both persist with route and volume.
2. Only the five NABH routes are accepted; an unknown route is rejected.
3. Negative or zero volume rejected; `occurred_at` in the future rejected (the vitals rule).
4. Balance = intake sum, output sum, net — over a supplied range.
5. Balance excludes superseded entries and counts the correction instead.
6. Correction writes a new row; the original remains readable and unmodified.
7. `assertCanEdit("IO_CHART")` denied → rejected.
8. Recording-nurse + edit-window rules enforced on correction (no widening).

**Integration** 9. Entries recorded across an ICU stay read back in order, with the balance. 10. A non-ICU (ward) admission is unaffected — **backward-compatibility gate**. 11. Entries survive a bed move within the same admission (the ICU-4 lesson: the admission is the
key, not the ward).

**Tenant / security** 12. Foreign entry `publicId` → 404, nothing written. 13. `supersedes_io_entry_id` pointing at another admission → rejected.

**Transaction / rollback** 14. A failed write leaves no row and no partial state. 15. An audit failure does not roll back a recorded entry (best-effort preserved).

**Frontend (Vitest)** 16. Entry form renders; a recorded entry appears with its route and volume. 17. Running totals display intake, output and net. 18. Superseded entry renders struck-through and remains visible. 19. No threshold/colour/interpretation logic — assert absence of derived judgement.

**Print** 20. `buildIoChartHtml` fills the five I/O columns from entries and leaves them blank when there
are none (so today's behaviour is preserved for hospitals that record nothing). 21. **D-2 separation:** a `vitals_records.urine_output_ml` value does **not** appear in the
balance, does **not** create an `icu_io_entry`, and the printed URINE O/P column is fed
**only** by `icu_io_entry`. 22. **D-2 UI:** with both recorded, the panel labels them distinctly and never sums them together.

**E1 standard:** every new guard verified to **fail** with its fix reverted.

**Regression gates:** `IcuVitalsCorrectionTest`, `VitalsServiceTest`, `IcuStayLifecycleTest`,
`IcuBoardServiceTest`, `FormAccessServiceTest`, full backend (552) and frontend (100).

---

## 11. Manual acceptance checklist

| ID       | Scenario                                                                          | Expected                                                                                                                |
| -------- | --------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------- |
| M-1      | ICU patient: record an IV fluid intake                                            | Appears with volume and time                                                                                            |
| M-2      | Record oral intake, urine output, vomit, Ryles aspiration                         | All five NABH routes accepted                                                                                           |
| M-3      | View running balance                                                              | Intake total, output total, net — arithmetic only                                                                       |
| M-4      | Correct an entry                                                                  | New row; **original still visible**, struck through                                                                     |
| M-5      | Balance after a correction                                                        | Counts the correction, not the superseded value                                                                         |
| M-6      | Print the I/O chart                                                               | The five columns are **filled**, not blank                                                                              |
| M-7      | Print with no entries recorded                                                    | Blank columns, exactly as today                                                                                         |
| M-8      | Move the patient bed/ward mid-stay                                                | Entries still visible (admission-keyed)                                                                                 |
| M-9      | Ward (non-ICU) patient                                                            | Unchanged behaviour                                                                                                     |
| M-10     | Files & Access: IO_CHART read-only for nurses                                     | Recording refused                                                                                                       |
| M-11     | Cross-tenant entry                                                                | 404, nothing written                                                                                                    |
| M-12     | ICU board / vitals unaffected                                                     | ICU-2/3/4 behaviour identical                                                                                           |
| **M-13** | **Record urine on BOTH the vitals form and the I/O chart, then view the patient** | Both appear, **distinctly labelled**; neither is merged into the other; the balance counts **only** the I/O entry (D-2) |

---

## 12. Decisions / blockers

| #       | Decision                                                                                                       | Recommendation                                                                                                                                                                                                  |
| ------- | -------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **D-1** | §12.2's premise is wrong (§3.1). Proceed on the corrected understanding?                                       | ✅ **APPROVED.** `ICU_SYSTEM_DESIGN.md` §12.2 now carries a dated correction notice; the original text is preserved unedited beneath it so the architectural history is not silently rewritten. See §12.1 below |
| **D-2** | **`vitals_records.urine_output_ml` (ICU-4) overlaps the URINE O/P column.** Two places could hold urine output | ✅ **APPROVED — see §12.2 below for the binding rule**                                                                                                                                                          |
| **D-3** | Is the printed NABH sheet in ICU-5 or deferred?                                                                | **In scope.** The columns already exist and are printed blank; filling them is the visible payoff and is cheap. Deferring would leave nurses hand-writing data the system now holds                             |
| **D-4** | Endpoint namespace: `/hospital/nurse/io` (beside vitals) or `/hospital/icu/io`?                                | **`/hospital/nurse/io`** — the chart is admission-scoped and gated by `IO_CHART`, not by the ICU module. That also keeps it working for a ward patient if a hospital wants it                                   |
| **D-5** | Restrict recording to ICU-period entries, or allow any IPD admission?                                          | **Allow any admission; apply the append-only guard only inside an ICU stay window** — exactly ICU-4's shape. The chart is an IPD form, not an ICU-only one                                                      |
| **D-6** | Free-text fluid name in `notes`, or a coded fluid list?                                                        | **Free text.** A coded list is a formulary decision, not ours to invent                                                                                                                                         |

### 12.1 D-1 as approved — the correction is on the record

`ICU_SYSTEM_DESIGN.md` §12.2 now opens with a dated **CORRECTION** block stating that the ICU-1
current-state assumption was wrong and was corrected during the ICU-5 audit after inspecting
`IoChartForm`. It tabulates each false assumption against the verified reality and its evidence,
and notes that option (c) survives but **for a different reason** than originally argued.

**The original ICU-1 text is preserved unedited below the notice**, and its heading is marked
_"as assessed in ICU-1 — see the correction above"_. Nothing was rewritten in place: the record of
how the decision was reached is as much a part of the architecture as the decision.

### 12.2 D-2 as approved — the binding rule on urine output

**`icu_io_entry` is the authoritative source for ICU fluid-balance calculations and the NABH I/O
chart.**

**`VitalsRecord.urine_output_ml` remains an independent point-in-time observation. It is NOT
automatically synchronised into `icu_io_entry`, in either direction.**

What this means for the implementation:

1. The running balance (§9) and the printed URINE O/P column (§7) read **`icu_io_entry` only**.
   `urine_output_ml` never contributes to a balance.
2. No trigger, no backfill, no write-through, no reconciliation job between the two. They are
   separate clinical statements: one is _"the output measured over this interval"_, the other is
   _"urine output observed at this instant"_, and conflating them would fabricate entries no
   clinician recorded.
3. **The ICU-4 column is not deleted, deprecated or hidden.** It is in use and stays exactly as it
   is; ICU-5 changes nothing about how it is written or read.
4. **The UI must make the distinction explicit wherever both are visible.** The vitals row's urine
   figure and the I/O chart's urine entries must never be presented as one number or in one
   column. Each is labelled for what it is — an observation on the vitals timeline, an entry on
   the fluid-balance chart — so a nurse reading either knows which they are looking at and why the
   two may legitimately differ. This is a required acceptance item (M-13), not a nicety.

> This is the one genuine duplicate-model risk in the phase, and it is one I introduced in ICU-4
> by adding `urine_output_ml` before the I/O model existed. The rule above resolves it by
> separating meanings rather than by syncing values — syncing would have been the failure mode,
> not the fix.

**No blocker.** ICU-3 supplies the stay window and ICU-4 the correction pattern. Nothing here
requires a change to shared IPD architecture, `IcuStay`, IPD movement, or any existing table.

---

## 13. Recommended implementation order

```
ICU-5.0  entity + repository + migration + schema mirror      (no behaviour)
ICU-5.1  IcuIoService: record + list + validation             (TDD; nothing calls it yet)
ICU-5.2  balance calculation                                  (pure arithmetic, easy to pin)
ICU-5.3  append-only correction, mirroring ICU-4
ICU-5.4  controller + tenancy tests
ICU-5.5  frontend panel beside Vitals
ICU-5.6  fill the printed NABH columns
ICU-5.7  tests, regression, manual checklist
```

5.1 before 5.2 so the balance is computed over data whose shape is already settled; 5.6 last
because the print is a pure read over everything above it.

---

## 14. Checkpoint

Audit and plan only. **No code, no migration, no test, no frontend file changed.** Awaiting
review of D-1 … D-6 before ICU-5.0.
