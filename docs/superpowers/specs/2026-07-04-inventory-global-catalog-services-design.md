# Inventory Redesign: Global Item Catalog + Per-Hospital Services

## Problem

The current hospital-inventory model conflates two distinct concepts into one
per-hospital `inventory_items` table: physical stock items (cotton, syringe)
and billable procedures (dressing, injection), distinguished only by a
`hasOwnStock` flag and a `linkedFeeId`. Each hospital re-enters the same
consumable names from scratch, and the flag-based item/service distinction is
confusing.

We are restructuring into a clean two-level model:

- **Global Items** (platform-level) — a shared dictionary of physical
  consumable/stock item names, curated centrally by the Platform Admin, used
  by every hospital.
- **Services** (per-hospital) — billable procedures with a charge and a list
  of relevant items (drawn from the global list) that get consumed when the
  service is performed.

This makes the item-vs-service distinction structural (two concepts) rather
than a flag, removes per-hospital duplication of item names, and matches how
real hospital supply-master + service-charge systems work.

## Scope

Hospital inventory + consultation-time consumption + platform-admin catalog
management. Removes the previously-added procedure templates and the
`hasOwnStock` flag. Does not touch medicine inventory, the pharmacy module,
or any non-inventory billing.

## Data model

### `inventory_master_items` (NEW — platform-global, no hospital_id)

| Column | Type | Notes |
|---|---|---|
| `id` | bigint PK | |
| `name` | varchar(255) NOT NULL | Global item name, e.g. "Cotton", "Syringe 5ml" |
| `created_at` | datetime(6) NOT NULL | |

Unique on `name` (case-insensitive at the app layer) to prevent duplicate
global entries. Managed exclusively by the Platform Admin (add form + CSV
import). Shared by all hospitals.

### `hospital_services` (NEW — per-hospital)

| Column | Type | Notes |
|---|---|---|
| `id` | bigint PK | |
| `hospital_id` | bigint NOT NULL | Tenant scope |
| `name` | varchar(150) NOT NULL | e.g. "Dressing", "Injection" |
| `charge` | decimal(10,2) NOT NULL | Standalone service charge (NOT linked to the Fees tab) |
| `is_active` | tinyint(1) NOT NULL DEFAULT 1 | Soft delete |
| `created_at` | datetime(6) NOT NULL | |

FK `hospital_id` → `hospitals(id)` ON DELETE CASCADE.

### `hospital_service_items` (NEW — join: which global items a service consumes)

| Column | Type | Notes |
|---|---|---|
| `id` | bigint PK | |
| `service_id` | bigint NOT NULL | FK → `hospital_services(id)` ON DELETE CASCADE |
| `master_item_id` | bigint NOT NULL | FK → `inventory_master_items(id)` |

One row per relevant item on a service. A service consumes 1 unit of each of
its relevant items per unit of service performed.

### `hospital_inventory` (KEEP — per-hospital physical stock)

Unchanged in structure (name, stock_quantity, unit_price, expiry_date,
min_stock_level, type, manufacturer, hospital_id, is_active, created_at).
Purchases add to it; item names are now chosen from the global master list.
Stock rows are matched to master items by name (consistent with the existing
name-based matching the deduction engine already uses).

### Removed

- `inventory_items` table (the old per-hospital catalog) — replaced by
  `inventory_master_items` (global names) + `hospital_services` (per-hospital
  services). The `hasOwnStock` column and the procedure-template feature are
  removed along with it.

### Migration

Idempotent `DatabaseMigrationRunner` patches create the three new tables (if
absent), following the established check-then-create pattern. No automated
data migration from the old `inventory_items` rows — this is a dev,
never-deployed branch with minimal data; existing rows are left in place
harmlessly (the old table simply stops being read once the new endpoints
replace the catalog ones). `setup/schema-full.sql` updated to match.

## Backend

### Platform Admin — global item catalog

New `InventoryMasterItem` entity + repository + `PlatformInventoryItemService`
+ `PlatformInventoryItemController` under `/platform/inventory-items`, gated
`@PreAuthorize("hasRole('SUPER_ADMIN')")`:

- `GET /platform/inventory-items` — list all global items.
- `POST /platform/inventory-items` — create one (body: `{name}`; rejects blank
  or case-insensitive duplicate).
- `DELETE /platform/inventory-items/{id}` — hard delete (global reference
  data; no soft-delete needed since these are just names).
- `POST /platform/inventory-items/import-csv` — multipart CSV upload; each row
  is an item name; skips blanks and case-insensitive duplicates; returns a
  count of added/skipped. Mirrors the existing medicine-catalog CSV import
  pattern.

Hospitals read the global list read-only via a hospital-scoped endpoint (e.g.
`GET /hospital/inventory-master` returning all global item names) for the
purchase autocomplete and the service relevant-items picker.

### Hospital — services

New `HospitalService` entity (table `hospital_services`) +
`HospitalServiceItem` entity (join) + repositories + service +
`HospitalServiceController` under `/hospital/services`, gated
`@PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'DOCTOR', 'RECEPTIONIST')")`:

- `GET /hospital/services` — list active services for the current hospital,
  each with its relevant-item list (names + master ids).
- `POST /hospital/services` — create `{name, charge, masterItemIds: [...]}`;
  validates name non-blank, charge ≥ 0, at least one relevant item; persists
  the service + its join rows.
- `PUT /hospital/services/{id}` — update name/charge/relevant items (full
  replace of the join rows, hospital-scoped).
- `DELETE /hospital/services/{id}` — soft delete (`is_active = false`).

All hospital-scoped via `SecurityHelper.getCurrentHospitalId()`.

### Consultation consumption (OPD + IPD)

A shared `HospitalInventoryService.consumeService(serviceId, quantity,
hospitalId)` method replaces the current `consumeChargeableItem`:

1. Load the service (hospital-scoped) and its relevant master items.
2. For each relevant item, resolve the hospital's active stock rows by name
   (FEFO by expiry). Validate total available ≥ `quantity`; if ANY relevant
   item is short, throw `IllegalArgumentException("Some items are out of
   stock: <names>")` — the whole submission is rejected (defense-in-depth
   behind the frontend check).
3. If all pass, deduct `quantity` units of each relevant item across its stock
   rows (FEFO), writing an `INVENTORY_DEDUCTED` audit log per deduction.
4. Return the service's `charge × quantity` so the caller creates the billing
   line.

`DoctorService.submitConsultation` and
`IpdAdmissionService.administerHospitalItems` are reworked: the request now
carries `{serviceId, quantity}` items instead of `{stockId, name, quantity}`;
each calls `consumeService(...)` and creates a `BillingItem` from the returned
charge. The old `degradeRelativeItems` / `consumeChargeableItem` /
`hasOwnStock`-branching logic is removed.

### Low-stock alerts

- `GET /hospital/inventory/low-stock` — returns the current hospital's active
  stock rows where `stock_quantity <= min_stock_level`.
- Role/tenant gating enforced at the UI layer (the endpoint itself is
  available to admin/receptionist/doctor; the dashboards decide who shows the
  banner based on `hospitalType`).

## Frontend

### Platform Admin — "Inventory Items" tab

New tab in `PlatformDashboard.jsx`: a list of global item names, an "Add Item"
button opening a single-field (name) modal, and an "Import from CSV" control
(file upload → `importInventoryItemsCsv`). New `platformService`/`apiService`
functions for the four endpoints.

### Hospital Inventory — "Service Lookup" (renamed from "Catalog Lookup")

In `HospitalInventoryTab.jsx`:
- Sub-tab label "Catalog Lookup" → "Service Lookup".
- "Add Catalog Item" / template picker / duplicate / stock-type toggle removed.
- "Add Service" button → form with **service name**, **service charge**
  (number), and **relevant items** (search-multi-select drawn from the global
  master list via `GET /hospital/inventory-master`). Edit/delete on each
  service row.
- The relevant-items picker and purchase-intake item-name autocomplete both
  source from the global master list, not a per-hospital catalog.

### Purchase form

Item-name autocomplete now searches the global master list. (A purchase still
creates/updates the per-hospital `hospital_inventory` stock row by name; it no
longer auto-creates a per-hospital catalog entry.)

### Consultation "Items Used" search

In `ConsultationModal.jsx`: the search now lists the hospital's **Services**
(`GET /hospital/services`). Selecting a service:
- Client-side, checks each relevant item's stock against the requested qty
  using the low-stock/stock data already loaded; if any is short, shows the
  toast **"Some items are out of stock"** and does not add it.
- Otherwise adds the service with its charge shown.
- Submit sends `{serviceId, quantity}` entries.

### Low-stock alert banner

A small dashboard banner/section listing low-stock item names, rendered on:
- Hospital Admin + Receptionist dashboards when `hospitalType === 'HOSPITAL'`.
- Hospital Admin + Receptionist + Doctor dashboards when
  `hospitalType === 'CLINIC'`.

Sourced from `GET /hospital/inventory/low-stock`.

## Non-breaking guarantee

- Medicine inventory, pharmacy, prescriptions, appointments, billing totals,
  and all non-inventory flows are untouched.
- The consultation medicine/prescription/lab sections of `ConsultationModal`
  are untouched; only the hospital-inventory "Items Used" sub-section changes.
- IPD admission's non-inventory logic (bed, discharge, prescriptions) is
  untouched; only its hospital-item administration switches to `consumeService`.
- The removed `inventory_items` table's rows are left in the DB (not dropped),
  so nothing errors on residual data; the code simply stops reading them.

## Explicitly out of scope

- Per-hospital ad-hoc item creation — hospitals can only stock/use item names
  the Platform Admin has added globally (intended trade-off of a shared
  catalog; a missing item is a request to the Platform Admin).
- Data migration of existing `inventory_items` rows into the new tables.
- Any change to the Fees tab / `HospitalFee` — service charges are now
  standalone on the service, independent of the Fees tab.
- Reordering of services or global items.
- Auto-reorder / purchase-order generation from low-stock alerts (alert is
  display-only).

## Testing notes

- Backend: unit tests for `PlatformInventoryItemService` (create, dedupe,
  CSV parse), `HospitalServiceService` (create with items, list scoped,
  update replaces items, soft-delete), and `consumeService` (all relevant
  items available → deducts + returns charge; any item short → throws "out of
  stock"; hospital-scoping), following the existing Mockito patterns.
- Backend: `@WebMvcTest` role-gating tests for the new controllers
  (SUPER_ADMIN-only platform endpoints; hospital-role service endpoints).
- Frontend: `tsc --noEmit` + `vite build` + live verification of: platform
  add-item + CSV import; hospital add-service with relevant items; purchase
  autocomplete from global list; consultation service selection with the
  out-of-stock toast; low-stock banner visibility per role/tenant.
