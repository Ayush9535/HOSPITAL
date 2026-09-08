# Treatment Note Quick-Presets

## Problem

When writing Treatment Notes during a consultation, doctors currently have to
type the same common advice over and over — "avoid oily food", "avoid junk
food", "drink plenty of water", etc. This wastes time on phrases that repeat
across most patients. We want a way to insert common notes with one click,
while still allowing free typing for anything specific to the patient.

## Scope

Only the **Treatment Notes** field in the Consultation modal
(`ConsultationModal.jsx`, Clinical Notes tab) for now. The Diagnosis field is
explicitly out of scope for this iteration — the owner may ask for the same
treatment on Diagnosis later, so the data model is built to extend to it
without a schema change (see below), but no UI for Diagnosis presets is
built now.

Different hospitals/clinics on this platform serve very different
specialties (skin, dental, orthopedic, general, etc.), so presets are
**per-hospital**, not global or hardcoded. A brand-new hospital starts with
zero presets — there's no sensible one-size-fits-all default list — and sees
a prompt to add their first one.

## Data model

New table `consultation_note_presets`, one row per preset phrase:

| Column | Type | Notes |
|---|---|---|
| `id` | bigint PK | |
| `hospital_id` | bigint, NOT NULL | Tenant scoping, matches every other hospital-scoped entity |
| `field_type` | varchar(30), NOT NULL | `'TREATMENT_NOTES'` for all rows created by this feature. Reserved for future `'DIAGNOSIS'` value — keeps this table reusable if Diagnosis presets are added later, without a migration |
| `text` | varchar(255), NOT NULL | The preset phrase, e.g. "Avoid oily food" |
| `display_order` | int, NOT NULL, default 0 | Controls chip order; admin can reorder |
| `is_active` | bit(1), NOT NULL, default true | Soft delete, matching this codebase's existing convention (`Patient.isActive`, `Doctor.isActive`, etc.) |
| `created_at` | datetime(6), NOT NULL | |

New entity `ConsultationNotePreset.java` mirrors this, following the same
field/annotation conventions as existing tenant-scoped entities (e.g.
`Patient.java`).

## Backend API

New endpoints under `/hospital/**`, tenant-scoped via
`SecurityHelper.getCurrentHospitalId()` like every other hospital endpoint.
Accessible to `HOSPITAL_ADMIN` and `DOCTOR` roles only (per
`@PreAuthorize`), matching the "both can manage" decision:

- `GET /hospital/consultation-note-presets?fieldType=TREATMENT_NOTES` — list
  active presets for the current hospital, ordered by `display_order`
- `POST /hospital/consultation-note-presets` — create a new preset
  (`fieldType`, `text`)
- `PUT /hospital/consultation-note-presets/{id}` — update `text` and/or
  `displayOrder`
- `DELETE /hospital/consultation-note-presets/{id}` — soft-delete
  (`isActive = false`)

New `ConsultationNotePresetService` (hospital-scoped CRUD, validates
`text` non-blank and reasonably short) and
`ConsultationNotePresetController`, following the existing
service/controller layering used throughout `service/hospital/` and
`controller/hospital/`.

New DB migration in `DatabaseMigrationRunner.java` (idempotent
check-then-create, matching the established pattern): create the
`consultation_note_presets` table if it doesn't exist. No backfill needed —
brand-new table, starts empty for every hospital.

`setup/schema-full.sql` updated with the new table definition, per this
repo's convention that it's the canonical schema source of truth.

## Frontend

### Chips under Treatment Notes

In `ConsultationModal.jsx`, directly below the existing "Treatment Notes"
`CharCountInput`, a new row renders one small pill/chip button per active
preset (fetched on modal open via the new GET endpoint, scoped to
`fieldType=TREATMENT_NOTES`). Clicking a chip appends its text to
`formData.treatmentNotes` on a new line (if the field isn't empty) or as the
first line (if empty) — never replaces existing text, so the doctor can mix
clicked presets with free typing in any order. A small "Manage" text link
sits at the end of the chip row.

If there are zero presets for the hospital, the chip row is replaced with a
single "Add your first quick note" prompt that opens the same Manage modal.

### Manage modal

New component `ManageNotePresetsModal.jsx`: a simple list showing each
preset's text with edit (inline text field swap), delete (with the existing
`ConfirmationModal` pattern), and reorder (up/down arrow buttons, calling
the `PUT` endpoint with new `displayOrder` values) controls, plus an
"Add new" input + button at the top. Opened from:
- the "Manage" link in `ConsultationModal.jsx` (both Doctor and Hospital
  Admin can reach this while consulting)
- a new entry under Hospital Admin's existing "Administration" sidebar
  group in `HospitalAdminDashboard.jsx` (Hospital Admin only, for
  configuring outside of a live consultation)

Both entry points render the same `ManageNotePresetsModal` component with
`fieldType='TREATMENT_NOTES'` — no duplicated logic.

New `hospitalService.js` functions: `getConsultationNotePresets`,
`createConsultationNotePreset`, `updateConsultationNotePreset`,
`deleteConsultationNotePreset`.

## Permissions

Both `HOSPITAL_ADMIN` and `DOCTOR` can view, add, edit, delete, and reorder
presets — enforced via the same `@PreAuthorize` role pattern already used
on other `/hospital/**` endpoints. `RECEPTIONIST` and `PHARMACIST` have no
access (they never see the Consultation modal or Treatment Notes).

## Explicitly out of scope

- Diagnosis-field presets (data model supports it via `field_type`, but no
  UI/seed for it yet — will be a small follow-up if requested)
- Any cross-hospital/shared/global preset library
- Auto-suggesting presets based on diagnosis or AI-generated suggestions
- Rich text / formatting within a preset (plain text only, same as the
  Treatment Notes field itself)

## Testing notes

- Backend: unit tests for `ConsultationNotePresetService` (create/list
  scoped to hospital, update, soft-delete, reorder), following the existing
  Mockito pattern used elsewhere in this codebase.
- Frontend: no test runner configured (matches rest of repo) — verify via
  `tsc --noEmit` + `vite build` + live Playwright-driven check that chips
  render, clicking appends text correctly (empty-field and
  already-has-text cases), and the Manage modal's add/edit/delete/reorder
  actions round-trip through the real API.
