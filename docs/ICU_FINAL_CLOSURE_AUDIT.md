# ICU — Final Closure Audit

**Read-only.** No production code, migration, test, frontend or schema was changed. No commit.
**Tip at audit:** `0b74960` · **Baselines:** backend **729**, frontend **174**.

## VERDICT: **CLOSED WITH DOCUMENTED DEFERMENTS**

Every requirement in `ICU_SYSTEM_DESIGN.md` §12.3 is implemented. Nothing an authoritative ICU
document requires is missing. Four items remain deferred **by recorded decision**, and two
documentation-only fixes are outstanding — neither is a functional gap.

---

## 1. Roadmap completeness

| §12.3 | Item                                 | Phase     | Closed                                                        |
| ----- | ------------------------------------ | --------- | ------------------------------------------------------------- |
| 1     | Continuous infusions                 | ICU-6     | ✅                                                            |
| 2     | Ventilator settings history          | ICU-7     | ✅                                                            |
| 3     | Timed severity scores                | ICU-8     | ✅ (GCS via ICU-4, D-1)                                       |
| 4     | Alert threshold configuration        | ICU-9     | ✅                                                            |
| 5     | Append-only for all of the above     | ICU-6/7/8 | ✅ — verified §7                                              |
| 6     | Multiple concurrent consultants (D4) | ICU-10    | ✅ **as decided** — option (c); the team model stays deferred |

Also delivered: ICU-0 audit, ICU-1 design, ICU-2 board, E1 IPD hardening, ICU-3 stay,
ICU-4 vitals, ICU-5 I/O.

**§12.3 item 6's wording is "decide or defer again."** ICU-10 decided. The item is closed by
decision, which is the outcome the roadmap itself offered.

---

## 2. Design ↔ implementation deviations

All four are **intentional**. None is a bug.

| #   | Design says                                                       | Implementation                                                                                                                        | Verdict                                                                                                                                     |
| --- | ----------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | §5.4 / §10.2: `PUT /hospital/icu/units/{wardId}` sets `unit_type` | **That endpoint was never built.** `unit_type` is set through `WardService` + `WardModal.jsx` (which calls `getUnitTypes`, ICU-gated) | Intentional — capability reachable, one fewer endpoint. **Stale design statement, §10 needs a note**                                        |
| 2   | Clinical records under ICU                                        | `IcuIoController`, `IcuInfusionController`, `IcuVentilatorController`, `IcuSeverityScoreController` live under `/hospital/nurse/**`   | Intentional — they are admission-scoped records gated by Files & Access, not ICU-module-gated. A ward patient's chart must work without ICU |
| 3   | ICU-8 approval message listed `observed_at`                       | Column is `scored_at`                                                                                                                 | Intentional, recorded in `ICU_PHASE8_PLAN` §16                                                                                              |
| 4   | §5.4 `icu_unit_profile`                                           | Not created                                                                                                                           | Intentional — the §5.4 precondition (nurse-ratio enforcement) has not arrived. Audited in `ICU_UNIT_PROFILE_PLAN.md`                        |

---

## 3. Database

**9 ICU tables.** Migration ↔ `schema-full.sql` ↔ live DB: **all three agree, no drift.**

`icu_stay`, `icu_io_entry`, `icu_infusion`, `icu_infusion_rate`, `icu_ventilator_parameter`,
`icu_ventilator_setting`, `icu_severity_score`, `icu_score_type_setting`, `icu_alert_threshold`
— plus `wards.unit_type` via `addColumnIfMissing`.

| Check                                                | Result                                                                                                                                     |
| ---------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ |
| Every table has migration creation logic             | ✅ (`icu_stay` / `icu_io_entry` use their own `ensureXxx` with inline DDL — they predate the `createTableIfMissing` helper added in ICU-6) |
| `schema-full.sql` mirrors it                         | ✅ all 9                                                                                                                                   |
| `hospital_id` on every tenant-owned table            | ✅                                                                                                                                         |
| `public_id` UNIQUE on every externally addressed row | ✅                                                                                                                                         |
| Uniqueness where it carries a rule                   | ✅ `icu_stay(hospital_id, active_marker)`; `(hospital_id, param_key)`; `(hospital_id, score_type)`; `(hospital_id, source, metric_key)`    |
| Admission+time indexes for chart reads               | ✅ on io/infusion/ventilator/score                                                                                                         |
| **Columns with no reader or writer**                 | **None found.** `icu_alert_threshold.notes` was deliberately not created; `ventilator_capacity` deliberately not created                   |
| Orphaned/dead ICU schema                             | **None** — no table exists that an earlier plan proposed and a later one dropped                                                           |

---

## 4. Backend

9 controllers, 16 classes in `service/hospital/icu/` (10 services + 6 registries), all
repositories tenant-scoped by finder name.

| Check                                                     | Result                                                                                                                                                                 |
| --------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **All 9 ICU controllers declared in `ControllerModules`** | ✅ verified one-by-one — trap T1 closed                                                                                                                                |
| Tenant scoping                                            | ✅ every finder is `...AndHospitalId`; the 6 reviewed `findById` sites are allowlisted (§5)                                                                            |
| Transaction boundaries                                    | ✅ `@Transactional` on public methods only (the C1 lesson); no ICU clinical service joins the IPD movement transaction or calls `IcuStayService`'s `MANDATORY` methods |
| Append-only correction paths                              | ✅ 5 (§7)                                                                                                                                                              |
| Realtime                                                  | ✅ every ICU **write** service calls `RealtimeNotifier`; `IcuBoardService` alone does not — correct, it is read-only                                                   |
| ICU ↔ IPD integration                                     | ✅ confined to the approved hooks: `IcuStayService.onWardSettled` / `onDischarged` (`MANDATORY`, ICU-3) and E1's hardening. No other ICU code touches movement         |

---

## 5. Security

| Check                                        | Result                                                                                                                                                                                     |
| -------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Caller-supplied foreign IDs                  | `intensivist_doctor_id` (I15 scoped lookup + `CrossTenantIsolationTest`), `prescription_id` (ICU-6, same-admission + tenant), `ward_id`, `ipd_admission_id`, every `publicId` — all scoped |
| Foreign tenant → **404**                     | ✅ uniform across all ICU services                                                                                                                                                         |
| Admin-only configuration                     | ✅ ventilator params, score types, alert thresholds — `hasRole('HOSPITAL_ADMIN')`                                                                                                          |
| Files & Access gates                         | ✅ `VITALS`, `IO_CHART`, `MEDICATION` (infusions), `VENTILATOR`, `SEVERITY_SCORE` — all server-side `assertCanEdit`, not UI-only                                                           |
| Nurse assignment / coverage                  | ✅ `NurseAccessGuard` (assignment **OR** coverage — the ICU-2 bug, fixed and kept), `NurseWriteAccess`, `NurseInchargeGuard`                                                               |
| Closed stay: read vs mutate                  | ✅ `requireActive` throws `ConflictException`; history stays readable. Guard-proven in ICU-10                                                                                              |
| Clinic / pharmacy exposure                   | ✅ **none.** No ICU controller carries a `/clinic` or `/pharmacy` alias; `ClinicPharmacyIsolationTest`'s golden set is intact                                                              |
| `TenantScopingArchTest` allowlist            | 6 ICU entries, each reviewed and each covered by a cross-tenant test                                                                                                                       |
| New roles / permissions across all 10 phases | **Zero**                                                                                                                                                                                   |

---

## 6. Frontend reachability — every capability verified mounted

| Capability                                              | Mounted in                                                         |
| ------------------------------------------------------- | ------------------------------------------------------------------ |
| ICU dashboard                                           | Admin, Doctor, Receptionist dashboards                             |
| Bed board                                               | Admin, Doctor, Receptionist, Nurse, Nurse Incharge                 |
| Ward classification (`unit_type`)                       | `WardModal.jsx`, ICU-gated                                         |
| ICU stay + intensivist                                  | `IpdDetails` (ICU-10)                                              |
| Vitals / I/O / Infusions / Ventilator / Severity scores | `IpdDetails` **and** `NursePatientDetail`                          |
| Ventilator params / Score types / Alert thresholds      | `HospitalAdminDashboard` Settings, ICU-gated                       |
| Files & Access                                          | verdict-gated tabs in both charts                                  |
| Realtime                                                | `refreshKey` reaches every ICU panel and settings card (`ddaef23`) |

**No orphaned capability.** Every ICU backend feature has a reachable screen — the defect ICU-10
was created to fix, re-checked here and clean.

---

## 7. History / data integrity

| Stream                 | Column                                                                                                                  | Original preserved                        |
| ---------------------- | ----------------------------------------------------------------------------------------------------------------------- | ----------------------------------------- |
| Vitals (ICU-4)         | `supersedes_vitals_id`                                                                                                  | ✅ `is_active` stays true, struck through |
| I/O (ICU-5)            | `supersedes_io_entry_id`                                                                                                | ✅ excluded from balance, still displayed |
| Infusion rate (ICU-6)  | `supersedes_rate_id`                                                                                                    | ✅                                        |
| Ventilator (ICU-7)     | `supersedes_setting_id`                                                                                                 | ✅                                        |
| Severity score (ICU-8) | `supersedes_score_id`                                                                                                   | ✅                                        |
| ICU stay               | n/a — `intensivist`/`reason` are current-state fields; the stay itself is never deleted, and closed stays stay readable | ✅ by design (D-3/D4)                     |

**No correction path anywhere destroys the original.** Every one is an appended row. Each
superseded-row filter was proven by reverting it — including the **mistimed-row** case, the only
scenario that isolates it (the ICU-6 lesson, reused in ICU-7 and ICU-8).

---

## 8. Cross-module boundaries — ICU took ownership of nothing

| Module                                                                       | Status                                                                                                  |
| ---------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------- |
| OT forms / `surgery_forms`                                                   | Untouched. `IO_CHART`'s key was _reused_, not redefined                                                 |
| OPD vitals config (`hospital_vitals`, `VitalRegistry`, `VitalsSettingsCard`) | Untouched — read only as a pattern                                                                      |
| MAR / `MedicationAdministration`                                             | Untouched. ICU-6 sits beside it; `MedicationPanel` keeps its original props                             |
| IPD movement                                                                 | Only E1 (approved) + ICU-3's `MANDATORY` hooks (approved). `IpdAdmission.doctorId` never written by ICU |
| Lab architecture                                                             | Untouched — E-1/E-2 escalated, not actioned                                                             |
| Nursing phases F/G                                                           | Untouched                                                                                               |
| Bed board / dashboards                                                       | ICU added its own views; no existing dashboard logic rewritten                                          |

**One boundary crossing, and it was approved:** `c1c242d` added a ward-scoped patient chart for
`NURSE_INCHARGE` — additive, staff-nurse rule untouched.

---

## 9. Test coverage

**Backend 729 · Frontend 174.** ICU-specific: 9 backend test classes in `service/hospital/icu/`,
4 security/tenancy classes, 8 frontend ICU test files.

Guard-revert proof was applied per phase: E1 (C1 transaction), ICU-6 (5), ICU-7 (5), ICU-8 (7),
ICU-9 (6), ICU-10 (4).

**Tests that could pass for the wrong reason — the E1/ICU-6/ICU-7 lesson.** Three were caught
_by_ the revert protocol during the work, and all three are fixed:

1. **ICU-6** — the correction test passed with the superseded filter removed (correction shared
   the original's `effectiveFrom` and won on id ordering). Rewritten as the mistimed-row case.
2. **ICU-10** — the closed-stay UI test used a fixture with no intensivist, so the button read
   "Set" while the test looked for "Change". Fixture fixed; both labels now asserted.
3. **ICU-2** — `AdmissionBedWardIsolationTest` passed for a test-harness reason
   (`TestRestTemplate` could not observe 401, no seeded users). Fixed with `java.net.http.HttpClient`.

**Genuine gaps: none identified.** No test is proposed merely to raise the count.

---

## 10. Documentation

| Issue                                                                                                                         | Severity                                                                                                                             |
| ----------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------ |
| **`ICU_PHASE3_PLAN.md` (20 KB) and `ICU_PHASE4_PLAN.md` (16 KB) are untracked in git** while ICU-2 and ICU-5…10 are committed | **Real** — two delivered plans are outside version control                                                                           |
| **`ICU_PHASE_PLAN.md` and `ICU_DOMAIN_GAP_ANALYSIS.md` are 0 bytes** and untracked — stale placeholders                       | Minor, but misleading to a future reader                                                                                             |
| `ControllerModules.java:10` javadoc cites **`EntitlementRegistryArchTest`, which does not exist**                             | Minor — a comment promising a fence that was never built. Trap T1 is instead closed by manual declaration discipline, verified in §4 |
| §5.4 / §10.2 reference `PUT /hospital/icu/units/{wardId}`, never built (§2 #1)                                                | Minor — stale design statement                                                                                                       |
| `docs/icu hisory.txt` — raw transcript, untracked                                                                             | Housekeeping                                                                                                                         |
| ICU-1 has no `ICU_PHASE1_PLAN.md`                                                                                             | **Not an issue** — ICU-1 _is_ `ICU_SYSTEM_DESIGN.md`                                                                                 |

No contradiction was found **between** phase plans, or between a plan and its implementation.

---

## 11. Remaining items

### A. REQUIRED BEFORE ICU CAN BE DECLARED CLOSED

**None.** No requirement from any authoritative ICU document is unimplemented.

_(Documentation-only, does not block closure: commit the ICU-3/ICU-4 plans and remove the two
empty placeholders — §10.)_

### B. DOCUMENTED DEFERRED / ESCALATED — **not work, do not convert**

| Item                                                          | Deferred by                                          |
| ------------------------------------------------------------- | ---------------------------------------------------- |
| Multiple concurrent consultants (`icu_stay_consultant`)       | ICU-10 D-1 = (c)                                     |
| `icu_unit_profile` / nurse-ratio enforcement                  | §5.4 precondition unmet — `ICU_UNIT_PROFILE_PLAN.md` |
| Lab result values (E-1) + `lab_orders.ipd_admission_id` (E-2) | Escalated in ICU-8/ICU-9; shared with OPD            |
| ICU-9 alert history / de-duplication / acknowledgement        | ICU-9 D-4                                            |
| ICU-9 sources beyond vitals                                   | ICU-9 D-1                                            |
| GCS as a severity score                                       | ICU-8 D-1 — it lives in vitals                       |

### C. NON-ISSUES / INTENTIONAL

- `/hospital/nurse/**` routing for ICU clinical records (§2 #2).
- `IO_CHART` categorised `"OT"` in `FormRegistry` — a reused pre-existing key, so the ICU I/O gate
  appears under "OT / Surgery Forms" in the settings card. Cosmetic; renaming would rewrite an
  OT-era key.
- `scored_at` vs `observed_at` (§2 #3).
- `IcuBoardService` has no notifier — read-only.
- No `icu_unit_profile`, no `ventilator_capacity` — deliberate absence.
- ICU-9 repeated notifications — D-4, asserted by a test so it reads as a decision.
- `NURSE_INCHARGE` outside `/ipd/:id` — resolved differently, by giving the incharge its own
  ward-scoped chart (`c1c242d`).

---

## 12. Final verdict

# CLOSED WITH DOCUMENTED DEFERMENTS

All six §12.3 items are closed, append-only holds across all five clinical streams, tenancy and
authorization are uniform, every capability is reachable, no cross-module ownership was taken, and
no table or column exists without a reader.

The only outstanding actions are **documentation-only** (§10), and the deferred items in B are
decisions already recorded — **not backlog**.

---

**STOP.** Audit only. Nothing implemented, no migration, no commit.
