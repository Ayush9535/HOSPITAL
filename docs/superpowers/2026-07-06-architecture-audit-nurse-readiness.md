# HMS Architecture Audit & Nurse-Module Readiness — 2026-07-06

Prepared from a full read of the codebase on branch `pharmacy`
(HEAD `ef15397` — "clinc and pharamcy all completed and isolated").
Everything below is based on the actual implementation; nothing is assumed.

---

## 1. Project Overview

### Overall architecture
A **multi-tenant SaaS monolith** in a monorepo:

- `backend/` — single Spring Boot application (Java 17, Maven) exposing a REST
  API on port 8080 plus a raw WebSocket endpoint.
- `frontend/` — single React 18 + Vite SPA on port 5173.
- `setup/` — canonical SQL schema (`schema-full.sql`), migrations, seed scripts.
- One shared MySQL 8 database, **shared-schema multi-tenancy** (row-level
  isolation via a `hospital_id` column, not schema-per-tenant).

There are **two tiers**: Platform (Super Admin manages tenant organizations,
plans, global catalogs) and Tenant (a "hospital" record that can be one of
three flavors — `HOSPITAL`, `CLINIC`, `PHARMACY` via the `HospitalType` enum).

### Tech stack
| Layer | Technology |
|---|---|
| Backend | Spring Boot, Spring Security (`@EnableMethodSecurity`), Spring Data JPA/Hibernate, Spring AOP, Spring Scheduling/Async, springdoc (Swagger) |
| Auth | JWT (jjwt), HS256, stateless; BCrypt password hashing |
| DB | MySQL 8; `spring.jpa.hibernate.ddl-auto=update` + `DatabaseMigrationRunner` (idempotent check-then-patch on `ApplicationReadyEvent`); `open-in-view=false` |
| Cache | Redis (optional — `RedisConfig` swallows cache errors so app runs without it) |
| Real-time | Raw Spring WebSocket (`HospitalWebSocketHandler` + JWT handshake interceptor), broadcast per `hospitalId` |
| PDF | OpenPDF + Thymeleaf templates (`case-paper.html`, etc.) via `PdfService` + `service/pdf/` (`BillingPdfService`, `ClinicalPdfService`, `ReportPdfService`, `PdfLayoutHelper`) |
| Messaging | WhatsApp Business API integration (per-tenant config), event-driven + schedulers |
| Frontend | React 18 (JSX, not TS), Vite, React Router 7, axios, `@tanstack/react-table`, Tailwind utility classes, code-split dashboards (`React.lazy`) |
| Frontend tests | Vitest-style unit tests exist for a few components/hooks (`*.test.jsx`); no E2E runner configured |

### Folder structure (backend `com.hms`)
```
component/    DataInitializer, PlatformStatsVerifier, PublicIdBackfillRunner
config/       SecurityConfig, RedisConfig, WebSocketConfig, AsyncConfig,
              JacksonConfig, OpenApiConfig, DatabaseMigrationRunner,
              WhatsAppTemplateConstants
controller/   hospital/ (21), platform/ (10), pharmacy/ (9 — standalone ERP),
              publicapi/ (2)
dto/          flat request/response DTOs + dto/pharmacy/
entity/       46 flat entities + entity/pharmacy/ (11 ERP entities)
event/        AppointmentCreatedEvent, ConsultationCompletedEvent,
              MedicineDispensedEvent
exception/    UnauthorizedException etc.
filter/       RateLimitFilter
repository/   Spring Data repos, + repository/pharmacy/
scheduler/    AppointmentReminderScheduler, PlanExpiryScheduler,
              WhatsAppRetryScheduler
security/     JwtUtil, JwtAuthenticationFilter, SecurityContextHelper,
              UserAuthenticationDetails, RequireModule + ModuleAccessAspect,
              WebSocket handler/interceptor
service/      hospital/ (19), platform/ (9), pharmacy/ (10), pdf/, whatsapp/,
              AuditLogService, PdfService
```

Frontend:
```
src/pages/hospital/    one large file per role dashboard + pharmacy/ (16 ERP views)
src/pages/platform/    PlatformDashboard, PlatformLogin
src/components/        ~50 shared components (+ components/pharmacy/, components/auth/)
src/services/          apiService (axios + interceptors), authService,
                       hospitalService (929 lines), platformService, wardService,
                       + services/pharmacy/ (9 per-domain API modules)
src/hooks/             useWebSocket, useFetch, useModal, useDebounce, pharmacy/useViewManager
src/context/           ToastContext
```

### Design patterns
- **Layered MVC**: Controller → Service → Repository → Entity. DTOs at the edge
  (inconsistently — many endpoints return entities or `Map<String,Object>` directly).
- **Aspect-oriented module gating**: `@RequireModule("X")` + `ModuleAccessAspect`.
- **Event-driven side effects**: `ApplicationEventPublisher` for
  appointment-created / consultation-completed / medicine-dispensed →
  `WhatsAppEventListener`.
- **Interface + impl** only in the newer pharmacy ERP (`service/pharmacy/impl/`);
  older hospital services are concrete classes with `@Autowired` field injection.
- **Frontend**: page-level "god components" (HospitalAdminDashboard = 5,695
  lines) with co-located table components at the bottom; shared primitives
  (`DataTable`, `ActionMenu`, `StatusBadge`, `ConfirmationModal`); newer pharmacy
  ERP is properly decomposed into per-view files + per-domain API modules.

### Multi-tenant implementation
- Every tenant-scoped table carries `hospital_id`.
- Tenant identity travels in **JWT claims**: `userId`, `role`, `hospitalId`
  (null for Super Admin), `modules` (enabled module list), `branchId`
  (Multi-Pharmacy branch logins only).
- `JwtAuthenticationFilter` parses the token and stores a
  `UserAuthenticationDetails` object on the `Authentication`;
  `SecurityContextHelper.getCurrentHospitalId()/getCurrentBranchId()/getCurrentUserId()/getCurrentUserRole()`
  is injected into every service, and every query/write is scoped by it
  (`findByIdAndHospitalId`, `findByHospitalIdAnd...`).
- Tenant *flavor* (`Hospital.type` = HOSPITAL/CLINIC/PHARMACY) is echoed to the
  frontend as `hospitalType` and drives portal selection and UI gating.

### Authentication flow
1. `POST /platform/login` (Super Admin) or `POST /login` (all tenant roles;
   `HospitalAuthController`). Separate frontend portals: `/platform/login`,
   `/login/hospital`, `/login/clinic`, `/login/pharmacy` (same `HospitalLogin`
   component, `portalType` prop).
2. Service verifies BCrypt password against the single `users` table, checks
   `isActive` and tenant `subscriptionStatus`, then issues a JWT containing
   userId/role/hospitalId/modules/branchId.
3. Frontend stores `token` + `user` JSON in **sessionStorage** (tab-isolated);
   `localStorage.lastPortal` remembers which login page to return to.
4. Axios request interceptor injects `Authorization: Bearer <token>`; the 401
   response interceptor clears the session and redirects to
   `authService.getLoginUrl()` (portal-aware).
5. Stateless sessions (`SessionCreationPolicy.STATELESS`); a `RateLimitFilter`
   runs before the JWT filter.

### Authorization flow (three layers)
1. **URL namespace** (SecurityConfig): `/platform/**` → `SUPER_ADMIN`;
   `/hospital/**`, `/clinic/**`, `/pharmacy/**` → any of `HOSPITAL_ADMIN`,
   `DOCTOR`, `RECEPTIONIST`, `PHARMACIST`; `/ws/**` → those four + SUPER_ADMIN.
2. **Method-level roles**: ~120 `@PreAuthorize("hasRole/hasAnyRole(...)")`
   annotations on controllers (role strings hardcoded per endpoint).
3. **Module gating**: `@RequireModule("IPD"|"BILLING"|"APPOINTMENTS"|
   "HOSPITAL_INVENTORY"|"REPORTS"|"MEDICAL_INVENTORY")` checked by
   `ModuleAccessAspect` against the JWT `modules` claim (Super Admin bypasses).
   Module list per tenant lives in `hospital_modules` and is driven by the
   subscribed Plan (`plans` / `plan_modules` / `plan_features` /
   `hospital_plan_subscriptions`).

### Tenant isolation
Row-level, enforced **in code, per service method** — there is no Hibernate
filter or discriminator. The pattern is disciplined (`findByIdAndHospitalId`
everywhere, cross-tenant lookups throw "not found") but relies on every new
method remembering to scope. Multi-Pharmacy adds a second scoping dimension
(`branchId`) used by ERP repos (e.g. `findByIdAndHospitalIdForUpdate(id,
hospitalId, branchId)`).

### Module isolation
- **Route-level**: hospital controllers are triple-mapped
  `{"/hospital/x", "/clinic/x", "/pharmacy/x"}` so each tenant flavor uses its
  own namespace against shared handlers.
- **Feature-level**: `@RequireModule` + JWT modules claim; frontend hides tabs
  whose module is absent (`useModule` hook / `user.modules.includes`).
- **Code-level**: the standalone Pharmacy ERP is genuinely isolated in its own
  packages (`controller/pharmacy`, `service/pharmacy`, `entity/pharmacy`,
  `frontend/src/pages/hospital/pharmacy/`, `services/pharmacy/`) — the most
  recent commit message ("clinc and pharamcy all completed and isolated")
  reflects this.

### Coding standards observed
- Entities: Lombok `@Data` **plus** hand-written getters/setters (redundant but
  consistent), `@Table(name=...)`, IDENTITY ids, `publicId` (UUID) + `customId`
  (readable, e.g. `HSP1234`) generated in `@PrePersist`, `isActive` soft-delete,
  `@CreationTimestamp`.
- Repos: derived query names; `JOIN FETCH` variants where `open-in-view=false`
  bites (`OpdRepository.findByIdWithPatientAndDoctor`).
- Services: field injection, `SecurityContextHelper` scoping,
  `IllegalArgumentException` → 400, best-effort audit logging in try/catch,
  `@Transactional` on multi-write paths.
- Controllers: `ResponseEntity<?>` + try/catch → `badRequest(msg)`; javadoc
  headers with "Phase-N" version tags.
- Tests: JUnit 5 + Mockito + AssertJ; `@WebMvcTest` with a nested
  `@EnableMethodSecurity` config (documented pattern; suite was 115+ passing).
- Full conventions are codified in `docs/superpowers/2026-07-04-antigravity-handoff-3.md` §8.

---

## 2. Current Modules

### 2.1 Authentication & Session
- **Purpose**: login for platform + tenant users, profile, settings.
- **Status**: complete.
- **APIs**: `POST /platform/login`; `POST /login`; `GET /auth/me`;
  `PUT /auth/profile`; tenant settings under
  `/{hospital|clinic|pharmacy}/settings/{fees,operations,barcode}`;
  `GET /{ns}/subscription`.
- **Tables**: `users` (single credential store for all roles), `hospital_settings`.
- **Features**: portal-aware login, module claims, rate limiting, dual-hat
  logins (single-doctor admin; solo-pharmacist admin via the
  `SINGLE_PHARMACIST_ADMIN` module marker).
- **Limitations**: no refresh tokens, no password-reset self-service (admin
  resets only), no MFA, JWT revocation not possible (stateless only).

### 2.2 Platform (Super Admin)
- **Purpose**: tenant lifecycle + global catalogs + support.
- **APIs**: `/platform/hospitals` (CRUD, stats, status toggle, reset-password),
  `/platform/plans` (CRUD + `/{publicId}/assign`), `/platform/users`,
  `/platform/medicines` (global medicine catalog + CSV import),
  `/platform/inventory-master` (global inventory item names + CSV import),
  `/platform/faqs`, `/platform/tickets`, `/platform/audit-logs`,
  `/platform/whatsapp/stats`.
- **Tables**: `hospitals`, `hospital_modules`, `plans`, `plan_modules`,
  `plan_features`, `hospital_plan_subscriptions`, `medicines_list`
  (`MedicineList`), `inventory_master_items`, `faqs`, `support_tickets`,
  `audit_logs`.
- **Features**: onboarding creates tenant + admin user; plan assignment writes
  the tenant's module list; plan types derive from tenant flavor (recent work).
- **Limitations**: no real billing/invoicing of tenants (subscription status is
  a string field); `PlatformDashboard.jsx.backup` sitting in the tree.

### 2.3 Patient
- **Purpose**: tenant-scoped patient registry and consultation lifecycle.
- **APIs**: `/{ns}/patients` CRUD, `/{publicId}/status`,
  `/{publicId}/start-consultation`, `/{publicId}/consultation-details`,
  `/{publicId}/latest-prescription`, medicine/prescription PDFs per OPD/IPD,
  `/report/pdf`.
- **Tables**: `patients` (with `date_of_birth` + computed age; UHID/NABH merge
  columns exist on the *other* branch's schema only).
- **Relationships**: referenced by appointments, opd, ipd_admission,
  medical_records, billing.
- **Status lifecycle**: `PatientStatus` enum REGISTERED → CONSULTING → COMPLETED.
- **Limitations**: no patient portal/login; no document uploads.

### 2.4 Doctor & Consultation
- **Purpose**: doctor staff CRUD + the consultation engine.
- **APIs**: `/{ns}/doctors` CRUD + reset-password + search;
  `POST /doctors/consultation` (submit), `GET /consultation/{appointmentId}`,
  prescription PDFs (`/prescription/{appointmentId}/pdf`, `/prescription/opd/{opdId}/pdf`).
- **Tables**: `doctors` (profile; FK to `users` for credentials),
  `medical_records` (symptoms, diagnosis, treatment notes, follow-up date,
  visitType OPD/IPD, `administered_items_json`), `prescriptions` (one row per
  medicine: dosage, frequency "1-0-1", duration, type TABLET/INJECTION/IV_FLUID,
  route ORAL/IV/IM, status ACTIVE/STOPPED/COMPLETED), `lab_orders`
  (entity + repo exist, written from `DoctorService`, **no controller/read UI** —
  effectively dormant), `consultation_note_presets`, `prescription_presets`
  + `prescription_preset_items`.
- **Features**: consultation modal with vitals, dx, note presets (chips),
  prescription presets (bundles), services/items consumption
  (`consumeService`), OPD queue integration.
- **Limitation**: consultation submission is a wide `ConsultationRequest`
  handled inside `DoctorService` — the single busiest code path.

### 2.5 Staff — Receptionist / Pharmacist
- **Purpose**: per-role staff CRUD by the tenant admin.
- **APIs**: `/{ns}/receptionists` and `/{ns}/pharmacists` — identical shapes:
  POST, GET list, GET/{id}, PUT/{id}, DELETE/{id}, POST/{id}/reset-password;
  both class-level `@PreAuthorize("hasRole('HOSPITAL_ADMIN')")`.
- **Tables**: `users` + thin profile entities (`Receptionist`, `Pharmacist`;
  also legacy `ClinicAdmin`, `HospitalAdmin`, `PharmacyAdmin` marker entities).
- **Pattern**: service creates the `User` row (role, BCrypt password,
  hospital_id) + the profile row; sequential `customId` for receptionists.
- **Limitations**: no departments, no shifts/rosters, no staff self-service.

### 2.6 Appointment
- **APIs**: `/{ns}/appointments` — create, list, `/today`, `/my-appointments`
  (doctor), `/doctor/{id}`, `/patient/{id}`, `/{id}` get/delete,
  `/{id}` update, `/{id}/status`, `/stats`. Gated `@RequireModule("APPOINTMENTS")`.
- **Tables**: `appointments`.
- **Features**: inline new-patient creation; publishes
  `AppointmentCreatedEvent` → WhatsApp confirmation;
  `BillingService.autoGenerateOpdBill(appointment)` auto-creates the OPD bill;
  daily 9 AM IST WhatsApp reminder scheduler.

### 2.7 OPD & Queue
- **APIs**: `/{ns}/opd` — create (vitals + problem + visit type NEW/FOLLOWUP),
  list, `/{id}`, `/{id}/pdf` (case paper), `/queue`, `/queue/my`,
  `/queue/doctor/{id}`, `/today-followups`, `/report/pdf`.
- **Tables**: `opd` (caseId unique, vitals bp/temp/pulse/weight/spo2, status
  QUEUED → CONSULTED → COMPLETED / IN_IPD, FKs patient/doctor/receptionist),
  `queue_entry` (opd + doctor + timestamp).
- **Features**: auto-queue of today's follow-ups; case-paper PDF.
- **Note**: vitals live **on the OPD row** — one snapshot per visit, no
  time-series vitals entity.

### 2.8 IPD, Wards & Beds
- **APIs**: `/{ns}/ipd` — `/admit`, list + `/admissions` + `/my`, `/{id}`,
  `/{id}/followup`, `/{id}/plan-discharge`, `/{id}/confirm-discharge`,
  `/{id}/administer` (medicines), `/{id}/administer-hospital-items`,
  `/{id}/prescriptions` (add), `/prescriptions/{id}/stop`, `/{id}/change-bed`.
  `/{ns}/wards` CRUD + `/bulk` + `/{wardId}/beds`; `/{ns}/beds/{bedId}`
  (status), `/beds/available`.
- **Tables**: `ipd_admission` (ipdNumber, admissionType EMERGENCY/ELECTIVE,
  status ADMITTED/DISCHARGED, sourceOpdId, wardId/bedId, primaryDiagnosis),
  `ipd_bed_history`, `wards`, `beds`, `discharge_summary` (1:1 with admission).
- **Features**: admit from OPD (OPD status → IN_IPD), bed occupancy tracking +
  bed-change history, IPD prescriptions with stop action, medicine/item
  administration (recorded into `medical_records.administered_items_json` and
  billed), two-step discharge (plan → confirm) producing a discharge summary
  and an IPD bill. Frontend: `IpdDetails.jsx` (1,342 lines), `WardsAndBeds.jsx`.
- **Limitation**: administration is a JSON blob on the medical record — no
  first-class Medication Administration Record (who/when/dose signature).

### 2.9 Billing
- **APIs**: `/{ns}/billing` — list, `/{id}/status`, `/{id}/items`,
  `/{id}/pdf`, `/ipd/{ipdId}/bill`, `/{billingId}/pay`,
  `/patient/{patientPublicId}`. Fees config: `/{ns}/settings/fees` +
  `/{ns}/settings/fees/custom` (`HospitalFee`). `@RequireModule("BILLING")`.
- **Tables**: `billing` (billingType OPD/IPD, paymentStatus, method,
  reference, markedPaidBy), `billing_items`, `billing_medicines` (dispensed
  medicines appended), `billing_payments` (partial payments), `hospital_fees`,
  `hospital_services` + `hospital_service_items` (service charge master),
  `billing_period` entity (platform-side).
- **Features**: auto OPD bill on appointment; consultation adds
  service/consultation charges; pharmacy dispensing appends medicine lines;
  partial payments; UPI reference capture; receipt PDF; publishes
  `ConsultationCompletedEvent`.
- **Limitations**: no insurance/TPA, no refunds/credit notes on the hospital
  side (returns exist only in the pharmacy ERP), no GST breakdown on hospital
  bills.

### 2.10 Hospital Pharmacy (in-hospital dispensing) — distinct from the ERP
- **APIs**: `/{ns}/pharmacy` — `/inventory`, `/inventory/low-stock`,
  `/inventory/stock` (PHARMACIST only), `/prescriptions/pending`,
  `/dispense/{prescriptionId}`. Medicine stock: `/{ns}/medicines`
  (`@RequireModule("MEDICAL_INVENTORY")`) — `/search`, `/catalog` (global
  platform list), `/purchases`, `/inventory` CRUD.
- **Tables**: `medicines` (per-tenant stock), `medicine_purchases`,
  `medicines_list` (global catalog).
- **Flow**: pharmacist sees ACTIVE prescriptions → dispenses → stock deducted,
  `MedicineDispensedEvent` published (WhatsApp "medicines ready"), line added
  to the patient's bill.
- **Limitations**: no batch/expiry tracking at hospital-pharmacy level (that
  exists only in the ERP), quantity-only stock.

### 2.11 Standalone Pharmacy ERP (PHARMACY tenant flavor)
- **Purpose**: full retail pharmacy ERP for standalone pharmacy tenants,
  including multi-branch.
- **APIs** (all under `/pharmacy/...`): `/categories`, `/manufacturers`,
  `/suppliers` (CRUD + status toggles); `/medicines` master (+ search,
  autocomplete, catalog search, status); `/purchases` (create draft,
  `/{id}/post` to stock); `/inventory` (list, `/search-batches`, `/low-stock`,
  `/expiring`, `/adjust`, `/transactions/{batchId}`, batch `/block` +
  `/dispose`, `/supplier-return`, `/returns-history`); `/sales` (POS create,
  list, `/{id}`, `/{id}/pdf`, `/stats`, `/search`, `/{id}/return`);
  `/branches` (CRUD + reset-password); `/reports` (`/dashboard`, `/export`).
- **Tables**: `medicine_master`, `medicine_categories`, `manufacturers`,
  `suppliers`, `purchase_invoices` + `purchase_invoice_items`,
  `medicine_batches`, `inventory_transactions` (full stock ledger with
  qty-before/after), `pharmacy_sales` + `pharmacy_sale_items`, `sale_returns`,
  `storage_locations`, `pharmacy_branch`, `pharmacy_audit_logs`.
- **Features**: batch-level stock with expiry; **pessimistic locking** on sale
  deduction (`findByIdAndHospitalIdForUpdate`); expiry-safe returns; branch
  scoping via JWT `branchId`; audit logs; 16 dedicated frontend views under
  `pages/hospital/pharmacy/` with their own API modules and `useViewManager`.
- **This is the most mature, best-isolated module in the codebase.**

### 2.12 Hospital Inventory & Services (consumables)
- **APIs**: `/{ns}/hospital-inventory` (`@RequireModule("HOSPITAL_INVENTORY")`)
  — purchases, inventory CRUD, `/low-stock`; `/{ns}/inventory-master` (global
  item names); `/{ns}/services` CRUD (service = name + charge + relevant
  master items).
- **Tables**: `inventory_master_items` (platform-global names),
  `hospital_inventory` + `hospital_inventory_purchases` (per-tenant stock),
  `hospital_services` + `hospital_service_items`; legacy `inventory_items`
  retained in DB but unused.
- **Model**: doctors pick **Services** during consultation/IPD; the service
  validates all relevant items are in stock ("Some items are out of stock"
  toast), deducts stock, returns the charge to billing. Low-stock alerts are
  role/tenant-gated (Hospital: admin+receptionist; Clinic: +doctor).

### 2.13 Communication (WhatsApp) & Support
- **APIs**: `/{ns}/whatsapp` — `/broadcast`, `/logs`, `/logs/failed-count`,
  `/config` (get/set/delete/test); `/platform/whatsapp/stats`;
  `/{ns}/tickets` (tenant → platform helpdesk), `/platform/tickets`;
  FAQs public read (`/api/public/faqs`) + platform CRUD.
- **Tables**: `whatsapp_config`, `whatsapp_message_log`, `support_tickets`, `faqs`.

### 2.14 Cross-cutting: Audit, Stats, WebSocket
- `AuditLogService` writes `audit_logs` (tenant + platform read endpoints, plus
  a `/audit-logs/pharmacy` view and separate `pharmacy_audit_logs`).
- `/{ns}/stats` + `/stats/analytics` + `/stats/patient-activity(+/pdf)`
  (`@RequireModule("REPORTS")`).
- WebSocket: JWT-authenticated handshake; `broadcast(hospitalId, json)` pushes
  dashboard refresh events; consumed by `useWebSocket` + `ActivityFeed`.

### Visible future TODOs in code
- `Opd` status enum already contains `IN_IPD`; sidebar icon map already
  contains **"Pathology"** and **"Operation Theatre"** entries and the admin
  sidebar group lists `'ot', 'pathology'` tab ids — plan modules `OT` and
  `PATHOLOGY` exist in `PlansTab.jsx` (`AVAILABLE_MODULES`) but have **no
  backend controllers/entities on this branch** (OT work lives on the other
  branch per handoff docs).
- `ConsultationNotePreset.fieldType` is "reserved for future Diagnosis reuse".
- `LabOrder` entity/repo written but never surfaced (no controller, no UI).
- A `@PreAuthorize` in the ERP references roles `'PHARMACY_ADMIN'` and
  `'INVENTORY_MANAGER'` that no login can ever have — declared intent for
  future finer-grained pharmacy roles.
- `PharmacyController` javadoc: "Grouping by Patient/Consultation might be
  better for UI … easier for V1 API".

---

## 3. User Roles

Roles are **strings on `users.role`** — there is no role/permission table.

### SUPER_ADMIN (`hospitalId = null`)
- Full `/platform/**`; bypasses module checks (aspect returns early).
- Frontend: `/platform/dashboard` only.
- Restricted: cannot call tenant namespaces as a tenant (no hospitalId scope).
- Expansion: platform staff sub-roles would require a permissions model.

### HOSPITAL_ADMIN (tenant owner — also the "Clinic Admin" and "Pharmacy Admin")
- Appears in virtually every tenant `@PreAuthorize`; exclusive rights: staff
  CRUD (doctors/receptionists/pharmacists), tickets, WhatsApp config, settings,
  fees, plans view, branch management (ERP).
- Frontend: `/hospital/admin` (grouped sidebar for HOSPITAL flavor: Patient
  Management / Staff / Pharmacy / Inventory / Finance / Reports /
  Communication / Administration). Dual-hat: with `isSingleDoctor` also gets
  `/hospital/doctor`; with `SINGLE_PHARMACIST_ADMIN` module also gets
  `/hospital/pharmacy`.
- Restricted: nothing within their tenant except PHARMACIST-only stock update.

### DOCTOR
- APIs: own appointments/queue (`/my-appointments`, `/queue/my`),
  consultations, prescriptions + presets, IPD clinical actions, patient
  read/status, medicines search; included in stats.
- Frontend: `/hospital/doctor` (DoctorDashboard), `/ipd/:id`.
- Restricted: staff CRUD, billing status changes, settings, WhatsApp,
  dispensing.

### RECEPTIONIST
- APIs: patient CRUD, appointments, OPD creation (vitals entry!), queue, IPD
  admit/bed management, billing (mark paid, items), wards/beds, low-stock view.
- Frontend: `/hospital/receptionist`, `/ipd/:id`.
- Restricted: consultations/prescriptions, staff CRUD, pharmacy stock,
  settings.

### PHARMACIST
- APIs: hospital dispensing namespace (`/{ns}/pharmacy/**`), medicine
  inventory/purchases, and the whole ERP namespace for PHARMACY tenants
  (branch-scoped when `branchId` present).
- Frontend: `/hospital/pharmacy` (PharmacyDashboard → hospital dispensing or
  full ERP shell depending on tenant flavor).
- Restricted: everything clinical and administrative.

### Expansion notes
The role system is **hardcoded strings in ~120 annotations + SecurityConfig +
frontend route guards** — adding a role is mechanical but touches many files.
`PHARMACY_ADMIN` / `INVENTORY_MANAGER` are already referenced in one
annotation, anticipating future roles. There is no NURSE anywhere in the
codebase today (verified by grep).

---

## 4. Current Hospital Workflow (HOSPITAL tenant, end to end)

```
Patient Registration (Receptionist; DOB, demographics)
        ↓
Appointment (optional; APPOINTMENTS module)
        ├── AppointmentCreatedEvent → WhatsApp confirmation
        └── BillingService.autoGenerateOpdBill() → OPD bill created
        ↓
OPD Visit created (Receptionist enters vitals: BP/temp/pulse/weight/SpO2 + problem)
        ↓  status QUEUED, QueueEntry per doctor; today's follow-ups auto-queued
Doctor Consultation (start-consultation → PatientStatus CONSULTING)
        │  symptoms, diagnosis, treatment notes (+note presets)
        │  prescriptions (+prescription presets)   → prescriptions rows (ACTIVE)
        │  services consumed (consumeService: stock check → deduct → charge)
        ↓  MedicalRecord written; OPD → CONSULTED; ConsultationCompletedEvent
   ┌────┴─────────────────────────────┐
   │ (needs admission)                │ (outpatient)
   ↓                                  ↓
IPD Admit (ward/bed assigned,      Billing updated (consultation fee,
 OPD → IN_IPD)                      case-paper fee, service charges)
   ↓                                  ↓
Daily cycle: followups, IPD Rx,    Pharmacy: pending ACTIVE prescriptions
 administer meds/items (billed)     → dispense → stock deducted →
   ↓                                  BillingMedicine lines + WhatsApp
Plan Discharge → Confirm            "medicines ready" (MedicineDispensedEvent)
 Discharge → DischargeSummary          ↓
 + IPD bill                        Reception marks bill PAID
   ↓                                (cash / UPI + UTR; partial payments
IPD bill settled at reception       supported) → receipt PDF
```
Real-time WebSocket broadcasts refresh the role dashboards throughout.

## 5. Clinic Workflow (CLINIC tenant)
Same engine, reduced module set (defaults in `PlansTab.jsx`: OPD, PHARMACY,
BILLING, APPOINTMENTS, MEDICAL_INVENTORY, REPORTS — **no IPD/wards**):

1. Usually **single-doctor mode** (`isSingleDoctor`): the admin toggles between
   Admin and Doctor dashboards (`activeDashboard` preference).
2. Receptionist (or the doctor-admin) registers patient → OPD with vitals →
   queue.
3. Doctor consults, prescribes, consumes services; low-stock banner is shown
   to the **doctor** in clinics (in hospitals only admin+receptionist see it).
4. Billing → optional in-clinic dispensing via the hospital-pharmacy module.
5. Flat sidebar (grouped sidebar is HOSPITAL-flavor only); logins at
   `/login/clinic`; cross-portal isolation enforced (recent commits).

## 6. Pharmacy Workflow (PHARMACY tenant — standalone ERP)
1. **Masters**: categories, manufacturers, suppliers, medicine master
   (autocomplete/catalog search).
2. **Procurement**: purchase invoice drafted → `POST /{id}/post` creates
   `medicine_batches` (batch no., expiry, MRP/cost) and ledger entries.
3. **Inventory**: batch list, low-stock, expiring soon, adjustments,
   block/dispose batch, supplier returns — every movement recorded in
   `inventory_transactions` (qty before/after).
4. **POS Sale** (Billing Counter view): batch search → sale with
   pessimistic-locked stock deduction → invoice PDF; sale returns with expiry
   safety check.
5. **Reports**: dashboard KPIs + export.
6. **Multi-branch**: admin creates `pharmacy_branch` logins; branch users get
   `branchId` in JWT and see only branch-scoped stock/sales. Solo mode:
   admin doubles as pharmacist (`SINGLE_PHARMACIST_ADMIN`).
7. Hospital/clinic tenants reuse none of this — their dispensing is the
   simpler `medicines`-table flow (§2.10).

## 7. Hospital-specific workflows recap
- OPD flow (§4), IPD flow (admit → bed lifecycle + `ipd_bed_history` →
  clinical documentation → two-step discharge), Ward/Bed management (bulk ward
  creation, bed status, availability), staff onboarding (admin creates
  doctor/receptionist/pharmacist with generated credentials + reset-password),
  fees & services configuration, WhatsApp broadcast + auto-notifications,
  support tickets to platform, analytics/patient-activity reports (PDF),
  audit-log review.

---

## 8. Database Design

Canonical schema: `setup/schema-full.sql` (48 tables, 25 explicit FK
constraints, ~49 keys/unique indexes — mostly Hibernate-generated FK indexes
and unique keys on `public_id` / natural keys like `opd.case_id`,
`ipd_admission.ipd_number`, `medicine_master.medicine_code`).

- **Tenant mapping**: `hospitals` is the tenant root; `hospital_modules`
  (element collection) holds enabled modules; `hospital_plan_subscriptions` →
  `plans` → `plan_modules`/`plan_features` drive them. `hospitals.type`
  distinguishes HOSPITAL/CLINIC/PHARMACY.
- **User/staff mapping**: single `users` table (email unique **globally**,
  role string, `hospital_id` nullable, `branch_id` nullable) + thin profile
  tables (`doctors`, and profile entities for receptionist/pharmacist).
  Doctor is the richest profile (specialization etc.) and is FK-referenced by
  clinical tables.
- **Patient mapping**: `patients.hospital_id`; referenced by `appointments`,
  `opd`, `ipd_admission`, `medical_records`, `billing`, `prescriptions`
  (via medical_record).
- **Clinical chain**: `opd` (vitals snapshot) → `medical_records`
  (opdId/ipdAdmissionId/appointmentId, unique key on appointment_id and opd_id)
  → `prescriptions` (medical_record_id). IPD: `ipd_admission` → `wards`/`beds`
  (+ `ipd_bed_history`), 1:1 `discharge_summary` (unique ipd_admission_id).
- **Billing chain**: `billing` (opdId/ipdAdmissionId/patientId) →
  `billing_items`, `billing_medicines`, `billing_payments`.
- **Inventory**: global `inventory_master_items` / `medicines_list`; per-tenant
  `hospital_inventory`(+purchases), `medicines`(+`medicine_purchases`);
  services `hospital_services`(+items). ERP has its own complete graph
  (§2.11) keyed by `hospital_id` (+`branch_id`).
- **Conventions**: BIGINT identity PKs everywhere; `public_id` UUID exposed to
  the UI instead of numeric ids on newer endpoints; many "FKs" are plain
  columns (e.g. `ipd_admission.patient_id`, `billing.*_id`) **without**
  DB-level constraints — integrity is app-enforced. There are **no composite
  indexes on `(hospital_id, …)` hot paths** beyond what Hibernate generates.

## 9. API Architecture

- **Route structure**: role/tier namespaces, not resource versioning:
  `/platform/**`, `/{hospital|clinic|pharmacy}/**` (triple-mapped shared
  controllers), ERP under bare `/pharmacy/*` resource roots, public under
  `/api/public/**`, WebSocket `/ws/**`. **No API versioning** anywhere.
- **Controller organization**: one controller per aggregate, thin, try/catch →
  400 with message body; `ApiResponse` DTO exists but most endpoints return
  entities, Maps, or bare lists.
- **Service organization**: `service/hospital` vs `service/platform` vs
  `service/pharmacy` (ERP, with interface+impl only for newer code) + shared
  `whatsapp/`, `pdf/`.
- **DTOs**: flat `dto/` package, request-oriented (`CreateOpdRequest`,
  `ConsultationRequest`, `AdministerItemsRequest`…), plus `dto/pharmacy/`.
  Response DTOs are the exception, not the rule.
- **Validation**: mix of `jakarta.validation` (`@Valid` on newer endpoints,
  `@Validated` on Ward/Bed controllers) and manual service-level checks
  throwing `IllegalArgumentException` → 400. No global `@ControllerAdvice`
  contract for error shape.
- **Naming**: plural resource nouns, verb sub-paths for actions
  (`/plan-discharge`, `/confirm-discharge`, `/dispense/{id}`, `/{id}/post`).

## 10. Frontend Architecture

- **Routing** (`App.jsx`): React Router 7; four login routes, five protected
  dashboards + `/ipd/:id`; all lazy-loaded. `LandingRedirect` maps role (and
  dual-hat flags) to the right dashboard. `ProtectedRoute` checks
  sessionStorage auth + `allowedRoles` (with single-doctor / solo-pharmacist
  exceptions).
- **Layouts**: no shared layout component — each dashboard composes
  `Sidebar` + `Navbar` itself.
- **Sidebar generation**: each dashboard builds a `tabs` array gated by
  `user.modules` / `hospitalType`; HOSPITAL admins get a hardcoded
  `SIDEBAR_GROUPS` regrouping (Patient Management, Staff, Pharmacy, Inventory,
  Finance, Reports, Communication, Administration). `Sidebar.jsx` is
  presentational with an icon map (which already includes Pathology/OT/IPD
  icons).
- **Permission rendering**: role prop (`isAdmin`) toggles action columns;
  module checks hide tabs; tenant flavor (`hospitalType`) gates
  grouped-sidebar, banners, portal redirects.
- **API layer**: `apiService.js` axios instance (token inject + 401 handler);
  domain wrappers `hospitalService.js` (929 lines, all hospital calls),
  `platformService.js`, `wardService.js`, `authService.js`; ERP has clean
  per-domain modules in `services/pharmacy/`.
- **State management**: local component state + prop drilling only — no
  Redux/Zustand/React Query. Server refresh via reload functions +
  `useWebSocket` push. `ToastContext` is the one global context.
- **Structure quality split**: legacy dashboards are monoliths
  (Admin 5,695 / Doctor 2,394 / Receptionist 2,138 lines); the pharmacy ERP
  frontend is decomposed per-view — the newer standard to follow.

## 11. Current Staff System

- **Creation**: tenant admin only (`@PreAuthorize("hasRole('HOSPITAL_ADMIN')")`).
  Service creates a `users` row (role, BCrypt password, hospital_id) plus the
  profile row (`Doctor`/`Receptionist`/`Pharmacist`), generates
  `publicId`/`customId` (DOC/USR/ADM prefixes; receptionists sequential).
- **Hierarchy**: flat — SUPER_ADMIN → HOSPITAL_ADMIN → (DOCTOR, RECEPTIONIST,
  PHARMACIST). No departments, no reporting lines, no seniority.
- **Login**: same `/login` endpoint for all tenant staff; role in JWT decides
  the dashboard.
- **Role assignment**: fixed at creation via the endpoint used; no role change
  after creation, no multi-role users (only the two dual-hat admin flags).
- **Permissions**: entirely role-string based (see §3); no per-user overrides.
- **Department assignment**: **does not exist** — no department entity
  anywhere; doctors have a `specialization` text field only.

## 12. Existing Notification System

- **Email**: none. **SMS**: none. **Push**: none.
- **WhatsApp** is the only outbound channel: per-tenant `whatsapp_config`,
  template constants, event listeners (appointment created, consultation
  completed, medicine dispensed), admin broadcast, message log with status,
  failed-count badge, test-send endpoint.
- **Internal notifications**: no notification center/inbox. Real-time is
  WebSocket dashboard-refresh broadcasts + `ActivityFeed` component +
  `LowStockBanner` (computed client-side from `/low-stock` endpoints).
- **Background jobs**: `AppointmentReminderScheduler` (cron 9:00 IST daily),
  `PlanExpiryScheduler` (00:05 daily — subscription expiry),
  `WhatsAppRetryScheduler` (every 5 min, retries failed sends),
  `BillingSchedulerService`; `@Async` enabled via `AsyncConfig`.

## 13. Existing Task System

**None.** There is no task/to-do/assignment entity or workflow of any kind.
The closest artifacts are `support_tickets` (tenant→platform helpdesk, not
intra-hospital) and IPD "plan discharge" (a status, not an assignable task).
Any nurse task queue (vitals due, meds due, handover) would be net-new.

## 14. Current Gaps

- **Missing modules**: Nursing, Pathology/Lab (LabOrder is dormant; module id
  reserved), Operation Theatre (module id + icon reserved; implementation on
  the other branch), Radiology, HR/payroll, insurance/TPA, patient portal,
  email/SMS channels, internal task management.
- **Missing workflows**: vitals time-series (vitals are one snapshot per OPD
  row; nothing for IPD monitoring), structured medication administration
  (JSON blob, no who/when/dose audit), nursing handover, sample→result lab
  loop, transfer between hospitals.
- **Missing entities**: Department, Shift/Roster, Nurse profile, VitalsRecord,
  MedicationAdministration, LabResult, Notification.
- **Missing permissions**: no permission/capability abstraction — roles are
  hardcoded in ~120 annotations, SecurityConfig namespace rules, and frontend
  guards; phantom roles (`PHARMACY_ADMIN`, `INVENTORY_MANAGER`) referenced but
  unassignable; no per-user or per-action overrides.
- **Missing APIs**: no versioning; no standardized error envelope; several
  read models return raw entities (lazy-proxy hazards documented in handoff
  gotchas); LabOrder has no endpoints.

## 15. Future-Ready Analysis

**Scales well**
- Tenant model: JWT-claims scoping + per-method filtering is simple and has
  held up across three tenant flavors and a branch sub-scope.
- Module/plan system: adding a feature flag is one string through
  plan → hospital_modules → JWT → `@RequireModule` → frontend tab gate.
- Staff-role scaffolding: Receptionist/Pharmacist controller+service+profile
  pattern is a proven copy-paste template.
- Pharmacy ERP: cleanly packaged, ledger-based, lock-safe — the architectural
  north star for future modules.
- Event bus + WhatsApp listener: new lifecycle notifications are one event +
  one listener method.

**Should be refactored (technical debt)**
- Monolithic dashboards (5,695-line admin file) — every new admin feature
  raises merge/regression risk; the ERP's per-view decomposition should be
  retrofitted.
- Role strings scattered across ~120 annotations; no central permission map.
- Entities returned directly from controllers; no response-DTO discipline;
  no global exception handler contract.
- `ddl-auto=update` + runtime migration runner races (documented gotcha) —
  Flyway/Liquibase would remove a whole class of bugs.
- Redundant profile marker entities (`HospitalAdmin`, `ClinicAdmin`,
  `PharmacyAdmin`), dormant `LabOrder`, leftover `inventory_items` table,
  `PlatformDashboard.jsx.backup`.
- App-enforced FKs without DB constraints on hot clinical tables; missing
  composite `(hospital_id, …)` indexes will show up at scale.

**Coupling map**
- Tightly coupled cluster: OPD ↔ Consultation (DoctorService) ↔ MedicalRecord
  ↔ Prescriptions ↔ Billing ↔ Hospital-Pharmacy dispensing ↔ Inventory
  consumption — a change to consultation ripples to four modules.
- Moderately coupled: IPD ↔ Wards/Beds ↔ Billing.
- Independent: Pharmacy ERP, WhatsApp, Audit, Tickets/FAQs, Plans/Platform.
- Bottlenecks: `DoctorService.submitConsultation` (busiest transaction),
  `hospitalService.js` (every hospital API call in one file), WebSocket
  broadcast fan-out per hospital (fine at current scale).

## 16. Readiness for a Hospital-only Nurse Module

Verified: the string "Nurse"/"NURSE" appears **nowhere** in the codebase.

**Reusable entities (no changes needed)**
`users` (role string — just a new value), `patients`, `opd` (vitals fields as
the pattern to generalize), `ipd_admission` + `wards`/`beds` +
`ipd_bed_history`, `prescriptions` (status ACTIVE/STOPPED is exactly what a
nurse works against), `medical_records`, `hospital_inventory` (consumables),
`audit_logs`, WebSocket infrastructure.

**New entities required**
- `Nurse` profile (clone of `Receptionist` — the thin-profile pattern).
- `VitalsRecord` (patient/ipd_admission FK, timestamped series) — today vitals
  are a one-shot snapshot on the OPD row, unusable for IPD monitoring.
- `MedicationAdministration` (prescription FK, nurse FK, scheduled vs actual
  time, dose, status) — today administration is `administered_items_json` on
  the medical record with no actor/time granularity.
- Optional per requirements: `NursingNote`, ward/shift assignment.

**APIs that already support nurse work**: IPD list/detail, ward/bed queries,
prescription read + `/prescriptions/{id}/stop`, `/administer` +
`/administer-hospital-items` (the billing/stock side of administration is
already built), patient read, low-stock.

**APIs needing modification** (all mechanical): add `NURSE` to
- `SecurityConfig` — the `/hospital/**` `hasAnyRole` list and `/ws/**` list
  (without this every nurse request 401s at the gate);
- the relevant `@PreAuthorize` lists on IPD, patient-read, prescription-read,
  ward/bed, OPD-queue endpoints (roughly 15–25 annotations);
- staff CRUD: new `NurseController`/`NurseService` cloned from Receptionist;
- `LoginResponse`/frontend user object (no JWT change needed — role is
  already a free string; claims structure untouched).

**Frontend reuse**: `HospitalLogin`, `ProtectedRoute`/`LandingRedirect`
(add role → route), `Sidebar`/`Navbar`, `DataTable`/`ActionMenu`/
`StatusBadge`/`ConfirmationModal`, `IpdDetails.jsx` (large parts read-only
reusable), `WardsAndBeds`, `StaffDetailsModal` for admin's Nurses tab. A new
`NurseDashboard.jsx` page is required (follow the ERP per-view decomposition,
not the monolith pattern).

**Workflows affected**: IPD daily cycle (nurse takes over administration +
vitals), OPD vitals capture (currently receptionist-entered — could shift to
nurse), discharge (nurse checklist), low-stock visibility rules
(role/tenant-gating logic already exists to extend).

**Modules that interact with Nurse**: IPD, Wards/Beds, Prescriptions,
Hospital Inventory (consumables during administration), Billing (indirectly —
administer endpoints already bill), WebSocket/Stats.

**Modules that must stay independent**: the standalone Pharmacy ERP (nurse is
hospital-only; the tenant-flavor gating already guarantees this), Platform,
WhatsApp config, Tickets/FAQs, Appointments (unless nurse triage is desired).

### Readiness score: **7 / 10**

**Why 7**: the hard multi-tenant problems are already solved and proven —
tenant scoping, module gating, the staff-creation template, route guards,
ward/bed and prescription plumbing, and even the billing side of medication
administration all exist and can be reused nearly verbatim. Adding the role
itself is mechanical.

**Why not higher**:
1. Role additions fan out across ~120 hardcoded annotations + SecurityConfig +
   frontend guards with no central permission registry — easy to miss a spot,
   and misses fail silently as 403s.
2. The two clinically essential nurse artifacts — time-series vitals and a
   real medication-administration record — do not exist; today's JSON-blob
   administration and OPD-row vitals are not extensible, so the core of the
   module is net-new schema and workflow, not reuse.
3. The monolithic dashboard pattern means a NurseDashboard done "like the
   others" would deepen existing debt; it should follow the ERP structure
   instead, which is more design work up front.
4. No task/notification system exists to hang "meds due at 14:00" on — a
   nurse worklist needs at least a minimal scheduling/task primitive.
