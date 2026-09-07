# Files & Access — Phase 1 Design (Form Registry + Access Config + Nurse Enforcement)

**Date:** 2026-07-10
**Status:** Approved design → plan
**Phase:** 1 of 2 (Phase 2 = doctor IPD sub-tab view, speced separately later)

## Goal

Let a Hospital Admin control, per hospital, **which clinical forms are active** and **who may edit each one** (Doctor / Nurse / Both), from a "Files & Access" table in Settings. Phase 1 delivers the config + enforces it on the **nurse** side. Phase 2 brings the doctor's IPD case view in line.

## Locked decisions

- **Forms under access control (19):** the 15 OT/NABH surgery forms in `surgeryFormsRegistry` **plus** 4 nursing records: **Vitals, Initial Assessment, Vulnerability Assessment, Sugar Chart**.
- **Not access-controlled (always on, role-specific):** Medication (doctor prescribes / nurse administers), Notes (both add), Overview.
- **Access values:** `DOCTOR`, `NURSE`, `BOTH`.
- **Rules:**
  - **Off** → the form is **hidden for the whole hospital** (shown to no one).
  - **On** → the role(s) named by *access* can **edit**; the other role gets **read-only**. (`BOTH` → both edit.)
- **Default (no config row):** **On + BOTH** — preserves today's behavior. No per-hospital seeding; defaults are applied lazily.
- **Shared record:** one underlying record per patient/form; when both roles can edit, they edit the same data.

## Data model — one new table (lazy defaults)

`hospital_form_access`:

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT PK auto | |
| `hospital_id` | BIGINT NOT NULL | tenant scope; FK → hospitals CASCADE |
| `form_key` | VARCHAR(60) NOT NULL | canonical key from the form registry |
| `enabled` | TINYINT(1) NOT NULL DEFAULT 1 | |
| `access_role` | VARCHAR(10) NOT NULL DEFAULT 'BOTH' | DOCTOR / NURSE / BOTH |
| `updated_at` | TIMESTAMP | |

Unique index `(hospital_id, form_key)`. Idempotent `ensureHospitalFormAccessTable()` migration + `schema-full.sql` mirror. A missing row ⇒ effective `enabled=true, access=BOTH`, so the table only ever holds *overrides*.

**Canonical form registry (backend constant `FormRegistry`).** An ordered list of `{ key, label, category }` for the 19 forms; `category ∈ { OT, NURSING }`. Keys reuse the existing frontend `type` values:
- OT (15): `BLOOD_CONSENT, IO_CHART, GA_CONSENT, DRUG_ADMIN_SHEET, INFORMED_CONSENT_ANAES, INFORMED_CONSENT_SURGERY, PRE_OP_CHECKLIST, PRE_ANAES_EVAL, GENERAL_ANAESTHESIA, SURGICAL_CASE_RECORD, POST_OP_CARE_PLAN, POST_OP_CHECKLIST_10, POST_OP_CHECKLIST_02, POST_ANAES_RECOVERY, WHO_CHECKLIST`
- NURSING (4): `VITALS, INITIAL_ASSESSMENT, VULNERABILITY_ASSESSMENT, SUGAR_CHART`

The registry is the source of truth for the settings list and for validating `form_key` on update.

## Backend

- `entity/HospitalFormAccess` + `repository/HospitalFormAccessRepository` (`findByHospitalIdAndFormKey`, `findByHospitalId`).
- `FormRegistry` constant (key/label/category) + an `AccessRole`/verdict notion.
- `FormAccessService`:
  - `list()` → every registry form with its effective `{key, label, category, enabled, accessRole}` (row override or default). Feeds the settings table.
  - `update(formKey, enabled, accessRole)` → validate key ∈ registry and role ∈ {DOCTOR,NURSE,BOTH}; upsert; audit `FORM_ACCESS_UPDATED`; broadcast `REFRESH_DATA`.
  - `effectiveForRole(role)` → `Map<formKey, verdict>` where verdict ∈ `HIDDEN | READ_ONLY | EDITABLE`:
    - disabled ⇒ `HIDDEN`;
    - enabled & (access == role or access == BOTH) ⇒ `EDITABLE`;
    - enabled & access == other role ⇒ `READ_ONLY`.
    (`role` here is normalized: NURSE_INCHARGE counts as NURSE for form access.)
- `FormAccessController` under `/hospital/form-access`, `@RequireModule` not required (forms exist across OPD/IPD/OT); tenant-scoped:
  - `GET /hospital/form-access` → `list()` — `@PreAuthorize hasRole('HOSPITAL_ADMIN')`.
  - `PUT /hospital/form-access/{formKey}` body `{enabled, accessRole}` → `update` — admin only.
  - `GET /hospital/form-access/effective` → `effectiveForRole(currentRole)` — any hospital staff role (DOCTOR, NURSE, NURSE_INCHARGE, HOSPITAL_ADMIN). Returns `{ formKey: "EDITABLE|READ_ONLY|HIDDEN" }`.

## Frontend

### Settings → "Files & Access"
A new section within the existing Settings tab (`HospitalAdminDashboard`). A table:

| Form | Accessed by | Status |
|---|---|---|
| *(label)* | `[Doctor ▾]` (disabled when Off) | `[On/Off toggle]` |

- Loads `GET /hospital/form-access`; grouped by category (OT / Nursing) for readability.
- Toggling **Off** disables the "Accessed by" dropdown and greys the row; **On** enables it.
- Each change → `PUT /hospital/form-access/{key}` → toast + optimistic update.

### Nurse enforcement (`NursePatientDetail` + panels)
- On open, fetch `GET /hospital/form-access/effective` once (verdict map for the nurse).
- **Sub-tabs** for the 4 nursing forms (Vitals, Initial Assessment, Vulnerability, Sugar): omit the tab when `HIDDEN`; render the panel with `readOnly` when `READ_ONLY`.
- **Consent Forms** list: show only forms whose verdict ≠ HIDDEN; open each in read-only when `READ_ONLY` (hide Save, keep Print).
- Medication, Notes, Overview tabs are unaffected.
- **`readOnly` prop:** add to `VitalsPanel`, the Initial Assessment panel, `VulnerabilityAssessmentPanel`, the Sugar Chart panel, and the consent `SurgeryFormFrame` — disables inputs and hides the Save action; existing data still renders.

## Testing
- `FormAccessServiceTest` (Mockito): default (no row) ⇒ enabled+BOTH+EDITABLE for both roles; disabled ⇒ HIDDEN; access=DOCTOR ⇒ doctor EDITABLE / nurse READ_ONLY; NURSE_INCHARGE normalized to NURSE; `update` rejects unknown key and bad role.
- Frontend: `npx vite build` succeeds; manual — toggle a form off ⇒ nurse tab disappears; set access=Doctor ⇒ nurse tab read-only.

## Out of scope (Phase 2)
Doctor's `IpdDetails` gaining nurse-style sub-tabs; doctor Medication (add-prescription) and Notes tabs inside that view; doctor-side read-only/edit driven by the same `effectiveForRole('DOCTOR')` map. Phase 1's service/endpoints are built to be reused verbatim by Phase 2.

## Milestones
- **P1-A Backend:** entity/repo/migration/schema, `FormRegistry`, `FormAccessService` + test, `FormAccessController`.
- **P1-B Frontend settings:** "Files & Access" table in Settings.
- **P1-C Nurse enforcement:** effective-map fetch + `readOnly`/hide wiring in `NursePatientDetail` and the five panels.
