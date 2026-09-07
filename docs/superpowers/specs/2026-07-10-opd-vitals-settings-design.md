# OPD Vitals Settings — Design

**Date:** 2026-07-10
**Status:** Approved design → plan

## Goal

Let each hospital decide which vitals are captured at OPD entry, and define their own extra vitals. A "Vitals" box in Settings (mirroring Files & Access) lists every vital with an On/Off toggle, plus an "Add Vital" form. Off vitals are hidden from the OPD entry form and omitted from the printed case paper. This covers every hospital's differing OPD-vitals scenario.

## Locked decisions

- **Off = hidden everywhere.** An Off vital is not shown in the OPD entry form, not captured, and not printed on the case paper.
- **Custom vital values live in a JSON column** on `opd` (`custom_vitals`), keyed by vital key — no joins, one row per OPD.
- **Deleting a custom vital keeps historical values.** The definition is removed so it disappears from new OPD forms and prints; values already recorded stay in the DB, unreferenced.
- **A custom vital is defined by name + unit.** Its value is free text, printed as `"110 mg/dL"`.
- **Custom vitals have NO validation.** Built-ins keep their existing validation (BP is free-text `120/80` with format checks; the rest are numeric with range checks).
- Built-in vitals can be toggled Off but **never deleted**. Only custom vitals are deletable.

## Data model

### `hospital_vitals` (new table)

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT PK auto | |
| `public_id` | VARCHAR(64) unique | UUID; used to delete custom vitals |
| `hospital_id` | BIGINT NOT NULL | FK → hospitals CASCADE |
| `vital_key` | VARCHAR(60) NOT NULL | built-in key, or derived key for a custom vital |
| `label` | VARCHAR(60) NOT NULL | display name |
| `unit` | VARCHAR(20) | e.g. `mmHg`, `mg/dL` |
| `enabled` | TINYINT(1) NOT NULL DEFAULT 1 | |
| `is_custom` | TINYINT(1) NOT NULL DEFAULT 0 | only custom rows are deletable |
| `sort_order` | INT DEFAULT 0 | customs appear after built-ins |
| `created_at` | TIMESTAMP | |

Unique index `(hospital_id, vital_key)`.

**Lazy defaults, same as Files & Access:** a built-in with **no row** is treated as **enabled**. Toggling a built-in writes an override row. Custom vitals always have a row (`is_custom = true`).

### `VitalRegistry` (backend constant — the 6 built-ins)

| key | label | unit | type | maps to `opd` column |
|---|---|---|---|---|
| `BP` | Blood Pressure | mmHg | TEXT | `bp` |
| `TEMPERATURE` | Temperature | °F | NUMBER | `temperature` |
| `PULSE` | Pulse | bpm | NUMBER | `pulse` |
| `HEIGHT` | Height | cm | NUMBER | `height` |
| `WEIGHT` | Weight | kg | NUMBER | `weight` |
| `SPO2` | SpO2 | % | NUMBER | `spo2` |

### `opd.custom_vitals` (new column)

JSON/TEXT, e.g. `{"GRBS":"110","BMI":"22.4"}`, keyed by `vital_key`. Built-ins keep their existing typed columns.

## Backend

`VitalSettingsService`:
- `list()` → built-ins (with effective `enabled`) + custom rows, ordered (built-ins first, then `sort_order`). Feeds the settings table.
- `toggle(vitalKey, enabled)` → upsert an override row for a built-in, or update the custom row. Validates the key exists (registry or a custom row).
- `addCustom(name, unit)` → derive `vital_key` from the name (uppercase, non-alphanumerics → `_`); reject duplicates (against registry keys and existing rows) and blank names; create with `is_custom = true`, `enabled = true`.
- `deleteCustom(publicId)` → 400 if the row is a built-in override (`is_custom = false`); otherwise delete the definition only.
- `enabledVitals()` → ordered list of `{key, label, unit, type, isCustom}` for the OPD form, consultation strip, and case paper.
- Audit each mutation (`VITAL_TOGGLED`, `VITAL_ADDED`, `VITAL_DELETED`) and broadcast `REFRESH_DATA`.

`VitalSettingsController` at `/hospital/vitals`:
- `GET /hospital/vitals` → `list()` — `hasRole('HOSPITAL_ADMIN')`
- `PUT /hospital/vitals/{vitalKey}` body `{enabled}` → `toggle` — admin
- `POST /hospital/vitals/custom` body `{name, unit}` → `addCustom` — admin
- `DELETE /hospital/vitals/custom/{publicId}` → `deleteCustom` — admin
- `GET /hospital/vitals/enabled` → `enabledVitals()` — `hasAnyRole('HOSPITAL_ADMIN','DOCTOR','RECEPTIONIST','NURSE','NURSE_INCHARGE')`

No `@RequireModule` (OPD exists for hospital and clinic tenants).

`CreateOpdRequest` gains `private Map<String, String> customVitals;`. `OpdService.createOpd`:
- serialize `customVitals` to JSON into `opd.customVitals` (only keys that are enabled custom vitals; ignore unknown keys),
- **skip validation for built-ins that are disabled** (a disabled BP must not fail its format check when absent).

## Frontend

### Settings → "Vitals" card
Mirrors `FilesAndAccessCard`, placed in the same Settings tab, shown for non-pharmacy tenants.
- Table: **Vital | Unit | Status** — toggle per row; custom rows also get a **Delete** action.
- **"+ Add Vital"**: name + unit → `POST /custom`.
- Optimistic in-place row update + toast; **load once on mount** (do not key the effect on `useToast` identities).

### OPD entry forms (Doctor / Reception / Admin)
Each fetches `GET /hospital/vitals/enabled` once.
- The **six built-in inputs stay as they are** (preserving their existing markup and validation) but each is wrapped in a check that its key is enabled. This keeps the change low-risk versus rewriting the forms.
- **Custom vitals render in a loop** after the built-ins as plain text inputs labelled `Name (unit)`, with **no validation**, collected into a `customVitals` map submitted with the payload.
- Validation for a built-in only runs when that built-in is enabled.

### Consultation vitals strip
`ConsultationModal` shows only enabled vitals — built-ins from their `opd` fields, customs parsed from `opd.customVitals` — each as `value unit`, `—` when unset.

### Case paper (`case-paper.html`)
`PdfService` passes a prepared `vitals` list of `{label, value, unit}` (enabled built-ins with their `opd` values, then enabled customs from the JSON). The template's fixed 5-cell row becomes a loop over that list, wrapping into a grid so 7+ vitals lay out cleanly.

## Tenant note
OPD entry, consultation, and the case paper are shared by **hospital and clinic** tenants, so the Vitals card and this behavior apply to both (not pharmacy). That is intended — it's what makes "every hospital's scenario" work.

## Testing
- `VitalSettingsServiceTest` (Mockito): built-in with no row ⇒ enabled; toggle writes an override; `addCustom` derives a key, rejects duplicates and blanks; `deleteCustom` rejects a built-in; `enabledVitals` omits disabled ones.
- Backend suite green; `npx vite build` succeeds.

## Milestones
- **V1 Backend:** entity/repo/`VitalRegistry`/migration/schema, `opd.custom_vitals`, `CreateOpdRequest.customVitals`, `OpdService`, `VitalSettingsService` + tests, controller.
- **V2 Settings UI:** `vitalsService.js`, `VitalsSettingsCard`, wired into Settings.
- **V3 OPD forms + consultation:** gate built-ins by enabled, render customs, submit `customVitals`, dynamic consultation strip.
- **V4 Case paper:** dynamic vitals list in `PdfService` + template loop.
