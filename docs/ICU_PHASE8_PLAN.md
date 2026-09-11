# ICU-8 — Timed Severity Scores

**Status:** PLAN ONLY — nothing implemented, no production code touched, no migration written.
**Authority:** `ICU_SYSTEM_DESIGN.md` §12.3 item 3 · `ICU_EXISTING_SYSTEM_AUDIT.md` line 69, §6 item 5
**Predecessor:** ICU-7 — Ventilator Settings History (`1f8b43d`, `ddaef23`), complete and manually verified.
**Baselines:** backend 653 tests, frontend 141 tests.

---

## 1. Phase name and roadmap position

`ICU_SYSTEM_DESIGN.md` §12.3, unchanged and not reordered:

| #   | Item                                            | State                   |
| --- | ----------------------------------------------- | ----------------------- |
| 1   | Continuous infusions — rate over time           | ICU-6, done             |
| 2   | Ventilator settings history — timed snapshots   | ICU-7, done             |
| 3   | **Timed severity scores (SOFA / APACHE / GCS)** | **← ICU-8, this phase** |
| 4   | Alert threshold configuration                   | ICU-9+                  |
| 5   | Append-only semantics for all of the above      | folded into each        |
| 6   | Multiple concurrent ICU consultants (D4)        | deferred again          |

Design text, verbatim:

> 3. Timed severity scores (SOFA / APACHE / GCS) — modelled on `RecoveryObservation`;
>    **record and display only, never interpret or recommend.**

`ICU_EXISTING_SYSTEM_AUDIT.md` line 69:

> | Scores (SOFA / APACHE / GCS) | **CREATE** | `RecoveryObservation` (Aldrete) is the exact precedent | timed-score entity — **document only, no interpretation** |

---

## 2. Purpose in simple terms

An ICU records a severity score at intervals — on admission, then daily — because the _trend_
is what the ward round discusses: "SOFA was 9 on Monday, 6 today." A single current number
answers nothing.

So this is the same machine as ICU-6 and ICU-7 for a third kind of value: a timed row per
scoring, nothing overwritten, append-only correction, and the score at any past moment still
readable.

**It records what a clinician scored. It does not score the patient.** No risk band, no
predicted mortality, no "this patient is deteriorating", no colour by value.

---

## 3. Two findings that shape this phase

Both were checked against source for this plan, and both change what ICU-8 should build.

### 3.1 GCS is already shipped — ICU-8 must not rebuild it

§12.3 lists GCS alongside SOFA and APACHE, but **ICU-4 already implemented it**:
`VitalsRecord.gcsEye / gcsVerbal / gcsMotor / gcsTotal`, summed by
`VitalsService.gcsTotalOf(...)`, with the ICU-4 append-only correction path and the ICU-4
conditional guard.

GCS belongs on the vitals chart — it is a bedside observation taken with the pulse and the BP,
not a daily scoring exercise. Moving or duplicating it would split one patient's neuro
observations across two screens and orphan every value already recorded.

**Recommendation (D-1): leave GCS exactly where it is.** ICU-8 covers SOFA and APACHE only, and
this document records that the roadmap item is _already partly satisfied_ rather than quietly
dropping a third of it.

### 3.2 SOFA and APACHE cannot be auto-computed — the inputs do not exist

`LabOrder` is the only lab entity, and it stores `testName`, `status`, `priority` and ids. **It
has no result value field.** There are no structured lab results anywhere in the system.

SOFA needs platelets, bilirubin, creatinine and PaO₂/FiO₂. APACHE II needs those plus sodium,
potassium, haematocrit and white cell count. **None of the four SOFA labs exist as data**, and
building them would be a lab-module redesign — a stop-and-escalate dependency under the standing
scope rule, not something ICU-8 does on the way past.

This is not a limitation to work around. It matches what the design already ordered: the
clinician enters the score.

---

## 4. Where the line falls between arithmetic and interpretation

This is the one design question ICU-8 turns on, so it is stated once, plainly.

| Operation                                                    | Verdict                       | Precedent                                                                  |
| ------------------------------------------------------------ | ----------------------------- | -------------------------------------------------------------------------- |
| Summing component subscores a clinician entered into a total | **Arithmetic — allowed**      | `VitalsService.gcsTotalOf` (ICU-4), `RecoveryObservation.aldreteScore`     |
| Mapping a raw value (creatinine 3.2 → renal subscore 3)      | **Interpretation — NOT done** | encodes a clinical band table; ICU records values rather than grading them |
| Deriving a subscore from vitals/infusion/ventilator rows     | **Interpretation — NOT done** | also silently couples four phases' data into one number                    |
| Predicted mortality from an APACHE total                     | **Interpretation — NOT done** | explicitly forbidden by §12.3                                              |
| Any threshold, alert or colour by value                      | **NOT done**                  | that is §12.3 item 4, a later phase                                        |

So: **the clinician enters each component; the system adds them up.** Exactly what ICU-4 does
with E+V+M, and what `RecoveryObservation` does with the five Aldrete components.

---

## 5. Relevant existing functionality, and what is reused

Everything structural already exists. ICU-8 writes almost no new _kind_ of code.

| Reused                                                  | For what                                                                           |
| ------------------------------------------------------- | ---------------------------------------------------------------------------------- |
| `RecoveryObservation`                                   | the row shape the design names — timed score rows on a parent                      |
| ICU-7's `IcuVentilatorSetting`                          | the working precedent: timed row, `values_json`, `supersedes_*_id`, `settingAt(t)` |
| `SecurityContextHelper`                                 | tenant + actor                                                                     |
| `NurseWriteAccess` / `NurseAccessGuard`                 | write access; staff-nurse assignment **OR** coverage                               |
| `PerformingNurseResolver` + `performed_by_nurse_id`     | Separate Nurse Login OFF                                                           |
| `FormAccessService.assertCanEdit(key)` + `FormRegistry` | server-side gate (D-3)                                                             |
| `AuditLogService`, `RealtimeNotifier.refresh(...)`      | audit and after-commit push                                                        |
| `DatabaseMigrationRunner.createTableIfMissing(...)`     | added ICU-6, reused unchanged                                                      |
| `IcuStayRepository.findCoveringInstant(...)`            | added ICU-7, reused for the provenance stamp                                       |
| ICU-4/5/6/7 correction rules                            | recorder-only + 12h `EDIT_WINDOW`, unchanged                                       |
| `VentilatorPanel.jsx`                                   | panel structure, strike-through, correct-on-the-row                                |
| `useEnabledVentilatorParams` + `refreshKey`             | the realtime shape from `ddaef23`                                                  |
| `IcuVentilatorServiceTest`                              | test structure, including the mistimed-row case                                    |

**New in kind: one registry class and one thin service.** Nothing else.

### 5.1 Reuse decision — component catalogue or fixed registry?

ICU-7 built a _configurable_ parameter catalogue because ventilator practice genuinely varies
between units. **Severity scores are the opposite case.** SOFA's six organ systems and APACHE
II's variables are internationally standardised; a hospital that renames or drops one no longer
has a SOFA score, and comparing it to anything becomes meaningless. Standardisation is the entire
point of a score.

**So the component list is a fixed Java registry** (`SeverityScoreRegistry`, matching
`VentilatorModeRegistry` / `CareUnitRegistry`), **not** a configurable table.

Configuration belongs one level up: **which score types this hospital uses.** Some units run
SOFA, some APACHE II, some neither. That is a small enable/disable list, and §7 proposes the
cheapest thing that does it rather than a second copy of ICU-7's catalogue machinery.

**Do not reuse `icu_ventilator_parameter` by adding a "domain" column.** It would conflate two
unrelated catalogues, and it means editing a table that shipped last week.

---

## 6. Exact missing functionality

1. No entity, table, service, controller or panel for a severity score of any kind.
2. No way to record SOFA or APACHE at all.
3. No timed score history, and therefore no trend.
4. No per-hospital choice of which score types are in use.

Confirmed absent: `grep -i "sofa\|apache" src/main/java` returns nothing;
no `%score%` table beyond `ot_recovery_observations`.

---

## 7. Proposed data model

One table, plus one small configuration table.

### 7.1 `icu_severity_score` — the clinical record

| Column                  | Type                          | Note                                                           |
| ----------------------- | ----------------------------- | -------------------------------------------------------------- |
| `id`                    | BIGINT PK                     |                                                                |
| `public_id`             | VARCHAR(255) UNIQUE           | UUID via `@PrePersist`, as ICU-5/6/7                           |
| `hospital_id`           | BIGINT NOT NULL               | tenant ownership (I12)                                         |
| `ipd_admission_id`      | BIGINT NOT NULL               | **the score-series identity** — survives ward/bed moves        |
| `patient_id`            | BIGINT NOT NULL               | denormalised, as ICU-6/7                                       |
| `icu_stay_id`           | BIGINT NULL                   | provenance stamp only, via `findCoveringInstant`               |
| `score_type`            | VARCHAR(20) NOT NULL          | `SOFA` / `APACHE_II` — a `SeverityScoreRegistry` key           |
| `components_json`       | TEXT NULL                     | `{"respiratory":2,"coagulation":1,...}` keyed by component key |
| `total_score`           | INT NULL                      | **sum of the entered components**, or the entered total (§8.2) |
| `scored_at`             | DATETIME(6) NOT NULL          | when the patient was scored                                    |
| `recorded_by_user_id`   | BIGINT NULL                   |                                                                |
| `performed_by_nurse_id` | BIGINT NULL                   |                                                                |
| `supersedes_score_id`   | BIGINT NULL                   | append-only correction                                         |
| `note`                  | VARCHAR(255) NULL             |                                                                |
| `is_active`             | TINYINT(1) NOT NULL DEFAULT 1 |                                                                |
| `created_at`            | DATETIME(6) NOT NULL          |                                                                |

Indexes: `UNIQUE(public_id)`, `(ipd_admission_id, score_type, scored_at)`, `(hospital_id)`.

`components_json` rather than a column per component, for the ICU-7 reason: SOFA has six
components and APACHE II has twelve, they are different sets, and one table holding both cannot
have a column each without eighteen mostly-null columns.

`total_score` **is** stored, unlike ICU-5's balance which is always recomputed. The difference is
that a balance is a derived view of rows that can each be corrected, whereas a total is part of
what was charted at that moment — the number that went in the notes and on the ward round. It is
computed on write and never recomputed on read.

### 7.2 `icu_score_type_setting` — which scores this hospital uses

Overrides only, lazy default (a registry type with no row is **enabled**), exactly the
`hospital_vitals` / `icu_ventilator_parameter` shape.

| Column        | Type                          | Note         |
| ------------- | ----------------------------- | ------------ |
| `id`          | BIGINT PK                     |              |
| `public_id`   | VARCHAR(255) UNIQUE           |              |
| `hospital_id` | BIGINT NOT NULL               |              |
| `score_type`  | VARCHAR(20) NOT NULL          | registry key |
| `enabled`     | TINYINT(1) NOT NULL DEFAULT 1 |              |
| `created_at`  | DATETIME(6) NOT NULL          |              |

`UNIQUE(hospital_id, score_type)`.

**No display name, no unit, no custom types, no delete.** A hospital cannot invent a severity
score or rename SOFA — see §5.1. This is a checkbox list, and the table matches that.

### 7.3 `SeverityScoreRegistry` (Java, not a table)

Fixed component sets, each with its own valid subscore range:

- **`SOFA`** — six components, each **0–4**: `respiratory`, `coagulation`, `liver`,
  `cardiovascular`, `cns`, `renal`. Total 0–24.
- **`APACHE_II`** — recorded as a **total only** (§8.2), 0–71.

Rationale for the APACHE split: SOFA's six organ subscores are each a single judgement a
clinician makes at the bedside. APACHE II's twelve variables are largely laboratory values the
system does not hold, so offering twelve component inputs would invite copying numbers off
another screen into a form that cannot check them. Recording the total the clinician already
calculated is honest about where the number came from.

---

## 8. Required database changes

**New tables: two** (§7.1, §7.2). **Existing-table changes: none** — nothing on `wards`,
`ipd_admissions`, `icu_stay`, `vitals_records`, `beds`, `icu_ventilator_*`, `hospital_vitals`.
`IpdAdmission` gains nothing (I21 holds).

**Migration:** one `ensureIcuSeverityScoreTables()` calling the existing `createTableIfMissing`,
plus the `setup/schema-full.sql` mirror.

**Trap:** nullable columns stay nullable; `score_type`, `enabled`, `is_active`, `scored_at` and
`created_at` carry explicit `columnDefinition`. The `NOT NULL`-without-`columnDefinition` trap
has hit this project three times.

**No backfill, no seeding.** No prior score data exists, and the lazy default means the settings
table starts empty.

---

## 9. Required backend changes

### 9.1 New files

| File                                                                      | Purpose                                                                                      |
| ------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------- |
| `entity/IcuSeverityScore.java`                                            | §7.1                                                                                         |
| `entity/IcuScoreTypeSetting.java`                                         | §7.2                                                                                         |
| `repository/IcuSeverityScoreRepository.java`                              | tenant-scoped finders + `findSupersededIds`                                                  |
| `repository/IcuScoreTypeSettingRepository.java`                           | tenant-scoped finders                                                                        |
| `service/hospital/icu/SeverityScoreRegistry.java`                         | §7.3 — types, components, ranges                                                             |
| `service/hospital/icu/ScoreTypeSettingService.java`                       | `list`, `enabledTypes`, `toggle`                                                             |
| `service/hospital/icu/IcuSeverityScoreService.java`                       | `record`, `correct`, `getByAdmission`, `latestOf(type)`, `scoreAt(type, t)`, `supersededIds` |
| `controller/hospital/IcuSeverityScoreController.java`                     | clinical API                                                                                 |
| `controller/hospital/IcuScoreTypeSettingController.java`                  | admin config API                                                                             |
| `dto/icu/IcuSeverityScoreRequest.java`, `IcuScoreTypeSettingRequest.java` | request bodies                                                                               |

### 9.2 Modified files

| File                                          | Change                                                                                                     |
| --------------------------------------------- | ---------------------------------------------------------------------------------------------------------- |
| `config/DatabaseMigrationRunner.java`         | `+ ensureIcuSeverityScoreTables()`                                                                         |
| `setup/schema-full.sql`                       | mirror DDL                                                                                                 |
| `entitlement/ControllerModules.java`          | both controllers → `CLINICAL_RECORDS` — **mandatory**, an undeclared controller reads as ALLOWED (trap T1) |
| `service/hospital/FormRegistry.java`          | `+ SEVERITY_SCORE` key, NURSING category (D-3)                                                             |
| `security/TenantScopingArchTest.java`         | allowlist the reviewed `requireAdmission`, as ICU-5/6/7                                                    |
| `service/hospital/FormAccessServiceTest.java` | form count 22 → 23                                                                                         |

### 9.3 Validation — structural only

- `score_type` must be a registry key **and** enabled for this hospital.
- Each component key must belong to that type's component set; unknown keys are **dropped**
  (the ICU-7 rule: the catalogue can change between form load and save).
- Each subscore must be an integer inside its declared range — a **range check on an entry field**,
  not a clinical judgement. `total_score` must fall inside the type's total range.
- `scored_at` may not be in the future.
- **No derived value beyond the sum.** No mortality estimate, no band, no trend arithmetic.

`scoreAt(type, t)` is ICU-7's `settingAt(t)` verbatim: newest non-superseded row of that type
scored at or before `t`.

---

## 10. Required frontend changes

**No new page.** The ICU Patient Chart stays one workspace.

| File                                          | Change                                                                                                                 |
| --------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------- |
| `pages/hospital/nurse/SeverityScorePanel.jsx` | **new** — latest score per enabled type, entry form driven by the registry, history with correction on the row's right |
| `hooks/useEnabledScoreTypes.js`               | **new** — mirrors `useEnabledVentilatorParams`, takes `refreshKey`                                                     |
| `pages/hospital/ScoreSettingsCard.jsx`        | **new** — a checkbox list of score types; mounted beside `VentilatorSettingsCard`, gated on `modules.includes('ICU')`  |
| `services/icuService.js`                      | clinical + config calls                                                                                                |
| `pages/hospital/nurse/NursePatientDetail.jsx` | new `scores` tab, `verdictFor('scores')`, **passing `refreshKey`**                                                     |
| `pages/hospital/IpdDetails.jsx`               | same tab, same verdict, **writable**, `refreshKey={panelRefreshKey}`                                                   |
| `pages/hospital/HospitalAdminDashboard.jsx`   | mount the settings card with `refreshKey={icuRefreshKey}`                                                              |

The panel renders components from the registry the API returns, so the component list lives in
one place. Realtime is wired from the start — ICU-7 had to be retrofitted in `ddaef23`, and that
lesson is applied here rather than repeated.

**Display rule:** the total is shown as a number beside its components and its date. No colour by
value, no arrow, no "improving/worsening" label. A trend the reader draws from a list of dated
numbers is theirs; a trend the system asserts is interpretation.

---

## 11. API changes

All **additive**. No existing endpoint changes shape.

**Clinical** — `/hospital/nurse/severity-scores`:

```
POST   /                                    record a score
GET    /admission/{id}                      history, newest first, + registry + supersededIds
GET    /admission/{id}/latest               latest non-superseded score per enabled type
POST   /{publicId}/correction               append-only correction
GET    /types                               enabled score types with their components and ranges
```

**Configuration** — `/hospital/icu/score-types` (hospital-only, no `/clinic` alias — clinics have
no IPD):

```
GET    /            HOSPITAL_ADMIN   every type with its effective enabled flag
PUT    /{type}      HOSPITAL_ADMIN   toggle
```

No POST (no custom types), no DELETE.

Clinical `@PreAuthorize` copies `IcuVentilatorController`:
`NURSE, NURSE_INCHARGE, DOCTOR, HOSPITAL_ADMIN, RECEPTIONIST`.

---

## 12. Security and tenant isolation

- Every query filters on `hospital_id` from the JWT. Foreign tenant → `ResourceNotFoundException`
  → **404, never 403**, on both clinical and config paths.
- Config writes `HOSPITAL_ADMIN` only.
- `NurseWriteAccess.assertCanWriteFor(admissionId)` on writes; `NurseAccessGuard` on reads when
  the role is `NURSE` — assignment **OR** coverage, **both branches** (the ICU-2 bug).
- `FormAccessService.assertCanEdit("SEVERITY_SCORE")` in the write path — server-side, not UI-only.
- Correction: recorder-only + 12h window, the ICU-4 rule unchanged. **No widening.**
- Disabled-score-type filtering is **server-side**.
- `ControllerModules` declaration mandatory for both controllers (T1).
- **No new role. No new permission.** A `FormRegistry` key is a new _form_ under the existing
  model — lazy-defaults to enabled + BOTH, needs no seeding, and appears in the Files & Access
  card automatically (that card renders from the API).
- `RealtimeNotifier.refresh(hospitalId)` after commit — never `HospitalWebSocketHandler` directly.

---

## 13. Transaction requirements

Identical to ICU-5/6/7: **recording a score is not a patient movement.**

- One `@Transactional` per public write, on the public method, never a private helper (that was C1).
- **Never** joins the IPD movement transaction; **never** calls `IcuStayService`'s `MANDATORY`
  methods. A failed score write cannot roll back an admission, a bed move or an ICU stay.
- Config writes are their own transaction.
- **No locking, and no concurrency test.** Two clinicians scoring the same patient produce two
  appended rows — correct, not a race. Per the standing rule, no test is written for a race that
  does not exist, and no concurrency protection is claimed.

---

## 14. Interaction with existing ICU features

| Feature                       | Interaction                                                                                                                                                                                                                                               |
| ----------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`IcuStay`**                 | **Read-only, one direction.** `findCoveringInstant` for the provenance stamp; never created, closed or mutated. Scores key on `ipd_admission_id`, so a MICU→SICU transfer keeps one continuous series.                                                    |
| **Vitals**                    | **None — and this is deliberate.** GCS stays on the vitals chart (§3.1). No subscore is derived from a vitals row; MAP feeding a cardiovascular subscore would be interpretation and would silently couple two phases.                                    |
| **I/O**                       | **None.** No fluid figure contributes to any score.                                                                                                                                                                                                       |
| **Infusions**                 | **None.** A vasopressor dose is part of the real SOFA cardiovascular criterion, and ICU-8 does **not** read it — classifying a drug as a vasopressor and grading its dose is exactly the interpretation §12.3 forbids. The clinician enters the subscore. |
| **Ventilator**                | **None.** FiO₂ is recorded in ICU-7 and is **not** combined with a PaO₂ the system does not hold.                                                                                                                                                         |
| **ICU Dashboard / Bed Board** | **No change** (D-4 precedent). The board is a capacity view built from beds; putting a severity score on it turns a bed grid into a triage display, which is interpretation and is not in the roadmap.                                                    |
| **Lab**                       | **None.** `LabOrder` has no result field (§3.2); ICU-8 neither reads nor extends it.                                                                                                                                                                      |
| **Billing**                   | **None.**                                                                                                                                                                                                                                                 |
| **Non-ICU IPD patients**      | Zero rows; behaviour identical to today.                                                                                                                                                                                                                  |

---

## 15. What MUST NOT be changed

- `IcuStay` lifecycle, IPD movement, `admitFromOpd`, `changeBed`, discharge.
- ICU dashboard and bed board.
- Vitals, including GCS — no move, no mirror, no duplicate.
- ICU-5 I/O, ICU-6 infusions, ICU-7 ventilator tables, services or panels.
- `icu_ventilator_parameter` — **not** extended with a "domain" column.
- The OPD vitals configuration (`hospital_vitals`, `VitalRegistry`, `VitalsSettingsCard`).
- `LabOrder` and the lab module.
- Security, tenancy, global error handling, CI/CD, pharmacy, billing.
- `icu_unit_profile` — still deferred, still not read.

---

## 16. Automated test plan

**Configuration** — `ScoreTypeSettingServiceTest`:

1. Both registry types list with their components and ranges, no rows present.
2. A type with no row reads as **enabled** (lazy default).
3. Toggling off writes an override; the type stays listed, disabled.
4. `enabledTypes` excludes disabled ones.
5. An unknown type key → 404.
6. Foreign tenant → 404; a toggle in one tenant does not affect another.

**Clinical** — `IcuSeverityScoreServiceTest`:

7. Recording a SOFA stores every component and the summed total.
8. **The total is the sum of the entered components** — arithmetic only.
9. A partial SOFA (some components) sums what was given.
10. APACHE II records a total with no components.
11. A second scoring **appends**; the first is unchanged.
12. History is ordered newest-first, per type.
13. `scoreAt(type, t)` returns the score in force; null before the first.
14. `latestOf` returns one row per enabled type.
15. A **disabled** score type cannot be recorded.
16. An unknown component key is dropped; a known one is kept.
17. A subscore outside its range is rejected; a total outside its range is rejected.
18. A future `scored_at` is rejected.
19. Recorder-only correction; another user → `AccessDeniedException`.
20. Edit-window expiry → rejected.
21. A correction appends with `supersedes_score_id`; the original stays `is_active`.
22. **Mistimed-row case** — a superseded row is removed from the timeline entirely. This is the
    only scenario that isolates the filter; ICU-6 proved the naive version passes without it.
23. `assertCanEdit` denial blocks every write.
24. Foreign tenant → 404 on read and correction; no cross-tenant write.
25. A failed write leaves no partial row.
26. Scores survive a bed/ward move (admission-keyed).
27. `icu_stay` row count and status unchanged across record/correct.
28. **No value beyond the sum is stored or returned** — no mortality figure, no band, no label.
29. Vitals, I/O, infusion and ventilator rows are untouched by a score write.
30. Disabling a score type writes **zero** rows to `icu_severity_score`; its history stays readable.

**Guard-revert proof** — for each, revert and confirm the matching test fails, then restore:
tenant check, `SEVERITY_SCORE` form gate, recorder-only correction, append-on-record, superseded
exclusion, disabled-type filter, subscore range check.

**Frontend** — `SeverityScorePanel.test.jsx`: renders components from the registry with no
hardcoded list; shows the latest per type; strikes through a superseded row and badges both;
sends a correction to the correction endpoint; hides write controls when `readOnly`; drops a type
an admin disables mid-shift (`refreshKey`); shows no risk band, colour or trend label.

**Regression:** full backend (653 baseline), full frontend (141 baseline),
`TenantScopingArchTest`, `ClinicPharmacyIsolationTest`, `mvn clean package`, `npm run build`.

---

## 17. Manual test plan

**Configuration** (`hadmin1@gmail.com`)

1. Settings → **Severity Scores** lists SOFA and APACHE II, both On.
2. Turn APACHE II **Off** → it disappears from the chart's entry form.
3. Confirm there is no add and no delete.
4. A non-admin cannot reach the screen.

**Charting** (admin or `doc1@gmail.com`) 5. Open an admitted ICU patient at `/ipd/:id` → **Severity Scores** tab present and **writable**. 6. Record SOFA with respiratory 2, coagulation 1, liver 0, cardiovascular 3, CNS 1, renal 2 →
total shows **9**, computed not typed. 7. Record a second SOFA the next day with a lower total → both rows in history, newest first. 8. **Correct** yesterday's score → original struck through and badged Superseded, correction
badged Correction, latest updates. 9. Age `created_at` past 12h → **Correct** no longer offered. 10. Try a subscore of 7 → refused.

**Isolation and non-interference** 11. Other hospital's admin: independent type list; a score `publicId` from hospital 1 → **404**. 12. Files & Access → turn **Severity Score** off → tab hidden for everyone. Set to DOCTOR only →
nurse sees history, no entry form. 13. Move the patient to another bed/ward → score history intact. 14. Vitals (**GCS still on the vitals tab**), Intake/Output, Medication/infusions and Ventilator
all unchanged. **ICU bed board unchanged.** 15. Two tabs open: record a score in one → the other updates without a manual reload. 16. Confirm no predicted mortality, risk band, colour or "improving/worsening" label anywhere.

---

## 18. Decisions required from you

**D-1 — GCS stays on the vitals chart.**
§12.3 names GCS under this item, but ICU-4 shipped it and its data lives in `vitals_records`.
**Recommend: leave it.** ICU-8 covers SOFA and APACHE II. Moving it would split neuro
observations across two screens and orphan existing values. _Confirm you accept the roadmap item
as partly already delivered._

**D-2 — fixed component registry, not a configurable catalogue.**
**Recommend: fixed** (§5.1). Score components are internationally standardised; a renamed or
dropped SOFA component is no longer SOFA. Configuration is limited to which _types_ are enabled.

**D-3 — new `SEVERITY_SCORE` Files & Access key.**
**Recommend: yes**, mirroring ICU-7's `VENTILATOR`. Additive, lazy-defaults to enabled + BOTH, no
migration, appears in the Files & Access card automatically.

**D-4 — APACHE II as a total only, no component entry.**
**Recommend: yes** (§7.3). Its twelve variables are largely labs the system does not hold;
offering twelve inputs would invite copying numbers from elsewhere into a form that cannot check
them.

**D-5 — scope is SOFA and APACHE II only.**
qSOFA, SAPS II, NEWS and similar are **not** in §12.3 and are not proposed. _Confirm, or name any
you want in scope — I will not add one on my own judgement._

**D-6 — store `total_score`, or recompute on read?**
**Recommend: store it.** Unlike ICU-5's balance, a total is part of what was charted at that
moment — the number that went in the notes. Computed on write, never recomputed on read.

---

## 19. Blockers and escalations

**Blockers: none.** Every dependency shipped in ICU-6 and ICU-7.

**Escalations — reported, not actioned:**

- **E-1 — no structured lab results.** `LabOrder` has no result value (§3.2). Auto-derived SOFA
  subscores are therefore impossible, and adding lab results is a lab-module redesign, which is a
  stop-and-escalate dependency. ICU-8 works entirely without it by design. **Raising it because
  it will resurface at §12.3 item 4 (alert thresholds), where a threshold on a lab value would hit
  the same wall.**

**Flags, unchanged from ICU-7:**

- **F-1 — `NURSE_INCHARGE` cannot reach any patient chart.** `/ipd/:id` allows only
  `RECEPTIONIST, DOCTOR, HOSPITAL_ADMIN`; the incharge dashboard has no patient detail view.
  Pre-existing, affects ICU-4 through ICU-8 equally. Fixing it is an authorisation change —
  escalated, not actioned.
- **F-2 — Separate Nurse Login is OFF for hospital 1**, so the staff-nurse chart is unreachable
  in the test tenant. Expected; it is why `/ipd/:id` must stay writable.

---

## 20. Implementation order

Configuration first — the clinical table cannot be tested without a type list to validate against.

1. `SeverityScoreRegistry`, `IcuScoreTypeSetting` entity + repository, migration, schema mirror.
2. `ScoreTypeSettingService` — `list`, `enabledTypes`, `toggle`. Config tests.
3. Config controller + `ControllerModules` declaration.
4. `IcuSeverityScore` entity + repository, migration, schema mirror.
5. `IcuSeverityScoreService.record` — with the disabled-type filter, component filter and range
   checks — plus reads (`getByAdmission`, `latestOf`, `scoreAt`).
6. Append-only correction + `supersededIds`.
7. Clinical controller, `TenantScopingArchTest` allowlist, `FormRegistry` `SEVERITY_SCORE` key,
   `FormAccessServiceTest` count 22 → 23.
8. Clinical tests, then **revert each guard and watch its test fail**.
9. `ScoreSettingsCard.jsx` + `useEnabledScoreTypes.js` + admin wiring, **with `refreshKey`**.
10. `SeverityScorePanel.jsx` rendering from the registry, **with `refreshKey`**.
11. Tab wiring in `NursePatientDetail.jsx` and `IpdDetails.jsx` — **writable in both**, verdict-gated,
    both passing their refresh key.
12. Frontend tests.
13. Full regression, build, `git diff --check`, diff review, **one local commit. No push.**

Reads before writes at each step, correction last among the writes — the ICU-5/6/7 order.

---

## 21. Definition of Done

ICU-8 is done when **all** of the following hold:

1. SOFA and APACHE II can be recorded, listed newest-first, and read back per type.
2. A SOFA total equals the sum of the components entered — verified by test, and by hand in §17.6.
3. Recording appends; nothing earlier is ever rewritten.
4. A correction appends and supersedes; the original stays `is_active` and visible, struck through.
5. `scoreAt(type, t)` answers correctly, and the **mistimed-row** test isolates the superseded filter.
6. A disabled score type cannot be recorded, and disabling one writes **zero** clinical rows while
   its history stays readable.
7. Every guard in §16 has been proven by reverting it and watching its test fail.
8. Foreign tenant → 404 on every clinical and config path; no cross-tenant write is possible.
9. `SEVERITY_SCORE` gates the write path server-side; no new role, no new permission.
10. GCS, vitals, I/O, infusions, ventilator, the ICU stay lifecycle, the dashboard and the bed
    board are all provably unchanged.
11. **No interpretation anywhere** — no mortality figure, risk band, threshold, alert, colour by
    value or trend label, in the API or the UI.
12. Realtime works from the start: a score recorded in one tab appears in another without a reload.
13. Backend and frontend suites green against the 653 / 141 baselines, both builds clean,
    `git diff --check` clean.
14. One local commit. **Not pushed.** ICU-9 not started.

---

**STOP.** Plan only. Awaiting D-1 … D-6 before any implementation.
