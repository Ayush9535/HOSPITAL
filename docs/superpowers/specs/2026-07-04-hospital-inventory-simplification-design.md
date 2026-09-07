# Hospital Inventory Catalog Simplification

## Problem

Setting up hospital inventory catalog items (procedures/charges linked to fees
and consumable stock) is slow when a hospital has many items, because every
item is built from a blank form. Separately, the catalog model doesn't
distinguish between:
- **Chargeable items** (have a linked fee, meant to be billed directly —
  e.g. "Dressing", "Injection") vs. **non-chargeable items** (pure
  consumables like cotton, syringes, bandages — never billed on their own,
  only ever consumed as part of a chargeable item's related-items list).
- **Service-type items** (a procedure with no physical stock of its own —
  e.g. "Dressing" isn't a thing anyone purchases in units; its availability
  is entirely determined by its related consumables) vs. **stocked items**
  (a real physical product purchased and counted — e.g. a Vitamin B12
  injection ampule).

Today, every catalog item is implicitly treated as "stocked," which causes
two concrete problems already observed:
1. During a consultation, staff can search and select non-chargeable
   consumables directly, seeing a confusing ₹0 charge (they were never
   meant to be billed individually).
2. A service-type item like "Dressing" needs its own physical stock
   quantity tracked, which nobody purchases directly — it inevitably drifts
   to 0 and incorrectly blocks the procedure, even though its actual
   consumables (cotton, bandage) are well-stocked.

## Scope

Hospital Inventory catalog (`InventoryItem` entity, `HospitalInventoryTab.jsx`,
`HospitalInventoryController`/`HospitalInventoryService`) and the
consultation-time consumption path (`ConsultationModal.jsx`'s "Items Used /
Charges" section, `DoctorService.submitConsultation`,
`IpdAdmissionService.administerHospitalItems`). Nothing outside hospital
inventory/consultation billing is touched.

## Design overview (four additive pieces)

### 1. `hasOwnStock` flag on `InventoryItem` (new field, default `true`)

New nullable-with-default boolean column `has_own_stock` on
`inventory_items`, defaulting to `true` for every existing row (preserves
current behavior for everything already set up — **zero behavior change for
existing items** unless a hospital explicitly edits one to flip it off).

- `true` ("Stocked" — has its own physical quantity): consultation-time
  logic checks/decrements this item's own `HospitalInventory` stock row
  (current behavior), in addition to cascading to related items.
- `false` ("Service — no stock of its own"): consultation-time logic
  **skips** checking/decrementing this item's own stock entirely, and only
  checks/decrements its related items. This is the "Dressing" case.

Surfaced in the catalog item form (`HospitalInventoryTab.jsx`) as a simple
toggle/radio: "This item has its own stock" vs. "This is a service — stock
comes from its related items."

### 2. Chargeable-only filtering in the consultation's "Items Used" search

`InventoryItem.linkedFeeId` already exists and is nullable — an item with a
fee is implicitly "chargeable," one without is "non-chargeable." No new
column needed here. The only change: the "Items Used" search in
`ConsultationModal.jsx` filters candidate items to those whose matching
catalog entry has a non-null `linkedFeeId`, so non-chargeable consumables
(cotton, syringes, bandages) never appear as directly selectable — they only
move via the existing `degradeRelativeItems` cascade when their parent
chargeable item is selected. Nothing about `degradeRelativeItems` itself
changes — it's purely a frontend-side filter on what's shown in one search
box.

### 3. Procedure templates ("Add from Template")

A small, curated, hardcoded starter list (backend constant or static JSON,
not a DB table — YAGNI, this is a fixed reference list, not user-editable
data): Injection, Dressing (Small), Dressing (Large), IV Cannula,
Nebulization, Suturing, Catheterization. Each template specifies: a
suggested `name`, `type`, `hasOwnStock` default (Injection → `true`,
Dressing/Suturing/Nebulization → `false`, etc.), and a list of *suggested
related-item names* (e.g. Dressing suggests "Cotton", "Bandage",
"Adhesive Tape") — these are just suggested search terms to pre-populate
the existing related-items search-select, not IDs (since matching to a
hospital's actual stock items happens by name search, same as today).

New "Add from Template" button in `HospitalInventoryTab.jsx`'s catalog
sub-tab, opening a simple list-picker; selecting one opens the *existing*
catalog item modal, pre-filled with the template's `name`/`type`/
`hasOwnStock`, and the related-items search pre-populated with best-effort
name matches against the hospital's existing catalog (exact/fuzzy match on
suggested names) — anything unmatched is left for the admin to search
manually, same UI as today. The admin still explicitly picks the linked fee
(from their own already-created Fees tab entries) and confirms/adjusts
everything before saving — this is a prefill convenience, not an automated
creation.

### 4. "Duplicate" action on existing catalog items

A "Duplicate" button next to each row in the catalog list
(`HospitalInventoryTab.jsx`). Opens the same catalog modal used for
"Add from Template," pre-filled with the source item's full field set
(type, `hasOwnStock`, linked fee, related items), with the name field
cleared for the admin to type a new one (e.g. cloning "Dressing (Small)" →
typing "Dressing (Large)", then adjusting the fee amount reference and
related-item quantities as needed). Backend: no new entity, just a new
`POST /hospital/hospital-inventory/catalog/{id}/duplicate` convenience
endpoint that reads an existing item and returns its field values for the
frontend to pre-fill the create form (does not itself create anything —
the admin still explicitly reviews and submits).

## Backend changes

- `InventoryItem.java`: add `hasOwnStock` (Boolean, default `true`).
- `DatabaseMigrationRunner.java`: idempotent `ALTER TABLE inventory_items
  ADD COLUMN has_own_stock TINYINT(1) NOT NULL DEFAULT 1` if missing,
  following the established pattern.
- `HospitalInventoryService`: the consultation-time stock-check/deduction
  path (called from `DoctorService.submitConsultation` and
  `IpdAdmissionService.administerHospitalItems`) branches on
  `hasOwnStock`: if `false`, skip the parent item's own
  `HospitalInventory` lookup/decrement entirely and go straight to
  `degradeRelativeItems`; if `true` (or the flag is absent/legacy), behave
  exactly as today.
- New `GET /hospital/hospital-inventory/catalog/templates` — returns the
  hardcoded template list (name, type, hasOwnStock, suggested related-item
  names) as a static, non-persisted JSON payload.
- New `GET /hospital/hospital-inventory/catalog/{id}/duplicate` — returns
  the source item's fields for prefill (read-only, creates nothing).

## Frontend changes

- `HospitalInventoryTab.jsx`: "Add from Template" button + simple picker
  list; "Duplicate" action per catalog row; `hasOwnStock` toggle added to
  the existing catalog item form (defaults to checked/"Stocked" for new
  manual items too, matching current implicit behavior).
- `ConsultationModal.jsx`: "Items Used" search filters to catalog items
  with a non-null `linkedFeeId` only.

## Explicitly out of scope

- CSV bulk import (discussed and declined in favor of templates).
- Barcode/RFID scanning (separate hardware-dependent problem).
- Changing the Fees tab or `HospitalFee` entity in any way — fee amounts
  remain entirely admin-managed there, templates only ever reference
  existing fees by selection, never create/edit them.
- Changing `degradeRelativeItems`'s cascade logic itself, or the
  name-based stock/catalog matching it relies on.
- Any change to the Purchase Intake flow (confirmed already working well).
- Editable/admin-managed template library (the template list is a fixed
  starter set shipped with the app, not a per-hospital configurable table
  — keeps this from becoming its own mini-CRUD feature).

## Backward compatibility / non-breaking guarantee

- `hasOwnStock` defaults to `true` for every existing row via the
  migration — every currently-configured catalog item keeps behaving
  exactly as it does today (own-stock check + cascade) unless an admin
  explicitly edits it to mark it as a service.
- The "Items Used" search filter only changes what's *shown* in one
  specific search box in `ConsultationModal.jsx` — it doesn't alter any
  stored data, any other screen, or the existing manual "+ Add Item" /
  Purchase flows.
- Templates and Duplicate are purely additive UI entry points into the
  *existing* create/update catalog form and API — no existing endpoint's
  request/response shape changes in a breaking way (only an additive
  `hasOwnStock` field on the payload, defaulting server-side if omitted).

## Testing notes

- Backend: unit tests for the `hasOwnStock`-branching stock-check logic
  (service-type item with insufficient related-item stock → blocked;
  service-type item with sufficient related-item stock → succeeds,
  parent's own stock never queried; stocked-type item behaves exactly as
  existing tests already cover).
- Backend: unit test for the duplicate-fetch endpoint (returns correct
  field values, does not persist anything).
- Frontend: `tsc`/`build` checks; live verification of template picker
  prefill, duplicate prefill, `hasOwnStock` toggle persisting correctly,
  and the Items Used search no longer showing non-chargeable items.
