# ICU-10 — Multiple Concurrent ICU Consultants (D4)

**Status:** IMPLEMENTED as D-1 option (c). See §18.
**Predecessor:** ICU-9 — Alert Thresholds (`15b1f60`), complete and manually verified.
**Baselines:** backend 728 tests, frontend 164 tests.

---

## 1. Roadmap item and its source

`ICU_SYSTEM_DESIGN.md` §12.3, read in order:

| #   | Item                                                                 | State                          |
| --- | -------------------------------------------------------------------- | ------------------------------ |
| 1   | Continuous infusions                                                 | ICU-6 done                     |
| 2   | Ventilator settings history                                          | ICU-7 done                     |
| 3   | Timed severity scores                                                | ICU-8 done                     |
| 4   | Alert threshold configuration                                        | ICU-9 done                     |
| 5   | **Append-only semantics for all of the above**                       | **satisfied** — verified below |
| 6   | **Multiple concurrent ICU consultants (D4) — decide or defer again** | **← ICU-10**                   |

**Item 5 verified complete**, not assumed: `icu_infusion_rate.supersedes_rate_id` (ICU-6),
`icu_ventilator_setting.supersedes_setting_id` (ICU-7), `icu_severity_score.supersedes_score_id`
(ICU-8). ICU-9 is configuration, not clinical history, so append-only does not apply to it.

**Item 6 is the last item in §12.3.** ICU-10 closes the roadmap.

Source text — §9, D4:

> **Deferred to ICU-2:** multiple concurrent consultants (cardiology + nephrology). The
> precedent is `SurgeryTeamMember`; adding it now would be speculative.

§12.4 row 8: _"Multiple concurrent ICU consultants — Defer to ICU-2; `SurgeryTeamMember` is the
precedent."_

**Note the roadmap's own wording: "decide or defer again."** Deferring is an approved outcome of
this phase, not a failure to deliver it. §14 D-1 puts that choice to you.

---

## 2. Purpose

A critically ill patient is often seen by more than one consultant at once — cardiology and
nephrology on the same ICU stay. Today `icu_stay` records exactly one
`intensivist_doctor_id`, so the second name has nowhere to go and the chart cannot say who is
involved in this patient's care.

---

## 3. Current implementation state (verified, not assumed)

| Fact                                                                                                                        | Evidence                               |
| --------------------------------------------------------------------------------------------------------------------------- | -------------------------------------- |
| `icu_stay.intensivist_doctor_id` exists, nullable, tenant-checked per I15                                                   | ICU-3, `IcuStayService.setIntensivist` |
| `PUT /hospital/icu/stays/{publicId}/intensivist` exists (ADMIN, DOCTOR)                                                     | `IcuStayController`                    |
| `IcuStayDTO` already carries `intensivistDoctorId` and `intensivistName`                                                    | `dto/icu/IcuStayDTO.java`              |
| **`grep -ri "intensivist" frontend/src` returns NOTHING**                                                                   | verified for this plan                 |
| The only frontend consumer of `getStaysForAdmission` is `VitalsPanel`, and only to decide whether to show ICU vitals fields | verified for this plan                 |
| Setting an intensivist pushes a realtime refresh                                                                            | added in `ddaef23`                     |

### 3.1 The finding that shapes this phase

**The single consultant D4 already shipped has no user interface at all.** There is no way to
set an intensivist, and no screen displays one. The backend field, the endpoint, the DTO and the
realtime push are all live and unreachable.

So the roadmap's question — _do we need many consultants?_ — cannot be answered from usage,
because **nobody has been able to record even one**. Building a team table on top of a field that
has never been written would create a structure whose only reader is a screen we would also be
inventing, which the standing rule against "data structures with no actual reader/writer"
directly warns about.

This is escalated as **D-1**, not resolved here.

---

## 4. Reusable from ICU-1 … ICU-9

| Reused                                                                 | For                                                                                                                                                        |
| ---------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `SurgeryTeamMember` + `CaseRole` + `SurgeryTeamService`                | the named precedent: `(hospitalId, parentId, caseRoleCode, userId, externalName)`, per-hospital custom roles in `case_roles`, `assign` / `remove` / `team` |
| `PatientNurseAssignment`                                               | the precedent for **history** on an assignment (`assigned_at` / `unassigned_at`) — relevant to D-3                                                         |
| `IcuStayService.setIntensivist`'s scoped doctor lookup (I15)           | the tenant check a consultant id needs                                                                                                                     |
| `DatabaseMigrationRunner.createTableIfMissing`                         | migration                                                                                                                                                  |
| `SecurityContextHelper`, 404-not-403, `ControllerModules`              | tenancy                                                                                                                                                    |
| `RealtimeNotifier.refresh`                                             | live updates, already wired on the stay                                                                                                                    |
| Guard-revert test method, `TenantScopingArchTest` allowlist discipline | tests                                                                                                                                                      |

**New in kind:** at most one table and one thin service. No new delivery, no new permission model.

---

## 5. Required database changes

_All of §5 is a **new proposal requiring approval**, and applies only if D-1 chooses to build._

`icu_stay_consultant` — modelled directly on `SurgeryTeamMember`:

| Column                                                      | Type                 | Source                                                                           |
| ----------------------------------------------------------- | -------------------- | -------------------------------------------------------------------------------- |
| `id`, `public_id`, `hospital_id`, `created_at`, `is_active` | existing ICU pattern | pattern                                                                          |
| `icu_stay_id`                                               | BIGINT NOT NULL      | pattern (`SurgeryTeamMember.surgeryId`)                                          |
| `doctor_id`                                                 | BIGINT NULL          | pattern — tenant-checked per I15                                                 |
| `external_name`                                             | VARCHAR(255) NULL    | pattern (`SurgeryTeamMember.externalName`) — a visiting consultant with no login |
| `role_label`                                                | VARCHAR(60) NULL     | **new proposal** — "Cardiology". See D-2: reuse `case_roles` or free text        |
| `assigned_at` / `unassigned_at`                             | DATETIME(6)          | **new proposal**, only if D-3 chooses history                                    |
| `assigned_by_user_id`                                       | BIGINT NULL          | pattern                                                                          |

`UNIQUE(icu_stay_id, doctor_id)` where `doctor_id` is not null, so the same doctor cannot be
added twice to one stay.

**`icu_stay.intensivist_doctor_id` is NOT dropped or migrated** — see D-4.

**No table is created if D-1 defers.**

---

## 6. Required backend changes

_Only if D-1 chooses to build._

| File                                                                                                            | Kind                                                     |
| --------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------- |
| `entity/IcuStayConsultant.java`, `repository/IcuStayConsultantRepository.java`                                  | new                                                      |
| `service/hospital/icu/IcuStayConsultantService.java`                                                            | new — `list(stayPublicId)`, `add`, `remove`              |
| `controller/hospital/IcuStayConsultantController.java` **or** three methods on the existing `IcuStayController` | new — D-5                                                |
| `dto/icu/IcuStayConsultantRequest.java`                                                                         | new                                                      |
| `DatabaseMigrationRunner`, `setup/schema-full.sql`, `ControllerModules`                                         | modified, additive                                       |
| `IcuStayDTO`                                                                                                    | **possibly** modified to carry the consultant list — D-4 |

`IcuStayService.setIntensivist` and the stay lifecycle are **not modified**.

---

## 7. Required frontend changes

**This is the larger half of the work, and the part that does not exist yet in any form.**

| File                                                                                                | Kind                                                                            |
| --------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------- |
| An ICU stay panel showing the intensivist and consultants                                           | **new** — no such surface exists (§3.1)                                         |
| `services/icuService.js`                                                                            | modified — list/add/remove, and `setIntensivist` which is also currently absent |
| Placement: a sub-tab or card in `IpdDetails` / `NursePatientDetail`, beside the existing ICU panels | pattern                                                                         |

**Read-only for nurses; write for `ADMIN` and `DOCTOR`**, matching the existing intensivist
endpoint's `@PreAuthorize`. No colour, no badge, no ranking of consultants.

---

## 8. Security and tenant isolation

- `hospital_id` on every query; foreign tenant → `ResourceNotFoundException` → **404**.
- `doctor_id` is caller-supplied and therefore the IDOR surface, exactly as
  `intensivist_doctor_id` is: it must be resolved through the **same scoped doctor lookup**
  (`findByIdAndHospitalIdAndIsActiveTrue`) that ICU-3 uses, and get its own
  `CrossTenantIsolationTest` case per I15.
- `icu_stay_id` resolved only via `findByPublicIdAndHospitalId`.
- Write roles: `HOSPITAL_ADMIN`, `DOCTOR` — the existing intensivist endpoint's set.
  **No new role, no new permission.**
- `ControllerModules` declaration mandatory (trap T1).
- **No Files & Access key** — a consultant list is not a clinical form, same reasoning as ICU-9 D-5.

---

## 9. Transactions and concurrency

- One `@Transactional` per public write, on the public method.
- **Never** joins the IPD movement transaction; **never** calls `IcuStayService`'s `MANDATORY`
  lifecycle methods. Adding a consultant must not be able to roll back an admission or a stay.
- **No concurrency test.** Two people adding different consultants to one stay produce two rows,
  which is correct. Two people adding the _same_ doctor is prevented by the unique index, which
  is a database guarantee rather than application logic and needs no interleaving test.
  Per the standing rule, no test for a race that does not exist and no claim of protection.

---

## 10. Interaction with existing ICU features

| Feature                                                  | Interaction                                                                                   |
| -------------------------------------------------------- | --------------------------------------------------------------------------------------------- |
| `IcuStay`                                                | a child row keyed on `icu_stay_id`; the lifecycle is untouched                                |
| `IpdAdmission.doctorId`                                  | **untouched** — I21 holds; the admitting doctor keeps the case, the billing and the dashboard |
| Billing                                                  | **none** — a consultant is not a biller in ICU-10                                             |
| Vitals / I/O / Infusions / Ventilator / Scores (ICU-4…8) | **none**                                                                                      |
| ICU-9 alerts                                             | **none** — consultants are not alert recipients in ICU-10 (see §11)                           |
| ICU dashboard / bed board                                | **no change** — the D-4/D-5 precedent from ICU-7 and ICU-8                                    |
| IPD movement                                             | **untouched**                                                                                 |

---

## 11. Explicitly out of scope

- **Adding consultants as ICU-9 alert recipients.** ICU-9's recipients are settled (assigned
  nurse + ward incharge); changing them is not this roadmap item.
- Dropping, migrating or deprecating `intensivist_doctor_id`.
- Any billing, fee-split or claim consequence of a consultant.
- Consultant _notes_, orders or any clinical authoring — this is a list of who is involved.
- Ranking, primary/secondary hierarchy, or on-call rotation.
- Notifying a consultant that they were added.
- Per-consultant permissions or chart access changes — being listed grants nothing.
- Anything touching `icu_unit_profile`, lab results, or nurse-ratio enforcement.

---

## 12. Testing strategy

_Only if D-1 chooses to build._

**Service** — `IcuStayConsultantServiceTest`: add resolves the doctor **tenant-scoped**; a
doctor from another hospital → 404; a stay from another hospital → 404; the same doctor twice →
rejected; an external-name-only consultant is allowed with no `doctor_id`; remove takes the row
out of the list (or closes it, per D-3); listing is scoped to one stay; the ICU stay row and its
status are unchanged by every operation; `IpdAdmission.doctorId` is never written.

**Guard-revert proof** (revert → confirm the test fails → restore): the scoped doctor lookup,
the stay tenant scoping, and the duplicate constraint.

**Frontend** — panel renders the intensivist and the consultant list, hides write controls for a
nurse, and shows no ranking or badge.

**Regression:** full backend (728), full frontend (164), `TenantScopingArchTest`,
`ClinicPharmacyIsolationTest`, both builds.

---

## 13. Manual test checklist

1. Open an ICU patient → the stay panel shows the intensivist (or "not set") and an empty
   consultant list.
2. As a doctor, set the intensivist → it displays. _(This alone is currently impossible — §3.1.)_
3. Add "Cardiology — Dr X" and "Nephrology — Dr Y" → both listed.
4. Add Dr X again → refused.
5. Add a visiting consultant by name only, no login → listed.
6. Remove one → it goes; the other stays.
7. As a nurse, the list is visible and read-only.
8. Another hospital's doctor id → refused as not found, not as forbidden.
9. Another hospital's stay `publicId` → 404.
10. Confirm the admitting doctor's IPD dashboard still lists the case.
11. Confirm vitals, I/O, infusions, ventilator, scores, alerts and the bed board are unchanged.
12. Two tabs: add a consultant in one → the other updates without reload.

---

## 14. Decisions requiring your approval

**D-1 — build, defer again, or complete the single consultant first?** _The roadmap explicitly
offers "decide or defer again", so all three are legitimate outcomes._

- **(a) Build the team model** — `icu_stay_consultant` per §5, plus the stay panel.
- **(b) Defer again** — record the decision and close the roadmap with ICU-9.
- **(c) Complete D4's existing single consultant first** — build only the stay panel that
  displays and sets `intensivist_doctor_id`, which is shipped, tested, tenant-checked, realtime
  and **completely unreachable today** (§3.1). No new table, no new endpoint.

_My recommendation: **(c)**, then revisit (a) once someone has actually used it._ The roadmap
calls a team model "speculative" precisely because nothing had exercised the single field yet,
and that is still true — it has never been written, because it cannot be. Option (c) is the
smallest change that makes the existing decision real, and it is also the prerequisite screen
that (a) would need anyway. **But (c) is not literally roadmap item 6**, so I will not choose it
for you.

**D-2 — how is a consultant's specialty recorded?** _New proposal._ Reuse `case_roles` (the OT
per-hospital role catalogue, already supports custom rows), or a free-text `role_label`, or
nothing at all.
_Recommendation: free text._ `case_roles` is OT-shaped and gated on `OT_SETTINGS`; borrowing it
couples two modules for one label.

**D-3 — is a consultant list historical or current-only?** _New proposal._ `SurgeryTeamMember`
is current-only (hard delete); `PatientNurseAssignment` keeps `assigned_at`/`unassigned_at`.
_Recommendation: current-only_, matching the named precedent. Roadmap item 5's append-only
requirement covers items 1–4, not this one, and a consultant list is a fact about now rather
than a clinical observation. **Escalating rather than assuming, because "who was consulting on
Tuesday?" is a reasonable audit question and the answer changes the model.**

**D-4 — does `intensivist_doctor_id` stay separate, or become row zero of the list?**
_Recommendation: stays separate and untouched._ It is referenced by I15, has its own
`CrossTenantIsolationTest` case, and folding it into a list would be a data migration on a
shipped column for no functional gain.

**D-5 — new controller, or methods on `IcuStayController`?**
_Recommendation: methods on `IcuStayController`_ — same resource, same roles, one fewer entry in
`ControllerModules`.

---

## 15. Dependencies, blockers, escalations

**Checked explicitly, as asked:**

| Deferred capability                                            | Does ICU-10 depend on it?                                                                 |
| -------------------------------------------------------------- | ----------------------------------------------------------------------------------------- |
| **Lab result values / lab architecture** (E-1, E-2)            | **No.**                                                                                   |
| **`icu_unit_profile`** (§7, deferred to the nurse-ratio phase) | **No.** Still must not be read.                                                           |
| **Nurse workflow / chart access** (F-1)                        | **No — and it is now resolved.** `c1c242d` gave the incharge a ward-scoped patient chart. |
| ICU-9 alert recipients                                         | **No** — deliberately out of scope (§11).                                                 |

**Blockers: none.**

**Escalation — the one thing I will not decide for you:** whether a hospital using this system
actually needs concurrent consultants. That is a clinical-workflow question about your users, not
something I can settle from the codebase, and §3.1 shows usage cannot answer it either. D-1.

---

## 16. Implementation order

_Applies to D-1 option (a). Option (c) is steps 6–8 only; option (b) is step 9 alone._

1. `IcuStayConsultant` entity + repository + migration + `schema-full.sql`.
2. `IcuStayConsultantService` — `list`, `add` (scoped doctor lookup), `remove`.
3. Endpoints on `IcuStayController` + `ControllerModules` + `TenantScopingArchTest` allowlist.
4. Service tests, then **revert each guard and watch its test fail**.
5. `CrossTenantIsolationTest` case for the consultant `doctor_id`, per I15.
6. The ICU stay panel: intensivist display **and setter** (currently missing entirely), plus the
   consultant list.
7. `icuService.js` — `setIntensivist` and the consultant calls, **with `refreshKey` from the start**.
8. Frontend tests.
9. Update this document with the decision taken and, if deferring, record ICU-10 as closed by
   decision rather than by implementation.
10. Full regression, builds, `git diff --check`, scope review, **one local commit. No push.**

---

## 17. Definition of Done

**If D-1 = (a) build:**

1. Several consultants can be recorded on one ICU stay, listed, and removed.
2. A doctor from another hospital cannot be added — refused as not found.
3. The same doctor cannot appear twice on one stay.
4. A consultant with no login can be recorded by name.
5. `intensivist_doctor_id`, `IpdAdmission.doctorId`, the stay lifecycle and the admitting
   doctor's dashboard are all provably unchanged.
6. Nurses see the list read-only; no new role or permission exists.
7. Every guard in §12 proven by reverting it.
8. No ranking, badge, colour, notification or billing consequence anywhere.
9. Suites green against 728 / 164; both builds clean; one local commit, not pushed.

**If D-1 = (b) defer:** this document records the decision and the reasoning, §12.3 is closed,
and no code changes.

**If D-1 = (c) complete the single consultant:** the intensivist can be set and seen by a doctor,
tenant-checked, realtime, with no new table or endpoint — and item 6 stays open pending real use.

---

## 18. Implementation record — D-1 = (c)

Approved: complete the shipped single-consultant capability. D-2 no specialty label (none was
needed — the doctor's name is the display). D-3 current-only. D-4 `intensivist_doctor_id` kept
separate. D-5 existing `IcuStayController`.

**Backend verification (step 1) — nothing was missing.** Entity field, `IcuStayDTO`
(`intensivistDoctorId` + resolved `intensivistName`), `PUT /hospital/icu/stays/{publicId}/intensivist`
gated `HOSPITAL_ADMIN, DOCTOR`, `setIntensivistAndView`, the I15 scoped doctor lookup,
`requireActive`'s closed-stay `ConflictException`, and the realtime push from `ddaef23` were all
already correct. **No backend production code was rewritten.** The one gap was the screen.

**Built:**

- `pages/hospital/icu/IcuStayCard.jsx` — the ICU stay and its intensivist, with a tenant-scoped
  doctor picker. Renders nothing when the admission has no ICU stay. Editable only for
  `HOSPITAL_ADMIN` / `DOCTOR` and only on an ACTIVE stay, mirroring what the server enforces.
- `icuService.setIntensivist` — the first caller the ICU-3 endpoint has ever had.
- Mounted in the `IpdDetails` aside beside the OT card, fed `panelRefreshKey` so it is realtime.
- `IcuStayCard.test.jsx` (10) and one backend test.

**`icu_stay_consultant` was NOT created.** No table, no migration, no schema change, no new
endpoint, no new role, no Files & Access key, no bed-board change.

**Deviations:**

1. **D-2 resolved to "no field".** The plan allowed a free-text specialty label _if display
   needed it_. Displaying one doctor by name does not, so no column was added — a field with no
   reader is exactly what the standing rule forbids.
2. **One backend test added**, `settingAnIntensivistNeverMovesTheCaseOffTheAdmittingDoctor`.
   Everything else on the approved test list (closed stay, set/change/clear, foreign doctor →
   404 with no mutation) was already covered by `IcuStayLifecycleTest`; that one was not.
3. **A weak test of mine was caught and fixed by the revert proof.** The closed-stay UI test
   originally used a fixture with no intensivist set, so the button would have read "Set" while
   the test looked for "Change" — it passed with the guard removed. The fixture now sets an
   intensivist and the test asserts neither label appears.

**Roadmap item 6 stays open by design.** Multiple concurrent consultants is not built. The
roadmap called it speculative because nothing had exercised the single field; that can now be
exercised, and the question can be revisited from real use rather than from guesswork.

---
