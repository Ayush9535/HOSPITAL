# ICU Phase 0 — Existing System Audit

**Phase:** ICU-0 (AUDIT)
**Date:** 2026-08-24
**Branch:** `icu`
**Status:** Inspection only — **no production code was modified in this phase.**

Purpose: map the ICU requirement onto the HMS codebase as it actually is, and decide what
ICU **reuses** versus what it must **create**. Everything below was read from the
repository, not assumed.

---

## 1. Integration-point inventory

Verified surface: 82 entities, 78 repositories, ~55 hospital services, 50 hospital
controllers, 82 backend test classes.

| Domain                          | Entity                                                                                                                                                                                                         | Repository                                            | Service                                                                                                                                            | Controller                                                                               | Frontend                                                                  | Tests                                                                                                         |
| ------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------- | ------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------- |
| Patient identity                | `entity/Patient` (`patients`, `publicId`, `customId`, `hospital_id`)                                                                                                                                           | `PatientRepository`                                   | `service/hospital/PatientService`                                                                                                                  | `PatientController` (`CORE`)                                                             | `ReceptionistDashboard`, `HospitalAdminDashboard`                         | `PatientApiTest`, `PatientServiceConsultationTest`, `PatientAgeTest`                                          |
| Inpatient episode               | `entity/IpdAdmission` (`ipd_admission`)                                                                                                                                                                        | `IpdAdmissionRepository`                              | `IpdAdmissionService` (1343 ln)                                                                                                                    | `IpdAdmissionController` (`IPD`)                                                         | `IpdDetails.jsx` (2124 ln, tabbed)                                        | `IpdAdmissionServiceTest`, `IpdSequenceQueryTest`, `IpdPrescriptionStopTenancyTest`                           |
| Ward                            | `entity/Ward` (`wards`, `incharge_nurse_id`, `bed_price`)                                                                                                                                                      | `WardRepository`                                      | `WardService`                                                                                                                                      | `WardController` (`WARDS`)                                                               | `WardsAndBeds.jsx`                                                        | `WardServiceTest`                                                                                             |
| Bed                             | `entity/Bed` (`beds`, `status`, `current_ipd_admission_id`), `entity/BedStatus`                                                                                                                                | `BedRepository`                                       | `BedService` + **`BedStatusService`**                                                                                                              | `BedController` (`BEDS`)                                                                 | `WardsAndBeds.jsx`, `nurse-incharge/WardBedsView.jsx`                     | `BedStatusServiceTest`, `AdmissionBedWardIsolationTest`                                                       |
| Bed occupancy history           | `entity/IpdBedHistory`, `entity/BedStatusAudit`                                                                                                                                                                | `IpdBedHistoryRepository`, `BedStatusAuditRepository` | written by `IpdAdmissionService` / `BedStatusService`                                                                                              | —                                                                                        | —                                                                         | `BedStatusServiceTest`                                                                                        |
| Doctor                          | `entity/Doctor`                                                                                                                                                                                                | `DoctorRepository`                                    | `DoctorService`                                                                                                                                    | `DoctorController` (`CORE`)                                                              | `DoctorDashboard.jsx`                                                     | —                                                                                                             |
| Nurse                           | `NurseProfile`, `NurseWardAssignment`, `NurseShiftSchedule`, `NurseAttendance`, `NurseSubstitution`, `PatientNurseAssignment`                                                                                  | 6 repositories                                        | `NurseService`, `NurseWorkspaceService`, `PatientAssignmentService`, `NurseCoverageService`, `NurseShiftScheduleService`, `NurseAttendanceService` | `NurseController`, `NurseInchargeController`, `NurseWorkspaceController`, +4 (`NURSING`) | `NurseDashboard`, `NurseInchargeDashboard`, `nurse/*`, `nurse-incharge/*` | `NurseServiceTest`, `NurseWorkspaceServiceTest`, `PatientAssignmentServiceTest`, `NurseInchargeGuardTest`, +4 |
| Vitals (inpatient)              | `entity/VitalsRecord` (`vitals_records`)                                                                                                                                                                       | `VitalsRecordRepository`                              | `VitalsService`                                                                                                                                    | `VitalsController` (`CLINICAL_RECORDS`)                                                  | `nurse/VitalsPanel.jsx`                                                   | `VitalsServiceTest`                                                                                           |
| Nursing notes                   | `entity/NursingNote` (`nursing_notes`, has `surgery_id`)                                                                                                                                                       | `NursingNoteRepository`                               | `NursingNoteService`                                                                                                                               | `NursingNoteController`                                                                  | `nurse/NotesPanel.jsx`                                                    | `NursingNoteServiceTest`                                                                                      |
| Assessments                     | `InitialAssessment`, `VulnerabilityAssessment`, `SugarChartEntry`                                                                                                                                              | 3 repositories                                        | 3 services                                                                                                                                         | 3 controllers                                                                            | `nurse/*Panel.jsx`                                                        | —                                                                                                             |
| Medication order                | `entity/Prescription` (`prescriptions`, keyed to `medical_record_id`)                                                                                                                                          | `PrescriptionRepository`                              | `IpdAdmissionService.addIpdPrescription` / `stopPrescription`                                                                                      | `IpdAdmissionController`                                                                 | `IpdDetails.jsx` Medication tab                                           | `IpdPrescriptionStopTenancyTest`                                                                              |
| Medication administration (MAR) | `entity/MedicationAdministration`                                                                                                                                                                              | `MedicationAdministrationRepository`                  | `MedicationAdministrationService`                                                                                                                  | `MedicationAdminController`                                                              | `nurse/MedicationPanel.jsx` (`readOnly` prop)                             | `MedicationAdministrationServiceTest`                                                                         |
| Clinical record spine           | `entity/MedicalRecord`                                                                                                                                                                                         | `MedicalRecordRepository`                             | via `DoctorService` / `IpdAdmissionService`                                                                                                        | `DoctorController`, `IpdAdmissionController`                                             | `IpdDetails.jsx`                                                          | —                                                                                                             |
| Lab / pathology                 | `entity/LabOrder` (`lab_orders`)                                                                                                                                                                               | `LabOrderRepository`                                  | **none** — written inline by `DoctorService`                                                                                                       | reachable only via `OpdController`                                                       | Admin `pathology` tab (module `PATHOLOGY`)                                | **none**                                                                                                      |
| Pharmacy                        | `entity/pharmacy/*`, `Medicine`, `MedicinePurchase`                                                                                                                                                            | `repository/pharmacy/*`                               | `service/pharmacy/*`                                                                                                                               | `controller/pharmacy/*` (9 controllers)                                                  | `pages/hospital/pharmacy/`                                                | `PharmacySaleServiceTest`                                                                                     |
| Consumables / inventory         | `HospitalInventory`, `HospitalInventoryPurchase`, `InventoryItem`                                                                                                                                              | 3 repositories                                        | `HospitalInventoryService`, `InventoryService`                                                                                                     | `HospitalInventoryController`                                                            | Admin `hospital-inventory` tab                                            | `HospitalInventoryServiceConsumeServiceTest`                                                                  |
| Billing                         | `Billing`, `BillingItem`, `BillingMedicine`, `BillingPayment`                                                                                                                                                  | 4 repositories                                        | `BillingService`, `BillingSchedulerService`                                                                                                        | `BillingController` (`BILLING`)                                                          | `BillingTable.jsx`                                                        | `BillingServiceTest`, `BillingControllerTest`                                                                 |
| Notifications                   | `entity/Notification`                                                                                                                                                                                          | `NotificationRepository`                              | `NotificationService`                                                                                                                              | `NotificationController`                                                                 | `notificationService.js`                                                  | `NotificationServiceTest`                                                                                     |
| Realtime                        | —                                                                                                                                                                                                              | —                                                     | **`service/RealtimeNotifier`**                                                                                                                     | `security/HospitalWebSocketHandler`, `config/WebSocketConfig`                            | `hooks/useWebSocket.js`                                                   | —                                                                                                             |
| Audit                           | `entity/AuditLog`                                                                                                                                                                                              | `AuditLogRepository`                                  | `service/AuditLogService`                                                                                                                          | `HospitalAuditController`                                                                | Admin `audit-logs` tab                                                    | `PlatformAuditControllerTest`                                                                                 |
| OT                              | `Surgery`, `SurgeryStateTransition`, `SurgeryForm`, `SurgeryTeamMember`, `SurgeryMilestone`, `WhoChecklist`, `OtRoom`, `OtRoomOccupancy`, `OtWorkflowPolicy`, **`RecoveryEpisode`**, **`RecoveryObservation`** | 11 repositories                                       | `service/hospital/ot/*` (9 services)                                                                                                               | 9 controllers (`OT`)                                                                     | `pages/hospital/ot/`                                                      | 9 test classes                                                                                                |
| Entitlement                     | —                                                                                                                                                                                                              | —                                                     | `entitlement/EntitlementRegistry`, `entitlement/ControllerModules`                                                                                 | consumed by `FacilityAccessAspect`                                                       | `components/PlansTab.jsx`                                                 | —                                                                                                             |

### OT → ICU handoff already exists

`RecoveryEpisode.transferDestination` is already declared as
`WARD | ICU | HDU | HOME | MORTUARY`
([RecoveryEpisode.java:50-51](backend/src/main/java/com/hms/entity/RecoveryEpisode.java#L50-L51)).
The OT module already _names_ ICU as a destination but has nothing to hand the patient to.
This is the already-declared integration seam — ICU should consume it, not invent a
parallel one.

---

## 2. Reuse-vs-create map per candidate ICU feature

| ICU feature                     | Verdict                    | Reuse                                                                                  | What must be new                                                                                         |
| ------------------------------- | -------------------------- | -------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------- |
| ICU patient identity            | **REUSE — create nothing** | `Patient` + `IpdAdmission`                                                             | nothing                                                                                                  |
| ICU bed / unit                  | **REUSE**                  | `Ward` + `Bed` + `BedStatus` + `BedStatusService`                                      | at most an ICU marker on `Ward` (a column, not a table)                                                  |
| ICU admission / transfer in-out | **REUSE**                  | `IpdAdmissionService.admitFromOpd` / `changeBed` / `confirmDischarge`, `IpdBedHistory` | ICU episode boundary record (§6)                                                                         |
| Vitals charting (q1h)           | **REUSE + extend**         | `VitalsRecord`, `VitalsService`, `VitalsPanel.jsx`                                     | high-frequency read path; no MAP, GCS, CVP, urine output today                                           |
| Ventilator settings             | **CREATE**                 | pattern from `RecoveryObservation`                                                     | timed-settings entity                                                                                    |
| Infusions / drips               | **CREATE**                 | `Prescription` for the order, `MedicationAdministration` for discrete doses            | continuous-rate model — neither carries rate / units / titration                                         |
| MAR                             | **REUSE**                  | `MedicationAdministration` + its service                                               | nothing structural                                                                                       |
| Intake / output balance         | **CREATE**                 | —                                                                                      | new entity                                                                                               |
| Nursing notes / handover        | **REUSE + extend**         | `NursingNote` (`category`, `orders`, `surgery_id`)                                     | shift-handover record if structured SBAR is required                                                     |
| Procedures (line, tube, trach)  | **CREATE**                 | `SurgeryForm` JSON-store pattern                                                       | new entity                                                                                               |
| Scores (SOFA / APACHE / GCS)    | **CREATE**                 | `RecoveryObservation` (Aldrete) is the exact precedent                                 | timed-score entity — **document only, no interpretation**                                                |
| Alerts                          | **REUSE + extend**         | `Notification` + `NotificationService` + `RealtimeNotifier`                            | threshold configuration storage                                                                          |
| ICU labs                        | **REUSE + extend**         | `LabOrder`                                                                             | `lab_orders` has `opd_id` but **no `ipd_admission_id`** — an inpatient lab cannot attach to an admission |
| Consumables charged in ICU      | **REUSE**                  | `HospitalInventoryService`, `IpdAdmissionService.administerHospitalItems`              | nothing                                                                                                  |
| ICU billing                     | **REUSE**                  | `Billing` + `BillingItem` + `BillingSchedulerService`, ward `bed_price`                | nothing — **billing redesign is an escalation trigger**                                                  |
| ICU Patient Chart UI            | **REUSE the shell**        | `IpdDetails.jsx` tabbed workspace + `nurse/NursePatientDetail.jsx`                     | ICU tab set inside the existing chart — **not 15 new pages**                                             |
| Realtime ICU board              | **REUSE**                  | `RealtimeNotifier.refresh(hospitalId)`, `useWebSocket`                                 | nothing                                                                                                  |
| Audit                           | **REUSE**                  | `AuditLogService.logAction(...)`, `BedStatusAudit`, `SurgeryStateTransition`           | nothing                                                                                                  |
| Form availability / edit rights | **REUSE**                  | `FormRegistry` + `FormAccessService.assertCanEdit(key)`                                | ICU form keys registered in `FormRegistry`                                                               |
| Module gating                   | **REUSE**                  | `@RequireModule`, `ModuleAccessAspect`                                                 | `ICU` key in `EntitlementRegistry` + `ControllerModules` + `PlansTab.jsx`                                |

---

## 3. Duplicate-model risks

**R1 — `ICUPatient` (critical).** `Patient` + `IpdAdmission` already express identity and
episode. A separate ICU patient row would fork identity and break
`PatientNurseAssignment`, `Billing.ipdAdmissionId`, and every `ipd_admission_id`-keyed
clinical record.
→ **ICU is a phase of an existing `IpdAdmission`, never a new patient.**

**R2 — `IcuBed` / `IcuWard`.** `Ward` + `Bed` already carry status lifecycle, audit,
pricing and incharge. A parallel bed table means two sources of truth for occupancy and
bypasses `BedStatusService`.
→ Mark existing wards as ICU; do not create a bed table.
Precedent to avoid repeating: OT originally identified theatres by _ward name containing
"OT"_ and had to be corrected into `OtRoom` (`ensureOtRoomsTable` — _"a theatre is a
resource, not a ward named OT"_). **Do not repeat the name-matching mistake for ICU.**

**R3 — `IcuVitals` duplicating `VitalsRecord`.** Same measurements, same
`ipd_admission_id` key. Two vitals tables means the doctor's IPD chart and the ICU chart
disagree.
→ Extend `VitalsRecord`; do not fork it.

**R4 — `IcuMedicationAdministration` duplicating the MAR.** `MedicationAdministration` is
already admission-keyed with `performed_by_nurse_id` resolution.
→ Reuse. **Continuous infusions are genuinely different** (rate over time, not a discrete
event) and are the one legitimate new medication entity.

**R5 — `IcuNurseAssignment` duplicating `PatientNurseAssignment`.** ICU ratios differ from
ward ratios, but the assignment _record_ is the same shape.
→ Reuse; ratio is a rule, not a table.

**R6 — a second audit trail.** `AuditLog`, `BedStatusAudit` and `SurgeryStateTransition`
already cover general / bed / state history.
→ Reuse `AuditLogService`; model ICU state history on `SurgeryStateTransition`.

**R7 — a parallel notification channel.** `Notification` + `RealtimeNotifier` exist, and
`RealtimeNotifier` already solves after-commit ordering.
→ Reuse. Never call `HospitalWebSocketHandler` directly.

**R8 — a separate ICU login / role.** Roles are plain strings with no central enum; a new
role requires `SecurityConfig` `hasAnyRole(...)` edits for `/hospital/**` and `/ws/**` —
a **security-architecture change and therefore an escalation trigger**.
→ Prefer `NURSE` / `NURSE_INCHARGE` / `DOCTOR` with ICU ward scope over a new role.

**R9 — append-only vs. overwrite.** `VitalsService.update(publicId, …)` and
`NursingNoteService.update` / `softDelete` **overwrite the row in place** with no prior
version retained. That conflicts with the ICU principle that clinical history preserves
prior values. ICU-authored records must be append-only from the start; changing the
existing IPD behaviour is out of ICU scope.

---

## 4. Tenant-scoping patterns in force

Any ICU-owned resource must follow all five.

1. **Column.** Every tenant-owned table carries `hospital_id BIGINT NOT NULL`. Confirmed on
   `patients`, `ipd_admission`, `wards`, `beds`, `vitals_records`, `nursing_notes`,
   `medication_administrations`, `notifications`, `lab_orders`, `ot_recovery_*`.
2. **Context.** `security/SecurityContextHelper.getCurrentHospitalId()`, read from the JWT
   via `UserAuthenticationDetails`. (Note: `CLAUDE.md` calls this `SecurityHelper`; the
   real class name is **`SecurityContextHelper`**.)
3. **Scoped finder or explicit compare.** Either a `findByXAndHospitalId(...)` repository
   method, or `findById` followed by a `hospitalId` comparison.
4. **Foreign resources are 404, not 403.** The established idiom, verbatim from
   [BedStatusService.java:44-48](backend/src/main/java/com/hms/service/hospital/BedStatusService.java#L44-L48):
   _"a tenant check, not a permission check — another hospital's bed must be
   indistinguishable from a missing one."_ → throw `ResourceNotFoundException`.
5. **Layered gates**, in order:
   `SecurityConfig` role matcher → `FacilityAccessAspect` (facility type) →
   `TenantTypeAspect` (`@TenantType`) → `ModuleAccessAspect` (`@RequireModule`) →
   `NurseInchargeGuard` / `NurseAccessGuard` / `NurseWriteAccess` (ward and patient scope) →
   `FormAccessService.assertCanEdit(formKey)` (per-form edit right).

### Build-time gates ICU code must satisfy

- **`security/TenantScopingArchTest`** — freezes the set of production methods calling
  `findById` / `getById` / `getOne` / `getReferenceById` on a repository (ALLOWLIST frozen
  2026-07-13, ~140 entries). Any new ICU service method doing a lookup-by-id **fails the
  build** until it is reviewed, covered in `CrossTenantIsolationTest`, and added.
- **`security/ClinicPharmacyIsolationTest`** — frozen golden set of controllers reachable
  via `/clinic` or `/pharmacy`. ICU is hospital-only, so ICU controllers must **not** be
  added there.
- **`security/OpdRepositoryScopingArchTest`**, `CrossTenantIsolationTest`,
  `FacilityAccessAspectTest`, `TenantTypeAspectTest`, `ModuleAccessAspectTest` — existing
  runtime coverage ICU must not break.

### Finding T1 — the entitlement fence documented in the code does not exist

`ControllerModules`'s javadoc states _"`EntitlementRegistryArchTest` fails if a controller
exists that is not listed here."_ **That test does not exist anywhere in the repository**
(verified by full-repo search: only three files reference these classes, all production).

This matters directly for ICU. `FacilityAccessAspect` calls
`ControllerModules.moduleOf(controller)` and, when the result is `null`, **returns as
allowed**
([FacilityAccessAspect.java:47-50](backend/src/main/java/com/hms/security/FacilityAccessAspect.java#L47-L50)).
An ICU controller not declared in `ControllerModules` would therefore be silently
**reachable by clinic and pharmacy tenants**.
→ **Mandatory for every ICU checkpoint:** declare each new controller in
`ControllerModules`. Recreating the missing arch test is a security-fence change →
**report, do not perform unilaterally.**

---

## 5. Transaction and concurrency boundaries (recorded, NOT modified)

Per scope discipline these are documented as constraints. **This phase changed none of
them, and no ICU phase may change them without escalation.**

### Established patterns

- **Service-level `@Transactional`** (`org.springframework.transaction.annotation`) is the
  norm; reads use `@Transactional(readOnly = true)`.
- **All bed status writes funnel through `BedStatusService.change()`** — `@Transactional`,
  writes `bed_status_audits`, best-effort `AuditLog`, then `RealtimeNotifier.refresh()`.
  System transitions (admit / discharge / transfer / OT complete) call it directly; user
  transitions are ward-scoped by `BedController` via `NurseInchargeGuard`.
- **Pessimistic locking exists in exactly two places**, both for interval/stock contention:
  `OtRoomRepository.findByIdForUpdate` (`PESSIMISTIC_WRITE`, used by
  `OtSchedulingService.lockRoom`) and `repository/pharmacy/MedicineBatchRepository` (×2).
  **This is the reference pattern for any ICU bed-contention work.**
- **`RealtimeNotifier` defers pushes to `afterCommit`** so clients cannot re-read a
  pre-change row. New ICU code must use `RealtimeNotifier`, never
  `HospitalWebSocketHandler` directly.
- **Side effects are best-effort** — audit, notification, bed history and websocket calls
  are individually try/caught so they can never roll back a clinical write.

### Bed-transfer / admission boundaries as they stand

| Path                                   | Transactional?                  | Locking | Notes                                                                                  |
| -------------------------------------- | ------------------------------- | ------- | -------------------------------------------------------------------------------------- |
| `IpdAdmissionService.admitFromOpd`     | **NO — see C1**                 | none    | reads bed status, allocates IPD number, writes admission + bed + history + billing     |
| `IpdAdmissionService.changeBed`        | yes (line 1211)                 | none    | vacates old bed → `cleaning`, occupies new bed, rewrites `IpdBedHistory`, adjusts bill |
| `IpdAdmissionService.confirmDischarge` | yes (line 1056)                 | none    | —                                                                                      |
| `BedStatusService.change`              | yes (line 38)                   | none    | single authoritative status writer                                                     |
| `PatientAssignmentService.onAdmission` | called best-effort inside admit | none    | —                                                                                      |

### Pre-existing findings — REPORTED, NOT FIXED

**C1 — `admitFromOpd` is not transactional.** At
[IpdAdmissionService.java:119-133](backend/src/main/java/com/hms/service/hospital/IpdAdmissionService.java#L119-L133)
the `@Transactional` annotation sits above a javadoc block, so it binds to the _next_
declaration — the **private** method `hasNursingModule()` — not to `admitFromOpd`.
Spring's proxy also ignores `@Transactional` on a private method, so the annotation is
inert in both positions. Admission's bed write, IPD-number allocation, bed history and
billing rows therefore commit independently and cannot roll back as a unit.

**C2 — IPD number allocation races.** `findMaxIpdSequence() + 1` with no lock, written to
a `UNIQUE` `ipd_number` column. Two concurrent admissions produce a duplicate-key failure.

**C3 — bed check-then-act races.** `admitFromOpd` reads `bed.status == "available"` and
`changeBed` reads `!= "occupied"`, with no lock and no unique constraint on
`beds.current_ipd_admission_id`. Two concurrent bookings can both pass. This is the exact
class of bug `OtSchedulingService.lockRoom` was introduced to solve for theatres.

**C4 — `changeBed` loads the target bed unscoped.** `bedRepository.findById(newBedId)` has
no `hospitalId` filter, and the occupancy check then runs against a foreign bed. The write
is ultimately stopped by `BedStatusService.change`'s tenant check, so this is an
**existence-disclosure** issue rather than a cross-tenant write.

> C1–C4 sit in **IPD sequencing, transaction and concurrency architecture** — three named
> escalation triggers. **ICU must not modify them.** ICU adds ICU-bed contention on top of
> exactly these paths, so a decision is needed before any ICU phase that admits or
> transfers a patient into an ICU bed.

---

## 6. Gaps ICU genuinely has to create

Everything else is reuse. These have no existing home:

1. **ICU episode boundary** — when ICU care starts and ends within an admission (the OT
   precedent is `RecoveryEpisode`: a phase record, not a case status).
2. **Continuous infusions** — rate over time; `Prescription` and
   `MedicationAdministration` model orders and discrete doses only.
3. **Ventilator settings history** — timed settings snapshots.
4. **Intake / output balance.**
5. **Timed severity scores** — model on `RecoveryObservation`; **record and display only,
   no interpretation or recommendation** (clinical-decision-support limit).
6. **ICU-specific vitals fields** — MAP, GCS, CVP and urine output are absent from
   `VitalsRecord`.
7. **Alert threshold configuration** — `Notification` delivers; nothing configures a
   threshold. Precedent for per-hospital config: `hospital_settings`, `hospital_vitals`,
   `hospital_form_access`, `OtWorkflowPolicy`.
8. **`ipd_admission_id` on `lab_orders`** — inpatient labs cannot currently attach to an
   admission. **Escalate before touching**, since `lab_orders` is shared with OPD.

---

## 7. Conventions any ICU checkpoint must follow

- **Migrations:** add `ensureIcuXxx()` to `config/DatabaseMigrationRunner.runMigrations()`
  (idempotent — `information_schema` check, then DDL, wrapped in try/catch) **and** mirror
  the DDL in `setup/schema-full.sql`. Roughly 100 `ensureXxx()` calls precede it.
- **Errors:** `GlobalExceptionHandler` — `IllegalArgumentException`→400,
  `UnauthorizedException`→401, `AccessDeniedException`→403,
  `ResourceNotFoundException`→404, `ConflictException`→409. `ApiResponse.error(msg)`
  populates the **`error`** field; the frontend reads `err.response.data.error`.
  **Global error handling is an escalation trigger — reuse it, do not extend it.**
- **Entities:** `publicId` UUID for externally referenced rows; `is_active` soft delete;
  non-updatable `created_at`.
- **Registries are Java, not tables** — `FormRegistry`, `VitalRegistry`, `OtPermissions`,
  `EntitlementRegistry`. ICU form and score keys follow suit.
- **Frontend:** `apiService.js` axios client, one service module per domain
  (`otService.js`, `nurseService.js` → an `icuService.js`), `ToastContext`, `DataTable`,
  `ActionMenu`, `StatusBadge`, `PageHeader`, `useWebSocket`.
- **Module wiring for a new `ICU` key touches four places:** `EntitlementRegistry`
  (constant + `SELLABLE[HOSPITAL]`), `ControllerModules` (**mandatory**, see T1),
  `@RequireModule("ICU")` on controllers, and
  `frontend/src/components/PlansTab.jsx` `AVAILABLE_MODULES`.

---

## 8. Open decisions for ICU-1 (DESIGN)

1. **ICU bed identification** — an ICU flag on `Ward`, or a dedicated ICU-unit resource as
   OT did with `OtRoom`? (R2 says: not name-matching.)
2. **ICU episode** — a phase record inside `IpdAdmission` (recommended, mirrors
   `RecoveryEpisode`), or a status on the admission?
3. **C1–C4** — a decision is required before any ICU admit/transfer path, because ICU adds
   contention to already-unprotected code. **Escalation, not an ICU-phase fix.**
4. **`lab_orders.ipd_admission_id`** — needed for ICU labs, shared with OPD. Escalation.
5. **Vitals** — extend `VitalsRecord` with ICU columns, or add a companion ICU observation
   table keyed to the same admission? (R3 forbids a fork.)
6. **Roles** — confirm ICU uses the existing `DOCTOR` / `NURSE` / `NURSE_INCHARGE` with
   ward scope; a new role is a `SecurityConfig` change and therefore an escalation
   trigger (R8).

---

## Phase ICU-0 result

**AUDIT complete.** No production files modified; no tests run, because nothing changed to
test. **Blocking on the six decisions in §8 before ICU-1 (DESIGN).**
