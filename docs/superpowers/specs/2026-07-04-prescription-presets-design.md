# Prescription Presets

## Problem

Doctors re-type the same prescription (same medicines, dosages, frequencies)
for common conditions over and over. We want to let a doctor save a named
bundle of medicines once (e.g. "Fever Protocol" = Paracetamol 500mg +
Cetirizine 10mg), then apply the whole bundle to a patient's prescription in
one action instead of re-entering each medicine manually.

Explicitly out of scope for this feature (deferred, per separate discussion):
age/weight-based automatic dosage calculation. That requires clinically
verified per-medicine dosing rules and safety guardrails, and will be
scoped as its own follow-up once we know whose dosing rules to encode.

## Scope

Prescription tab of the Consultation modal (`ConsultationModal.jsx`) only.
Per-hospital presets, same tenant model as the existing Quick Notes feature
(`ConsultationNotePreset`) — different specialties (skin, dental, ortho,
general) need entirely different preset lists, so there is no shared/global
preset library and no default seed list.

## Data model

Two new tables, since a preset holds a *list* of medicine rows, not a
single value (unlike the one-phrase-per-row `consultation_note_presets`):

**`prescription_presets`** — one row per named preset:
| Column | Type | Notes |
|---|---|---|
| `id` | bigint PK | |
| `hospital_id` | bigint NOT NULL | Tenant scoping |
| `name` | varchar(150) NOT NULL | e.g. "Fever Protocol" |
| `display_order` | int NOT NULL default 0 | Controls dropdown/list order |
| `is_active` | bit(1) NOT NULL default 1 | Soft delete |
| `created_at` | datetime(6) NOT NULL | |

**`prescription_preset_items`** — one row per medicine within a preset:
| Column | Type | Notes |
|---|---|---|
| `id` | bigint PK | |
| `preset_id` | bigint NOT NULL, FK → `prescription_presets(id)` ON DELETE CASCADE | |
| `medicine_name` | varchar(255) NOT NULL | Mirrors `Prescription.medicineName` |
| `dosage` | varchar(50) | Mirrors `Prescription.dosage`, e.g. "500mg" |
| `frequency` | varchar(50) | Mirrors `Prescription.frequency`, e.g. "1-0-1" |
| `duration` | varchar(50) | Mirrors `Prescription.duration`, e.g. "5 Days" |
| `instructions` | varchar(200) | Mirrors `Prescription.instructions`, e.g. "After food" |
| `sort_order` | int NOT NULL default 0 | Order of medicines within the preset |

Field names/lengths deliberately mirror the existing `Prescription` entity
(`backend/src/main/java/com/hms/entity/Prescription.java`) so a preset item
maps 1:1 onto a prescription row with no field-name translation needed.

No `hospital_id` on items — tenant scoping happens via `preset_id` →
`prescription_presets.hospital_id`, avoiding denormalization.

## Backend API

Under `/hospital/**`, tenant-scoped, `HOSPITAL_ADMIN` + `DOCTOR` roles only
(matches the Quick Notes permission model):

- `GET /hospital/prescription-presets` — list all presets for the current
  hospital, each with its full list of items, ordered by `display_order`.
- `POST /hospital/prescription-presets` — create a preset: `{ name, items:
  [{medicineName, dosage, frequency, duration, instructions}, ...] }`.
- `PUT /hospital/prescription-presets/{id}` — replace a preset's `name`
  and/or `items` (full replace of the item list is simplest — no per-item
  add/remove endpoints needed).
- `DELETE /hospital/prescription-presets/{id}` — soft-delete the preset
  (cascades are DB-level for the items table, but soft-delete only flips
  `is_active` on the preset row itself — items stay physically present
  under a soft-deleted preset, consistent with how deleted quick-notes
  keep their row).

## Frontend

### Applying a preset

A `<select>` dropdown at the top of the Prescription tab
(`ConsultationModal.jsx`, next to "Add Medicine"), listing every saved
preset's `name`. Selecting one **appends** all of that preset's medicine
rows to `formData.prescription` (does not clear existing rows — a doctor
can apply two presets, or apply one and still add more manually). The
dropdown resets to its placeholder after each selection so it can be used
again immediately.

### Editing prescription rows (new capability)

Today, `formData.prescription` rows are display-only with a remove (×)
button — there's no way to edit a row in place; a doctor has to delete and
re-add it. This feature adds an "Edit" action next to "Remove" on each row:
clicking it loads that row's values into the existing "Add Medicine" form
fields, and saving replaces that row in place (by index) instead of
appending a new one. This is a small, standalone improvement that isn't
specific to preset-inserted rows — it applies to every prescription row,
manually added or preset-inserted alike, and is what makes the "apply a
preset then tweak the dosage" workflow actually work.

### Managing presets

New component `PrescriptionPresetsManager.jsx` (mirrors the shape of the
existing `NotePresetsManager.jsx`, but for multi-item presets):
- **Quick-create**: a "Save current prescription as preset" button/link in
  the Consultation Prescription tab — grabs whatever's currently in
  `formData.prescription`, prompts for a name, and saves it as a new
  preset. This is the primary/fastest way to create a preset (no separate
  manual entry form needed for the common case).
- **Full manager**: a list of saved presets (name + item count), each
  expandable to view/edit its medicine rows (reusing the same
  name/dosage/frequency/duration/instructions inputs as the existing "Add
  Medicine" form) and reorder/delete. Also supports building a preset from
  scratch here (for the less common case of creating one without an
  active consultation).

Reachable from two places, both rendering the same manager component,
matching the Quick Notes pattern:
- A "Manage Presets" link next to the new dropdown in
  `ConsultationModal.jsx`'s Prescription tab.
- A new "Prescription Presets" entry under Hospital Admin's existing
  "Administration" sidebar group.

## Permissions

`HOSPITAL_ADMIN` and `DOCTOR` can view, create, edit, and delete presets —
same `@PreAuthorize` pattern as Quick Notes. `RECEPTIONIST`/`PHARMACIST`
have no access (they don't see the Consultation modal).

## Explicitly out of scope

- Age/weight-based automatic dosage calculation (separate future feature,
  requires clinically-sourced dosing rules).
- Diagnosis-linked/auto-suggested presets (no "if diagnosis = X, suggest
  preset Y" logic — purely manual selection via the dropdown).
- Cross-hospital/shared preset library.
- Per-item edit/reorder within the Prescription tab's live row list beyond
  what's described above (e.g. drag-and-drop) — the existing
  add/edit/remove pattern is sufficient.

## Testing notes

- Backend: unit tests for the service layer (create with items, list
  scoped+ordered, update replaces items correctly, soft-delete), following
  the same Mockito pattern as `ConsultationNotePresetServiceTest`; a
  `@WebMvcTest` controller test for role gating, following
  `ConsultationNotePresetControllerTest`.
- Frontend: `tsc --noEmit` + `vite build` + live verification (apply a
  preset via the dropdown and confirm rows appear correctly; edit a row
  in place and confirm it updates rather than duplicating; save current
  prescription as a new preset and confirm it's retrievable).
