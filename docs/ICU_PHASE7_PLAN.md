# ICU-7 — Ventilator Settings History

**Status:** AUDIT ONLY — nothing implemented, no production code touched, no migration written.
**Authority:** `ICU_SYSTEM_DESIGN.md` §12.3 item 2 · `ICU_EXISTING_SYSTEM_AUDIT.md` §6 item 3
**Predecessor:** ICU-6 — Continuous Infusions (`c592ed8`, `0a994f1`), complete and manually verified.
**Revision:** r2 — D-5 decided (configurable parameter catalogue). §6–§9 and §14–§18 rewritten
accordingly; §16 records what changed and why. r1's fixed-column table is superseded.

---

## 1. Why this is the next phase

`ICU_SYSTEM_DESIGN.md` §12.3 lists the remaining ICU scope in order:

| #   | Item                                              | State                   |
| --- | ------------------------------------------------- | ----------------------- |
| 1   | Continuous infusions — rate over time             | **ICU-6, done**         |
| 2   | **Ventilator settings history — timed snapshots** | **← ICU-7, this phase** |
| 3   | Timed severity scores (SOFA / APACHE / GCS)       | ICU-8+                  |
| 4   | Alert threshold configuration                     | ICU-9+                  |
| 5   | Append-only semantics for all of the above        | folded into each        |
| 6   | Multiple concurrent ICU consultants (D4)          | deferred again          |

`ICU_PHASE6_PLAN.md` §19 states the same: _"Items 2–4 and 6 remain ICU-7+."_
The roadmap is read, not reordered.

---

## 2. Purpose in plain words

A ventilated patient's machine settings are changed repeatedly through the day. The chart has to
answer _"what was the vent set to at 4 a.m.?"_ the morning after, and _"how long was this patient
ventilated?"_ at discharge. Neither is answerable today — nothing in the system stores a
ventilator value at all.

Same problem ICU-6 solved for infusion rates, same answer: a timed row per change, nothing
overwritten.

**It does not interpret.** No P/F ratio, no compliance, no weaning readiness, no "settings look
wrong" warning. Values in, values out.

**And which values exist is the hospital's choice, not ours** (D-5). Ventilator practice varies
between units in a way infusion rates do not, so the parameter list is administrator-configurable
in the same shape as OPD vitals.

---

## 3. Exact requirements from the existing design

Verbatim, `ICU_SYSTEM_DESIGN.md` §12.3:

> 2. Ventilator settings history — timed snapshots; `RecoveryObservation` is the shape.

and item 5:

> **Append-only semantics for all of the above**, per R9 and §12.1's supersede pattern.

`ICU_EXISTING_SYSTEM_AUDIT.md` line 63:

> | Ventilator settings | **CREATE** | pattern from `RecoveryObservation` | timed-settings entity |

The design fixes three things: **CREATE** a new entity, **timed snapshots** shaped like
`RecoveryObservation`, **append-only correction**. It names **zero ventilator parameters** — which
is exactly why D-5 existed, and why the answer is a configurable catalogue rather than a column
list argued from general medical practice.

### Explicitly out of scope by prior decision

`ICU_SYSTEM_DESIGN.md` §7 defers `icu_unit_profile` (which carries `ventilator_capacity`) to
_"the later phase that implements nurse-ratio enforcement"_, and forbids earlier code from reading
it. **ICU-7 must not create it.** Counting ventilators per ward is capacity planning; ICU-7
records what one patient's machine is set to.

---

## 4. What already exists

| Concern                             | State                                                                                                                                                                                                                                    |
| ----------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Ventilator data of any kind         | **None.** `grep -ri ventilat` over `backend/src/main/java` returns zero hits; no table matches `%vent%` in the live schema.                                                                                                              |
| Ventilation in OT forms             | `GeneralAnaesthesiaRecordForm.jsx` "Ventilation", `PostAnaesthesiaRecoveryForm.jsx` "Unplanned ICU Shifting / Ventilation". Untyped strings inside the `surgery_forms` JSON blob. **Not a source of truth, not reusable, not migrated.** |
| The clinical-row shape to copy      | `entity/RecoveryObservation.java` — timed observation rows keyed to a parent episode.                                                                                                                                                    |
| **The configuration shape to copy** | `entity/HospitalVital.java` + `VitalRegistry` + `VitalSettingsService` + `VitalSettingsController` + `VitalsSettingsCard.jsx` + `useEnabledVitals.js`. Audited in full for r2 — see §5.                                                  |
| The clinical precedent              | ICU-6: `IcuInfusion` + `IcuInfusionRate`, `IcuInfusionService`, `InfusionPanel.jsx`.                                                                                                                                                     |

---

## 5. Existing Vitals configuration architecture (audited for r2)

This is the pattern D-5 says to follow. Every claim below was read from source for this audit.

### 5.1 Entities and tables

| Piece                                           | Detail                                                                                                                                                                                                    |
| ----------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `service/hospital/VitalRegistry.java`           | Java class, not a table. `record Vital(key, label, unit, type)` × 6 built-ins. `isBuiltIn(key)`, `toKey(name)`.                                                                                           |
| `entity/HospitalVital.java` → `hospital_vitals` | `id`, `public_id` (UNIQUE, UUID via `@PrePersist`), `hospital_id`, `vital_key` (60), `label` (60), `unit` (20), `enabled`, `is_custom`, `sort_order`, `created_at`. **`UNIQUE(hospital_id, vital_key)`.** |
| Value storage                                   | Built-ins → typed columns on `Opd`. Customs → `opd.custom_vitals`, a `TEXT` JSON map keyed by `vital_key`.                                                                                                |

**The table stores overrides only.** A built-in with no row is enabled — a lazy default, no
seeding, no migration when the registry grows.

### 5.2 Admin UI

`pages/hospital/VitalsSettingsCard.jsx` (194 lines), mounted at
`HospitalAdminDashboard.jsx:4032`. One list with a toggle per row, a Delete on custom rows only,
and a two-field add form (name + unit). Optimistic toggle with rollback on failure.

### 5.3 API

`VitalSettingsController`, `@RequestMapping({"/hospital/vitals", "/clinic/vitals"})`:

| Endpoint                    | Role                                                          |
| --------------------------- | ------------------------------------------------------------- |
| `GET /`                     | `HOSPITAL_ADMIN` — every vital with its effective flag        |
| `GET /enabled`              | `HOSPITAL_ADMIN, DOCTOR, RECEPTIONIST, NURSE, NURSE_INCHARGE` |
| `PUT /{vitalKey}`           | `HOSPITAL_ADMIN` — toggle                                     |
| `POST /custom`              | `HOSPITAL_ADMIN` — add                                        |
| `DELETE /custom/{publicId}` | `HOSPITAL_ADMIN` — delete definition                          |

### 5.4 Enable/disable behaviour

Lazy default (no row ⇒ enabled). Toggling a built-in **creates** the override row, copying label
and unit from the registry. Enforcement is **server-side**: `enabledBuiltInKeys()` and
`enabledCustomKeys()` are called by `OpdService` to drop disabled values from a submission — the
UI hiding a field is not the control.

### 5.5 Parameter identity

`vital_key`. Built-ins use the registry constant. Customs derive it from the name:
`VitalRegistry.toKey("Random Sugar")` → `RANDOM_SUGAR`.

**This is the one place the existing pattern does not meet the D-5 requirement.** The key is
derived from the display name, and there is **no update path at all** — `toggle()` writes only
`enabled`, and no rename endpoint exists. So today a vital cannot be renamed, and if it could,
re-deriving the key would orphan every historical value. D-5 requires an editable display name
that does not break history, so ICU-7 must decouple the two. See §6.3.

### 5.6 Unit handling

A free-text `unit` column, `@Size(max = 20)` + `@NoEmoji`, displayed beside the value and never
used in arithmetic. **No conversion anywhere** — the same posture as
`InfusionRateUnitRegistry`.

### 5.7 Frontend rendering

`hooks/useEnabledVitals.js` → `{ isOn(key), customs[], loaded }`. Built-ins render as their own
typed inputs when `isOn(key)`; customs render generically from `customs[]`. While loading, every
built-in reads as on so the form never flickers fields away.

### 5.8 Gaps this audit found in the existing pattern

| #   | Gap                                                                      | Effect on ICU-7                                      |
| --- | ------------------------------------------------------------------------ | ---------------------------------------------------- |
| G1  | No `category` column                                                     | Must add — ICU-7 has two categories                  |
| G2  | No `value_type` on custom rows (`view()` hardcodes `"TEXT"`)             | Must add — Mode needs a controlled type              |
| G3  | **No rename/update path, and the custom key is derived from the name**   | Must add an update endpoint with the key held stable |
| G4  | `deleteCustom` throws `UnauthorizedException` (401) for a foreign tenant | ICU convention is 404; ICU-7 uses 404                |
| G5  | `broadcastRefresh` calls `HospitalWebSocketHandler` directly             | ICU rule is `RealtimeNotifier`; ICU-7 uses that      |

G4 and G5 are **pre-existing in the vitals code and are not fixed by ICU-7** — flagged, not
actioned. ICU-7 simply does not copy them.

### 5.9 Verdict — does the existing architecture already support this?

**Mostly yes; a small extension is required.** The override-row model, lazy defaults, server-side
enable filtering, delete-definition-keep-values, `public_id` addressing and the hook/card shapes
all transfer unchanged. Three fields (`category`, `value_type`) and one missing operation
(rename, §6.3) are the extension. **No architectural change; no change to the vitals code
itself.**

---

## 6. Data model

Two tables, and the boundary between them is the point of D-5.

> **Configuration** answers _"what parameters are available?"_
> **Clinical record** answers _"what value was recorded at a particular time?"_
> Configuration is never stored as a clinical observation, and a configuration change never
> rewrites a clinical row.

### 6.1 Configuration — `icu_ventilator_parameter`

Overrides and custom definitions only, exactly like `hospital_vitals`.

| Column         | Type                          | Note                                                 |
| -------------- | ----------------------------- | ---------------------------------------------------- |
| `id`           | BIGINT PK                     |                                                      |
| `public_id`    | VARCHAR(255) UNIQUE           | UUID via `@PrePersist`, as `HospitalVital`           |
| `hospital_id`  | BIGINT NOT NULL               | tenant ownership (I12)                               |
| `param_key`    | VARCHAR(60) NOT NULL          | **stable identity — never re-derived, never edited** |
| `display_name` | VARCHAR(60) NOT NULL          | **editable**                                         |
| `unit`         | VARCHAR(20) NULL              | display only, never converted                        |
| `category`     | VARCHAR(20) NOT NULL          | `SETTING` / `OBSERVATION`                            |
| `value_type`   | VARCHAR(20) NOT NULL          | `NUMBER` / `TEXT` / `MODE`                           |
| `enabled`      | TINYINT(1) NOT NULL DEFAULT 1 |                                                      |
| `is_custom`    | TINYINT(1) NOT NULL DEFAULT 0 |                                                      |
| `sort_order`   | INT NULL                      |                                                      |
| `created_at`   | DATETIME(6) NOT NULL          |                                                      |

`UNIQUE(hospital_id, param_key)`. Lazy default: a built-in with no row is **enabled**.

### 6.2 Built-in catalogue — `VentilatorParameterRegistry` (Java, not a table)

The nine approved parameters. **No parameter added beyond this list; none removed.**

| `param_key`            | Display name         | Unit  | Category        | Value type |
| ---------------------- | -------------------- | ----- | --------------- | ---------- |
| `mode`                 | Mode                 | —     | SETTING         | **MODE**   |
| `fio2`                 | FiO₂                 | %     | SETTING         | NUMBER     |
| `peep`                 | PEEP                 | cmH₂O | SETTING         | NUMBER     |
| `tidal_volume`         | Tidal Volume         | mL    | SETTING         | NUMBER     |
| `set_respiratory_rate` | Set Respiratory Rate | /min  | SETTING         | NUMBER     |
| `pressure_support`     | Pressure Support     | cmH₂O | SETTING         | NUMBER     |
| `ie_ratio`             | I:E Ratio            | —     | SETTING         | TEXT       |
| `peak_pressure`        | Peak Pressure        | cmH₂O | **OBSERVATION** | NUMBER     |
| `plateau_pressure`     | Plateau Pressure     | cmH₂O | **OBSERVATION** | NUMBER     |

Category assignment follows the approved catalogue exactly: seven settings, two
observations/measurements. This also resolves r1's noted incoherence — peak and plateau are read
off the machine rather than dialled into it, and now say so.

`value_type = MODE` is **reserved for the built-in `mode` key**. A custom parameter may be
`NUMBER` or `TEXT` only, so mode values stay controlled while parameter _names_ are configurable.

### 6.3 Identity rules (the D-5 requirement the vitals code does not yet meet)

1. `param_key` is assigned **once** and never changes. Built-ins take the registry constant.
   Customs derive it from the name **at creation only** (`toKey`, as vitals does), then it is
   frozen.
2. `display_name` is editable and carries **no** identity.
3. Clinical rows store **`param_key`**, never the display name.
4. Renaming therefore cannot break history: the stored key still resolves; only the label
   rendered beside it changes.
5. **No delete.** The approved capability list is enable / disable / add / edit display name +
   unit + category. Omitting delete means every recorded key always resolves to a name — a
   stronger historical guarantee than vitals, which allows deleting a definition and leaves old
   values labelled by raw key.

### 6.4 Clinical record — `icu_ventilator_setting`

One row per change, never updated.

| Column                  | Type                          | Note                                                                            |
| ----------------------- | ----------------------------- | ------------------------------------------------------------------------------- |
| `id`                    | BIGINT PK                     |                                                                                 |
| `public_id`             | VARCHAR(255) UNIQUE           | external id, as ICU-5/6                                                         |
| `hospital_id`           | BIGINT NOT NULL               | tenant ownership (I12)                                                          |
| `ipd_admission_id`      | BIGINT NOT NULL               | **the ventilation course identity**                                             |
| `patient_id`            | BIGINT NOT NULL               | denormalised, as `icu_infusion`                                                 |
| `icu_stay_id`           | BIGINT NULL                   | provenance stamp only (D-2)                                                     |
| `ventilation_status`    | VARCHAR(20) NOT NULL          | `INVASIVE` / `NIV` / `OFF` (D-1) — **structural, not a configurable parameter** |
| `values_json`           | TEXT NULL                     | `{"fio2":60,"peep":8,...}` keyed by `param_key`                                 |
| `observed_at`           | DATETIME(6) NOT NULL          | when the setting applied                                                        |
| `recorded_by_user_id`   | BIGINT NULL                   |                                                                                 |
| `performed_by_nurse_id` | BIGINT NULL                   |                                                                                 |
| `supersedes_setting_id` | BIGINT NULL                   | append-only correction                                                          |
| `note`                  | VARCHAR(255) NULL             |                                                                                 |
| `is_active`             | TINYINT(1) NOT NULL DEFAULT 1 |                                                                                 |
| `created_at`            | DATETIME(6) NOT NULL          |                                                                                 |

Indexes: `UNIQUE(public_id)`, `(ipd_admission_id, observed_at)`, `(hospital_id)`.

**What changed from r1:** the nine fixed clinical columns collapse into `values_json`. A
configurable catalogue cannot have a column per parameter — adding one would mean a migration per
hospital preference. This is the `opd.custom_vitals` pattern, applied to every parameter rather
than only the custom ones, because here even the built-ins are configurable.

`ventilation_status` stays a typed `NOT NULL` column: D-1 makes it structural (it is what
distinguishes a ventilated row from an extubation row), and it must remain queryable without
parsing JSON.

**Existing-table changes: none.** No column on `wards`, `ipd_admissions`, `icu_stay`,
`vitals_records`, `beds`, `hospital_vitals`. `IpdAdmission` gains nothing (I21 holds).

**Migration:** `ensureIcuVentilatorTables()` calling the existing `createTableIfMissing` (added
in ICU-6). **Trap:** nullable columns stay nullable; `enabled`, `is_custom`, `is_active`,
`category`, `value_type` and `created_at` carry explicit `columnDefinition` — the
`NOT NULL`-without-`columnDefinition` trap has bitten this project three times.

**No backfill and no seeding.** No prior ventilator data exists, and the lazy default means the
config table starts empty.

---

## 7. Historical-data strategy

The rule, stated as an invariant:

> **A configuration change is a change to what may be charted next. It is never a change to what
> was charted before.**

| Event                  | Effect on `icu_ventilator_parameter` | Effect on `icu_ventilator_setting` |
| ---------------------- | ------------------------------------ | ---------------------------------- |
| Parameter disabled     | `enabled = 0`                        | **None**                           |
| Parameter renamed      | `display_name` changes               | **None** — rows store `param_key`  |
| Unit changed           | `unit` changes                       | **None**                           |
| Category changed       | `category` changes                   | **None**                           |
| Custom parameter added | new row                              | **None**                           |
| Parameter deleted      | _not offered_ (§6.3)                 | —                                  |

**Reading history when a parameter is disabled or renamed.** The read API returns each clinical
row's `values_json` plus a resolved **label map** for the keys present, built from the current
config, falling back to the registry, falling back to the raw key. The label is resolved at read
time and **never stored on the clinical row** — storing it would be exactly the "administrator
configuration as clinical observation" the directive forbids.

**Disabled parameters in history are shown, marked.** A disabled parameter's past values stay
visible with a quiet "no longer charted" marker. Hiding them would be silent data loss, which is
the same reasoning that keeps a superseded row struck through rather than deleted.

**Charting a disabled parameter is refused server-side.** `IcuVentilatorService` filters the
submitted map against `enabledKeys(hospitalId)` before persisting — the `OpdService` precedent.
An unknown or disabled key is dropped, not silently stored.

---

## 8. Backend work

| New file                                                                  | Purpose                                                                           |
| ------------------------------------------------------------------------- | --------------------------------------------------------------------------------- |
| `entity/IcuVentilatorParameter.java`                                      | §6.1                                                                              |
| `repository/IcuVentilatorParameterRepository.java`                        | tenant-scoped finders                                                             |
| `service/hospital/icu/VentilatorParameterRegistry.java`                   | the nine built-ins (§6.2)                                                         |
| `service/hospital/icu/VentilatorModeRegistry.java`                        | controlled mode values                                                            |
| `service/hospital/icu/VentilatorParameterService.java`                    | `list`, `enabledParameters`, `toggle`, `addCustom`, `update`, `labelMapFor`       |
| `controller/hospital/IcuVentilatorParameterController.java`               | admin config API                                                                  |
| `entity/IcuVentilatorSetting.java`                                        | §6.4                                                                              |
| `repository/IcuVentilatorSettingRepository.java`                          | tenant-scoped finders + `findSupersededIds`                                       |
| `service/hospital/icu/IcuVentilatorService.java`                          | `record`, `correct`, `getByAdmission`, `current`, `settingAt(t)`, `supersededIds` |
| `controller/hospital/IcuVentilatorController.java`                        | clinical API                                                                      |
| `dto/icu/IcuVentilatorRequest.java`, `IcuVentilatorParameterRequest.java` | request bodies                                                                    |

| Modified file                         | Change                                                                                              |
| ------------------------------------- | --------------------------------------------------------------------------------------------------- |
| `config/DatabaseMigrationRunner.java` | `+ ensureIcuVentilatorTables()`                                                                     |
| `setup/schema-full.sql`               | mirror DDL                                                                                          |
| `entitlement/ControllerModules.java`  | both controllers declared — **mandatory**, an undeclared controller is treated as ALLOWED (trap T1) |
| `service/hospital/FormRegistry.java`  | `+ VENTILATOR` key (D-3)                                                                            |
| `security/TenantScopingArchTest.java` | allowlist the reviewed `requireAdmission` lookup, as ICU-5/6                                        |

`settingAt(t)` is ICU-6's `rateAt(t)` verbatim: newest non-superseded row with
`observed_at <= t`. Selection, not calculation.

**Validation** — structural only, never clinical: `NUMBER` must parse as a number, `MODE` must be
a `VentilatorModeRegistry` key, `TEXT` is free. **No ranges, no maxima, no appropriateness
check** — r1 proposed a 21–100 FiO₂ range; a configurable catalogue has no place to put it, and
bounding a clinical value is a judgement ICU records rather than makes.

---

## 9. Frontend / admin work

**No new page.** The ICU Patient Chart stays one workspace, and the config joins the existing
Settings tab.

| File                                          | Change                                                                                                                                                                   |
| --------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `pages/hospital/VentilatorSettingsCard.jsx`   | **new** — mirrors `VitalsSettingsCard`, plus a category column and a category selector on the add form. Mounted in `HospitalAdminDashboard` beside `VitalsSettingsCard`. |
| `hooks/useEnabledVentilatorParams.js`         | **new** — mirrors `useEnabledVitals`; returns enabled parameters grouped by category                                                                                     |
| `pages/hospital/nurse/VentilatorPanel.jsx`    | **new** — current settings + timed history, rendered **generically from the catalogue**, correction on the row's right (the ICU-5 layout)                                |
| `services/icuService.js`                      | clinical calls + parameter-config calls                                                                                                                                  |
| `pages/hospital/nurse/NursePatientDetail.jsx` | new `ventilator` tab, `verdictFor('ventilator')`                                                                                                                         |
| `pages/hospital/IpdDetails.jsx`               | same tab, same verdict — **writable, not hard-coded read-only** (the ICU-6 mistake fixed in `0a994f1`)                                                                   |

The panel renders two labelled groups — **Settings** and **Observations** — from `category`. It
holds no hardcoded parameter list; adding a custom parameter must require no frontend change, or
the catalogue is decorative.

**Placement:** ventilator is its own sub-tab beside Vitals and Intake/Output, not beside the MAR.
It is a machine record, not medication.

**Reachability, carried from ICU-6:** `/ipd/:id` is routed for
`RECEPTIONIST, DOCTOR, HOSPITAL_ADMIN` only; `NURSE_INCHARGE` has no patient-chart route.
Pre-existing, out of ICU-7 scope — see F-1.

---

## 10. API changes

All **additive**. No existing endpoint changes shape.

**Configuration** — `/hospital/icu/ventilator-parameters`, mirroring `/hospital/vitals`:

```
GET    /                      HOSPITAL_ADMIN   every parameter + effective enabled flag
GET    /enabled               all staff roles  only the enabled ones, for charting
PUT    /{paramKey}            HOSPITAL_ADMIN   toggle and/or edit name, unit, category
POST   /custom                HOSPITAL_ADMIN   add a custom parameter
```

No `DELETE` (§6.3).

**Clinical** — `/hospital/nurse/ventilator`:

```
POST   /                                  record a snapshot
GET    /admission/{id}                    history, newest first, + label map
GET    /admission/{id}/current            the setting in force now
GET    /at?admissionId=&at=               the setting in force at an instant
POST   /{publicId}/correction             append-only correction
GET    /modes                             the mode catalogue
```

Clinical `@PreAuthorize` copies `IcuInfusionController`:
`NURSE, NURSE_INCHARGE, DOCTOR, HOSPITAL_ADMIN, RECEPTIONIST`.

---

## 11. Security / tenant requirements

- Every query filters on `hospital_id` from the JWT. Foreign tenant → `ResourceNotFoundException`
  → **404, never 403** — including the config endpoints (**not** the vitals code's 401, G4).
- Config writes are `HOSPITAL_ADMIN` only, matching `VitalSettingsController`.
- `NurseWriteAccess.assertCanWriteFor(admissionId)` on clinical writes; `NurseAccessGuard` on
  reads when the role is `NURSE` — assignment **OR** coverage, both branches (the ICU-2 bug).
- `FormAccessService.assertCanEdit("VENTILATOR")` in the clinical write path — server-side.
- Correction: recorder-only + 12h window, the ICU-4 rule unchanged. **No widening.**
- **Disabled-parameter filtering is server-side**, not UI-only.
- `ControllerModules` declaration mandatory for both controllers (T1).
- **No new role. No new permission.** A `FormRegistry` key is a new _form_ under the existing
  model — lazy-defaults to enabled + BOTH, needs no seeding, and appears in the Files & Access
  card automatically (verified: the card renders from the API).
- `RealtimeNotifier.refresh(hospitalId)` after commit — **not** `HospitalWebSocketHandler`
  directly (G5).

---

## 12. Transaction requirements

Identical to ICU-5/6: **recording a ventilator setting is not a patient movement.**

- One `@Transactional` per public write, never on a private helper (that was C1).
- **Never** joins the IPD movement transaction; **never** calls `IcuStayService`'s `MANDATORY`
  methods. A failed ventilator write cannot roll back an admission, a bed move or an ICU stay.
- Config writes are their own transaction, independent of any clinical write.
- No `PESSIMISTIC_WRITE`. Two nurses charting the same patient produce two appended rows — that
  is correct, not a race. Per the standing rule, no concurrency test for a race that does not
  exist.

---

## 13. Relationship with `IcuStay` and existing functionality

**`IcuStay` — read-only, one direction.** It remains the ICU episode authority. ICU-7 calls
`existsCoveringInstant(...)` to stamp `icu_stay_id` (D-2) and to label history. It never creates,
closes or mutates a stay. Same posture as `IcuIoService.isInIcuAt`.

**Not keyed to the stay** (D-1): a patient ventilated in MICU and transferred to SICU has two
stays and one continuous ventilation course. `ipd_admission_id` remains the course identity.

| Area                                                    | Effect                                                                                                                                                 |
| ------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------ |
| IPD movement, `IcuStay` lifecycle, bed board, dashboard | **none**                                                                                                                                               |
| Vitals (ICU-4)                                          | **none.** SpO₂ is a patient observation; FiO₂ is a machine setting. Separate meanings, separate records, no synchronisation — the ICU-5 D-2 principle. |
| I/O (ICU-5), Infusions (ICU-6), MAR                     | **none**                                                                                                                                               |
| OPD vitals configuration                                | **none** — `hospital_vitals`, `VitalRegistry` and the vitals card are read for their pattern and left untouched                                        |
| Billing                                                 | **none.** Ventilator-day charging is billing redesign — out of scope by standing rule.                                                                 |
| Non-ICU IPD patients                                    | zero rows; behaviour identical to today                                                                                                                |

---

## 14. Automated tests required

**Configuration** — `VentilatorParameterServiceTest`:

1. All nine built-ins list with correct category and value type, with no rows present.
2. A built-in with no row reads as **enabled** (lazy default).
3. Toggling off writes an override; the built-in list still contains it, disabled.
4. `enabledParameters` excludes disabled ones.
5. Renaming changes `display_name` and **leaves `param_key` unchanged**.
6. A custom parameter gets a key derived at creation; renaming it later does **not** re-derive.
7. A custom parameter cannot claim `value_type = MODE`.
8. A duplicate key is rejected.
9. Foreign tenant → 404 on read and on update.
10. Config endpoints reject non-admin roles.

**Clinical** — `IcuVentilatorServiceTest`:

11. A snapshot stores every enabled parameter supplied.
12. A second snapshot **appends**; the first is unchanged.
13. History ordered newest-first.
14. `settingAt(t)` returns the setting in force; null before the first.
15. A correction appends with `supersedes_setting_id`; the original stays `is_active`.
16. A superseded row is excluded from `settingAt(t)` — **use the mistimed-row case**, the only
    scenario that isolates the filter (ICU-6 proved the naive version passes without it).
17. `ventilation_status = OFF` is recordable with an empty value map.
18. A **disabled** parameter submitted for charting is **dropped**, not stored.
19. An unknown key is dropped.
20. `MODE` must be a registry key; `NUMBER` must parse.
21. Recorder-only correction; another user → `AccessDeniedException`.
22. Edit-window expiry → rejected.
23. `assertCanEdit` denial blocks every clinical write.
24. Foreign tenant → 404 on read and correction; no cross-tenant write.
25. A failed write leaves no partial row.
26. Settings survive a bed/ward move (admission-keyed).
27. `icu_stay` row count unchanged across record/correct.
28. **No derived value** (P/F ratio, compliance) is stored or returned.
29. I/O balance and infusion rates unaffected.

**Historical stability** — the tests that carry D-5:

30. Record FiO₂ → **disable** FiO₂ → the historical row still returns the FiO₂ value, and the
    parameter is marked no-longer-charted.
31. Record FiO₂ → **rename** it to "Inspired O₂" → the historical row is unchanged, resolves to
    the new label, and the key is still `fio2`.
32. Disabling a parameter writes **zero** rows to `icu_ventilator_setting`.
33. Renaming writes **zero** rows to `icu_ventilator_setting`.

**Guard-revert proof** — for each new guard, revert it and confirm the matching test fails:
tenant check, form-access gate, recorder check, append-on-record, superseded exclusion,
**disabled-parameter filter**.

**Frontend** — `VentilatorPanel.test.jsx` and `VentilatorSettingsCard.test.jsx`: renders
Settings and Observations groups from the catalogue with no hardcoded list; renders a historical
value for a disabled parameter; strikes through a superseded row; sends a correction to the
correction endpoint; hides every write control when `readOnly`; shows no computed figure; the
admin card toggles, renames and adds with a category.

**Regression:** full backend suite (600 at ICU-6), full frontend suite (124 at ICU-6),
`TenantScopingArchTest`, `ClinicPharmacyIsolationTest`, `mvn clean package`, `npm run build`.

**Not written:** concurrency tests (no shared-resource race).

---

## 15. Manual testing checklist

**Configuration**

1. As `hadmin1@gmail.com`, Settings → Ventilator Parameters lists all nine, grouped
   Settings / Observations, all enabled.
2. Rename "FiO₂" → "Inspired O₂"; it renders with the new name.
3. Add a custom parameter (name + unit + category OBSERVATION) → appears in its group.
4. Disable "Plateau Pressure" → gone from the group.
5. Non-admin roles cannot reach the config screen.

**Charting**

6. Open an admitted ICU patient at `/ipd/:id` → **Ventilator** tab present and **writable**.
7. Record `INVASIVE`, mode `VC`, FiO₂ 60, PEEP 8, Vt 450 → shows as current, correctly grouped.
8. Change FiO₂ to 40 → current shows 40; the 60 row is still in the history.
9. Correct the 40 row to 45 → original struck through and badged **Superseded**, correction
   badged **Correction**, current reads 45.
10. Age `created_at` past 12h → **Correct** no longer offered.
11. Record `OFF` (extubated) → current shows not ventilated; history fully readable.

**The D-5 guarantee**

12. With FiO₂ values recorded, **disable** FiO₂ → the entry form drops it; **every historical
    FiO₂ value is still displayed**, marked no longer charted.
13. Re-enable it → charting resumes, history unbroken.
14. Rename a parameter that has recorded values → history shows the new label against the same
    old values; nothing is lost or duplicated.

**Isolation**

15. The other hospital's admin: the same `publicId` returns **404**, not 403; their parameter
    list is independent.
16. Turn `VENTILATOR` off in Files & Access → tab disappears for everyone. Set to DOCTOR only →
    nurse sees history, no entry form.
17. Move the patient to another bed/ward → ventilator history intact.
18. Intake/Output balance unchanged; Medication/infusions unchanged; **ICU bed board unchanged**.
19. Confirm no P/F ratio, compliance figure or warning colour appears anywhere.

---

## 16. Decisions

**D-1 — snapshot rows + explicit `ventilation_status`.** APPROVED.
**D-2 — nullable `icu_stay_id` as provenance.** APPROVED.
**D-3 — separate `VENTILATOR` Files & Access key.** APPROVED.
**D-4 — no ventilator status on the bed board.** APPROVED.

**D-5 — configurable parameter catalogue.** **DECIDED.** Ventilator parameters are split into
`SETTING` and `OBSERVATION`, administrator-configurable in the `hospital_vitals` shape, with a
stable `param_key` separate from an editable `display_name`, and configuration changes affecting
future charting only. Initial catalogue: the nine parameters in §6.2, none removed, none added.
Mode keeps a controlled `VentilatorModeRegistry` and is carried in the catalogue as a `SETTING`
with `value_type = MODE`.

**What r2 changed as a consequence**, so the diff from r1 is not silent:

| r1                          | r2                                       | Why                                                                                                                                                  |
| --------------------------- | ---------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- |
| Nine fixed clinical columns | `values_json` keyed by `param_key`       | A configurable catalogue cannot have a column per parameter                                                                                          |
| No config table             | `icu_ventilator_parameter`               | D-5                                                                                                                                                  |
| `mode` a typed column       | catalogue parameter, `value_type = MODE` | Directive: the config model identifies Mode with an appropriate value type                                                                           |
| FiO₂ range 21–100           | no range                                 | A configurable catalogue has nowhere to hold it, and bounding a clinical value is judgement ICU does not make                                        |
| Peak/plateau ambiguous      | explicitly `OBSERVATION`                 | Resolves the incoherence r1 flagged                                                                                                                  |
| Delete not discussed        | **no delete offered**                    | The approved capability list is enable / disable / add / edit — delete is absent, and omitting it guarantees every historical key resolves to a name |

### Remaining decisions

**D-6 — may an administrator disable `mode` or `ventilation_status`-adjacent essentials?**
The model permits disabling any catalogue parameter, `mode` included, which would leave numbers
with no mode beside them. **Recommend: allow it, no special case** — hospitals with a single-mode
protocol exist, and carving out exceptions makes the catalogue rules harder to reason about than
the problem warrants. Flagging because it is a real consequence, not because I think it needs
blocking. `ventilation_status` is unaffected either way: it is a structural column, not a
parameter, and cannot be disabled.

**D-7 — does the ventilator parameter config belong to the hospital tenant only, or also to
clinics?** `VitalSettingsController` is aliased to `/clinic/vitals`. **Recommend:
hospital-only** — clinics have no IPD, so ICU-7 does not alias. Confirm.

---

## 17. Blockers

**None.** ICU-6 landed every clinical dependency; the vitals module supplies the config pattern.

Two **flags**, neither blocking:

- **F-1 — `NURSE_INCHARGE` cannot reach any patient chart.** `/ipd/:id` allows only
  `RECEPTIONIST, DOCTOR, HOSPITAL_ADMIN`, and the incharge dashboard has no patient detail.
  Pre-existing; affects ICU-4/5/6 equally. Fixing it is an authorisation change — **escalated,
  not actioned.**
- **F-2 — Separate Nurse Login is OFF for hospital 1**, so the staff-nurse chart is unreachable
  in the test tenant. Expected; it is why `/ipd/:id` must stay writable.

Plus **G4/G5** (§5.8): two pre-existing deviations in the vitals code (401 instead of 404;
direct WebSocket broadcast). ICU-7 does not copy them and does not fix them.

---

## 18. Implementation order

Configuration first — the clinical table cannot be tested without a catalogue to validate against.

1. `IcuVentilatorParameter` entity, repository, `VentilatorParameterRegistry`,
   `VentilatorModeRegistry`, migration, `schema-full.sql` mirror.
2. `VentilatorParameterService` — `list`, `enabledParameters`, `toggle`, `addCustom`, `update`,
   `labelMapFor`.
3. Config controller + `ControllerModules` declaration. Config tests **including the
   rename-keeps-the-key proof**.
4. `IcuVentilatorSetting` entity, repository, migration, schema mirror.
5. `IcuVentilatorService` — `record` (with the disabled-parameter filter) + reads.
6. Append-only correction + `supersededIds`.
7. Clinical controller, `ControllerModules`, `TenantScopingArchTest` allowlist,
   `FormRegistry` `VENTILATOR` key.
8. Clinical + historical-stability tests, then **revert each guard and watch its test fail**.
9. `VentilatorSettingsCard.jsx` + `useEnabledVentilatorParams.js` + admin wiring.
10. `VentilatorPanel.jsx` rendering generically from the catalogue.
11. Tab wiring in `NursePatientDetail.jsx` and `IpdDetails.jsx` — **writable in both**.
12. Frontend tests.
13. Full regression, build, `git diff --check`, diff review, local commit. **No push.**

Reads before writes at each step, correction last among the writes — the ICU-5/6 order.

---

**STOP.** Audit only. Awaiting D-6 and D-7 before any implementation.
