# ICU-9 — Alert Threshold Configuration

**Status:** IMPLEMENTED. See §16 for what was decided during implementation.
**Predecessor:** ICU-8 — Timed Severity Scores (`070b7ac`), complete and manually verified.
**Baselines:** backend 702 tests, frontend 157 tests.

---

## 1. Roadmap item

`ICU_SYSTEM_DESIGN.md` §12.3 item 4, verbatim:

> 4. Alert threshold configuration — delivery reuses `Notification` + `RealtimeNotifier`;
>    only the threshold storage is new.

`ICU_EXISTING_SYSTEM_AUDIT.md` line 70 and §6 item 7:

> | Alerts | **REUSE + extend** | `Notification` + `NotificationService` + `RealtimeNotifier` | threshold configuration storage | 7. **Alert threshold configuration** — `Notification` delivers; nothing configures a threshold.
> Precedent for per-hospital config: `hospital_settings`, `hospital_vitals`,
> `hospital_form_access`, `OtWorkflowPolicy`.

Roadmap order after ICU-8 is unchanged. Item 5 (append-only) is folded into each phase;
item 6 (multiple concurrent consultants, D4) remains deferred.

**Clinical-boundary note:** CLAUDE.md permits _"document values and configurable alerts only"_.
A threshold the **hospital sets** that fires a **notification** is inside that line. A default
the system ships, a severity grading, or an escalation rule is not — see §9.

---

## 2. Purpose

An ICU records a value every few minutes. Nobody watches every one. A hospital wants to say
_"tell someone when MAP goes below 65"_ — in its own numbers — and have the system notice at the
moment the value is charted.

Today nothing configures a threshold and nothing evaluates one. `Notification` delivers; it is
never triggered by a clinical value.

---

## 3. Current system state (verified for this plan)

| Fact                                                                                                                                                                                                                                               | Source                                                       |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------ |
| `Notification` — `recipientUserId`, `type`, `title`, `message`, `referenceType`, `referenceId`, `isRead`                                                                                                                                           | read from `entity/Notification.java`                         |
| `NotificationService.create(recipientUserId, hospitalId, type, title, message, referenceType, referenceId)` — try/catch fail-safe, and it already broadcasts `REFRESH_DATA` itself                                                                 | read from `service/hospital/NotificationService.java`        |
| **Delivery is per-user.** There is no "notify a ward" or "notify a role" call                                                                                                                                                                      | same file — drives D-2                                       |
| No threshold table, entity, service or evaluator exists anywhere                                                                                                                                                                                   | `grep -i threshold\|alert` over `backend/src/main` — nothing |
| Numeric ICU values that exist today: `VitalsRecord.pulse/spo2/mapMmhg/cvpCmh2o/urineOutputMl/gcsTotal` (typed), ICU-5 I/O balance (computed), ICU-6 infusion rates, ICU-7 `values_json` (configurable catalogue), ICU-8 `total_score` + components | ICU-4…ICU-8 entities                                         |
| **`LabOrder` still has no result value field**                                                                                                                                                                                                     | verified in ICU-8 §3.2 — E-1                                 |

---

## 4. Reusable architecture

| Reused                                                                          | For                                             |
| ------------------------------------------------------------------------------- | ----------------------------------------------- |
| `NotificationService.create(...)`                                               | delivery — **no new delivery code**             |
| `RealtimeNotifier` / the WS push inside `create`                                | the bell updating live                          |
| `hospital_vitals` / `icu_ventilator_parameter` / `icu_score_type_setting` shape | per-hospital override rows, lazy defaults       |
| `DatabaseMigrationRunner.createTableIfMissing`                                  | migration                                       |
| `SecurityContextHelper`, 404-not-403 idiom, `ControllerModules`                 | tenancy                                         |
| `VentilatorSettingsCard` / `ScoreSettingsCard`                                  | admin card shape                                |
| `useEnabledVentilatorParams` + `refreshKey`                                     | realtime settings card                          |
| `NurseInchargeGuard.myWardIds()`                                                | recipient resolution if D-2 goes the ward route |
| `IcuVentilatorServiceTest` structure, guard-revert method                       | tests                                           |

**New in kind:** one config table, one evaluator, one admin card. No new delivery mechanism.

---

## 5. Required changes

### 5.1 Database — one table

`icu_alert_threshold` — _new proposal, requires approval (§12 D-1/D-3)_.

| Column                                                      | Type                          | Source                                                 |
| ----------------------------------------------------------- | ----------------------------- | ------------------------------------------------------ |
| `id`, `public_id`, `hospital_id`, `created_at`, `is_active` | as ICU-5…ICU-8                | existing pattern                                       |
| `source`                                                    | VARCHAR(20) NOT NULL          | new proposal — which module the value comes from (D-1) |
| `metric_key`                                                | VARCHAR(60) NOT NULL          | new proposal — the key inside that source              |
| `min_value`                                                 | DECIMAL(12,3) NULL            | new proposal                                           |
| `max_value`                                                 | DECIMAL(12,3) NULL            | new proposal                                           |
| `enabled`                                                   | TINYINT(1) NOT NULL DEFAULT 1 | existing pattern                                       |
| `updated_by_user_id`                                        | BIGINT NULL                   | existing pattern                                       |

`UNIQUE(hospital_id, source, metric_key)`.

At least one of `min_value` / `max_value` must be set. **No row means no alert** — there is no
lazy default here, unlike every other config table in the module, because a default threshold
would be the system asserting a clinical norm (§9).

**Deliberately NOT created:** an alert-event table. See D-4 — it is a real gap and a real
decision, not an oversight.

### 5.2 Backend

| File                                                                           | Kind                                                                                     |
| ------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------- |
| `entity/IcuAlertThreshold.java`, `repository/IcuAlertThresholdRepository.java` | new                                                                                      |
| `service/hospital/icu/AlertMetricRegistry.java`                                | new — the fixed list of (source, metric_key) a threshold may target, D-1                 |
| `service/hospital/icu/IcuAlertThresholdService.java`                           | new — `list`, `upsert`, `toggle`                                                         |
| `service/hospital/icu/IcuAlertEvaluator.java`                                  | new — `evaluate(admissionId, source, Map<key,number>)`, called from the write paths      |
| `controller/hospital/IcuAlertThresholdController.java`                         | new — admin config                                                                       |
| `DatabaseMigrationRunner`, `setup/schema-full.sql`, `ControllerModules`        | modified, additive                                                                       |
| The ICU-4…ICU-8 write services                                                 | **one call added each**, after save, inside the existing transaction — see D-1 for which |

The evaluator is a **comparison and a message**. It does not grade, rank, rate-limit by severity,
or decide anything beyond "this number is outside the range this hospital typed".

### 5.3 Frontend

| File                                     | Kind                                                                |
| ---------------------------------------- | ------------------------------------------------------------------- |
| `pages/hospital/AlertThresholdsCard.jsx` | new — admin card, one row per registry metric, min/max/on-off       |
| `services/icuService.js`                 | modified — list/upsert/toggle                                       |
| `HospitalAdminDashboard.jsx`             | modified — mount under Settings, gated on `modules.includes('ICU')` |

**No change to any clinical panel.** Alerts surface through the existing `NotificationBell`.
_Nothing is coloured, flagged or badged on the chart itself_ — that would put an
interpretation on the record (§9).

---

## 6. Security and tenant isolation

- `hospital_id` on every query; foreign tenant → `ResourceNotFoundException` → **404**.
- Config read/write: `HOSPITAL_ADMIN` only, matching `IcuScoreTypeSettingController`.
- The evaluator runs server-side inside the clinical write; it is never called from a client.
- Recipient resolution must be tenant- and ward-checked (D-2) — a notification is the one place
  ICU-9 could leak a patient name to someone who cannot open that patient's chart.
- `ControllerModules` declaration mandatory (trap T1).
- **No new role, no new permission.** The threshold card is admin config, like the ICU-7 and
  ICU-8 cards. _Open question in D-5: whether the clinical write path needs a Files & Access key
  at all, given the evaluator is not a form._

---

## 7. Transactions and concurrency

- The evaluator is called **inside** the clinical write transaction that produced the value, but
  `NotificationService.create` is already fail-safe (try/catch) so a notification failure cannot
  roll back a clinical record. That matches ICU-5…ICU-8: **an alert must never cost a vitals row.**
- **No scheduler, no background job, no polling.** Evaluation happens when a value is written.
  Adding a periodic sweep would need a scheduler and a "still breached" concept, neither of which
  the roadmap asks for.
- **No concurrency test.** Two nurses charting simultaneously produce two evaluations and possibly
  two notifications; that is correct, not a race. Per the standing rule, no test for a race that
  does not exist and no claim of concurrency protection. _If D-4 introduces de-duplication, that
  DOES create a real race and would need a real proof — noted there._

---

## 8. Interaction with ICU-4 … ICU-8

| Feature               | Interaction                                                                                                          |
| --------------------- | -------------------------------------------------------------------------------------------------------------------- |
| ICU-4 Vitals          | evaluator called after a vitals save; **no change to the record**, no derived field                                  |
| ICU-5 I/O             | balance is computed on read, not written — see D-1, it may not be evaluable at all                                   |
| ICU-6 Infusions       | rate is a number in a unit that is never converted; a threshold would be per unit                                    |
| ICU-7 Ventilator      | `values_json` keys come from a configurable catalogue — a threshold must survive a rename, so it keys on `param_key` |
| ICU-8 Scores          | `total_score` is the obvious target; components are already range-bounded                                            |
| `IcuStay`             | untouched — read-only at most, for the ward/recipient lookup                                                         |
| Bed board / dashboard | **no change** (the D-4/D-5 precedent from ICU-7 and ICU-8)                                                           |
| IPD movement          | **untouched**                                                                                                        |

Every one of these is **one added call after an existing save**. No clinical entity gains a
column, and no existing behaviour changes shape.

---

## 9. Explicitly out of scope

- **Any shipped default threshold.** The table starts empty. A default is the system stating a
  clinical norm, which is exactly what "no invented clinical interpretation" forbids.
- **Severity, priority or colour on an alert.** A breach is a breach.
- **Escalation** — no "if unread for 10 minutes, tell someone else".
- **Trend or rate-of-change rules** ("falling fast") — that is derived interpretation.
- **Combining sources** ("low MAP _and_ high lactate") — a rule engine, not a threshold.
- **Any chart-side colouring, flagging or badge** on vitals/ventilator/score panels.
- **Lab-based thresholds** — no lab result values exist (E-1).
- **Periodic re-evaluation / scheduler.**
- **SMS, email or push delivery.** In-app `Notification` only, as the roadmap says.
- **Suppressing or acknowledging an alert clinically** — `Notification.isRead` already exists.

---

## 10. Testing strategy

**Config** — `IcuAlertThresholdServiceTest`: registry metrics list; upsert creates then updates
one row; a threshold with neither min nor max is rejected; an unknown `(source, metric_key)` is
rejected; disabling stops evaluation without deleting the row; foreign tenant → 404; toggling in
one tenant does not affect another.

**Evaluator** — `IcuAlertEvaluatorTest`: a value inside range fires nothing; below `min` fires
once; above `max` fires once; a metric with no row fires nothing; a **disabled** row fires
nothing; a null/absent value fires nothing; the notification carries the admission reference; a
`NotificationService` failure does **not** propagate (the clinical write survives); no threshold
is ever evaluated across tenants.

**Integration** — one test per wired source proving the clinical row is written **and** the alert
fired, and one proving a thrown notification error still leaves the clinical row committed.

**Guard-revert proof** (revert → confirm the matching test fails → restore):
tenant scoping on the threshold read, the disabled-row check, the "no row means no alert" check,
the fail-safe boundary around `NotificationService`, and the recipient scoping from D-2.

**Frontend** — `AlertThresholdsCard.test.jsx`: renders from the registry with no metric name
hardcoded; sends min/max on save; refuses an empty min+max; toggles; re-reads on `refreshKey`.

**Regression:** full backend (702), full frontend (157), `TenantScopingArchTest`,
`ClinicPharmacyIsolationTest`, both builds.

---

## 11. Manual test checklist

1. Settings → **Alert Thresholds** lists the registry metrics, all empty and off.
2. Set MAP min 65 → save → the row persists and reads back.
3. Chart a vitals row with MAP 58 → the recipient's bell shows one notification naming the
   patient and the value.
4. Chart MAP 70 → no new notification.
5. Disable the MAP threshold → chart MAP 58 → nothing fires; the row is still listed.
6. Clear both min and max → refused.
7. Other hospital's admin: independent list; their thresholds never fire on your patients.
8. A user who cannot open that patient's chart does **not** receive the notification (D-2).
9. Force a notification failure (stop the notification path) → **the vitals row still saves**.
10. Confirm no colour, badge or flag appears on any clinical panel.
11. Two tabs: set a threshold in one → the settings card in the other updates without reload.

---

## 12. Decisions I need from you

These are genuine forks, not confirmations. **I have not chosen any of them.**

**D-1 — which sources are in scope?** _New proposal._ The roadmap says "threshold
configuration" without naming a source. Candidates: ICU-4 vitals, ICU-6 infusion rates,
ICU-7 ventilator values, ICU-8 score totals. ICU-5 I/O is doubtful — its balance is computed on
read, so there is no write moment to evaluate.
_My recommendation: ICU-4 vitals only for ICU-9._ It is where the numbers a hospital actually
alerts on live (MAP, SpO₂, urine output), it is one wiring point, and it proves the whole
mechanism. The other three are then one added call each in a later phase.

**D-2 — who receives the notification?** _New proposal — `NotificationService` is per-user, so
this cannot be avoided._ Options: (a) the assigned staff nurse for that admission; (b) the ward's
incharge; (c) both; (d) an admin-configured recipient list.
_My recommendation: (c) — the assigned nurse and the ward incharge_, resolved through the
existing assignment record and `Ward.incharge_nurse_id`, since both already have chart access to
that patient and no new access is implied. **(d) would be new configuration architecture.**

**D-3 — one threshold per metric per hospital, or per ward / per patient?** _New proposal._
_My recommendation: per hospital only._ Per-ward multiplies the config surface, and per-patient
is a clinical order rather than a setting.

**D-4 — store alert events, or fire and forget?** _This is the one I most want your call on._
The roadmap says _"only the threshold storage is new"_, which reads as config-only. But with no
event record: nothing answers "was anyone actually told?", and charting MAP 58 five times in an
hour sends five notifications.
_My recommendation: add a minimal `icu_alert_event` row per fire_ — it makes the audit answerable
and enables a simple "not again within N minutes for the same admission+metric" rule.
**But that is one table more than the roadmap authorises, and de-duplication introduces a real
race that would need a real concurrency proof.** If you prefer to hold the line at config-only,
say so and I will ship it fire-and-forget with the duplicate-notification behaviour documented.

**D-5 — does the alert path need a Files & Access key?** _New proposal._ ICU-5…ICU-8 each got
one because each was a form. An evaluator is not a form and has no editable surface.
_My recommendation: no new key._ Config is admin-gated; the evaluator is internal.

---

## 13. Dependencies and blockers

**Blockers: none.**

**Escalations carried forward, not actioned:**

- **E-1 — no structured lab results.** `LabOrder` has no result value, so lab thresholds are
  impossible. Flagged in ICU-8 and now confirmed to bite exactly where predicted.
- **E-2 — `lab_orders` has no `ipd_admission_id`** (`ICU_EXISTING_SYSTEM_AUDIT.md` §6 item 8),
  and that table is shared with OPD. Any future lab-threshold work must escalate first.

**Flag:** `NotificationService.create` swallows every exception and broadcasts
`REFRESH_DATA` itself. That is the fail-safe ICU-9 relies on, so ICU-9 must not add a second
push around it.

---

## 14. Implementation order

1. `AlertMetricRegistry` + entity + repository + migration + `schema-full.sql`.
2. `IcuAlertThresholdService` (list / upsert / toggle) + config tests.
3. Config controller + `ControllerModules` + tenancy tests.
4. `IcuAlertEvaluator` + evaluator tests (no wiring yet).
5. Recipient resolution per D-2 + its scoping test.
6. Wire the single D-1 source; integration test proving the clinical row survives a notification
   failure.
7. Guard-revert proofs for all five guards in §10.
8. `AlertThresholdsCard.jsx` + `icuService.js` + admin mount, **with `refreshKey` from the start**.
9. Frontend tests.
10. Full regression, builds, `git diff --check`, scope review, **one local commit. No push.**

---

## 15. Definition of done

1. A hospital can set, edit, disable and re-enable a min/max threshold per registry metric.
2. The table ships empty; no default threshold exists anywhere in the codebase.
3. Charting a value outside an enabled threshold delivers exactly one `Notification` to each
   resolved recipient, naming the patient and the value.
4. Charting inside range, or with the threshold disabled or absent, delivers nothing.
5. A notification failure never rolls back or fails the clinical write — proven by test.
6. Foreign tenant → 404 on config; one tenant's thresholds never evaluate against another's data.
7. Recipients are limited to users who already have access to that patient's chart.
8. Every guard in §10 proven by reverting it and watching its test fail.
9. **No interpretation anywhere:** no severity, priority, colour, escalation, trend rule or
   chart-side badge, in the API or the UI.
10. No clinical entity gains a column; no existing behaviour changes shape.
11. Settings card is realtime from the start.
12. Backend and frontend suites green against 702 / 157; both builds clean.
13. One local commit, not pushed. ICU-10 not started.

---

## 16. Implementation record

Approved as D-1 vitals-only, D-2 assigned nurse + ward incharge, D-3 per hospital, D-4 **no
event table**, D-5 no Files & Access key. Built exactly to that.

**Deviations from the plan as written:**

1. **The ICU-stay check moved inside the evaluator and became lazy.** The plan implied
   `VitalsService` would compute it and pass a boolean. Doing that made _every_ vitals save —
   ward patients included — perform an extra `icu_stay` lookup, and it broke `VitalsServiceTest`
   with an unmocked repository. The evaluator now takes a `BooleanSupplier` and checks
   `thresholds.isEmpty()` first, so a hospital that has configured nothing pays for no extra
   read at all. Same behaviour, cheaper, and the guard is still proven by reverting it.

2. **`VitalsServiceTest` gained one `@Mock`** for the evaluator. No assertion changed.

3. **Two arch-test allowlist entries added** — `IcuAlertEvaluator#evaluateVitals` and
   `#recipientsFor`. Both resolve a ward/nurse/patient by id and then compare against the
   admission's own `hospitalId`; the incharge tenant filter is covered by
   `anInchargeProfileFromAnotherHospitalIsNotNotified`.

4. **`AlertThresholdsCard.test.jsx` mocks `useToast` as one stable object.** A fresh object per
   render changed the identity of the card's `load` callback and re-fired its effect
   continuously. The real `ToastContext` is stable; this aligns the mock with it.

**Known limitation, by decision (D-4), to be stated to users:** there is no alert-event table,
so nothing records what fired, nothing de-duplicates, and nothing can be acknowledged as an
alert. Charting a breaching value three times sends three notifications to each recipient.
`IcuAlertEvaluatorTest.repeatedBreachesSendRepeatedNotifications` asserts this deliberately, so
it reads as a decision rather than a bug.

**Not built, as agreed:** no event/history table, de-duplication, escalation, acknowledgement,
priority, severity, colour, scheduling, chart badges, default thresholds, or non-vitals sources.

---
