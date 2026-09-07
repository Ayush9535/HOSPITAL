# Files & Access — Phase 2 Design (Doctor IPD sub-tabs + shared forms)

**Date:** 2026-07-10
**Status:** Approved design → plan
**Phase:** 2 of 2 (Phase 1 = config + nurse enforcement, already shipped)

## Goal

Give the doctor's IPD open-case page (`/ipd/:id` → `IpdDetails.jsx`) the same sub-tabs the nurse has, with each form editable or read-only per the hospital's Files & Access config (from the doctor's perspective). Doctors edit Medication (prescriptions) and Notes; the four assessment forms + consent forms follow the access rules. Enforcement is server-side, not just UI.

## Locked decisions

- **Backend:** open the nurse write endpoints (vitals, initial-assessment, vulnerability, sugar) to `DOCTOR` **and enforce Files & Access server-side** — a role may write a form only when its effective verdict for that role is `EDITABLE`. This also tightens the nurse side (a nurse can no longer write a Doctor-only form).
- **Panels:** reuse the existing nurse panels, made **role-aware** (hide nurse-only bits like "Performed By Nurse" for non-nurses); they already accept `readOnly`.
- **Content map:** Overview tab = admission info + daily follow-ups + discharge summary; Medication tab = the existing IPD prescriptions / add-medicine section; new tabs for Vitals, Notes, Initial Assessment, Vulnerability, Sugar, Consent Forms.
- **Top-right header:** only **Discharge** (Plan/Confirm) and **Create Surgery Request**. Follow-up / bed-change / bill actions live inside the relevant tabs.
- **Access from the doctor's view:** editable when access is Doctor/Both, read-only when Nurse-only, tab hidden when Off — the mirror of the nurse rule, driven by the same `GET /hospital/form-access/effective` (which returns verdicts for the caller's role).

## Backend

### Enforce edit access
- `FormAccessService.assertCanEdit(String formKey)`: reads `effectiveForRole(securityHelper.getCurrentUserRole()).get(formKey)`; if it is not `EDITABLE`, throw `AccessDeniedException` ("You do not have edit access to this form"). HOSPITAL_ADMIN normalizes to BOTH (always editable), matching Phase 1.
- Call `assertCanEdit(<KEY>)` at the top of the **write** paths (create/record/update) of:
  - `VitalsService` → `VITALS`
  - `InitialAssessmentService` → `INITIAL_ASSESSMENT`
  - `VulnerabilityAssessmentService` → `VULNERABILITY_ASSESSMENT`
  - `SugarChartService` → `SUGAR_CHART`
- Reads are **not** asserted (both roles may read; tab visibility handles HIDDEN client-side).
- Notes (`NursingNoteService`) and Medication (prescriptions) are **not** access-gated (always-on, role-specific) — no assertCanEdit.

### Open endpoints to DOCTOR
Widen `@PreAuthorize` so a doctor can reach the data (writes still pass through `assertCanEdit`):
- `VitalsController`: write endpoints (record/update) → `hasAnyRole('NURSE','NURSE_INCHARGE','DOCTOR','HOSPITAL_ADMIN')`.
- `SugarChartController`: write endpoints → same.
- `InitialAssessmentController` (class-level `hasRole('NURSE')`) → read + write to `hasAnyRole('NURSE','NURSE_INCHARGE','DOCTOR','HOSPITAL_ADMIN')`.
- `VulnerabilityAssessmentController` (class-level) → same.
- Reads already allow doctor for vitals/sugar/notes; keep as-is. Add `NURSE_INCHARGE` where missing for consistency.
- **Access-check inside the services stays the real gate;** widening roles only lets the request reach the service.

### Tests
- `FormAccessServiceTest`: `assertCanEdit` passes when EDITABLE, throws `AccessDeniedException` when READ_ONLY or HIDDEN (nurse role vs a DOCTOR-only form).
- Existing service tests for the four forms: add a `FormAccessService` mock stubbed to allow edit (verdict EDITABLE) so they keep passing.

## Frontend

### Role-aware panels
The five panels (`VitalsPanel`, `NotesPanel`, `InitialAssessmentPanel`, `VulnerabilityAssessmentPanel`, `SugarChartPanel`) already take `readOnly`. Make them role-aware:
- Read the current user (`authService.getCurrentUser()`); treat `NURSE`/`NURSE_INCHARGE` as nurse.
- Hide the **"Performed By Nurse"** selector and any nurse-only affordances when the user is not a nurse (a doctor just records as themselves). The record payload already carries the actor server-side.
- No other behavior change; the `readOnly` fieldset from Phase 1 still applies.

`ConsentFormsPanel` already takes `formVerdicts`; reused as-is.

### `IpdDetails.jsx` restructure
- Fetch `formAccessService.effective()` once (doctor's verdicts).
- Add a sub-tab bar (same ids/labels as the nurse): `overview, vitals, medication, notes, assessment, vulnerability, sugar` + `consent` when the admission has a surgery. Hide `vitals/assessment/vulnerability/sugar/consent` tabs whose verdict is `HIDDEN`.
- **Overview tab:** the existing Admission Info card, Daily Follow-ups (with its Add Follow-up button), and the Discharge Summary block.
- **Medication tab:** the existing prescriptions list + "Add Medicine" flow (`addIpdPrescription`, stop, etc.).
- **Notes tab:** `<NotesPanel admissionId={...} />` (doctor can add).
- **Vitals / Initial Assessment / Vulnerability / Sugar tabs:** the reused panels with `readOnly={verdict !== 'EDITABLE'}`.
- **Consent Forms tab:** `<ConsentFormsPanel admissionId={...} formVerdicts={formVerdicts} />`.
- **Header (top-right):** a right-aligned action row with **Plan Discharge / Confirm Discharge** (existing handlers, shown per status/role) and **Create Surgery Request** (existing `SurgeryRequestModal` trigger, shown when `hasOT && isDoctor`). Remove these actions from the old aside.
- The `admissionId` the panels need = the IPD admission id already loaded on the page.

## Out of scope
Nurse-incharge doctor-style editing; per-field audit of who edited a shared form; changing Medication/Notes access model. Redesign of the nurse `NursePatientDetail` (Phase 1 already handles the nurse side).

## Milestones
- **P2-A Backend:** `assertCanEdit` + call it in the four write services; widen the four controllers to DOCTOR; fix/extend tests.
- **P2-B Frontend panels:** make the five panels role-aware (hide nurse-only UI for doctors).
- **P2-C Frontend IpdDetails:** sub-tab restructure, content split, reused panels, top-right Discharge + Create Surgery Request.
