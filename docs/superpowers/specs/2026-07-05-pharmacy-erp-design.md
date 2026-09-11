# Pharmacy ERP — Design

**Date:** 2026-07-05
**Status:** Approved (design), Phase 1 to be planned first
**Scope:** Turn the pharmacy module into a full ERP with three plan-driven admin modes and an upgraded pharmacist dashboard, without touching working hospital/clinic behavior.

## Goal

A pharmacy tenant (`hospitalType = 'PHARMACY'`) can be provisioned by the platform admin as exactly one of three **pharmacy plan types**. Each type gives the pharmacy admin a different dashboard. All modes share one common **pharmacist dashboard**, which gets several workflow upgrades.

## Non-goals / guardrails

- **This ERP is for the standalone pharmacy tenant only** — a pharmacy created by the platform admin (`hospitalType === 'PHARMACY'`). The **in-house pharmacy module inside a HOSPITAL or CLINIC tenant is explicitly out of scope** and must keep its current behavior. Upgrading the hospital/clinic pharmacy is a separate, later effort.
- **Shared components must be gated.** The pharmacist-dashboard views (`PurchaseForm`, `InventoryView`, `SuppliersView`, `ExpiryView`, `PharmacyDashboard`) are the same files a hospital/clinic PHARMACY module renders. Every Phase 1 change is therefore applied **conditionally** on standalone-pharmacy context (`isStandalonePharmacy` — i.e. `hospitalType === 'PHARMACY'`, equivalently PHARMACY module present without OPD). When that condition is false, render exactly as today.
- **Do not change hospital or clinic behavior.** Hospital/clinic code is fully working; every change here is gated on pharmacy context (`hospitalType === 'PHARMACY'` or the pharmacy plan type).
- The platform **medicine master** (`medicine_list`) is shared by hospital/clinic. Do **not** alter it or its API. Pharmacy-side relabeling of "type"→"category" is frontend-only and standalone-pharmacy-only.

## Key decisions (from brainstorming)

1. **Plan type is exactly one mode** (radio, already implemented): `SINGLE_PHARMACIST_ADMIN` / `SINGLE_PHARMACY` / `MULTI_PHARMACY`. Not multi-select.
2. **Branch = sub-entity under one tenant** (Multi Pharmacy only). One pharmacy tenant owns a `pharmacy_branch` table; pharmacy data tables gain a `branch_id`. (Chosen over separate-tenant despite being more invasive.)
3. **Logins use email + password** (existing auth). Pharmacist/branch logins are `PHARMACIST` users; no new "username" credential.
4. **Single Pharmacist Admin reuses the single-doctor pattern** — one account, top-right switcher toggles Admin ↔ Pharmacist view (new `isSinglePharmacist`-style flag).
5. **Manufacturer in the purchase form is free text** (platform list has no manufacturer; name+type come from the platform list).
6. **Build order:** Phase 1 = pharmacist dashboard changes → Phase 2 = Single Pharmacy admin → Phase 3 = Single Pharmacist Admin → Phase 4 = Multi Pharmacy.

## Deriving the mode

The pharmacy plan type is stored as a module string on the plan and propagated to the tenant's `modules`. Frontend derives:

```
pharmacyMode = modules.includes('MULTI_PHARMACY') ? 'MULTI'
             : modules.includes('SINGLE_PHARMACIST_ADMIN') ? 'SOLO'
             : modules.includes('SINGLE_PHARMACY') ? 'SINGLE'
             : null
```

(Only one is ever present, per decision 1.)

---

## A. Single Pharmacy admin dashboard

Sidebar: **Overview · Pharmacists · Billing · Analytics · Audit Logs · Settings · Support**
(Overview, Audit Logs, Settings, Support already exist and work.)

- **Pharmacists tab** — list + create. Create form fields: name, phone, **"Give login access"** toggle. When on, reveal **email + password**; submitting creates a `PHARMACIST` user scoped to this tenant who logs in at `/login/pharmacy`. When off, store a staff-only record with no login.
- **Billing tab** — paginated list of this tenant's pharmacy sales bills, 10 per page with prev/next arrows (same pattern as Audit Logs). Read-only.
- **Analytics tab** — reuse existing pharmacy analytics/reports data.

## B. Single Pharmacist Admin dashboard

Identical to Single Pharmacy **without the Pharmacists tab** (one login only; the admin is the sole pharmacist). Adds a **top-right Admin ↔ Pharmacist switcher** mirroring the single-doctor implementation: the one `HOSPITAL_ADMIN` account can render either the admin dashboard or the full pharmacist dashboard for its own shop.

## C. Multi Pharmacy admin dashboard

Sidebar: **Overview · Pharmacies · Billing · Analytics · Audit Logs · Settings · Support**

**Branch data model** (sub-entity):
- New `pharmacy_branch` table: `id`, `hospital_id` (owner tenant), `name`, `address`, `phone`, `login_user_id` (the branch's `PHARMACIST` user), timestamps.
- Pharmacy data tables (`medicines`, `medicine_purchase`, sales/bills, `suppliers`, returns, expiry) gain `branch_id`. All pharmacist-dashboard queries become branch-scoped when in Multi mode.

**Pharmacies tab**:
- Table of branches for this owner.
- **Create branch** button (top-right) → form: branch name/address/phone + **email + password** (the branch's single `PHARMACIST` login). Enforce branch count ≤ plan `maxOutlets` (null = unlimited).
- Row three-dot actions: **Edit details**, **Reset password**, **Delete branch**.
- Row **Open branch** → full-screen pharmacist dashboard scoped to that branch. Sidebar = normal pharmacist tabs **plus** two extra: **Add Pharmacists** (staff records, no login) and **Back** (returns to the owner admin dashboard).

**Billing & Analytics tabs**: top-right **branch filter dropdown** — "All branches (merged)" (aggregate) plus one entry per branch. Dropdown (not toggle) because the list can be long.

---

## D. Pharmacist dashboard (standalone pharmacy only) — Phase 1

Tabs: **Dashboard · Billing Counter · Inventory · Purchase Management · Suppliers · Returns & Refunds · Expiry Management · Reports & Analytics**. (Medicine Master already removed.)

> **Gating:** all four changes below apply only when `isStandalonePharmacy` is true. A hospital/clinic in-house pharmacy renders these views unchanged.

1. **Purchase form** (`PurchaseForm.jsx`):
   - **Medicine name** — searched from the platform medicine list (`name`).
   - **Type** — auto-fills from the selected platform medicine's `type` on select, but remains **editable** (a single medicine may be bought in a different form).
   - **Manufacturer** — new **free-text** field per line.
   - **GST%** — free-typable number, any percentage (not a fixed dropdown).
2. **Inventory tab** (`InventoryView.jsx`):
   - Remove the **"All Categories"** filter control.
   - Relabel **"Type" → "Category"** in the pharmacy UI only (do not touch the platform medicine tab).
   - Remove the **"Add Medicine"** button.
3. **Suppliers tab** (`SuppliersView.jsx`): add a three-dot **action menu** per row — **View details**, **Edit details**, **Delete**.
4. **Expiry Management tab** (`ExpiryView.jsx`): add a **"Dispatch back to supplier"** operation on an expired batch — records a return-to-supplier at an entered (typically lower) rate and decrements stock. Backend records the return.

---

## Phasing

| Phase | Scope | Depends on |
|---|---|---|
| **1** | Pharmacist dashboard changes (section D) | none — independent of modes/branches |
| **2** | Single Pharmacy admin (section A) | — |
| **3** | Single Pharmacist Admin dual dashboard (section B) | single-doctor pattern |
| **4** | Multi Pharmacy + branch model (section C) | branch_id data model (largest, most invasive) |

Each phase becomes its own implementation plan. This document is the shared architecture.

## Open items to resolve per-phase (not blocking the design)

- **Phase 1 / Expiry**: exact shape of the return-to-supplier record (new table vs. reuse of existing returns model) — decide during Phase 1 planning after reading the returns/expiry code.
- **Phase 4 / branch_id**: migration strategy for adding `branch_id` to existing pharmacy tables and back-filling a default branch for any pre-existing Multi tenant.
