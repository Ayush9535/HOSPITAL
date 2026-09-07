# E1 — IPD Hardening (C1–C4)

**Checkpoint:** E1 (AUDIT → PLAN)
**Date:** 2026-08-25
**Status:** Audit complete, plan proposed — **awaiting approval. No production code changed.**
**Inputs:** [ICU_EXISTING_SYSTEM_AUDIT.md](ICU_EXISTING_SYSTEM_AUDIT.md) (ICU-0),
[ICU_SYSTEM_DESIGN.md](ICU_SYSTEM_DESIGN.md) r2 (ICU-1), [ICU_PHASE2_PLAN.md](ICU_PHASE2_PLAN.md) (ICU-2)

---

## 1. Executive Summary

Four pre-existing defects on the shared IPD movement paths block ICU-3, because ICU-1's
`IcuStayService` uses `Propagation.MANDATORY` and therefore _requires_ a caller transaction
that does not currently exist on the admission path.

**The single most important finding of this audit reverses an assumption carried since
ICU-0:** the six `AdmissionBedWardIsolationTest` errors are **not** evidence of C1. They have
**two independent test-side causes**, both introduced by commit `233b66e`
(_harden authentication and session revocation_), and **neither is a production defect**.
`CrossTenantIsolationTest` hits the _same endpoint_ with the _same assertions_ and passes
12/12 — because it was repaired for both causes and `AdmissionBedWardIsolationTest` was not.

That matters in both directions:

- E1 must **not** treat those errors as a C1 symptom, and
- C1 currently has **no failing test at all**. The very assertions that would prove C1
  (`test4_allOwnResources_admissionSucceeds`, and the `assertRefusedAndInert` state
  snapshots) have been unobservable since `233b66e`. **Repairing the test is therefore step
  one, and it may turn six transport errors into a genuine C1 failure.** That is the
  intended outcome, not a regression.

Severity, after evidence:

| ID     | Defect                                    | Real-world impact                                                      | Blocks ICU-3?            |
| ------ | ----------------------------------------- | ---------------------------------------------------------------------- | ------------------------ |
| **C1** | `admitFromOpd` is not transactional       | Partial admissions survive a mid-flow failure; `MANDATORY` cannot join | **Yes — hard blocker**   |
| **C3** | Bed claim is check-then-act with no lock  | Two patients admitted to one bed                                       | **Yes**                  |
| **C2** | IPD number `MAX+1` race                   | Duplicate-key 500 on concurrent admits; DB integrity holds             | No, but same transaction |
| **C4** | `changeBed` loads the target bed unscoped | Existence disclosure; write is stopped downstream                      | No                       |

**Canonical implementation order (approved):**

```
E1.0  repair AdmissionBedWardIsolationTest   (test only, establishes the C1 baseline)
E1.1  C1 — transaction boundary on admitFromOpd
E1.2  C3 — pessimistic bed locking
E1.3  C2 — IPD number: single read + retry at a transaction boundary
E1.4  C4 — scoped target-bed lookup in changeBed
```

**C1 MUST precede C3**: a `PESSIMISTIC_WRITE` lock is only effective while held inside a
transaction, so locking before the transaction boundary exists would produce a lock that
reviews as correct and prevents nothing at runtime. Full dependency reasoning in §11.

Total production surface: **3 files** (`IpdAdmissionService`, `BedRepository`,
`BedStatusService`), ~60 lines, **no migration, no schema change**.

---

## 2. Repository Baseline

|                   |                                                                                                                                                                                                                                            |
| ----------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Branch            | `icu`                                                                                                                                                                                                                                      |
| HEAD SHA          | `3872994dc3cb42dae910dc43e8c1eda6e78b06be`                                                                                                                                                                                                 |
| HEAD subject      | `feat(icu): ICU dashboard and bed board (ICU-2, read-only)`                                                                                                                                                                                |
| Working tree      | **Clean of tracked changes.** Two untracked, empty (0-byte) placeholder files: `docs/ICU_DOMAIN_GAP_ANALYSIS.md`, `docs/ICU_PHASE_PLAN.md`                                                                                                 |
| Uncommitted work  | **None.** Nothing was discarded, reset, stashed or modified during this audit                                                                                                                                                              |
| Branch switch     | **None performed.** All analysis ran on `icu`                                                                                                                                                                                              |
| Base relationship | `main` is at `d80ba80`; **`git merge-base HEAD main` is empty** — `icu` and `main` share no common ancestor in this clone (unrelated histories). `icu` is the working line and carries 10+ commits including the security-hardening series |

**Relevant history** (newest first, on `icu`):

```
3872994 feat(icu): ICU dashboard and bed board (ICU-2, read-only)
fa04a3e docs: record the non-negotiable engineering principles in CLAUDE.md
5138666 merge: integrate validated security hardening into staging
baf25a7 fix(security): enforce facility access and tenant isolation   <- last touched AdmissionBedWardIsolationTest
...
233b66e fix(security): harden authentication and session revocation   <- introduced both test-breaking changes
```

E1 should branch from `3872994`.

---

## 3. Current Architecture — the IPD movement paths

Three service methods move a patient. All three are in
`service/hospital/IpdAdmissionService` (1343 lines).

| Path           | Method             | Line | `@Transactional`? |
| -------------- | ------------------ | ---- | ----------------- |
| Admit from OPD | `admitFromOpd`     | 133  | **NO — see C1**   |
| Transfer       | `changeBed`        | 1212 | Yes (line 1211)   |
| Discharge      | `confirmDischarge` | 1057 | Yes (line 1056)   |

Supporting components:

- `BedStatusService.change(bedId, status, remarks)` — `@Transactional`, the **only**
  legitimate writer of `beds.status`; writes `bed_status_audits`, best-effort `AuditLog`,
  then `RealtimeNotifier.refresh`.
- `BedRepository` — `findByBedIdAndHospitalId` (scoped), `findByWardIdAndHospitalId`,
  `findByHospitalIdAndStatus`, plus ICU-2's `findByHospitalIdAndWardIdIn`. **No locking
  finder of any kind.**
- `OtSchedulingService.lockRoom` + `OtRoomRepository.findByIdForUpdate` — the **only**
  pessimistic-lock precedent for a resource-claim race in the codebase (§7).
- `RealtimeNotifier` — defers every push to `afterCommit`.

---

## 4. C1 Audit — `admitFromOpd` transaction boundary

### 4.1 Complete execution trace

Controller: `IpdAdmissionController` → `POST /hospital/ipd/admit` → `admitFromOpd(opdId,
wardId, bedId, admissionType, primaryDiagnosis)`.

| #   | Step                    | Class / method                                                    | Repository           | Writes                                   | Joins caller TX?       | Can fail? | On failure                            |
| --- | ----------------------- | ----------------------------------------------------------------- | -------------------- | ---------------------------------------- | ---------------------- | --------- | ------------------------------------- |
| 1   | Resolve tenant          | `SecurityContextHelper.getCurrentHospitalId`                      | —                    | —                                        | n/a                    | yes (401) | nothing written yet — safe            |
| 2   | Load OPD                | `opdRepository.findByIdAndHospitalIdWithPatientAndDoctor`         | Opd                  | —                                        | n/a                    | yes (404) | safe                                  |
| 3   | Load bed                | `bedRepository.findByBedIdAndHospitalId`                          | Bed                  | —                                        | n/a                    | yes (404) | safe — **scoped, fixed by `baf25a7`** |
| 4   | **Check bed available** | inline `bed.getStatus().equalsIgnoreCase("available")`            | —                    | —                                        | —                      | yes (400) | **check-then-act — see C3**           |
| 5   | Load ward               | `wardRepository.findByWardIdAndHospitalId`                        | Ward                 | —                                        | n/a                    | yes (404) | safe — scoped                         |
| 6   | Nursing-incharge rule   | `hasNursingModule()` + `ward.getInchargeNurseId()`                | Hospital             | —                                        | —                      | yes (400) | safe                                  |
| 7   | **Allocate IPD number** | `ipdAdmissionRepository.findMaxIpdSequence() + 1`                 | IpdAdmission         | —                                        | **no TX**              | yes       | **race — see C2**                     |
| 8   | **Save admission**      | `ipdAdmissionRepository.save(ipd)`                                | IpdAdmission         | **COMMITS ALONE**                        | **no**                 | yes       | **row persists permanently**          |
| 9   | Bed history             | `ipdBedHistoryRepository.save(...)`                               | IpdBedHistory        | commits alone                            | no                     | yes       | **swallowed** by try/catch → logged   |
| 10  | **Claim bed**           | `bedStatusService.change(bedId, OCCUPIED, ...)`                   | Bed + BedStatusAudit | **own TX**                               | **NO — opens its own** | yes       | **step 8 already committed**          |
| 11  | Back-link bed           | `bedRepository.save(occupiedBed)`                                 | Bed                  | commits alone                            | no                     | yes       | bed occupied w/o back-link            |
| 12  | Nurse assignment        | `patientAssignmentService.onAdmission(saved)`                     | several              | own TX                                   | no                     | yes       | **swallowed** — by design             |
| 13  | Close OPD               | `opd.setStatus(IN_IPD)` + `opdRepository.save`                    | Opd                  | commits alone                            | no                     | yes       | OPD left open                         |
| 14  | Clear queue             | `queueEntryRepository.deleteByOpdId`                              | QueueEntry           | own TX (`@Transactional` on repo method) | no                     | yes       | **swallowed**                         |
| 15  | Billing                 | `billingRepository.save` + `billingItemRepository.save`           | Billing, BillingItem | commits alone                            | no                     | yes       | admission with no bill                |
| 16  | Audit                   | `auditLogService.logAction`                                       | AuditLog             | own TX                                   | no                     | yes       | **swallowed** — by design             |
| 17  | Realtime                | `webSocketHandler.broadcast` **(direct, not `RealtimeNotifier`)** | —                    | —                                        | —                      | yes       | **swallowed** — by design             |

### 4.2 Findings against the nine required questions

**1. Where `@Transactional` currently exists.** At
[IpdAdmissionService.java:119](../backend/src/main/java/com/hms/service/hospital/IpdAdmissionService.java#L119)
— placed **above a javadoc block**, so it binds to the _next_ declaration, which is the
**private** method `hasNursingModule()` at line 125. `admitFromOpd` at line 133 carries no
annotation at all.

```java
    @Transactional          // line 119
    /**
     * Whether this hospital's plan includes NURSING ...
     */
    private boolean hasNursingModule() { ... }        // line 125  <- binds here

    public IpdAdmission admitFromOpd(...) { ... }      // line 133  <- NOT transactional
```

Doubly inert: Spring's proxy ignores `@Transactional` on a **private** method, so the
annotation has no effect in either position. This is not a subtle placement issue — it is a
no-op annotation next to an unprotected method.

**2. Correct service boundary?** No. The boundary belongs on `admitFromOpd`, the unit of
work the user invoked. `changeBed` and `confirmDischarge` already have it correctly.

**3. Are all critical state changes atomic?** No. Steps 8, 10, 11, 13, 15 each commit
independently.

**4. Can a database write commit independently?** Yes — every one of steps 8–15.

**5. Does a try/catch swallow a failure that should roll back?** Yes for step 9
(`IpdBedHistory`) — the bed span for the admission is silently lost. Steps 12, 14, 16, 17
are swallowed **by design** and should stay that way (§8).

**6. Does `BedStatusService` participate in the same transaction?** **No.** It is
`@Transactional` with default `REQUIRED`. Called from a non-transactional caller, `REQUIRED`
**starts a new transaction and commits it independently**. The bed claim is therefore a
separate durable commit from the admission row.

**7. Does IPD number allocation participate?** No — no transaction exists to participate in.
`findMaxIpdSequence()` is a `nativeQuery` read with no lock, and the subsequent `save` is its
own commit.

**8. Can admission and bed claim diverge?** **Yes, in both directions** — see §5.3.

**9. Can `IcuStayService` with `Propagation.MANDATORY` safely join?** **No.**
`MANDATORY` throws `IllegalTransactionStateException` when no transaction is active. Called
from today's `admitFromOpd` it would **throw on every direct ICU admission**. ICU-1 chose
`MANDATORY` deliberately so this fails loudly rather than committing an orphan stay — the
design is correct, and C1 is exactly the precondition it named. **This is the hard blocker.**

---

## 5. C1 — Failure / rollback analysis

### 5.1 The six `AdmissionBedWardIsolationTest` errors — investigated, not assumed

All six fail with the **same** exception, none with an assertion failure:

```
ResourceAccess I/O error on POST request for "http://localhost:PORT/hospital/ipd/admit":
cannot retry due to server authentication, in streaming mode
```

**Evidence gathered:**

1. **Reproduced in isolation** — `mvn test -Dtest=AdmissionBedWardIsolationTest` → 6 errors.
2. **Reproduced on the untouched base** — all ICU work stashed, run at `5138666`
   → identical 6/6. Then restored. **Pre-existing, not caused by ICU-2.**
3. **The sibling test passes.** `CrossTenantIsolationTest` POSTs the _same_
   `/hospital/ipd/admit` (lines 373, 411) with the same two-tenant premise → **12 tests, 0
   failures, 0 errors.**
4. **Difference A — transport.** `CrossTenantIsolationTest` lines 183–195 already document
   this exact failure, verbatim:

   > _"TestRestTemplate cannot be used here any more. Its `SimpleClientHttpRequestFactory`
   > streams the request body, and `233b66e` made the unauthenticated path answer 401 with a
   > body… HttpURLConnection reacts to the 401 by trying to replay the request, which a
   > streamed PUT/DELETE cannot do, and throws 'cannot retry due to server authentication, in
   > streaming mode' before any status is observed — so the refusal this class exists to
   > assert became unobservable."_

   It was migrated to `java.net.http.HttpClient`. `AdmissionBedWardIsolationTest` still
   `@Autowired TestRestTemplate`.

5. **Difference B — fixture.** `grep -c "UserRepository|new User()"` on
   `AdmissionBedWardIsolationTest` → **0**. It mints
   `jwtUtil.generateToken(2L, "admin@bravo.com", …)` for a user id that has no row.
   `233b66e` made `JwtAuthenticationFilter` revalidate every request via
   `userRepository.findActiveTokenVersion(userId)`; an empty Optional **denies**. So the
   request is 401 before it reaches the controller.
   (`tokenVersion` itself is **not** the problem: the 8-arg overload delegates with `0`, and
   the builder writes `tokenVersion == null ? 0 : tokenVersion`.)

**Conclusion: two independent, purely test-side causes. Neither is C1, C2, C3 or C4.**

| Test                                         | Failure                       | Root cause                                                       | Class                         | Fix required?   |
| -------------------------------------------- | ----------------------------- | ---------------------------------------------------------------- | ----------------------------- | --------------- |
| `test1_foreignOpd_withOwnWard_andForeignBed` | I/O error, no status observed | (A) `TestRestTemplate` streaming + 401 · (B) no `User` row → 401 | **TEST INFRA**                | Yes — test only |
| `test2_ownOpd_foreignWard_ownBed`            | same                          | same                                                             | **TEST INFRA**                | Yes — test only |
| `test3_ownOpd_ownWard_foreignBed`            | same                          | same                                                             | **TEST INFRA**                | Yes — test only |
| `test5_ownOpd_foreignWard_foreignBed`        | same                          | same                                                             | **TEST INFRA**                | Yes — test only |
| `aForeignBedAnswersExactlyLikeAMissingOne`   | same                          | same                                                             | **TEST INFRA**                | Yes — test only |
| `test4_allOwnResources_admissionSucceeds`    | same                          | same                                                             | **TEST INFRA**, but see below | Yes — test only |

> **Prediction to verify first.** Once transport and fixture are repaired, these six become
> _observable_ for the first time since `233b66e`. `test4` and the `assertRefusedAndInert`
> snapshots assert exactly the partial-state behaviour C1 describes — the class javadoc
> already states _"admitFromOpd had already written the admission row, and it has no
> transaction to undo it."_ **Repair the test before fixing C1 and record what it reports.**
> If it then fails on an assertion, that is the first real reproduction of C1 and becomes the
> regression gate.

### 5.2 Test repair is the first task of E1

Two changes, both confined to `AdmissionBedWardIsolationTest`:

1. Replace the `TestRestTemplate` transport with the `java.net.http.HttpClient` helper
   already written and documented in `CrossTenantIsolationTest.call(...)`.
2. Seed real `User` rows and mint tokens from them, exactly as
   `CrossTenantIsolationTest.seedUser` / `tokenFor` do.

No assertion, endpoint or tenant semantic changes.

### 5.3 Partial-state scenarios — current actual behaviour

| Scenario                                                                                            | Current behaviour                                                                                                                                                                                                      | Verdict                                                                                |
| --------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------- |
| **A. Admission succeeds, bed claim fails** (step 10 throws — foreign bed, invalid status, DB error) | Steps 7–9 already committed. An `ipd_admission` row exists, `ipd_number` consumed, bed still `available`. The API returns an error, but **the patient is admitted in the database**.                                   | **CRITICAL — must be atomic**                                                          |
| **B. Bed claim succeeds, admission fails**                                                          | Not reachable in the current ordering (bed claim is step 10, after the save), **but the ordering itself is the only thing preventing it.** Under C3's fix the claim must move earlier, so atomicity becomes mandatory. | **CRITICAL — must be atomic**                                                          |
| **C. IPD number allocated, a later step fails**                                                     | The number is consumed by the committed row in step 8 and never reused. Under a duplicate-key failure at step 8 the caller sees a 500 and no admission.                                                                | **CRITICAL — same transaction**                                                        |
| **D. Nurse assignment fails, admission succeeds**                                                   | Caught and logged; admission stands.                                                                                                                                                                                   | **BEST-EFFORT — leave as is**                                                          |
| **E. Bed history write fails**                                                                      | Caught and logged; the bed span is silently lost.                                                                                                                                                                      | **Move to critical** (§8, I-2) — it is the record the ICU board and LOS reporting read |
| **F. Billing fails**                                                                                | Uncaught → propagates. Admission and bed already committed.                                                                                                                                                            | **CRITICAL — same transaction**                                                        |
| **G. OPD close / queue clear fails**                                                                | OPD stays open; queue entry lingers (swallowed).                                                                                                                                                                       | Best-effort today; brought inside the TX at no cost by the C1 fix                      |
| **H. Audit / notification / websocket fails**                                                       | Swallowed.                                                                                                                                                                                                             | **BEST-EFFORT — leave as is**                                                          |

---

## 6. C2 Audit — IPD number allocation race

### 6.1 Trace

- **Generator:** inline in `admitFromOpd`, lines 158–160. No service, no dedicated class.
  ```java
  int nextIpd = (ipdAdmissionRepository.findMaxIpdSequence() != null
                 ? ipdAdmissionRepository.findMaxIpdSequence() : 0) + 1;
  ipd.setIpdNumber("IPD-" + nextIpd);
  ```
  Note it calls `findMaxIpdSequence()` **twice** — two separate reads for one decision.
- **Query:** `IpdAdmissionRepository.findMaxIpdSequence()`, `nativeQuery = true`:
  ```sql
  SELECT COALESCE(MAX(CAST(SUBSTRING(ipd_number, 5) AS DECIMAL(20,0))), 0)
  FROM ipd_admission WHERE ipd_number LIKE 'IPD-%'
  ```
  `DECIMAL(20,0)` was chosen for MySQL/H2 portability (commit `895a657`). **No lock, no
  `FOR UPDATE`.**
- **Column:** `ipd_admission.ipd_number VARCHAR(255) NOT NULL`.
- **Constraint:** `UNIQUE KEY UK_3p2j5aiaxya8xnh9epblbmlhp (ipd_number)` — present in
  `setup/schema-full.sql:293`, Hibernate-generated from `@Column(unique = true)`.
- **Transaction:** none (C1).
- **Locking / retry:** none of either.
- **Failure:** `DataIntegrityViolationException` → mapped by `GlobalExceptionHandler` to a
  500 (it is not an `IllegalArgumentException`/`ConflictException`).

### 6.2 Is the race possible?

**Yes.**

```
Request A: MAX -> 41, computes 42
Request B: MAX -> 41, computes 42     (A has not committed; even if it had, no lock)
Request A: INSERT IPD-42              -> OK
Request B: INSERT IPD-42              -> UNIQUE violation -> 500
```

**The database does protect integrity** — no duplicate can be stored. The defect is a
**usability and correctness-of-response** problem: a legitimate second admission fails with
an opaque 500 instead of succeeding. Under C1's fix it will at least roll back cleanly
instead of leaving debris.

Note also: `ipd_number` is **globally unique, not per tenant.** Two hospitals share one
sequence, so tenants contend with each other. Changing that is a data-model change and is
**out of E1 scope** — recorded as an observation.

### 6.3 Options

|                     | Approach                                                                                                                                            | Pros                                                                                                                          | Cons                                                                                   |
| ------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------- |
| **1 (recommended)** | Keep `MAX+1`, but wrap `admitFromOpd` in a transaction (C1) and **retry once** on `DataIntegrityViolationException` for the `ipd_number` constraint | Smallest change; no schema change; the unique index remains the authority; matches the "DB is the arbiter" style already used | A retry loop is a little inelegant; needs a bounded attempt count                      |
| 2                   | `SELECT … FOR UPDATE` on a sequence/counter row                                                                                                     | Deterministic, no retry                                                                                                       | Needs a **new table** and a migration; heavier than the problem                        |
| 3                   | DB `AUTO_INCREMENT` / identity column for the sequence                                                                                              | Native, race-free                                                                                                             | Changes the format/meaning of `ipd_number`; touches historical rows; **schema change** |
| 4                   | Lock the `ipd_admission` table / `MAX(...) FOR UPDATE`                                                                                              | —                                                                                                                             | Not portable to H2; serialises all admissions hospital-wide                            |

**Recommended: option 1.** It adds no schema object, honours the existing unique index as
the source of truth, and becomes safe the moment C1 gives it a transaction to roll back.
Also collapse the duplicated `findMaxIpdSequence()` call to one.

---

## 7. C3 Audit — bed claim concurrency

### 7.1 The race

`admitFromOpd` (steps 4 → 10) and `changeBed` (line 1231 → 1252) both do:

```
read bed.status  ...  [no lock, no transaction in admit]  ...  write bed.status
```

```
User A: reads bed 7 -> "available"
User B: reads bed 7 -> "available"
User A: claims bed 7 -> OCCUPIED, admission 100
User B: claims bed 7 -> OCCUPIED, admission 101   (overwrites current_ipd_admission_id)
```

Result: **two admissions, one bed.** ICU-2's board already detects and displays the
downstream symptom (`occupancyConsistent = false`), which will make this visible in
production — but it does not prevent it.

`changeBed` is additionally weaker: it only rejects `"occupied"`, so a bed in `cleaning` or
`maintenance` can be claimed by a transfer.

There is **no unique constraint** on `beds.current_ipd_admission_id`, so the database offers
no backstop the way it does for C2.

### 7.2 Callers of the bed-claim path

| Caller                                                 | Reads availability           | Writes bed                                               |
| ------------------------------------------------------ | ---------------------------- | -------------------------------------------------------- |
| `IpdAdmissionService.admitFromOpd`                     | inline `"available"` check   | `bedStatusService.change(OCCUPIED)`                      |
| `IpdAdmissionService.changeBed`                        | inline `!= "occupied"` check | `bedStatusService.change(CLEANING)` + `change(OCCUPIED)` |
| `IpdAdmissionService.confirmDischarge`                 | —                            | `change(CLEANING)` — release only, no race               |
| `BedService.getAvailableBeds`                          | read-only list for the UI    | —                                                        |
| `BedController` mark cleaned / maintenance / available | status precondition          | `change(...)`                                            |
| `WardService.getWardsForAdmission`                     | read-only filter             | —                                                        |
| OT complete                                            | —                            | release only                                             |

Only the two claim paths race.

### 7.3 The in-repo locking precedent — `OtSchedulingService.lockRoom`

```java
// OtRoomRepository
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT r FROM OtRoom r WHERE r.id = :id")
Optional<OtRoom> findByIdForUpdate(@Param("id") Long id);

// OtSchedulingService
/** Locks the theatre for the rest of the transaction. Call before checking for a clash. */
public OtRoom lockRoom(Long roomId) {
    return roomRepository.findByIdForUpdate(roomId)
            .orElseThrow(() -> new IllegalArgumentException("Theatre not found"));
}
```

| Question                | Answer                                                                                                                                                 |
| ----------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------ |
| What does it lock?      | One `ot_rooms` row, by id                                                                                                                              |
| How?                    | JPA `PESSIMISTIC_WRITE` → `SELECT … FOR UPDATE`                                                                                                        |
| Transaction requirement | **Must** run inside one — the lock is held until commit. Its only caller, `SurgeryService.schedule` (line 132), is `@Transactional` (line 131)         |
| Repository query        | Explicit `@Query` so the lock hint applies to a JPQL select rather than the derived `findById`                                                         |
| Exception behaviour     | Missing row → `IllegalArgumentException` → 400                                                                                                         |
| DB support              | MySQL InnoDB: yes. **H2 in MySQL mode (the test profile): yes** — `SELECT … FOR UPDATE` is supported, so the pattern is testable in the existing suite |
| Why chosen there        | _"interval overlap cannot be expressed as a unique index and a read-then-write check races"_                                                           |

**Verdict: directly adaptable.** A bed claim is the same shape as a theatre booking — a
contended resource whose availability cannot be expressed as a unique index. The one
difference works in our favour: a bed claim is a point-in-time state check, simpler than an
interval overlap.

**Critical dependency:** the lock is worthless without a surrounding transaction. **C3
cannot be implemented before C1.** See §11.

---

## 8. C4 Audit — `changeBed` tenant scoping

### 8.1 Trace of every lookup in `changeBed`

| Line      | Lookup                                                    | Scoped?                                                                                     |
| --------- | --------------------------------------------------------- | ------------------------------------------------------------------------------------------- |
| 1213      | `requireOwnedAdmission(ipdId)` — the admission            | ✅ tenant-checked                                                                           |
| 1218      | `hospitalSettingRepository.findByHospital_Id(hospitalId)` | ✅                                                                                          |
| **1231**  | **`bedRepository.findById(newBedId)`**                    | ❌ **UNSCOPED — the defect**                                                                |
| 1236      | `bedRepository.findById(oldBedId)`                        | ⚠️ unscoped, but the id comes from the tenant's own admission row — not attacker-controlled |
| 1238      | `wardRepository.findById(oldBed.getWardId())`             | ⚠️ same — derived, not supplied                                                             |
| 1257      | `wardRepository.findById(newBed.getWardId())`             | ❌ **derived from the unscoped bed**                                                        |
| 1265/1270 | `wardRepository.findById(...)` for pricing                | ❌ same                                                                                     |

```java
Bed newBed = bedRepository.findById(newBedId)
        .orElseThrow(() -> new ResourceNotFoundException("New bed not found"));
if ("occupied".equalsIgnoreCase(newBed.getStatus()) && !newBedId.equals(ipd.getBedId())) {
     throw new IllegalArgumentException("Requested bed is already occupied");
}
```

### 8.2 Actual exposure

A foreign bed **is** loaded and **is** inspected. The caller can therefore distinguish:

- a non-existent bed → `ResourceNotFoundException` (404)
- another tenant's **occupied** bed → `IllegalArgumentException` "already occupied" (400)
- another tenant's **available** bed → proceeds to `bedStatusService.change`, which
  **is** tenant-scoped and throws `ResourceNotFoundException` (404)

**No cross-tenant write occurs** — `BedStatusService.change` is the backstop. The defect is
**existence and status disclosure** over enumerable ids, and it violates the codebase's own
stated rule: _"another hospital's bed must be indistinguishable from a missing one."_

There is also a **latent correctness bug**: because the ward is derived from the unscoped
bed, the price-difference billing block at lines 1263–1290 would read a **foreign ward's
`bed_price`** — but it is wrapped in a bare `catch (Exception)` and the flow dies at
`BedStatusService` first, so it is unreachable today. It becomes reachable if anyone ever
reorders that method. Fixing C4 removes the latent hazard as well as the disclosure.

`BedRepository.findByBedIdAndHospitalId` **already exists** (added by `baf25a7` for exactly
this class of bug on the admit path) and is unused here. The fix is a one-line substitution.

---

## 9. Domain Invariants E1 must guarantee

| #       | Invariant                                                                               | Enforcement layer                                                                                      | Failure response                                                      |
| ------- | --------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------- |
| **I-1** | A bed cannot be claimed by two admissions                                               | **DB** (`SELECT … FOR UPDATE` on the bed row) + **service** (status re-check inside the lock) + **TX** | 409 `ConflictException` "This bed was just taken"                     |
| **I-2** | Admission, bed claim, bed history, IPD number and billing commit together or not at all | **TX** — one `@Transactional` on `admitFromOpd`                                                        | whole request rolls back; caller sees the original error              |
| **I-3** | An IPD number cannot be assigned twice                                                  | **DB** (existing unique index) + **service** (bounded retry)                                           | 409 after retries exhausted, never a 500                              |
| **I-4** | A target bed belongs to the caller's hospital                                           | **Repository** (`findByBedIdAndHospitalId`)                                                            | 404 — indistinguishable from missing                                  |
| **I-5** | A target ward belongs to the caller's hospital                                          | **Service** — derive the ward from the _scoped_ bed, or load via `findByWardIdAndHospitalId`           | 404                                                                   |
| **I-6** | Existing tenant isolation is unchanged                                                  | **TEST** — `CrossTenantIsolationTest`, `AdmissionBedWardIsolationTest`, `TenantScopingArchTest`        | build fails                                                           |
| **I-7** | `IcuStayService` with `Propagation.MANDATORY` can join the movement transaction         | **TX** — I-2 supplies the transaction                                                                  | `IllegalTransactionStateException` if I-2 regresses (loud, by design) |
| **I-8** | Non-ICU IPD behaviour does not regress                                                  | **TEST** — `IpdAdmissionServiceTest`, `BedStatusServiceTest`, `WardServiceTest`, `BillingServiceTest`  | build fails                                                           |

---

## 10. Transaction Boundaries — target design

### 10.1 `admitFromOpd` (after E1)

```
POST /hospital/ipd/admit
└─ @Transactional  ◄── NEW: the boundary is the user's unit of work
   │
   │  CRITICAL DOMAIN STATE — atomic, all or nothing
   ├─ resolve tenant, OPD, ward                      (validation, no writes)
   ├─ LOCK the bed row          SELECT … FOR UPDATE  ◄── NEW (C3)
   ├─ re-check status == available INSIDE the lock   ◄── NEW (C3)
   ├─ allocate IPD number  (retry once on unique violation)  ◄── C2
   ├─ save admission
   ├─ save IpdBedHistory        (no longer swallowed) ◄── I-2 / scenario E
   ├─ bedStatusService.change(OCCUPIED)  — REQUIRED joins this TX, no new one
   ├─ back-link bed.current_ipd_admission_id
   ├─ close OPD + clear queue entry
   ├─ billing (bill + bed-price item)
   │
   │  ── ICU-3 will insert here ──
   ├─ icuStayService.onWardChanged(...)   Propagation.MANDATORY  ✅ joins safely
   │
   └─ COMMIT
      │
      │  BEST-EFFORT SIDE EFFECTS — after commit, never roll back the movement
      ├─ patientAssignmentService.onAdmission   (try/catch — unchanged)
      ├─ auditLogService.logAction              (try/catch — unchanged)
      └─ realtime refresh                       (afterCommit — unchanged)
```

### 10.2 `changeBed` (after E1)

```
PUT /hospital/ipd/{id}/bed
└─ @Transactional  (already present — unchanged)
   ├─ requireOwnedAdmission                          (already scoped)
   ├─ load target bed  findByBedIdAndHospitalId       ◄── NEW (C4)
   ├─ LOCK the target bed row                         ◄── NEW (C3)
   ├─ re-check claimable INSIDE the lock              ◄── NEW (C3)
   ├─ release old bed -> CLEANING
   ├─ claim new bed  -> OCCUPIED
   ├─ rewrite IpdBedHistory
   ├─ price-difference billing item
   └─ COMMIT  ─► audit, realtime (best-effort, unchanged)
```

### 10.3 `confirmDischarge`

Already `@Transactional`. **Not in E1 scope** — it only _releases_ a bed, which cannot race
for a resource. Listed for completeness.

### 10.4 The critical / best-effort split

| CRITICAL — inside the transaction          | BEST-EFFORT — unchanged, after commit                    |
| ------------------------------------------ | -------------------------------------------------------- |
| `ipd_admission` row                        | `AuditLog`                                               |
| `beds.status` + `current_ipd_admission_id` | `Notification`                                           |
| `ipd_bed_history` **(promoted)**           | `RealtimeNotifier` / websocket                           |
| IPD number allocation                      | `PatientAssignmentService.onAdmission`                   |
| `Billing` + `BillingItem`                  | `queueEntryRepository.deleteByOpdId` _(stays swallowed)_ |
| OPD status close                           |                                                          |
| _(ICU-3)_ `icu_stay` open/close            |                                                          |

Only **one** best-effort service is promoted (`IpdBedHistory`), and only because ICU-2's
board and length-of-stay reporting read it as a record of fact. Everything else keeps the
best-effort design it was given deliberately.

---

## 11. Implementation Order — dependency-driven

**Not C1 → C2 → C3 → C4.** The code analysis gives a different order:

```
E1.0  Repair AdmissionBedWardIsolationTest        (no production code)
        │  Nothing can be verified until the harness observes real statuses.
        │  Also converts C1 from "reasoned" to "reproduced".
        ▼
E1.1  C1 — transaction boundary on admitFromOpd
        │  MUST precede C3: a PESSIMISTIC_WRITE lock is released at commit, so
        │  without a transaction it is held for one statement and protects nothing.
        │  Also gives C2's retry something to roll back.
        ▼
E1.2  C3 — lock + re-check the bed claim in both paths
        │  Depends on E1.1. Delivers I-1.
        ▼
E1.3  C2 — single MAX read + bounded retry on the unique violation
        │  Depends on E1.1 for clean rollback between attempts.
        ▼
E1.4  C4 — scoped target-bed lookup in changeBed
           Independent of all the above; last because it is the lowest severity
           (no cross-tenant write is possible today). Could be done first if a
           separate reviewer prefers to land the security fix early.
```

**The load-bearing dependency: C3 cannot work before C1.** A `SELECT … FOR UPDATE` outside a
transaction is released immediately, so implementing C3 first would produce a lock that
appears correct in review and prevents nothing at runtime — the worst possible outcome.

---

## 12. Test Plan

### 12.1 Existing test audit

| Test                                          | Protects                                                                            | Status                           | Role after E1                                                       |
| --------------------------------------------- | ----------------------------------------------------------------------------------- | -------------------------------- | ------------------------------------------------------------------- |
| `AdmissionBedWardIsolationTest`               | C4-class tenancy on admit; C1 partial-state (`assertRefusedAndInert`, `snapshot()`) | **6 errors — test-infra** (§5.1) | **Repair first**, then primary C1 + tenancy gate                    |
| `CrossTenantIsolationTest`                    | cross-tenant admit/read/write                                                       | ✅ 12/12                         | Add C4 `changeBed` foreign-bed case                                 |
| `TenantScopingArchTest`                       | new unreviewed `findById`                                                           | ✅                               | C4 **removes** an entry (`changeBed`); allowlist edit is the review |
| `IpdAdmissionServiceTest`                     | admit/discharge service behaviour                                                   | ✅ 5/5                           | Regression gate for I-8                                             |
| `BedStatusServiceTest`                        | bed transitions + audit                                                             | ✅                               | Regression gate                                                     |
| `WardServiceTest`                             | ward/bed CRUD, ICU-2 unit types                                                     | ✅ 13/13                         | Regression gate                                                     |
| `BillingServiceTest`                          | bill totals                                                                         | ✅                               | Regression gate (billing now inside the TX)                         |
| `IpdSequenceQueryTest`                        | `findMaxIpdSequence` portability                                                    | ✅                               | Extend for C2                                                       |
| `IcuBoardServiceTest` / `IcuBoardTenancyTest` | ICU-2 read model                                                                    | ✅ 27/27                         | Must stay green — E1 must not disturb ICU-2                         |
| **Concurrency tests**                         | C2, C3                                                                              | **None exist anywhere**          | **New — the core E1 gate**                                          |

**No test in the repository currently exercises concurrency.** That is the single biggest
coverage gap E1 closes.

### 12.2 Proposed test matrix

| ID  | Test                                                                                   | Covers                    | Type        | New?                                      |
| --- | -------------------------------------------------------------------------------------- | ------------------------- | ----------- | ----------------------------------------- |
| T1  | Repaired `AdmissionBedWardIsolationTest` (6 cases)                                     | C1 partial state, tenancy | integration | repair                                    |
| T2  | `admitFromOpd` rolls back everything when billing throws                               | C1 / I-2                  | integration | **new**                                   |
| T3  | `admitFromOpd` rolls back when the bed claim throws                                    | C1 / I-2, scenario A      | integration | **new**                                   |
| T4  | Two threads admit different patients to one bed                                        | C3 / I-1                  | concurrency | **new**                                   |
| T5  | Two threads admit concurrently → distinct IPD numbers, no 500                          | C2 / I-3                  | concurrency | **new**                                   |
| T6  | Two threads `changeBed` onto the same target                                           | C3 / I-1                  | concurrency | **new**                                   |
| T7  | `changeBed` to a foreign bed → 404, nothing mutated                                    | C4 / I-4                  | integration | **new** (into `CrossTenantIsolationTest`) |
| T8  | `changeBed` to a foreign **available** bed → 404, not 400                              | C4 disclosure             | integration | **new**                                   |
| T9  | Best-effort side effects still swallowed (audit/notification throw → admission stands) | §10.4                     | unit        | **new**                                   |
| T10 | A `MANDATORY` bean invoked from `admitFromOpd` joins rather than throwing              | I-7, ICU-3 readiness      | integration | **new**                                   |

T10 is the explicit ICU-3 gate: a throwaway `@Transactional(propagation = MANDATORY)` probe
proves the transaction exists at the point where `IcuStayService` will be called.

### 12.3 Concurrency test design

Concurrency tests need a **real MySQL** — H2 is in-memory and its locking does not reproduce
InnoDB row-lock contention faithfully under the existing test profile. `AbstractMySqlIT`
already provides Testcontainers MySQL 8.0, `@Testcontainers(disabledWithoutDocker = true)`,
so these **skip rather than fail** where Docker is absent. T4–T6 should extend it.

- **TEST A (T4)** — two `CountDownLatch`-synchronised threads POST `/hospital/ipd/admit` for
  two different OPD cases against the same available bed.
  _Expected after fix:_ exactly one 2xx, one 409; `beds.status = occupied`;
  `current_ipd_admission_id` matches the winner; exactly one new `ipd_admission` row.
- **TEST B (T5)** — two simultaneous admissions to two _different_ beds.
  _Expected:_ both succeed, two distinct `ipd_number` values, no 500.
- **TEST C (T6)** — two simultaneous `changeBed` calls targeting one bed.
  _Expected:_ exactly one succeeds; the loser's admission keeps its original bed; the target
  bed's `current_ipd_admission_id` names the winner only.
- **TEST D (T7/T8)** — hospital A moves its patient onto hospital B's bed.
  _Expected:_ 404; hospital B's bed unchanged; A's admission unchanged; no `bed_status_audits`
  row written.

---

## 13. Proposed Fixes

### C1 — transaction boundary

**Current behaviour.** `@Transactional` at line 119 binds to the private `hasNursingModule()`
and is inert; `admitFromOpd` runs with no transaction, and each write commits alone.

**Root cause.** Annotation placed above a javadoc block rather than above the method, on a
method Spring cannot proxy anyway.

**Proposed fix.** Move `@Transactional` onto `admitFromOpd`; delete it from
`hasNursingModule()`. Remove the try/catch around the `IpdBedHistory` save (scenario E) so a
lost bed span fails the admission. Leave audit / notification / assignment / websocket
try/catch exactly as they are.

**Why this fix.** Smallest possible change (two annotation lines + one try/catch), matches
what `changeBed` and `confirmDischarge` already do, and creates the transaction `MANDATORY`
requires.

**Alternative considered.** A programmatic `TransactionTemplate` around the critical block.
**Rejected:** no other service in the codebase uses one, it would not read like the
surrounding code, and it offers nothing declarative `@Transactional` does not.

**Files.** `IpdAdmissionService.java`.
**DB changes.** None. **Transaction implications.** `BedStatusService.change` (`REQUIRED`)
stops opening its own transaction and joins — the intended behaviour.
**Coverage.** T1, T2, T3, T9, T10.

### C2 — IPD number allocation

**Current.** Two unlocked `MAX+1` reads, then an insert against a unique index.
**Root cause.** Read-then-write with no lock, no retry, no transaction.
**Proposed fix.** Read `findMaxIpdSequence()` **once**; wrap the allocate-and-save in a
bounded retry (2–3 attempts) that catches `DataIntegrityViolationException` for
`ipd_number` and re-reads. After the attempts, throw `ConflictException` → 409.
**Why.** No schema object, no migration; the existing unique index stays the arbiter.
**Alternatives.** Sequence table + `FOR UPDATE` (needs a migration); `AUTO_INCREMENT`
(changes `ipd_number` semantics and touches history). **Rejected as disproportionate.**
**Files.** `IpdAdmissionService.java` (+ possibly a small private helper).
**DB.** None. **Coverage.** T5, extended `IpdSequenceQueryTest`.

> Retry semantics note: the retry must re-run only the allocate+save, not the side effects.
> Simplest correct shape is to let the whole `@Transactional` method fail and retry at the
> caller — **decision D-3 below.**

### C3 — bed claim concurrency

**Current.** Check-then-act with no lock in both `admitFromOpd` and `changeBed`.
**Root cause.** Availability is a mutable row read outside any lock.
**Proposed fix.** Add to `BedRepository`, mirroring `OtRoomRepository` exactly:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT b FROM Bed b WHERE b.bedId = :id AND b.hospitalId = :hospitalId")
Optional<Bed> findByIdForUpdate(@Param("id") Long id, @Param("hospitalId") Long hospitalId);
```

(tenant-scoped in the same query, so it satisfies C4 on the admit path too). Then in both
claim paths: lock → re-check status **inside** the lock → claim. On a failed re-check throw
`ConflictException` → 409 "This bed was just taken."
**Why.** It is the pattern this repository already chose for the same class of problem, with
the same justification, and it is testable on the existing MySQL Testcontainers base.
**Alternatives.** (a) Optimistic locking via `@Version` on `Bed` — **rejected:** adds a
column and a migration, and turns a preventable conflict into a retry storm across every
existing bed writer. (b) Unique index on `current_ipd_admission_id` — **rejected:** it is
nullable and reused, and it would not stop two claims on an `available` bed.
**Files.** `BedRepository.java`, `IpdAdmissionService.java`, possibly `BedStatusService.java`.
**DB.** None. **Transaction implications.** Requires C1. Lock is held to commit; the locked
region must stay short — do the billing work after the claim.
**Coverage.** T4, T6.

### C4 — `changeBed` tenant scoping

**Current.** `bedRepository.findById(newBedId)` with no tenant filter; the ward is then
derived from that foreign bed.
**Root cause.** A client-supplied id resolved without the tenant, in the one movement path
`baf25a7` did not repair.
**Proposed fix.** Use `findByBedIdAndHospitalId(newBedId, hospitalId)` — or the C3 locking
finder, which is already scoped — and derive/verify the target ward via
`findByWardIdAndHospitalId`. Foreign → `ResourceNotFoundException` (404).
**Why.** One-line substitution using a finder that already exists for exactly this purpose.
**Alternative.** A `@PreAuthorize` check. **Rejected:** wrong layer, and it produces 403,
which discloses existence — the opposite of the codebase's stated rule.
**Files.** `IpdAdmissionService.java` (+ `TenantScopingArchTest` allowlist entry removed).
**DB.** None. **Coverage.** T7, T8.

**Total production surface: `IpdAdmissionService.java`, `BedRepository.java`, possibly
`BedStatusService.java` — plus test files. No migration, no schema change, no frontend.**

---

## 14. Manual Validation Plan

Run after implementation, in order. Two browser sessions / two API clients required for the
concurrency cases.

---

**M-1 — Happy path admission**
_Pre:_ hospital with IPD; an OPD case marked admit-recommended; a ward with an incharge and ≥1 `available` bed.
_Steps:_ Reception → admit the OPD case to that ward/bed.
_Expected:_ 2xx; `ipd_admission` row with the next `IPD-n`; bed → `occupied` with
`current_ipd_admission_id` set; one `ipd_bed_history` row with `released_at` null; one
`Billing` with the bed-price item; one `bed_status_audits` row.
_Fail if:_ any of the above is missing or the bed stays `available`.
_Evidence:_ API response; `SELECT * FROM ipd_admission ORDER BY id DESC LIMIT 1;` and the
matching `beds`, `ipd_bed_history`, `billing`, `bed_status_audits` rows.

---

**M-2 — Rollback on a mid-flow failure**
_Pre:_ note `SELECT COUNT(*)` for `ipd_admission`, `billing`, `ipd_bed_history`; note the bed's status.
_Steps:_ force a failure after the bed claim (temporarily point the ward's `bed_price` at a
value that breaks the billing insert, or run the admit against a ward whose bill row cannot
be created). Attempt the admission.
_Expected:_ error response; **all four counts unchanged**; bed still `available`; **no**
`ipd_number` consumed (the next successful admission gets the number this attempt would have).
_Fail if:_ an `ipd_admission` row exists, or the bed is `occupied`, or the sequence advanced.
_Evidence:_ before/after counts; the bed row; the next admission's `ipd_number`.

---

**M-3 — Same-bed concurrency**
_Pre:_ two OPD cases in the same hospital; one ward with exactly **one** `available` bed.
_Steps:_ fire both admit requests within ~50 ms (two terminals, or a two-thread script).
_Expected:_ exactly **one** 2xx and one **409**; exactly one new `ipd_admission`; the bed is
`occupied` and `current_ipd_admission_id` equals the winner's id.
_Fail if:_ both succeed, or two admissions reference the same `bed_id`, or the loser gets a 500.
_Evidence:_ both HTTP statuses and bodies; `SELECT id, ipd_number, bed_id FROM ipd_admission
WHERE bed_id = <bed>;` (must return one row); the `beds` row.

---

**M-4 — Duplicate IPD number race**
_Pre:_ two OPD cases; **two different** available beds.
_Steps:_ fire both admissions simultaneously.
_Expected:_ **both succeed**; two distinct `ipd_number` values; **no 500 anywhere**.
_Fail if:_ either returns 500, or the two numbers are equal, or one is skipped without a
corresponding rollback.
_Evidence:_ both responses; `SELECT ipd_number FROM ipd_admission ORDER BY id DESC LIMIT 2;`

---

**M-5 — `changeBed` concurrency**
_Pre:_ two admitted patients in different beds; one third `available` bed.
_Steps:_ fire two transfer requests, both targeting the third bed, simultaneously.
_Expected:_ exactly one succeeds; the loser gets 409 and **keeps its original bed**; the
target bed's `current_ipd_admission_id` names the winner only; two `ipd_bed_history` rows for
the winner (old closed, new open), none for the loser.
_Fail if:_ both succeed, or the loser's admission points at a bed it does not occupy.
_Evidence:_ both statuses; `ipd_admission.bed_id` for both patients; `ipd_bed_history` rows.

---

**M-6 — Cross-tenant target bed**
_Pre:_ hospitals A and B; an admitted patient in A; note the id of an **available** bed in B.
_Steps:_ as an A user, transfer the A patient onto B's bed id.
_Expected:_ **404**, with the same body a non-existent bed id produces.
_Fail if:_ 400 "already occupied", 403, 200, or any mutation.
_Evidence:_ response status **and body** for the foreign id and for a random non-existent id
— they must be byte-identical; B's bed row unchanged; no new `bed_status_audits` row for B.

---

**M-7 — Cross-tenant foreign ward**
_Pre:_ same as M-6, using B's **ward** id in an admit request from A.
_Expected:_ 404; no `ipd_admission` row created in either tenant.
_Fail if:_ an admission row exists anywhere afterwards.
_Evidence:_ response; `SELECT COUNT(*) FROM ipd_admission;` before and after.

---

**M-8 — Existing regression sweep**
_Steps:_ `cd backend && mvn test`.
_Expected:_ **0 failures, 0 errors** — including all six `AdmissionBedWardIsolationTest`
cases and the 27 ICU-2 tests.
_Evidence:_ the surefire summary line.

---

**M-9 — ICU-3 readiness (`Propagation.MANDATORY`)**
_Steps:_ run T10 — a probe bean annotated `@Transactional(propagation = Propagation.MANDATORY)`
invoked from inside `admitFromOpd`.
_Expected:_ it executes; **no** `IllegalTransactionStateException`.
_Fail if:_ it throws — E1 has not delivered the transaction ICU-3 needs, and ICU-3 stays blocked.
_Evidence:_ the test result plus the log line proving the probe ran inside the admission.

---

**M-10 — ICU-2 untouched**
_Steps:_ open the ICU dashboard and bed board; admit a patient to an ICU bed; refresh.
_Expected:_ counts still match; the new patient appears on the correct bed;
`occupancyConsistent` is true for every row.
_Fail if:_ any mismatch flag appears on a healthy tenant — that would mean E1 changed the
records ICU-2 reads.
_Evidence:_ screenshot of the board plus the `GET /hospital/icu/board` payload.

---

## 15. Scope / Non-Scope

### E1 WILL change

- `IpdAdmissionService` — transaction boundary, bed lock + re-check, IPD number allocation, scoped target-bed lookup.
- `BedRepository` — one locking finder.
- `BedStatusService` — only if the lock is best placed there (decision D-2).
- `AdmissionBedWardIsolationTest` — transport + fixture repair.
- `CrossTenantIsolationTest`, `TenantScopingArchTest` — cases and allowlist.
- New concurrency and rollback tests.

### E1 WILL NOT change

ICU clinical chart · `IcuStay` (anything) · ICU dashboard or bed board · ICU code of any kind
· vitals · I/O · ventilator · medication · nursing workflow · billing redesign (billing moves
_inside_ the existing transaction; no logic, schema or amount changes) · pharmacy · OT ·
`confirmDischarge` logic · `getAvailableBeds` · bed status _values_ · frontend · migrations ·
schema · CI config · global error handling (existing `ConflictException` → 409 is reused, not
extended) · `ipd_number` per-tenant scoping (recorded in §6.2, out of scope) · the
best-effort design of audit, notification, realtime and nurse assignment.

---

## 16. Risks and Dependencies

| Risk                                                                                                | Likelihood          | Mitigation                                                                          |
| --------------------------------------------------------------------------------------------------- | ------------------- | ----------------------------------------------------------------------------------- |
| The repaired `AdmissionBedWardIsolationTest` reveals a **real** C1 failure                          | **High — expected** | That is the point of E1.0. Record it as the C1 reproduction before fixing.          |
| Making `admitFromOpd` transactional surfaces a previously-swallowed error as a user-visible failure | Medium              | Correct behaviour, but review each promoted write; only `IpdBedHistory` is promoted |
| `PESSIMISTIC_WRITE` lock contention slows admissions                                                | Low                 | The lock is one row, held for milliseconds; keep billing outside the locked region  |
| H2 cannot reproduce InnoDB contention                                                               | Medium              | Concurrency tests extend `AbstractMySqlIT` (Testcontainers, skips without Docker)   |
| A longer transaction holds a connection under load                                                  | Low                 | Hikari default pool; the added work is a few statements                             |
| Billing inside the transaction changes bill timing                                                  | Low                 | Same statements, same order — only the commit point moves                           |
| E1 disturbs ICU-2's read model                                                                      | Low                 | ICU-2 is read-only; M-10 + the 27 ICU tests are the gate                            |

**Dependencies:** C3 depends on C1 (§11). ICU-3 depends on all of E1. Docker is required to
_run_ the concurrency tests (they skip without it — confirm before treating a green build as
proof).

---

## 17. E1 Definition of Done

E1 is complete only when **all** of the following hold:

1. C1 resolved — `admitFromOpd` transactional; verified by T2/T3.
2. C2 resolved — no duplicate `ipd_number`, no 500 under concurrency; verified by T5.
3. C3 resolved — one bed, one admission under concurrency; verified by T4/T6.
4. C4 resolved — foreign bed is 404 and indistinguishable from missing; verified by T7/T8.
5. `AdmissionBedWardIsolationTest` **passes 6/6**.
6. All existing regression tests pass — `mvn test`: **0 failures, 0 errors**.
7. Concurrency tests pass against real MySQL (Docker present — a skip is **not** a pass).
8. Cross-tenant tests pass; `TenantScopingArchTest` allowlist edited deliberately.
9. **T10 proves a `MANDATORY` bean joins the admission transaction** — the ICU-3 gate.
10. ICU-2's 27 tests still pass; the board shows no new mismatches (M-10).
11. Backend build passes.
12. `git diff --check` clean.
13. Manual validation M-1 … M-10 executed and evidence captured.
14. Diff reviewed.
15. Local commit created.
16. Checkpoint report produced in the project format.
17. **Nothing pushed.**

---

## 18. Decisions Required

| #       | Decision                                                                                     | Recommendation                                                                                                                                                   |
| ------- | -------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **D-1** | Implementation order **C3 → C1** or **C1 → C3**?                                             | **C1 first** (§11) — a lock without a transaction protects nothing                                                                                               |
| **D-2** | Where does the bed lock live — `IpdAdmissionService` or a new `BedStatusService.claim(...)`? | **`BedStatusService`**, mirroring how `OtSchedulingService` owns `lockRoom`; it is already the single bed writer                                                 |
| **D-3** | C2 retry: inside the method, or let the transaction fail and retry at the controller?        | **Retry at the boundary** (`@Retryable`-style or an explicit loop wrapping the transactional call) — retrying inside a rolled-back transaction does not work     |
| **D-4** | Promote `IpdBedHistory` from best-effort to critical?                                        | **Yes** — ICU-2 and LOS reporting read it as fact                                                                                                                |
| **D-5** | Concurrency tests on Testcontainers MySQL (skip without Docker) or H2-only?                  | **Testcontainers** — H2 will not reproduce the race, and a green H2 run would be false assurance                                                                 |
| **D-6** | Conflict response code for a lost bed race                                                   | **409 `ConflictException`** — already mapped by `GlobalExceptionHandler` (`dfd599b`)                                                                             |
| **D-7** | `ipd_number` is globally unique across tenants (§6.2). Address in E1?                        | **No** — data-model change, separate checkpoint                                                                                                                  |
| **D-8** | Also swap `admitFromOpd`'s direct `webSocketHandler.broadcast` for `RealtimeNotifier`?       | **Optional.** It is a 1-line correctness improvement (after-commit ordering) squarely inside the touched method. Your call — I will leave it out unless approved |

---

## 19. Recommended Next Checkpoint

**E1 implementation**, in the order of §11, starting with **E1.0 — repair
`AdmissionBedWardIsolationTest`** and reporting what the repaired test actually says about
C1 **before** any production line is changed.

**E1 is ready for implementation approval**, subject to D-1 … D-8. No production code, no
migration, no test and no ICU code was changed in this checkpoint.
