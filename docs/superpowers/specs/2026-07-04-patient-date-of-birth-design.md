# Patient Date of Birth (replacing Age)

## Problem

Patient records store `age` as a plain integer, entered once at registration.
Since age changes every year, this value silently goes stale — staff must
remember to manually correct it, and nobody does. We're replacing the stored
`age` with a `dateOfBirth`, computing age live wherever it's shown, so it's
always correct with zero maintenance.

## Scope

Patient records only. Staff profiles (`HospitalAdmin`, `Doctor`,
`Receptionist`, `Pharmacist`) have their own, unrelated `age` field and are
out of scope — they are not touched by this change.

## Core design decision: computed `getAge()` on the backend

`Patient.age` becomes a transient, computed property instead of a stored
column:

```java
public Integer getAge() {
    return dateOfBirth != null
        ? Period.between(dateOfBirth, LocalDate.now()).getYears()
        : null;
}
```

Because Jackson (JSON serialization) and Thymeleaf (PDF templates) both call
getters via normal property access, every existing API response, DTO, and
PDF template that currently reads `patient.getAge()` / `${patient.age}`
keeps working completely unchanged — they now receive a live-computed value
instead of a stored one. This is the key insight that shrinks the change
surface from "11 display sites across 6 files" down to just the handful of
places that actually collect age as *input*, plus the two places we're
explicitly asked to also show DOB.

## Migration strategy for existing patients

Existing patients have only an integer age on file, never an exact birth
date — there's no way to derive one precisely. Decision: **approximate DOB
from the existing age, then drop the age column entirely.**

- One-time backfill: `date_of_birth = DATE_SUB(CURDATE(), INTERVAL age YEAR)`
- Approximate DOBs will be off by up to ~1 year until corrected via Edit
  Patient — acceptable since the alternative (keeping `age` around
  indefinitely as a fallback) doesn't match what was asked for and leaves
  the staleness problem half-solved.
- Follows this project's existing idempotent-migration-patch pattern in
  `DatabaseMigrationRunner` (see `simplifyMedicineListTable()` for
  precedent: check-then-patch, wrapped so one failure doesn't block others).

## Prescription/billing age snapshot — preserved as-is

Prescriptions and bills currently store a point-in-time snapshot of the
patient's age at creation (`PharmacyController.java` calls
`patient.getAge()` once and stores that integer on the record). This
behavior is **unchanged** — the snapshot still calls `getAge()` at creation
time (now computed from DOB instead of copied from a stored field) and
stores the resulting integer. Old prescriptions keep showing the age the
patient was at that visit, not their current age.

## Backend changes

- **`Patient.java`**: remove the `age` field/column. Add
  `private LocalDate dateOfBirth` (persisted, `NOT NULL`). Add the transient
  `getAge()` computed getter described above. No `setAge()` — callers use
  `setDateOfBirth(...)`.
- **`PatientService.java`**: create/update logic accepts and stores
  `dateOfBirth` instead of `age`. Validate: not in the future, not more than
  ~120 years ago (mirrors today's 0–120 age range).
- **`Appointment.java`** (transient carrier field for the inline "new
  patient during booking" flow) + **`AppointmentService.java`**: swap
  `patientAge` (Integer) → `patientDateOfBirth` (LocalDate); the
  auto-created Patient gets `setDateOfBirth(...)` instead of `setAge(...)`.
- **`IpdAdmissionSummaryDTO` / `IpdAdmissionDetailsDTO`**: unchanged — they
  call `patient.getAge()`, which keeps working via the computed getter.
- **`PharmacyController.java`**: unchanged, see snapshot behavior above.
- **DB migration** (`DatabaseMigrationRunner`): add nullable
  `date_of_birth`, backfill from `age` per the formula above, make it
  `NOT NULL`, then drop `age`. Each step individually idempotent
  (check-before-act) per the established pattern in this file.
- **`setup/schema-full.sql`**: updated to match, per this repo's convention
  that this file is the canonical schema source of truth.

## Frontend changes

- **`PatientModal.jsx`** (Add/Edit Patient, shared by Admin/Receptionist)
  and **`AppointmentModal.jsx`** (inline "New Patient" toggle during
  booking): replace the numeric Age input with a native
  `<input type="date">` DOB picker. Show a small live-computed "Age: NN"
  preview next to it as the user picks a date (client-side calculation,
  cosmetic only — the source of truth is the DOB sent to the backend).
  Payload sends `dateOfBirth` instead of `age`.
- **`ConsultationModal.jsx`**: currently shows only age. Add DOB alongside
  it (e.g. "34 years (DOB: 12 Jan 1992)"). Both values come directly off the
  patient object already returned by the API — no new API call needed.
- **Everywhere else** (patient tables in all three dashboards, patient
  search/autocomplete dropdowns, `PatientDetailsModal.jsx`,
  `IpdDetails.jsx`, pharmacy prescription/billing views): **no changes**.
  They already render `patient.age`, which is still present in every API
  response — just computed now instead of stored.
- **`utils/validation.js`**: add a `dob` validator (not in the future, not
  more than ~120 years ago). The existing `age` validator becomes unused by
  Patient forms after this change; left in place as a generic utility
  rather than removed, since removing it is a separate dead-code-cleanup
  concern, not part of this feature.

## Explicitly out of scope

- The confirmed-dead legacy Patient add/edit form embedded in
  `HospitalAdminDashboard.jsx` (unreachable — the modal that would render it
  explicitly excludes `type === 'patients'`). Left untouched; flagged for a
  separate dead-code cleanup pass.
- Staff (non-Patient) age fields.
- Any broader duplicate-code consolidation (e.g. per-dashboard table
  components) — separate, later effort per explicit user decision.

## Testing notes

- Verify computed age is correct across a DOB that hasn't had this year's
  birthday yet vs one that has (off-by-one boundary case for
  `Period.between(...).getYears()`).
- Verify the migration backfill runs cleanly against the current dev DB and
  that `age` is actually dropped afterward.
- Verify old prescriptions/bills still show their original snapshot age
  after the migration (not recomputed).
- Verify PDF templates (case paper, prescription, discharge, patient report)
  still render age correctly with no code changes.
