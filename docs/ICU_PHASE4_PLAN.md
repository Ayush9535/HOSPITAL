# ICU Phase 4 — ICU Observations (Vitals) & Append-Only Correction

**Checkpoint:** ICU-4 (AUDIT → DESIGN → PLAN)
**Date:** 2026-08-25
**Branch:** `icu` · **Base SHA:** `d82227f`
**Status:** Audit + plan — **awaiting approval. No implementation, no migration, no code changed.**
**Roadmap source:** [ICU_SYSTEM_DESIGN.md](ICU_SYSTEM_DESIGN.md) §12.1 (the declared PREREQUISITE),
scoped by [ICU_PHASE3_PLAN.md](ICU_PHASE3_PLAN.md) §15 ("ICU-4+")

---

## 1. Next phase name

**ICU-4 — ICU Observations (Vitals) & Append-Only Correction.**

### How this was determined (no phase invented)

There is no separate roadmap file: `ICU_PHASE_PLAN.md` and `ICU_DOMAIN_GAP_ANALYSIS.md` are
**0-byte placeholders**, and `icu hisory.txt` is an 18,971-line session transcript, not a plan.
The authoritative forward list is **`ICU_SYSTEM_DESIGN.md` §12**, which ICU-3 §15 defers to
"ICU-4+": vitals, ventilator, infusions, intake/output, severity scores, alerts.

§12 orders itself. **§12.1 and §12.2 are labelled PREREQUISITE**; §12.3 is "remaining scope".
`ICU_PHASE2_PLAN.md` line 14 states both prerequisites "travel with the chart". §12.1 is the
data spine — every later item (ventilator, infusions, I/O, scores) reuses its append-only
correction pattern — so it is first.

**Scope decision, flagged as D-1:** the roadmap's chart list is six items. Your standing rule is
one feature per phase, so ICU-4 takes **§12.1 only**. §12.2 (I/O) becomes ICU-5, §12.3 items
ICU-6+. This narrows a phase; it does not replace or reorder the roadmap.

---

## 2. Purpose in simple terms

An ICU nurse records observations far more often than a ward nurse, and in critical care **a
prior value is itself clinical evidence** — a falling SpO₂ over three readings is the finding.
Today a vitals row is **edited in place**, so the earlier number disappears.

ICU-4 does two things:

1. Lets ICU record the observations critical care actually needs (MAP, GCS, CVP, urine output),
   **in the existing vitals table** rather than a second one.
2. Makes an ICU-period observation **correctable without destroying the original** — a
   correction writes a new row pointing at the one it supersedes, and both remain visible.

Ward vitals behave exactly as they do today.

---

## 3. Current-state audit

### 3.1 What exists (verified, not assumed)

| Component                                   | State | Note                                                                                                                                             |
| ------------------------------------------- | ----- | ------------------------------------------------------------------------------------------------------------------------------------------------ |
| `entity/VitalsRecord`                       | ✅    | `temperature, pulse, bpSystolic, bpDiastolic, respiratoryRate, spo2, weight, painScore, remarks`, `isActive`, `recordedAt`, `performedByNurseId` |
| `VitalsService.create`                      | ✅    | `assertCanEdit("VITALS")` → validate → save → audit → `notifier.refresh`                                                                         |
| `VitalsService.update`                      | ⚠️    | **overwrites in place.** Already guarded by an `EDIT_WINDOW` and "only the recording nurse", but no prior version is kept                        |
| `VitalsController`                          | ✅    | `/hospital/nurse/vitals` — POST, GET `/admission/{id}`, PUT `/{publicId}`                                                                        |
| `VitalsRecordRepository`                    | ✅    | `findByPublicId`, `findByIpdAdmissionIdAndIsActiveTrueOrderByRecordedAtDesc`, ICU-2's `findLatestForAdmissions`                                  |
| `FormAccessService.assertCanEdit("VITALS")` | ✅    | per-hospital Files & Access gate, already in both write paths                                                                                    |
| `IcuStay`                                   | ✅    | **ICU-3.** The stay window this phase needs already exists                                                                                       |
| `VitalsPanel.jsx`                           | ✅    | 382 lines, nurse entry + history                                                                                                                 |
| `IpdDetails.jsx` vitals tab                 | ✅    | doctor's mirror, `readOnly`                                                                                                                      |
| `VitalsServiceTest`                         | ✅    | 8 tests                                                                                                                                          |

### 3.2 Two findings that change §12.1's plan

**F1 — `ClinicalPdfService` does not read `vitals_records` at all** (0 references). §12.1 warns
that ICU columns "must not leak into the OPD case-paper VITAL SIGNS table". That table is built
from **OPD** vitals (`hospital_vitals` / the `opd` typed columns), a different capability that
merely shares the word. **No PDF work is needed, and no guard against leakage is needed.**

**F2 — `update()` is already narrower than R9 implied.** It rejects edits by anyone but the
recording nurse and after `EDIT_WINDOW`. So the exposure is smaller than "any doctor can silently
overwrite an ICU observation": in practice it is _the same nurse, inside the window_. The defect
is real — the prior value is still destroyed — but this reframes the guard as **preserving
history**, not as closing a security hole. It also means the ICU-conditional 409 will fire rarely.

### 3.3 What is missing

- No MAP, GCS, CVP or urine-output field anywhere in the codebase.
- No `supersedes_vitals_id` — no correction chain, no way to render a superseded value.
- No link from a vitals row to the ICU stay whose window contains it.
- No frontend affordance for correcting an ICU observation, or for showing a superseded one.

---

## 4. Required changes (summary)

| Area       | Change                                              | Size               |
| ---------- | --------------------------------------------------- | ------------------ |
| Schema     | 6 nullable columns on `vitals_records`              | 1 migration method |
| Entity     | same 6 fields on `VitalsRecord`                     | small              |
| DTO        | same 6 on `VitalsRequest` + response                | small              |
| Service    | ICU-window guard on `update`; new `correct()`       | moderate           |
| Repository | 1 stay-window query                                 | 1 method           |
| Controller | 1 endpoint (`POST /{publicId}/correction`)          | small              |
| Frontend   | ICU fields + correction flow + superseded rendering | moderate           |

No new table. No change to `hospital_vitals`, `VitalRegistry`, OPD, or the PDF layer.

---

## 5. Reusable existing architecture

Reused unchanged — **nothing here is re-implemented**:

- **`VitalsRecord` / `vitals_records`** — extended, never forked (R3).
- **`IcuStay`** — ICU-3 is the lifecycle authority; ICU-4 only _reads_ stay windows to decide
  whether a row is in an ICU period. It writes no stay and moves no patient.
- **`FormAccessService.assertCanEdit("VITALS")`** — the per-hospital gate stays as the only
  permission check on ICU columns. **No new gate, no new role.**
- **`PerformingNurseResolver` / `performedByNurseId`** — the Separate-Nurse-Login rule is
  untouched.
- **`NurseAccessGuard` / `NurseWriteAccess`** — patient scoping unchanged.
- **`AuditLogService`, `RealtimeNotifier`** — best-effort, after-commit, as today.
- **`VitalsPanel.jsx` / `IpdDetails` vitals tab** — extended, not replaced.
- **IPD movement** — completely untouched. ICU-4 adds no movement path.

---

## 6. Database changes

One migration method, `ensureVitalsIcuColumns()`, six **nullable** columns:

| Column                                 | Type         | Why                                                           |
| -------------------------------------- | ------------ | ------------------------------------------------------------- |
| `map_mmhg`                             | INT NULL     | Mean arterial pressure                                        |
| `cvp_cmh2o`                            | INT NULL     | Central venous pressure                                       |
| `urine_output_ml`                      | INT NULL     | Urine output for the interval                                 |
| `gcs_eye` / `gcs_verbal` / `gcs_motor` | TINYINT NULL | GCS components                                                |
| `gcs_total`                            | TINYINT NULL | Derived and stored, mirroring `RecoveryObservation`'s Aldrete |
| `supersedes_vitals_id`                 | BIGINT NULL  | Self-referencing correction chain                             |

That is 8 columns; "6" above counts GCS as one concern.

**Nullable is the backward-compatibility guarantee**: every existing row and every ward reading is
unaffected, and no backfill is required.

**ICU-2's lesson applies:** any NOT NULL column needs an explicit `columnDefinition`, because
`ddl-auto=update` runs _before_ `DatabaseMigrationRunner`. All of these are nullable, so the trap
does not fire — but the rule is restated so it is not re-learned a fourth time.

Mirror all of it in `setup/schema-full.sql`.

---

## 7. Backend changes

**`VitalsService.update` — ICU-conditional guard.** If the row's `recordedAt` falls inside any ICU
stay window for its admission, reject with **`ConflictException` (409)** and point at the
correction endpoint. Rows outside every ICU window behave **exactly as today**. This is the whole
of §12.1 step 3 and is deliberately conditional — it is not a change to IPD vitals semantics.

**`VitalsService.correct(publicId, req)` — the append-only path.** Writes a **new** row carrying
the corrected values with `supersedes_vitals_id` set to the original. The original is **not**
deleted and **not** `isActive=false`; it must stay visible as a superseded value, because hiding
it would recreate the very loss this phase exists to prevent. Same `assertCanEdit("VITALS")`, same
nurse resolution, same audit, same after-commit notify.

**`IcuStayRepository`** — one query: does an ICU stay for this admission contain this instant?
Closed stays have `dischargedAt`; the active one is open-ended.

**`VitalsController`** — `POST /hospital/nurse/vitals/{publicId}/correction`. Existing
`@PreAuthorize` roles, no new namespace, no new module gate — vitals already sit behind
`CLINICAL_RECORDS`.

**Read path** — `getByAdmission` returns the correction chain so the UI can render it. It must
**not** filter superseded rows out.

**No clinical interpretation.** No thresholds, no severity, no colour-coding by value, no alerts.
GCS total is arithmetic (E+V+M), not a judgement. MAP is **stored as measured**, not computed
(D-2).

---

## 8. Frontend changes

- **`VitalsPanel.jsx`** — ICU fields shown **only when the admission has an active ICU stay**
  (the board already tells us). A ward nurse's form is unchanged.
- **Correction flow** — for an ICU-period row, "Edit" becomes "Correct", posting to the new
  endpoint.
- **Superseded rendering** — a superseded row stays in the history, visually struck through, with
  the correcting value linked. §12.1 step 2 explicitly requires "renders struck-through rather
  than vanishing".
- **`IpdDetails` vitals tab** — same rendering, `readOnly` as today.
- **`vitalsService.js`** — one method.

No new page, no new route, no new tab.

---

## 9. Security / tenant requirements

- Every read/write stays scoped by `hospital_id`; foreign → **404**, never 403.
- **`findByPublicId` is unscoped today** and followed by a manual hospital check. ICU-4 must not
  copy that shape into `correct()` — use a scoped finder, or the arch test will (correctly) flag a
  new lookup-by-id. See D-4.
- `supersedes_vitals_id` must be validated to reference a row in the **same admission and same
  tenant**; otherwise a correction could point across patients.
- No new role, no new module, no change to `SecurityConfig`.
- `TenantScopingArchTest` allowlist: only if an unavoidable lookup-by-id is added.

---

## 10. Transaction requirements

- `create`, `update` and `correct` are each `@Transactional` — the boundary already exists on
  `create`/`update` and `correct` mirrors it.
- **ICU-4 does not participate in the IPD movement transaction.** It is not a movement, and
  `IcuStayService`'s `MANDATORY` methods are **not** called. Recording an observation must never
  be able to roll back an admission.
- Reading the stay window is a plain read inside the vitals transaction; it takes no lock. There
  is no contended resource here — two nurses recording vitals do not race for anything, so **no
  concurrency test is required** (contrast E1's bed claim).
- Audit and realtime stay best-effort, after commit.

---

## 11. Test plan

**Unit — `VitalsServiceTest` (extend, 8 existing)**

1. ICU columns persist and read back; nulls for a ward reading.
2. `gcs_total` equals E+V+M when components are supplied.
3. `update` on a **non-ICU-period** row still works exactly as today _(regression)_.
4. `update` on an **ICU-period** row → 409 naming the correction path.
5. `correct` writes a NEW row; the original still exists, unmodified, still `isActive`.
6. `correct` sets `supersedes_vitals_id` to the original.
7. `correct` respects `assertCanEdit("VITALS")` (denied → `AccessDeniedException`).
8. A correction chain of three renders in order.

**Integration** 9. ICU stay open → record → correct → close stay → history intact. 10. Vitals recorded **before** a stay opened remain editable by the old path. 11. `getByAdmission` returns superseded rows, not just current ones.

**Tenant / security** 12. Foreign vitals `publicId` → 404 on `correct`. 13. `supersedes_vitals_id` pointing at another admission's row → rejected. 14. Cross-tenant case added to `CrossTenantIsolationTest` _if_ a lookup-by-id is introduced.

**Transaction / rollback** 15. A failure inside `correct` leaves **no** new row and the original untouched. 16. An audit failure does **not** roll back a recorded observation _(best-effort preserved)_.

**Concurrency** — **none required** (§10). State that explicitly rather than writing a test that
proves nothing, per the E1 lesson.

**Frontend (Vitest)** 17. ICU fields render only with an active ICU stay. 18. ICU-period row offers "Correct", ward row offers "Edit". 19. A superseded row renders struck-through and still visible. 20. No threshold/colour logic exists — assert absence of derived judgement.

**Regression gates** — `VitalsServiceTest`, `FormAccessServiceTest`, `IcuBoardServiceTest`,
`IcuStayLifecycleTest`, `NurseWorkspaceServiceTest`, full backend (534) and frontend (94).

**E1 standard:** each new guard must be verified to **fail** with its fix reverted.

---

## 12. Manual acceptance checklist

| ID   | Scenario                                          | Expected                                                    |
| ---- | ------------------------------------------------- | ----------------------------------------------------------- |
| M-1  | Ward patient: record vitals                       | Unchanged; no ICU fields shown                              |
| M-2  | Ward patient: edit within the window              | Still edits in place, as today                              |
| M-3  | ICU patient: record vitals with MAP/GCS/CVP/urine | Saved; GCS total = E+V+M                                    |
| M-4  | ICU patient: attempt Edit                         | Offered **Correct**, not Edit                               |
| M-5  | ICU patient: correct a reading                    | New row appears; **original still visible**, struck through |
| M-6  | View history after correction                     | Both rows present, in order, linked                         |
| M-7  | Vitals recorded before ICU entry                  | Still editable by the old path                              |
| M-8  | Files & Access: VITALS set read-only for nurses   | Both record and correct refused                             |
| M-9  | Doctor's IPD vitals tab                           | Shows ICU fields + supersede chain, read-only               |
| M-10 | Cross-tenant: correct another hospital's row      | 404, nothing written                                        |
| M-11 | ICU board unaffected                              | ICU-2/ICU-3 behaviour identical                             |

---

## 13. Blockers / decisions

| #       | Decision                                                                     | Recommendation                                                                                                                            |
| ------- | ---------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------- |
| **D-1** | Scope ICU-4 to §12.1 (vitals) only, with §12.2 I/O as ICU-5?                 | **Yes** — one feature per phase; the roadmap is narrowed, not changed                                                                     |
| **D-2** | MAP stored or derived? (§12.1 requires an explicit answer)                   | **Stored.** An arterial-line MAP is measured and differs from a cuff-derived value; deriving it would silently discard a real measurement |
| **D-3** | GCS: components or total only?                                               | **Both** — E/V/M plus total, mirroring `RecoveryObservation`'s Aldrete precedent                                                          |
| **D-4** | `findByPublicId` is unscoped. Add a scoped finder, or keep the manual check? | **Add a scoped finder** for the new path; leave the existing one alone (out of scope)                                                     |
| **D-5** | Should a superseded row be excluded from "latest vitals" on the ICU board?   | **Yes** — the board must show the corrected value. Cheap, but must be explicit                                                            |
| **D-6** | Does the `EDIT_WINDOW` still apply to `correct()`?                           | **No** — a correction is a new clinical record, not an edit. But this widens who can write history; **your call**                         |
| **D-7** | Confirm §12.1 strategy **(b)** over (a)/(c)                                  | **Yes** — (c) forks the table and violates R3                                                                                             |

**No blocker.** ICU-3 delivered the stay window this phase depends on. Nothing here requires a
change to shared IPD architecture, so **no dependency needs escalating**.

**One caveat worth your attention (D-6):** today only the recording nurse, inside a time window,
can alter a vitals row. A correction path that anyone with `VITALS` edit rights can use at any
time is _more_ permissive than today's edit. That is arguably correct for critical care — a later
correction is a real clinical need — but it is a widening, and I will not decide it silently.

---

## 14. Recommended implementation order

```
ICU-4.0  schema + entity + DTO           (nullable columns; no behaviour change)
ICU-4.1  stay-window query + the ICU-conditional guard on update()
           TDD; prove it fires only inside an ICU period
ICU-4.2  correct() + endpoint            (append-only; original preserved)
ICU-4.3  read path returns the chain     (board picks the corrected value — D-5)
ICU-4.4  frontend: ICU fields, Correct flow, superseded rendering
ICU-4.5  tests, regression, manual checklist
```

**4.1 before 4.2** for the same reason C1 preceded C3 in E1: the guard is what makes the
correction path necessary, and it can be tested exhaustively before anything writes.

---

## 15. Checkpoint

Audit and plan only. **No production code, no migration, no test and no frontend file was
changed.** Awaiting review of D-1 … D-7 before ICU-4.0.
