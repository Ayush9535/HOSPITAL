# ICU Phase 3 — ICU Stay Lifecycle

**Checkpoint:** ICU-3 (AUDIT → DESIGN → PLAN)
**Date:** 2026-08-25
**Branch:** `icu`
**Base SHA:** `59002fa`
**Status:** Audit + plan — **awaiting approval. No implementation.**
**Inputs:** [ICU_SYSTEM_DESIGN.md](ICU_SYSTEM_DESIGN.md) r2 (ICU-1),
[ICU_PHASE2_PLAN.md](ICU_PHASE2_PLAN.md) (ICU-2), [IPD_HARDENING_PLAN.md](IPD_HARDENING_PLAN.md) (E1)

**Objective:** give ICU an episode of care — record _when_ a patient entered critical care, _why_,
_under whom_, and _how they left_ — by writing the `icu_stay` record ICU-1 designed and ICU-2
deliberately deferred.

---

## 1. Executive Summary

ICU-1 designed `IcuStay`. ICU-2 shipped the board **without** it, on the rule that a table with no
writer gets populated with values nothing honours. ICU-3 builds the writers. That is the whole
phase: no new screens, no clinical charting, no vitals.

**ICU-3 is now unblocked.** E1 delivered the two preconditions ICU-1 named:

| Precondition                                                        | Delivered by | Evidence                                                                                                                                                                   |
| ------------------------------------------------------------------- | ------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| A transaction on `admitFromOpd` for `Propagation.MANDATORY` to join | E1 (C1)      | `IpdAdmissionTransactionTest.aMandatoryPropagationBeanCanJoinTheAdmissionTransaction`, and the same test throwing `NoTransactionException` when the annotation is reverted |
| A bed claim that cannot be won twice                                | E1 (C3)      | `IpdConcurrencyIT` 3/3 on real MySQL; `twoSimultaneousBedChangesOntoTheSameTarget` fails when the lock is removed                                                          |

**One E1 finding changes an ICU-1 assumption and needs a decision.** The C2 retry lives on
`IpdAdmissionController`, not the service, so **`admitFromOpd` called directly is not
retry-protected**. ICU-3 adds no new entry point, so this is not blocking — but it must be stated
because ICU-1 §10.1 drew the stay hook inside the service transaction and said nothing about who
calls it. See §9 / D-4.

Scope: **1 table, 1 column-free migration, ~4 production files, 2 read endpoints.** No schema
change to any existing table. No frontend beyond populating a field the board DTO already has.

---

## 2. Repository Baseline

|                |                                                                                     |
| -------------- | ----------------------------------------------------------------------------------- |
| Branch         | `icu`                                                                               |
| HEAD           | `59002fa` — `fix(icu): give wards.unit_type a real database default`                |
| Working tree   | clean of tracked changes (untracked: empty ICU placeholders, `docs/icu hisory.txt`) |
| Backend suite  | **518 tests, 0 failures, 0 errors**                                                 |
| Concurrency IT | `IpdConcurrencyIT` 3/3 against MySQL 8.0 (`-Dhms.it.mysql.url=…`)                   |
| Prerequisites  | `wards.unit_type` repaired (0 blanks); `ICU` module enabled on plan 3 + hospital 1  |

Preceding checkpoints on `icu`: `c161e52` (E1), `82f4ed5` (E1 concurrency), `3872994` (ICU-2).

---

## 3. What exists, and what ICU-3 must add

### 3.1 Already in place — ICU-3 writes nothing new here

| Capability                   | Where                                                                                | Note                                                                         |
| ---------------------------- | ------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------- |
| Critical-care classification | `wards.unit_type` + `CareUnitRegistry.isCriticalCare`                                | 8 keys; never name-matched                                                   |
| ICU board read model         | `IcuBoardService`, `IcuDashboardController`                                          | one read-only transaction                                                    |
| Forward-compatible slot      | `IcuBedRowDTO.icuStay`                                                               | **already present and null** — ICU-3 fills it with no DTO or frontend rework |
| Movement transaction         | `admitFromOpd` (E1/C1), `changeBed`, `confirmDischarge`                              | all three `@Transactional`                                                   |
| Bed claim safety             | `BedStatusService.lockForClaim` (E1/C3)                                              | `MANDATORY`, tenant-scoped                                                   |
| Bed span history             | `ipd_bed_history`                                                                    | promoted to critical state by E1/D-4                                         |
| OT → ICU seam                | `RecoveryService.discharge(surgeryId, destination)`, `DESTINATIONS` includes `"ICU"` | destination is validated but leads nowhere                                   |

### 3.2 The three hook points — located, with line numbers

All in `service/hospital/IpdAdmissionService`:

| Hook                         | Method             | Insert after                                             | Currently at          |
| ---------------------------- | ------------------ | -------------------------------------------------------- | --------------------- |
| **Open** on admission        | `admitFromOpd`     | `ipdBedHistoryRepository.save(initialHist)`              | line 211              |
| **Close + open** on transfer | `changeBed`        | `ipdAdmissionRepository.save(ipd)` / bed-history rewrite | lines 1334, 1338–1351 |
| **Close** on discharge       | `confirmDischarge` | `ipd.setStatus("DISCHARGED")`                            | line ~1183            |

A fourth, in `service/hospital/ot/RecoveryService.discharge` (line 91), for the OT handoff.

Every one of these is already inside a transaction, which is exactly why `MANDATORY` is safe.

---

## 4. C-A — `icu_stay`, the only new table

Per ICU-1 §5.1, unchanged except where E1 taught us otherwise.

| Column                  | Type                 | Note                                      |
| ----------------------- | -------------------- | ----------------------------------------- |
| `id`                    | BIGINT PK            |                                           |
| `public_id`             | VARCHAR UNIQUE       | house convention                          |
| `hospital_id`           | BIGINT NOT NULL      | tenant — I12                              |
| `ipd_admission_id`      | BIGINT NOT NULL      | immutable — I2                            |
| `patient_id`            | BIGINT NOT NULL      | denormalised — I3                         |
| `ward_id`               | BIGINT NOT NULL      | equals admission's ward while ACTIVE — I9 |
| `status`                | VARCHAR(10) NOT NULL | `ACTIVE` \| `CLOSED` — I5                 |
| `source`                | VARCHAR(20) NOT NULL | §5                                        |
| `source_ref_id`         | BIGINT NULL          | discriminated by `source` — §6            |
| `admitted_at`           | DATETIME NOT NULL    |                                           |
| `admission_reason`      | VARCHAR(255) NULL    |                                           |
| `intensivist_doctor_id` | BIGINT NULL          | D4; the only caller-supplied foreign id   |
| `admitted_by_user_id`   | BIGINT NULL          |                                           |
| `disposition`           | VARCHAR(20) NULL     | required on close — I6                    |
| `discharged_at`         | DATETIME NULL        | required on close — I6                    |
| `discharged_by_user_id` | BIGINT NULL          |                                           |
| `active_marker`         | BIGINT NULL          | see below                                 |
| `created_at`            | DATETIME NOT NULL    | non-updatable                             |

**No `is_active`.** Deliberate departure from the house soft-delete convention (ICU-1 §10.3): a
hideable critical-care episode falsifies the ICU length-of-stay and readmission figures the module
exists to produce.

**I4 enforced by the database.** MySQL has no partial index and `UNIQUE(ipd_admission_id, status)`
cannot work because `CLOSED` repeats. `active_marker` holds `ipd_admission_id` while ACTIVE and
`NULL` once closed; MySQL treats NULLs as distinct in a unique index:

```sql
UNIQUE KEY uk_icu_stay_active (hospital_id, active_marker)
```

**Migration lesson from ICU-2, applied.** The entity must carry an explicit
`columnDefinition` for every NOT NULL column with a default, because `ddl-auto=update` runs
_before_ `DatabaseMigrationRunner`. `ensureIcuStayTable()` is a `CREATE TABLE IF NOT EXISTS`, which
sidesteps the problem for a new table — but the rule is written down here so it is not re-learned
a fourth time.

---

## 5. Decision — `source` values (E1 has no bearing; ICU-1 §5.2 stands)

`EMERGENCY` · `OPD` · `WARD` · `OT_RECOVERY` · `ICU_TRANSFER` · `EXTERNAL_REFERRAL`

Derived, not chosen: `IpdAdmission.admissionType` is already `EMERGENCY`/`ELECTIVE`, so a direct
admit maps `EMERGENCY → EMERGENCY` and `ELECTIVE → OPD`. Only `EXTERNAL_REFERRAL` is
operator-selected.

---

## 6. `source_ref_id` integrity (ICU-1 §5.3, unchanged)

| `source`            | References                      | Required                                                    |
| ------------------- | ------------------------------- | ----------------------------------------------------------- |
| `EMERGENCY` / `OPD` | `opd.id`                        | yes                                                         |
| `WARD`              | `wards.ward_id` stepped up from | yes                                                         |
| `OT_RECOVERY`       | `ot_recovery_episodes.id`       | **optional** — `RECOVERY_TRACKING` is a per-hospital policy |
| `ICU_TRANSFER`      | `icu_stay.id` just closed       | yes                                                         |
| `EXTERNAL_REFERRAL` | —                               | must be NULL                                                |

Six rules, all validated inside the writing transaction: presence, resolution, tenancy (404 on
foreign), **episode coherence** (an `ICU_TRANSFER` or `OT_RECOVERY` referent must resolve to the
same `ipd_admission_id`), immutability, and **tolerant reads** (wards are deletable, so an
unresolvable referent renders "unknown" and never fails a read).

---

## 7. Transitions — all reuse E1-hardened paths

| #   | Transition               | Mechanism                                             | Stay effect                                   |
| --- | ------------------------ | ----------------------------------------------------- | --------------------------------------------- |
| T1a | Emergency → ICU          | `admitFromOpd`, `admissionType=EMERGENCY`             | open, `source=EMERGENCY`                      |
| T1b | OPD → ICU                | `admitFromOpd`, `ELECTIVE`                            | open, `source=OPD`                            |
| T1c | External referral → ICU  | `admitFromOpd`                                        | open, `EXTERNAL_REFERRAL`                     |
| T2  | Ward → ICU               | `changeBed`                                           | open, `source=WARD`                           |
| T3  | OT → ICU                 | `RecoveryService.discharge(..., "ICU")` + `changeBed` | open, `OT_RECOVERY`                           |
| T4  | ICU → Ward               | `changeBed`                                           | close, `disposition=WARD`                     |
| T5  | ICU → discharge          | `confirmDischarge`                                    | close, `HOME`/`LAMA`/`REFERRED_OUT`/`EXPIRED` |
| T6  | ICU → other ICU          | `changeBed`                                           | close + open, one transaction                 |
| T7  | Same ward, different bed | `changeBed`                                           | **none** — the stay is bounded by the ward    |

**T3 is the one genuinely new wiring.** `RecoveryService.discharge` validates `"ICU"` today and
then does nothing with it. Whether it should _drive_ a bed move or merely _record provenance_ is
**D-3 below** — it is the only place ICU-3 touches OT code.

---

## 8. Domain invariants ICU-3 must enforce

ICU-1 §4's 22 invariants, with the enforcement now real rather than planned:

| #       | Invariant                                                       | Enforced by                                               |
| ------- | --------------------------------------------------------------- | --------------------------------------------------------- |
| I4      | ≤1 ACTIVE stay per admission                                    | **DB** `uk_icu_stay_active` + TX                          |
| I5      | `CLOSED` is terminal; no field writable                         | TX                                                        |
| I6      | Close requires `disposition` + `discharged_at`                  | TX                                                        |
| I7      | ACTIVE stay ⟹ admission's ward is critical care                 | TX                                                        |
| I8      | Critical-care ward + active admission ⟹ exactly one ACTIVE stay | TX                                                        |
| I9      | ACTIVE stay's `ward_id` = admission's `ward_id`                 | TX (T6 closes/reopens)                                    |
| I10     | `DISCHARGED` admission has no ACTIVE stay                       | TX (T5)                                                   |
| I11     | Ward may not be re-typed while a bed is occupied                | **already shipped** in ICU-2 `WardService.applyUnitType`  |
| I12–I18 | Tenancy on every edge                                           | TX + `TenantScopingArchTest` + `CrossTenantIsolationTest` |
| I19     | ICU creates no billing row                                      | test                                                      |
| I20     | ICU writes no bed status directly                               | test                                                      |
| I21     | ICU never writes `IpdAdmission.doctorId`/`status`/`ipdNumber`   | test                                                      |
| I22     | Realtime only via `RealtimeNotifier`                            | test                                                      |

**I7 + I8 give the biconditional the board depends on: ACTIVE stay ⟺ patient occupies a
critical-care bed.** ICU-2's `occupancyConsistent` flag becomes a second, independent check on it.

---

## 9. Transaction design — and the E1 correction

`IcuStayService` lifecycle methods are `@Transactional(propagation = Propagation.MANDATORY)`,
exactly as ICU-1 specified and E1 verified. They **join** the movement transaction; they never
open one.

```
admitFromOpd  @Transactional  (E1/C1)
├─ lockForClaim(bedId)                    MANDATORY  (E1/C3)
├─ save admission, IPD number, bed history
├─ bedStatusService.change(OCCUPIED)
├─ icuStayService.onWardEntered(...)      MANDATORY  ◄── ICU-3
└─ COMMIT ─► audit / realtime (best-effort, unchanged)
```

**Correction carried from E1.** ICU-1 §10.1 drew this diagram but did not say who calls
`admitFromOpd`. E1 moved the C2 IPD-number retry to `IpdAdmissionController`, because each retry
must be a _fresh_ transaction and `admitFromOpd` **is** the transaction. Consequence:

- The HTTP path retries. **A direct service call does not.**
- ICU-3 adds no new entry point, so nothing regresses.
- But any future ICU code that calls `admitFromOpd` directly inherits an unretried
  `DataIntegrityViolationException`. Recorded as **D-4**.

**Critical vs best-effort** (ICU-1 §10.4) is unchanged. The stay is **critical**: a patient in an
ICU bed with no stay record breaks I8 silently and loses the admission time permanently. Audit,
notification and realtime stay best-effort.

---

## 10. API surface

Namespace `/hospital/icu/**` on the **existing** `IcuDashboardController` conventions:
`@RequireModule("ICU")`, `@TenantType(HOSPITAL)`, **no `/clinic` or `/pharmacy` alias**, declared
in `ControllerModules`.

**Reads** (new)

| Method | Path                                     | Purpose                         |
| ------ | ---------------------------------------- | ------------------------------- |
| GET    | `/hospital/icu/stays/{publicId}`         | one stay, ACTIVE or CLOSED      |
| GET    | `/hospital/icu/admissions/{ipdId}/stays` | full stay history, newest first |

**Mutations — narrowly scoped, ACTIVE only** (ICU-1 §10.2 r2)

| Method | Path                                              | Writes                  | Audit action               |
| ------ | ------------------------------------------------- | ----------------------- | -------------------------- |
| PUT    | `/hospital/icu/stays/{publicId}/intensivist`      | `intensivist_doctor_id` | `ICU_INTENSIVIST_ASSIGNED` |
| PUT    | `/hospital/icu/stays/{publicId}/admission-reason` | `admission_reason`      | `ICU_REASON_UPDATED`       |

**No create and no close endpoint.** A stay opens and closes only as a consequence of a movement.
Exposing "create ICU stay" would let the record disagree with the bed the patient is in — the
same one-source-of-truth rule that kept ICU-2 read-only.

**Board change:** `IcuBoardService` populates the existing `IcuBedRowDTO.icuStay` slot. **No DTO
change, no frontend change** beyond rendering fields that arrive.

---

## 11. Proposed files

**Backend — new (7)**

```
entity/IcuStay.java
repository/IcuStayRepository.java
service/hospital/icu/IcuStayService.java
dto/icu/IcuStayDTO.java
controller/hospital/IcuStayController.java
test/service/hospital/icu/IcuStayServiceTest.java
test/security/IcuStayTenancyTest.java
```

**Backend — modified (7)**

```
service/hospital/IpdAdmissionService.java     3 hook calls (open / close+open / close)
service/hospital/ot/RecoveryService.java      T3 handoff — D-3 only
service/hospital/icu/IcuBoardService.java     populate IcuBedRowDTO.icuStay
dto/icu/IcuBedRowDTO.java                     type the icuStay slot
entitlement/ControllerModules.java            declare IcuStayController (MANDATORY — the
                                              FacilityAccessAspect null-means-allowed trap)
config/DatabaseMigrationRunner.java           ensureIcuStayTable()
setup/schema-full.sql                         mirror the DDL
```

**Tests — modified (3)**

```
security/TenantScopingArchTest.java           allowlist, if any lookup-by-id is unavoidable
security/CrossTenantIsolationTest.java        foreign stay read
integration/IpdConcurrencyIT.java             one case: concurrent ICU entry ⇒ one ACTIVE stay
```

**Frontend — new (1), modified (1)**

```
pages/hospital/icu/IcuStayPanel.jsx           stay header + history inside /ipd/:id
pages/hospital/IpdDetails.jsx                 conditional tab, as Consent Forms already does
```

**No new table beyond `icu_stay`. No column on any existing table. No new role.**

---

## 12. Implementation order

```
ICU-3.0  entity + repository + migration + schema mirror        (no behaviour)
ICU-3.1  IcuStayService: open/close, MANDATORY, invariants I4-I10
           TDD against IcuStayServiceTest; nothing calls it yet
ICU-3.2  wire the three IpdAdmissionService hooks (T1, T2, T4, T5, T6, T7)
           the transaction already exists (E1/C1) — this is the payoff
ICU-3.3  read endpoints + board population
ICU-3.4  narrow mutation endpoints + tenancy tests
ICU-3.5  T3 OT handoff                                          (D-3 dependent)
ICU-3.6  frontend panel + tab
```

**3.1 before 3.2** for the same reason E1 put C1 before C3: a lifecycle with no caller can be
tested exhaustively and cheaply; a lifecycle wired into admissions cannot.
**3.5 last** because it is the only OT-touching step and may be descoped entirely by D-3.

---

## 13. Test plan

| ID  | Test                                                                        | Covers          | Type                    |
| --- | --------------------------------------------------------------------------- | --------------- | ----------------------- |
| S1  | open/close happy path, all six `source` values                              | I5, I6, §5      | unit                    |
| S2  | second ACTIVE stay rejected — **DB constraint, not just the service check** | I4              | integration (MySQL)     |
| S3  | closed stay is immutable; every mutation endpoint 409s                      | I5              | unit + integration      |
| S4  | close without disposition rejected                                          | I6              | unit                    |
| S5  | stay ward always equals admission ward; T6 closes and reopens               | I9              | unit                    |
| S6  | discharge closes the stay                                                   | I10             | integration             |
| S7  | `source_ref_id` rules 1–6, incl. episode coherence and tolerant reads       | §6              | unit                    |
| S8  | foreign stay read ⇒ 404; foreign intensivist ⇒ 404                          | I15, I17        | integration             |
| S9  | ICU writes no billing / bed status / admission column                       | I19–I21         | unit (verify-never)     |
| S10 | **`MANDATORY` throws when called outside a transaction**                    | §9              | unit                    |
| S11 | two concurrent ICU entries ⇒ exactly one ACTIVE stay                        | I4              | **concurrency (MySQL)** |
| S12 | board `icuStay` populated for ICU beds, null for non-ICU                    | §10             | unit                    |
| S13 | non-ICU IPD admission still creates **zero** stays                          | backward-compat | integration             |

**S11 must use the same real-MySQL harness E1 built** (`IpdConcurrencyIT`,
`-Dhms.it.mysql.url=…`). E1 proved the lesson twice: H2 will not reproduce the race, **and a
race released by a latch alone may not interleave at all** — the rendezvous technique in
`IpdConcurrencyIT` is required, and the fix must be reverted once to confirm the test fails.

**S13 is the regression gate.** A hospital with no ICU ward must be untouched.

---

## 14. Risks

| Risk                                                                            | Likelihood  | Mitigation                                                                      |
| ------------------------------------------------------------------------------- | ----------- | ------------------------------------------------------------------------------- |
| A stay-write bug blocks admissions entirely                                     | Medium      | Correct by design (critical state), but 3.1 is fully tested before 3.2 wires it |
| I8 violated by data predating ICU-3 — patients already in ICU beds with no stay | **Certain** | **D-1**: backfill, or accept and let the board show them stay-less              |
| `MANDATORY` throws if a future caller forgets the transaction                   | Low         | That is the intent — loud, and S10 pins it                                      |
| Ward re-typed under occupants                                                   | Low         | Already prevented by ICU-2 `applyUnitType`                                      |
| OT handoff drifts from `changeBed`                                              | Medium      | D-3; descoping 3.5 removes it                                                   |
| Direct `admitFromOpd` calls lack the C2 retry                                   | Low         | D-4; ICU-3 adds no such caller                                                  |

---

## 15. Scope / Non-scope

**ICU-3 WILL:** create `icu_stay`; open/close it on the existing movement paths; expose two reads
and two narrow field mutations; populate the board's existing `icuStay` slot; add a stay panel to
the existing patient workspace.

**ICU-3 WILL NOT:** touch E1 (accepted and frozen); add vitals, ventilator, infusions,
intake/output, severity scores or alerts (ICU-4+); create `icu_unit_profile` or nurse ratios;
change bed status values, billing, pharmacy, nursing workflow or global error handling; add a
role; add a migration to any existing table; modify `admitFromOpd`'s transaction or the C2 retry;
alter OT beyond the single D-3 handoff.

---

## 16. Definition of Done

1. `icu_stay` created via `ensureIcuStayTable()` **and** mirrored in `schema-full.sql`.
2. Stay opens/closes correctly on T1a–T7.
3. I4 enforced by the **database**, proven by S2 and S11.
4. I5–I10 enforced in-transaction, proven by S1, S3–S6.
5. Tenancy: S8 green; `TenantScopingArchTest` allowlist edited deliberately if at all.
6. **S13: a non-ICU admission creates zero stays** — backward compatibility.
7. `mvn test` 0 failures, 0 errors (baseline 518).
8. `IpdConcurrencyIT` + S11 green on real MySQL — **a skip is not a pass**.
9. Each new invariant test verified to **fail** with its fix reverted (the E1 standard).
10. ICU-2 board unaffected; `occupancyConsistent` shows no new mismatches.
11. Frontend build green; diff reviewed; local commit; checkpoint report. **Nothing pushed.**

---

## 17. Decisions Required

| #       | Decision                                                                                                                                    | Recommendation                                                                                                                                                                               |
| ------- | ------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **D-1** | Patients **already** in ICU beds have no stay. Backfill an ACTIVE stay per current occupant, or start clean?                                | **Backfill**, `source=EXTERNAL_REFERRAL`, `admitted_at = admission_datetime`, reason "Backfilled at ICU-3". Otherwise I8 is violated from the first minute and the board contradicts itself. |
| **D-2** | Ship the two mutation endpoints in ICU-3, or reads only?                                                                                    | **Ship them.** Without an intensivist field the stay records nothing a bed does not already say.                                                                                             |
| **D-3** | T3 OT handoff: does `RecoveryService.discharge(...,"ICU")` **drive** the bed move, or only record provenance for a move reception performs? | **Provenance only.** Driving a bed move from OT would put a second actor on the movement path E1 just hardened. Descope 3.5 to setting `source_ref_id` when the transfer happens.            |
| **D-4** | `admitFromOpd` called directly is not C2-retry-protected (E1). Accept for ICU-3?                                                            | **Accept.** ICU-3 adds no direct caller. Revisit only if a non-HTTP admission path is ever added.                                                                                            |
| **D-5** | Multiple concurrent ICU consultants                                                                                                         | **Defer again.** `SurgeryTeamMember` is the precedent when demand is real.                                                                                                                   |
| **D-6** | `icu_unit_profile` / nurse ratio                                                                                                            | **Still deferred** (ICU-1 §5.4). No ICU-3 code reads it.                                                                                                                                     |

---

## 18. Recommended Next Checkpoint

**ICU-3.0 + ICU-3.1** — entity, repository, migration and a fully tested `IcuStayService` that
nothing calls yet. That is the cheapest place to get the invariants right, and it produces zero
behaviour change until 3.2 wires it in.

**Blocked on D-1 and D-3.** The rest can proceed on the recommendations above.
