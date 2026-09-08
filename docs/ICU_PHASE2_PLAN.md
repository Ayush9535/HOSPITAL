# ICU Phase 2 — ICU Dashboard & Bed Board

**Phase:** ICU-2 (AUDIT → DESIGN → PLAN)
**Date:** 2026-08-25
**Branch:** `icu`
**Status:** Audit + plan — **awaiting review and approval. No code written.**
**Inputs:** [ICU_EXISTING_SYSTEM_AUDIT.md](ICU_EXISTING_SYSTEM_AUDIT.md) (ICU-0),
[ICU_SYSTEM_DESIGN.md](ICU_SYSTEM_DESIGN.md) r2 (ICU-1)

**Objective:** a fast operational view of ICU capacity and current patients.

> **Scope note.** ICU-1 r2 §12 assumed ICU-2 would be the ICU Patient Chart. The approved
> ICU-2 objective is the **Dashboard & Bed Board** instead. The chart and its clinical
> tables move to ICU-3+. ICU-1 §12's two prerequisites (`VitalsRecord` append-only,
> `IO_CHART` reconciliation) travel with the chart and are **not** ICU-2 work. This
> reordering is an improvement: ICU-2 is now entirely **read-only**, which changes its risk
> profile substantially (§11).

---

# PART A — AUDIT

## A1. Existing architecture relevant to this checkpoint

The system already contains **two working bed boards and one working ward-scoped
dashboard**. ICU-2 is therefore not new construction; it is a third view over the same
data, filtered to critical-care wards.

| Layer                     | What exists today                                                                                                                                            |
| ------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Bed occupancy state       | `beds.status` (4 states) + `beds.current_ipd_admission_id`; **all** writes funnel through `BedStatusService.change` and are audited into `bed_status_audits` |
| Patient-in-bed state      | `ipd_admission.ward_id` / `bed_id` / `status`, written by `IpdAdmissionService`                                                                              |
| Ward-scoped aggregation   | `NurseWorkspaceService.getInchargeDashboard()` → `NurseInchargeDashboardDTO`                                                                                 |
| Hospital-wide aggregation | `HospitalStatsService.getStats()` — **Redis-cached** (§A10)                                                                                                  |
| Bed board UI (incharge)   | `nurse-incharge/WardBedsView.jsx` — 345 lines, ward selector + grid + badges + actions + history                                                             |
| Bed board UI (admin)      | `WardsAndBeds.jsx` + `components/BedListDrawer.jsx` — thin, list-only                                                                                        |
| Ward scope RBAC           | `NurseInchargeGuard.myWardIds()`, `NurseAccessGuard`                                                                                                         |
| Live refresh              | `RealtimeNotifier.refresh(hospitalId)` → `useWebSocket` → dashboards refetch                                                                                 |
| Module gating             | `@RequireModule`, `ModuleAccessAspect`, `EntitlementRegistry`, `ControllerModules`                                                                           |

**Occupancy is already represented in two rows** — `beds.status`/`current_ipd_admission_id`
and `ipd_admission.ward_id`/`bed_id`. That is pre-existing, not something ICU introduces,
and it directly shapes the "no double representation" acceptance gate (§A11, §D4).

## A2. Relevant entities

| Entity           | Fields that matter here                                                                                             | Note                                             |
| ---------------- | ------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------ |
| `Ward`           | `wardId`, `hospitalId`, `wardName`, `bedPrice`, `totalBeds`, `floorNumber`, `inchargeNurseId`                       | **No unit/type column** — the single schema gap  |
| `Bed`            | `bedId`, `hospitalId`, `wardId`, `bedCode`, `status`, `currentIpdAdmissionId`                                       | occupancy source                                 |
| `BedStatus`      | `available`, `occupied`, `cleaning`, `maintenance`                                                                  | **no `reserved`, no `isolation`**                |
| `IpdAdmission`   | `patientId`, `doctorId`, `wardId`, `bedId`, `status`, `admissionDatetime`, `primaryDiagnosis`, `admissionConfirmed` | patient + consultant                             |
| `Patient`        | `name`, `getAge()`, `gender`, `customId`                                                                            | display                                          |
| `Doctor`         | `name`                                                                                                              | consultant column                                |
| `VitalsRecord`   | `spo2`, `respiratoryRate`, `pulse`, `bpSystolic/Diastolic`, `temperature`, `painScore`, `recordedAt`                | **the only respiratory data in the system**      |
| `BedStatusAudit` | previous/new status, changedBy, remarks                                                                             | history                                          |
| `Surgery`        | active statuses                                                                                                     | already surfaced in `MyPatientDTO.surgeryStatus` |

**Absent everywhere** (verified by full-source search): ventilator, oxygen-delivery mode,
severity/triage score, isolation flag, reserved-bed state. See §A9 and §D3.

## A3. Repositories

| Repository                                                          | Existing finders relevant to ICU-2                                                                        | Gap                                           |
| ------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------- | --------------------------------------------- |
| `BedRepository`                                                     | `findByWardIdAndHospitalId`, `findByHospitalIdAndStatus`, `findByHospitalId`, `findByBedIdAndHospitalId`  | no "beds in this set of wards" finder         |
| `WardRepository`                                                    | `findByHospitalId`, `findByWardIdAndHospitalId`, `findByHospitalIdAndInchargeNurseId`                     | no filter by unit type                        |
| `IpdAdmissionRepository`                                            | `findByHospitalIdAndStatus`, `findByHospitalIdAndStatusIn`, `findByHospitalIdAndAdmissionDatetimeBetween` | no ward-set filter                            |
| `VitalsRecordRepository`                                            | `findByIpdAdmissionIdAndIsActiveTrueOrderByRecordedAtDesc`                                                | no "latest per admission, batched" — N+1 risk |
| `PatientRepository`, `DoctorRepository`, `BedStatusAuditRepository` | standard                                                                                                  | —                                             |

All are `hospitalId`-scoped already. Good baseline.

## A4. Services

| Service                                                        | Relevance                                                                                                                                                                                                                 |
| -------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`NurseWorkspaceService.getInchargeDashboard()`**             | **The closest existing analogue.** Already computes, ward-scoped: bed totals by all four statuses, admitted-patient total, new admissions today, discharges today, nurse counts + attendance. Not cached — computed live. |
| `NurseWorkspaceService.getWardPatients()` / `buildMyPatient()` | Patient row assembly: name, age, gender, ward, bed, doctor, diagnosis, surgery status                                                                                                                                     |
| `NurseWorkspaceService.getMyWards()`                           | Wards + their beds for the incharge board                                                                                                                                                                                 |
| `BedStatusService`                                             | The only legitimate bed-status writer; also `history(bedId)`                                                                                                                                                              |
| `BedService`                                                   | `getAvailableBeds`, `updateStatus` (UI restricted to `maintenance → available`)                                                                                                                                           |
| `WardService`                                                  | ward CRUD, `getWardsForAdmission`, `setIncharge`; `WardResponse` mapping                                                                                                                                                  |
| `HospitalStatsService`                                         | hospital-wide counters — **`@Cacheable("hospitalStats")`**                                                                                                                                                                |
| `RealtimeNotifier`                                             | after-commit tenant broadcast                                                                                                                                                                                             |

## A5. Controllers

| Controller                                             | Path(s)                                                | Notes for ICU-2                                             |
| ------------------------------------------------------ | ------------------------------------------------------ | ----------------------------------------------------------- |
| `BedController`                                        | `/hospital/beds`, **`/clinic/beds`, `/pharmacy/beds`** | Aliased → **in `ClinicPharmacyIsolationTest`'s golden set** |
| `WardController`                                       | `/hospital/wards` (+ aliases)                          | ward CRUD, `/{id}/beds`                                     |
| `NurseInchargeController` / `NurseWorkspaceController` | `/hospital/nurse-incharge/**`                          | serves the incharge dashboard + `my-wards`                  |
| `HospitalStatsController`                              | `/hospital/stats` (+ aliases)                          | `@PreAuthorize` ADMIN/RECEPTIONIST/DOCTOR                   |
| `IpdAdmissionController`                               | `/hospital/ipd/**`                                     | the chart target                                            |

## A6. Frontend components

| File                                                               | Lines                                                                                                               | Relevance                                                                                            |
| ------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| `pages/hospital/nurse-incharge/WardBedsView.jsx`                   | 345                                                                                                                 | **Best reuse candidate** — ward selector, bed grid, `STATUS_META` badge map, action + history modals |
| `pages/hospital/NurseInchargeDashboard.jsx`                        | —                                                                                                                   | stat-card dashboard + 8-tab shell                                                                    |
| `pages/hospital/WardsAndBeds.jsx` + `components/BedListDrawer.jsx` | 155 + 89                                                                                                            | admin bed list                                                                                       |
| `components/WardModal.jsx`                                         | —                                                                                                                   | ward create/edit form (`wardName`, `bedPrice`, `totalBeds`, `floorNumber`)                           |
| `components/WardCard.jsx`                                          | —                                                                                                                   | ward tile                                                                                            |
| **`pages/hospital/HospitalAdminDashboard.jsx`**                    | **10,055**                                                                                                          | `allTabs` (L1902), `SIDEBAR_GROUPS` (L2013), render switch (L~1013)                                  |
| `pages/hospital/IpdDetails.jsx`                                    | 2,124                                                                                                               | the current patient workspace; **also holds a duplicate `getSidebarTabs()` (L140)**                  |
| Shared                                                             | `DataTable`, `StatusBadge`, `EmptyState`, `LoadingSpinner`, `PageHeader`, `Sidebar`, `ToastContext`, `useWebSocket` | reuse as-is                                                                                          |
| Services                                                           | `wardService.js`, `nurseService.js`, `hospitalService.js`, `apiService.js`                                          | pattern to follow                                                                                    |
| Routing                                                            | `/ipd/:id` → `IpdDetails` (lazy)                                                                                    | the bed-click target                                                                                 |

## A7. Existing tests

| Test                                                                                    | Relevance                                                                                                                                            |
| --------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- |
| `security/CrossTenantIsolationTest`                                                     | **The gate for acceptance #1.** Real two-hospital JWT harness; ICU read cases go here                                                                |
| `security/AdmissionBedWardIsolationTest`                                                | Real-DB bed/ward tenancy — the pattern to copy                                                                                                       |
| `security/TenantScopingArchTest`                                                        | Frozen ~140-entry allowlist of repository lookup-by-id; **build fails on a new one**                                                                 |
| `security/ClinicPharmacyIsolationTest`                                                  | Frozen golden set of `/clinic`+`/pharmacy` controllers — ICU must **not** appear                                                                     |
| `security/ModuleAccessAspectTest`, `FacilityAccessAspectTest`, `TenantTypeAspectTest`   | module/tenant gating                                                                                                                                 |
| `service/hospital/WardServiceTest`, `BedStatusServiceTest`, `NurseWorkspaceServiceTest` | direct analogues for the new service test                                                                                                            |
| Frontend                                                                                | **vitest + @testing-library/react**; 14 test files, component-level only (`StatusBadge.test.jsx`, `NotePresetsManager.test.jsx`) — no page tests yet |

## A8. What can be reused

**Backend**

1. **All bed occupancy state** — `beds.status` + `current_ipd_admission_id`. No second store (acceptance #4).
2. **`NurseInchargeDashboardDTO`'s shape** — bed counts ×4 + patient counts. Mirror it.
3. **The aggregation logic** in `getInchargeDashboard()` — same loops, ICU ward filter.
4. **Row assembly** from `buildMyPatient()` — patient, ward, bed, doctor, diagnosis, surgery.
5. **Tenancy** — `SecurityContextHelper`, scoped finders, 404-not-403.
6. **Ward scope** — `NurseInchargeGuard.myWardIds()` unchanged (ICU units are wards).
7. **Live refresh** — `RealtimeNotifier` already fires on every bed change; the ICU board needs **zero** new broadcast code.
8. **Module gating** — `@RequireModule`, `@TenantType`, `EntitlementRegistry`.

**Frontend** 9. **`WardBedsView.jsx` as the structural template** — selector + grid + `STATUS_META`. 10. `DataTable`, `StatusBadge`, `EmptyState`, `LoadingSpinner`, `PageHeader`, `useWebSocket`, `ToastContext`. 11. `/ipd/:id` route — the existing patient workspace is the bed-click target (§D5). 12. Service-module pattern (`wardService.js` → `icuService.js`).

## A9. What must be added

**One schema object only.**

| #   | Addition                                                     | Why nothing existing works                                                                                                                  |
| --- | ------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | **`wards.unit_type VARCHAR(20) NOT NULL DEFAULT 'GENERAL'`** | ICU-1 D1/D2. Nothing distinguishes an ICU ward from a general one. Name-matching is explicitly rejected (R2 — the OT "FOOT WARD" precedent) |
| 2   | `CareUnitRegistry` (Java)                                    | Java-registry convention; 8 keys, `isCriticalCare()`                                                                                        |
| 3   | ICU board read service + DTOs                                | `getInchargeDashboard` is incharge-scoped and ward-agnostic; it cannot serve an admin ICU view                                              |
| 4   | ICU read controller                                          | new namespace, hospital-only, module-gated                                                                                                  |
| 5   | Two repository finders                                       | `findByHospitalIdAndWardIdIn` on beds and admissions — to avoid the existing full-scan-and-filter-in-Java pattern                           |
| 6   | ICU dashboard + bed board UI                                 | new views, new files (**not** inside the 10k-line dashboard)                                                                                |
| 7   | `unitType` on ward DTOs + `WardModal`                        | so an admin can classify a ward                                                                                                             |

**Explicitly NOT added in ICU-2:** `icu_stay` (§D2), `icu_unit_profile` (ICU-1 §5.4),
any bed status value, any second occupancy store, any write endpoint.

**Requested features with no data behind them** — verified absent by full-source search:

| Requested                   | Status                                                    | Recommendation                                                                                        |
| --------------------------- | --------------------------------------------------------- | ----------------------------------------------------------------------------------------------------- |
| Ventilator status           | **No data anywhere**                                      | Omit the column in ICU-2; lands with the ventilator table in ICU-3                                    |
| Oxygen status               | **No data anywhere**                                      | Show **latest SpO₂ + respiratory rate** from `VitalsRecord` — real recorded values, no interpretation |
| Severity / high-risk marker | **No score anywhere**                                     | **Omit** (§D3) — deriving one is clinical interpretation                                              |
| Reserved beds               | Not a `BedStatus` value                                   | **Escalate** (§A13/E5)                                                                                |
| Isolation beds              | No flag anywhere                                          | **Escalate** (§A13/E5)                                                                                |
| New admissions              | ✅ `admissionDatetime`                                    | include                                                                                               |
| Pending work                | ✅ `admissionConfirmed = false`, `beds.status = cleaning` | include                                                                                               |

This is what "where data exists" resolves to on inspection.

## A10. Security / tenant risks

| #   | Risk                                                                                                                                                                      | Mitigation in the plan                                                                            |
| --- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------- |
| S1  | **Cross-tenant ICU bed read** (acceptance #1). Bed and ward ids are sequential and enumerable                                                                             | Every read via `…AndHospitalId` finders; foreign → **404**; new `CrossTenantIsolationTest` cases  |
| S2  | **`TenantScopingArchTest` build failure.** Any new `findById` fails the frozen allowlist                                                                                  | Design the service with **no** bare `findById`; if unavoidable, allowlist edit is the review gate |
| S3  | **Clinic/pharmacy leakage.** `BedController` is `/clinic`+`/pharmacy` aliased; copying it would expose ICU                                                                | ICU controller: **no alias**, `@TenantType(HOSPITAL)`, absent from `ClinicPharmacyIsolationTest`  |
| S4  | **Undeclared controller bypass (T1).** `FacilityAccessAspect` treats a `null` module lookup as _allowed_; the documented `EntitlementRegistryArchTest` **does not exist** | Declare in `ControllerModules` — mandatory, on the checklist                                      |
| S5  | **Raw entity leakage.** `getMyWards()` returns raw `Bed` entities, exposing `hospitalId`/`currentIpdAdmissionId`                                                          | ICU returns DTOs only; do not copy that pattern                                                   |
| S6  | **Ward-scope bypass.** An incharge/nurse could read ICU wards they don't cover                                                                                            | `NurseInchargeGuard.myWardIds()` intersection for non-admin roles                                 |
| S7  | **Patient data broadened.** The board shows names + diagnoses on one screen                                                                                               | Row-level scope per role (§D6); diagnosis omitted for roles without patient scope                 |

## A11. Transaction / concurrency risks

**ICU-2 is read-only.** It creates no write path, so **E1 (C1–C4) does not block ICU-2** —
a meaningful change from ICU-1, where E1 blocks ICU-3.

| #   | Risk                                                                                                                                                                                                   | Mitigation                                                                                                                                                                                                             |
| --- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| T1  | **Torn read.** Bed rows and admission rows are read separately; a concurrent transfer can produce counts that don't match rows — acceptance #2                                                         | **One `@Transactional(readOnly = true)` per dashboard request**, so all reads share a single InnoDB REPEATABLE-READ snapshot                                                                                           |
| T2  | **Stale cache.** `HospitalStatsService.getStats` is `@Cacheable("hospitalStats")` and is evicted **only** on patient writes and a websocket hook — **not on bed status changes**                       | **ICU counts must not go into `hospitalStats`.** Compute live. Putting them there would fail acceptance #2 outright                                                                                                    |
| T3  | **`current_ipd_admission_id` divergence.** `admitFromOpd` is non-transactional (C1), so a failure mid-flow can leave an occupied bed with a null pointer, or an admission whose bed doesn't point back | Derive the patient from **`IpdAdmission`** (status + `ward_id`/`bed_id`) as primary; use `beds.status` for the bed's own state; **surface divergence as a visible "needs attention" flag rather than hiding it** (§D4) |
| T4  | **N+1 on vitals.** Latest SpO₂ per patient × N beds                                                                                                                                                    | Batch: one query per unit, or drop vitals from the row and load on hover/expand                                                                                                                                        |
| T5  | **Full-scan aggregation.** `getInchargeDashboard`/`getWardPatients` load all hospital admissions and filter in Java                                                                                    | Add the two ward-set finders (§A9 #5) rather than copying that pattern                                                                                                                                                 |
| T6  | **No new locking.** ICU-1 TB6 forbids ICU adding a locking scheme to `beds`                                                                                                                            | Read-only; nothing to lock                                                                                                                                                                                             |

## A12. Exact proposed files

**Backend — new (9)**

```
backend/src/main/java/com/hms/service/hospital/icu/CareUnitRegistry.java
backend/src/main/java/com/hms/service/hospital/icu/IcuBoardService.java
backend/src/main/java/com/hms/controller/hospital/IcuDashboardController.java
backend/src/main/java/com/hms/dto/icu/IcuDashboardDTO.java
backend/src/main/java/com/hms/dto/icu/IcuUnitSummaryDTO.java
backend/src/main/java/com/hms/dto/icu/IcuBedRowDTO.java
backend/src/test/java/com/hms/service/hospital/icu/IcuBoardServiceTest.java
backend/src/test/java/com/hms/service/hospital/icu/CareUnitRegistryTest.java
backend/src/test/java/com/hms/security/IcuBoardTenancyTest.java
```

**Backend — modified (11)**

```
backend/src/main/java/com/hms/entity/Ward.java                       + unitType
backend/src/main/java/com/hms/dto/WardResponse.java                  + unitType
backend/src/main/java/com/hms/dto/CreateWardRequest.java             + unitType
backend/src/main/java/com/hms/dto/UpdateWardRequest.java             + unitType
backend/src/main/java/com/hms/service/hospital/WardService.java      map/validate + I11 guard
backend/src/main/java/com/hms/repository/BedRepository.java          + findByHospitalIdAndWardIdIn
backend/src/main/java/com/hms/repository/IpdAdmissionRepository.java + findByHospitalIdAndStatusInAndWardIdIn
backend/src/main/java/com/hms/entitlement/EntitlementRegistry.java   + ICU constant + SELLABLE[HOSPITAL]
backend/src/main/java/com/hms/entitlement/ControllerModules.java     + IcuDashboardController  (MANDATORY — S4)
backend/src/main/java/com/hms/config/DatabaseMigrationRunner.java    + ensureWardUnitTypeColumn()
setup/schema-full.sql                                                 mirror the DDL
```

> **Implemented differently — recorded 2026-08-25 (gap G6).** The ICU cross-tenant cases were
> **not** added to `CrossTenantIsolationTest`. They live in a dedicated
> `security/IcuBoardTenancyTest` (7 cases) instead, built on the same real-database two-tenant
> harness. Two reasons: `TenantScopingArchTest`'s obligation to extend
> `CrossTenantIsolationTest` triggers only when a repository lookup-by-id is added, and
> `IcuBoardService` deliberately uses none; and that class's fixture has no ICU-typed ward, so
> covering ICU there would have meant reshaping a frozen shared security test for a feature it
> does not otherwise touch. `CrossTenantIsolationTest` is therefore **unchanged**, and ICU
> tenancy is covered at equal strength in its own file. No test architecture was rewritten.

**Backend tests — modified (3)**

```
backend/src/test/java/com/hms/security/CrossTenantIsolationTest.java  + ICU read cases (acceptance #1)
backend/src/test/java/com/hms/security/TenantScopingArchTest.java     only if a lookup-by-id is unavoidable
backend/src/test/java/com/hms/service/hospital/WardServiceTest.java   + unit_type + I11 guard
```

`ClinicPharmacyIsolationTest` — **unchanged, deliberately** (S3).
`CrossTenantIsolationTest` — **unchanged**; see the G6 note above.

**Frontend — new (5)**

```
frontend/src/pages/hospital/icu/IcuDashboard.jsx
frontend/src/pages/hospital/icu/IcuBedBoard.jsx
frontend/src/services/icuService.js
frontend/src/pages/hospital/icu/IcuDashboard.test.jsx
frontend/src/pages/hospital/icu/IcuBedBoard.test.jsx
```

**Frontend — modified (5)**

```
frontend/src/pages/hospital/HospitalAdminDashboard.jsx   allTabs L1902 + SIDEBAR_GROUPS L2013 + render switch
frontend/src/pages/hospital/IpdDetails.jsx               getSidebarTabs L140 — DUPLICATE list, must match
frontend/src/pages/hospital/NurseInchargeDashboard.jsx   + ICU tab
frontend/src/components/WardModal.jsx                    + unit-type selector
frontend/src/components/PlansTab.jsx                     + ICU in AVAILABLE_MODULES
```

**Totals: 14 new backend/test, 14 modified, 5 new frontend, 5 modified frontend.**

> **Reported, not fixed:** the admin sidebar tab list is **duplicated** between
> `HospitalAdminDashboard.jsx` (L1902) and `IpdDetails.jsx` (L140). Adding one tab means
> editing both, and they can silently drift. De-duplicating is an unrelated refactor —
> reported here, not performed.

## A13. Outside my ownership — escalations

**E5 — NEW. Reserved / isolation beds require changing the bed status lifecycle.**
`BedStatus` has exactly four values; adding `RESERVED` or an isolation flag would touch
`BedStatus.isValid`, `BedService.isValidStatus`, `BedStatusService.change`,
`bed_status_audits` semantics, `getAvailableBeds`, `admitFromOpd`'s availability check,
`WardService.getWardsForAdmission`, the incharge board, and every existing consumer. That
is **shared IPD/nursing bed architecture**, not ICU-owned. **Requesting a decision:** omit
from ICU-2 (recommended), or schedule as a separate bed-lifecycle checkpoint.

**E1 — C1–C4 — not blocking for ICU-2** (read-only), still blocking for ICU-3. T3 above is
a visible _symptom_ of C1 that the board will surface rather than mask.

**E3 — `EntitlementRegistryArchTest` still missing.** ICU-2 works around it by declaring
the controller manually (S4). Recreating the fence remains a security-fence change.

**E2 (`lab_orders`)** and **E4 (no new role)** — unchanged, not touched by ICU-2.

---

# PART B — DESIGN & PLAN

## D1. Shape

`GET /hospital/icu/board` returns **one payload** driving both views: hospital-level
totals, a per-unit array, and a per-bed array. One request, one snapshot, one set of
numbers — the dashboard and the bed board cannot disagree because they are the same
response (acceptance #2).

```
IcuDashboardDTO
├── totals        : total, occupied, available, cleaning, maintenance,
│                   patients, newAdmissionsToday, pendingConfirmation
├── units[]       : IcuUnitSummaryDTO { wardId, wardName, unitType, unitTypeLabel,
│                                       + the same count block }
└── beds[]        : IcuBedRowDTO
```

`IcuBedRowDTO` — the bed row required by the objective:

| Field                                                         | Source                                                        | Note                                                   |
| ------------------------------------------------------------- | ------------------------------------------------------------- | ------------------------------------------------------ |
| `bedId`, `bedCode`, `wardId`, `wardName`, `unitType`          | `Bed` + `Ward`                                                |                                                        |
| `status`                                                      | `beds.status`                                                 | the existing 4 states — **no second store**            |
| `ipdAdmissionId`, `ipdNumber`, `patientName`, `age`, `gender` | `IpdAdmission` + `Patient`                                    | occupied beds only                                     |
| `admittedAt`, `primaryDiagnosis`                              | `IpdAdmission`                                                | diagnosis role-gated (S7)                              |
| `consultantName`                                              | `Doctor` via `admission.doctorId`                             | ICU-1 D4: intensivist arrives with `icu_stay` in ICU-3 |
| `respiratorySummary`                                          | latest `VitalsRecord.spo2` + `respiratoryRate` + `recordedAt` | **recorded values only, no interpretation**            |
| `admissionConfirmed`                                          | `IpdAdmission`                                                | "pending work"                                         |
| `occupancyConsistent`                                         | derived (§D4)                                                 | integrity flag                                         |
| `icuStay`                                                     | **null in ICU-2**                                             | forward-compatible slot filled in ICU-3                |

Endpoints (all `GET`, all `@Transactional(readOnly = true)`):

| Path                        | Purpose                                      |
| --------------------------- | -------------------------------------------- |
| `/hospital/icu/board`       | the full payload above                       |
| `/hospital/icu/board/units` | units + totals only (lighter dashboard poll) |
| `/hospital/icu/unit-types`  | `CareUnitRegistry` list, for `WardModal`     |

Controller: `@RequireModule("ICU")`, `@TenantType(HospitalType.HOSPITAL)`, **no
`/clinic` or `/pharmacy` alias**, declared in `ControllerModules`.

## D2. ICU-2 ships `wards.unit_type` only — **no `icu_stay`**

The bed board **does not need `icu_stay`**: occupancy is `beds.status` +
`ipd_admission.bed_id`, and ICU-ness is `ward.unit_type`. Adding `icu_stay` in ICU-2 would
create a table with **no writer** — its writers are the movement hooks (T1–T7), which are
ICU-3 and blocked by E1. It would sit empty while the board read real occupancy, so every
row would show a null stay.

That is precisely the anti-pattern ICU-1 §5.4 used to defer `icu_unit_profile` — _"a table
that exists before its enforcement logic is a table that gets populated with values nothing
honours"_. The same rule applies here, so the same call is made.

**Effect on acceptance #3** ("an ICU bed points to the correct active patient/stay"): in
ICU-2 it is satisfied as **bed → active admission**, which is the correct patient and is
verifiable today. `IcuBedRowDTO.icuStay` is present-but-null so ICU-3 fills it with **no
DTO change and no frontend rework**. Recorded as decision **#1** for your approval.

## D3. Severity marker — omitted, deliberately

No severity or triage score exists. Producing one would mean **we** decide that a
particular SpO₂/BP/pulse combination means "high risk" — clinical interpretation, which
your principles restrict to _"document values and configurable alerts"_, with alerts
neither configurable nor stored yet (an ICU-3 item).

ICU-2 therefore shows **recorded values and their timestamp**, and no computed judgement.
When configurable thresholds land, the marker becomes a _configured_ alert — hospital
policy, not our inference. Decision **#2**.

## D4. Occupancy consistency — surfaced, not hidden

Occupancy already lives in two rows (§A1). ICU-2 adds **no third** (acceptance #4), and
resolves them with a stated precedence:

1. **`IpdAdmission` is authoritative for _who_** — status `ADMITTED`/`DISCHARGE_PLANNED`
   with `bed_id = X` means patient P is in bed X.
2. **`beds.status` is authoritative for _the bed's own state_** — `available`, `cleaning`,
   `maintenance` are bed facts with no admission.
3. **`beds.current_ipd_admission_id` is treated as a cache, not a source.** Because C1
   leaves `admitFromOpd` non-transactional, it can diverge.
4. When (1) and (2) disagree — occupied with no admission, or an admission on a
   non-occupied bed — the row carries `occupancyConsistent = false` and the UI flags it.
   **Making a real integrity problem visible to the ward is more useful than a board that
   quietly picks one row and looks tidy.** It is also free evidence for the E1 decision.

## D5. Bed click target

The objective says clicking an occupied ICU bed opens the ICU Patient Chart. **The ICU
chart does not exist yet** (ICU-3+). In ICU-2 the click navigates to the existing
`/ipd/:id` workspace — the current patient chart, and the one place ICU tabs will be added.
The link target therefore never changes; only the tabs inside it grow. This is the
one-coherent-workspace principle applied in sequence rather than a placeholder. Decision **#3**.

## D6. Role / access

| Role                        | ICU board scope                                          |
| --------------------------- | -------------------------------------------------------- |
| `HOSPITAL_ADMIN`            | all critical-care wards                                  |
| `DOCTOR`                    | all ICU beds; patient detail limited to own patients     |
| `NURSE_INCHARGE`            | intersection with `myWardIds()`                          |
| `NURSE`                     | own patients only (`NurseAccessGuard`)                   |
| `RECEPTIONIST`              | counts and bed availability; **no diagnosis, no vitals** |
| `PHARMACIST`, `OT_INCHARGE` | no access                                                |

No new guard, no new role (E4). Reuses `NurseInchargeGuard` and `NurseAccessGuard`.

## D7. Live refresh

None needed. `BedStatusService.change` already calls `RealtimeNotifier.refresh(hospitalId)`
after commit; the ICU views subscribe with the existing `useWebSocket` hook and refetch.
**Zero new broadcast code.**

## D8. Test plan (acceptance gates)

| Gate                                      | Test                                                                                                                                                                                      | Where                                              |
| ----------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------- |
| **#1 Cross-tenant ICU bed read refused**  | Hospital B token → A's ICU board and A's bed id ⇒ **404**; A's own ⇒ 200                                                                                                                  | `CrossTenantIsolationTest` + `IcuBoardTenancyTest` |
| **#2 Counts match records**               | Seed a known ward/bed/admission mix; assert every counter equals a direct repository count; assert `sum(units) == totals`; assert counts are **not** served from `hospitalStats`          | `IcuBoardServiceTest`                              |
| **#3 Bed → correct active patient**       | Occupied ICU bed resolves to the admission whose `bed_id` matches; discharged admissions never appear; a bed in a `GENERAL` ward never appears                                            | `IcuBoardServiceTest`                              |
| **#4 No double occupancy representation** | ICU writes no bed status (assert no `BedStatusService`/`bedRepository.save` call from ICU code); a deliberate divergence yields `occupancyConsistent = false` rather than a corrected row | `IcuBoardServiceTest`                              |
| Module + tenant gating                    | ICU off ⇒ 403; clinic/pharmacy token ⇒ denied; controller absent from `ClinicPharmacyIsolationTest`                                                                                       | existing aspect tests                              |
| Unit types                                | all 8 keys; `isCriticalCare` correct; `GENERAL` default; unknown key rejected                                                                                                             | `CareUnitRegistryTest`                             |
| Ward typing                               | set/read `unit_type`; **I11** — re-typing a ward with an occupied bed ⇒ 400                                                                                                               | `WardServiceTest`                                  |
| Frontend                                  | dashboard renders counts; empty state with no ICU wards; occupied bed click routes to `/ipd/:id`; unavailable columns absent                                                              | vitest + RTL                                       |

## D9. Execution order

| Step | Work                                                                               | Gate                              |
| ---- | ---------------------------------------------------------------------------------- | --------------------------------- |
| 1    | `CareUnitRegistry` + test                                                          | unit test green                   |
| 2    | `wards.unit_type`: entity, DTOs, `WardService` + I11, migration, `schema-full.sql` | `WardServiceTest` green           |
| 3    | Repository finders                                                                 | no `TenantScopingArchTest` breach |
| 4    | `IcuBoardService` + DTOs (TDD from D8)                                             | `IcuBoardServiceTest` green       |
| 5    | Controller + module wiring (all 4 places)                                          | aspect tests green                |
| 6    | Tenancy tests                                                                      | acceptance #1                     |
| 7    | Full backend regression                                                            | `mvn test` green                  |
| 8    | `icuService.js` + the two views                                                    | vitest green                      |
| 9    | Tab wiring (**both** duplicate lists) + `WardModal`                                | build + manual check              |
| 10   | `npm run build` + full `mvn test`                                                  | both green                        |
| 11   | Diff review → local commit → checkpoint report                                     | **no push**                       |

## D10. Decisions requested before implementation

| #   | Decision                                                                              | Recommendation                                   |
| --- | ------------------------------------------------------------------------------------- | ------------------------------------------------ |
| 1   | Ship `wards.unit_type` only, deferring `icu_stay` to ICU-3? (§D2)                     | **Yes** — no table without its writer            |
| 2   | Omit the severity marker until configurable thresholds exist? (§D3)                   | **Yes** — otherwise it is our clinical inference |
| 3   | Bed click → existing `/ipd/:id` workspace? (§D5)                                      | **Yes** — target never changes, tabs grow        |
| 4   | **E5** — reserved/isolation beds: omit, or open a bed-lifecycle checkpoint?           | **Omit from ICU-2**; separate checkpoint         |
| 5   | Ventilator column: omit, or show a disabled placeholder?                              | **Omit** — an always-empty column is noise       |
| 6   | Show `occupancyConsistent` warnings to all roles, or admin/incharge only?             | **Admin + incharge**                             |
| 7   | ICU tab placement: new `Critical Care` sidebar group, or inside `Patient Management`? | **New group** — it will gain siblings in ICU-3   |

---

## Phase ICU-2 result

**AUDIT complete, plan proposed, awaiting approval.** No code written, no tests run,
nothing committed. One schema column, 14 new files, 19 modified, one new escalation (E5),
seven decisions requested.

**CHECKPOINT: implementation does not begin until this is reviewed and approved.**
