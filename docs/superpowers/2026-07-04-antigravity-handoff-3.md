# Antigravity Handoff #3 — 2026-07-04

This document is a **cold-start handoff**. Read it top to bottom. It contains
everything needed to continue the work without prior context: environment,
conventions, what was done this session, the exact pending task, and the
gotchas that will bite you if you don't know them.

Branch: **`test`** (never push, never touch `main` — see Rules).

---

## 0. TL;DR — where things stand right now

- All session work is **committed** on branch `test`. Working tree is clean.
- The **immediate pending task** is a large inventory redesign that is
  **fully specced and planned but NOT started** (0 of 17 tasks done):
  - Spec: `docs/superpowers/specs/2026-07-04-inventory-global-catalog-services-design.md`
  - Plan: `docs/superpowers/plans/2026-07-04-inventory-global-catalog-services.md`
- That redesign **replaces / partially reverts** the "Hospital Inventory
  Simplification" feature that was *just built and committed earlier today*
  (commits `1389491`..`75c53e5`). Do not be confused that you're removing
  code that was added hours ago — that is intentional; the user changed
  direction. The plan's Task 9 explicitly removes the templates /
  `hasOwnStock` / catalog / `consumeChargeableItem` code.
- Execution method the user chose for every feature this session:
  **subagent-driven-development** (fresh subagent per task, spec review +
  code-quality review after each, then commit). The plan is written for that.

---

## 1. Environment (exact)

- **OS**: Windows 11. Shells available: **Git Bash** (POSIX) and
  **PowerShell 5.1**. Use Git Bash syntax for the `curl`/`mysql`/`grep`
  one-liners in this doc.
- **Repo root**: `e:\Projects\HOSPITAL`
- **Backend**: Spring Boot / Java 17 / Maven. Run from `backend/`.
  - Start: `cd backend && mvn spring-boot:run` (port **8080**).
  - Build: `mvn -q -o clean compile`. Test: `mvn test -q`.
  - No hot-reload — restart the JVM to pick up Java changes.
- **Frontend**: React 18 / Vite. Run from `frontend/`.
  - Start: `npm run dev` (port **5173**).
  - Verify: `npx tsc --noEmit` and `npx vite build --mode development`.
  - **No test runner configured** — there are no frontend unit tests. Verify
    frontend via tsc + vite build + live Playwright checks.
- **Database**: MySQL 8, database **`hospital_management_ot`**.
  - Creds (from `backend/.env`): user `root`, password `Kartik123@`, host
    `localhost:3306`.
  - CLI: `mysql -u root -pKartik123@ -D hospital_management_ot -e "..."`
    (it prints a password-on-CLI warning to stderr — harmless; pipe through
    `grep -v Warning` if it's noisy).
- **`backend/.env`** holds `SPRING_DATASOURCE_*`, `JWT_SECRET`, `FRONTEND_URL`,
  Redis host/port. **`JWT_SECRET`** (needed to craft test tokens):
  `MwntKXW5stMORKfVPi8U7me51CQNsWFnKGGFlsPMtEBGdT1Yvik2adyaPXdmYVkpDS+n5QMq3+/MoStED1zogw==`
- **Redis**: optional locally. `RedisConfig implements CachingConfigurer` and
  overrides `errorHandler()` to log-and-swallow cache errors, so the app runs
  fine whether Redis is up or down. Do not "fix" a Redis-down warning.
- **`spring.jpa.hibernate.ddl-auto=update`** and
  **`spring.jpa.open-in-view=false`** in
  `backend/src/main/resources/application.properties`. Both matter — see
  Gotchas §6.

---

## 2. Hard rules (from the user + prior handoffs)

1. **Never push to any remote. Never touch `main`.** The owner pushes when
   their own work is done. Stay on branch `test`.
2. **Don't break other modules.** The user repeats this every feature. Every
   change is additive/behind flags where possible; verify the full backend
   test suite + frontend build after each task.
3. **Commit style**: end commit messages with
   `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>` (the harness adds
   this; keep whatever attribution convention your own harness uses). Stage
   **only** the files a task touches (`git add <paths>`, never `git add -A`/`.`)
   — the working tree periodically carries unrelated in-progress changes that
   must not be swept into a feature commit. (As of this handoff the tree is
   clean, but keep the discipline.)
4. **No CSV/template "just ship it" shortcuts** unless the user asked. The
   user is opinionated about workflow; when unsure, ask.
5. This project follows the **superpowers workflow**: `brainstorming` →
   `writing-plans` → `subagent-driven-development` (or `executing-plans`) →
   `finishing-a-development-branch`. Specs go to
   `docs/superpowers/specs/YYYY-MM-DD-<topic>-design.md`, plans to
   `docs/superpowers/plans/YYYY-MM-DD-<topic>.md`.

---

## 3. Architecture cheat-sheet

Multi-tenant SaaS HMS. Two tiers: **Platform** (Super Admin manages
hospitals) and **Hospital** (per-hospital staff). Every hospital-scoped
entity carries `hospital_id`; the backend reads it from JWT via
`SecurityContextHelper.getCurrentHospitalId()` and every service method
scopes by it.

- Backend packages under `backend/src/main/java/com/hms/`:
  `entity/`, `repository/`, `service/hospital/`, `service/platform/`,
  `controller/hospital/`, `controller/platform/`, `dto/`, `security/`,
  `config/`.
- API namespaces: `/platform/**` (SUPER_ADMIN), `/hospital/**` (hospital
  roles), `/api/pharmacy/**`, `/ws/**`.
- Roles: `SUPER_ADMIN`, `HOSPITAL_ADMIN`, `DOCTOR`, `RECEPTIONIST`,
  `PHARMACIST`. Gated with `@PreAuthorize("hasAnyRole(...)")`.
- Tenant "flavor": `Hospital.hospitalType` ∈ `HOSPITAL` / `CLINIC` /
  `PHARMACY`, echoed into the login response / `user` object as
  `hospitalType`. Used for UI gating (e.g. grouped sidebar shows only for
  `HOSPITAL`; the pending low-stock banner shows for the Doctor only in
  `CLINIC`).
- Frontend: `frontend/src/pages/hospital/*Dashboard.jsx` (one big file per
  role, with co-located table components at the bottom),
  `frontend/src/pages/platform/PlatformDashboard.jsx`,
  `frontend/src/components/*`, `frontend/src/services/*.js` (axios wrappers:
  `apiService.js` interceptors, `hospitalService.js`, `platformService.js`,
  `authService.js`), `frontend/src/context/ToastContext.jsx`
  (`const { success, error: toastError } = useToast();`).
- **`setup/schema-full.sql` is the canonical schema** (per `CLAUDE.md`).
  Update it alongside every entity/migration change. Entity classes mirror it.
- **DB migrations**: `backend/src/main/java/com/hms/config/DatabaseMigrationRunner.java`
  — idempotent check-then-patch methods run on `ApplicationReadyEvent`,
  each wrapped in try/catch so one failure doesn't block others. Fields:
  `jdbcTemplate`, `log`. Pattern: query `information_schema.COLUMNS`/`.TABLES`
  for existence, then `ALTER`/`CREATE` if absent. (See Gotchas §6 for the
  ddl-auto race.)

---

## 4. What was built this session (all committed on `test`)

Each was done via brainstorming → writing-plans → subagent-driven-development
→ finishing-a-development-branch (user always chose "keep branch as-is").

### 4.1 Patient Date-of-Birth (commits `6f8550f`..`d49c29e`)
Replaced stored `Patient.age` (Integer) with stored `dateOfBirth` (LocalDate)
+ a **computed** `getAge()` (`Period.between(dob, now).getYears()`). Jackson
and Thymeleaf call getters, so every age display kept working with zero
changes. `dateOfBirth` is nullable at DB level (required enforced in
`PatientService`). Migration backfills DOB from old age, drops `age`. Frontend
Add/Edit Patient + inline new-patient Appointment now use a date picker with a
live "Age: NN" preview; Consultation shows both. Spec/plan:
`docs/superpowers/{specs,plans}/2026-07-04-patient-date-of-birth*`.

### 4.2 Quick Note Presets (commits around `6936504`/`d49c29e` era)
Per-hospital reusable phrases (e.g. "Avoid oily food") shown as chips under
**Treatment Notes** in `ConsultationModal.jsx`; clicking appends. Entity
`ConsultationNotePreset` (per-hospital, `fieldType` reserved for future
Diagnosis reuse). Managed via a "Manage" modal (`ManageNotePresetsModal` →
`NotePresetsManager`) reachable from the consult screen and a "Quick Notes"
tab under Hospital Admin's "Administration" sidebar group. Roles:
HOSPITAL_ADMIN + DOCTOR. Spec/plan: `*treatment-note-presets*`.

### 4.3 Prescription Presets (commits `fa974f8`..`066a74d`)
Named bundles of medicines (e.g. "Fever Protocol" = Paracetamol + Cetirizine)
applied to a prescription via a **dropdown** in the Prescription tab. Two
tables: `prescription_presets` (name) + `prescription_preset_items` (rows).
Also added **editable prescription rows** (previously delete-and-re-add only).
`PrescriptionPresetsManager` + `ManagePrescriptionPresetsModal`, plus a
"Prescription Presets" admin tab. Roles: HOSPITAL_ADMIN + DOCTOR. Two bugs
found+fixed in-flight: needed `@Transactional` on the derived-delete update
path; `editingMedicineIndex` went stale when an earlier row was removed.
Spec/plan: `*prescription-presets*`.

### 4.4 Hospital Inventory Simplification (commits `1389491`..`75c53e5`)
**⚠️ This is the feature the pending redesign REPLACES.** Added a
`hasOwnStock` flag on `InventoryItem`, a shared `consumeChargeableItem`
service method (branch on the flag), procedure templates + a duplicate
endpoint, a stock-type toggle, and reworked the consultation "Items Used"
search to browse chargeable catalog items. Two bugs found+fixed live:
(a) Hibernate `ddl-auto` created the `has_own_stock` column before the
migration ran, defaulting existing rows to 0 — fixed with
`@Column(columnDefinition="tinyint(1) not null default 1")`; (b)
`updateCatalogItem` never persisted `hasOwnStock`. Spec/plan:
`*hospital-inventory-simplification*`. **The pending task removes most of
this.**

### 4.5 Live bug fixes (standalone commits)
- **Add-patient 500 (`is_merged` doesn't have a default value)**: orphaned
  NOT NULL boolean columns (`is_merged`, `is_temporary`, `is_unknown`) on the
  shared dev `patients` table, owned by the **other branch**
  (`phase-0-01-discharge-isolation`, a NABH patient-merge feature). Fixed by
  `ALTER TABLE patients MODIFY <col> BIT(1) NOT NULL DEFAULT b'0'` **directly
  on the dev DB** (not a code change — it's the other branch's feature). See
  Gotchas §7.
- **OPD case-paper PDF 500 (`LazyInitializationException`)**: with
  `open-in-view=false`, `OpdService.getOpdById` returned lazy
  `Opd.patient`/`Opd.doctor` proxies that `OpdController.getOpdPdf` touched
  after the session closed. Fixed by adding
  `OpdRepository.findByIdWithPatientAndDoctor` (JOIN FETCH) and pointing
  `getOpdById` at it. (Commit `fa5c109`.) A sibling `GET /hospital/opd/{id}`
  JSON endpoint has the same latent issue one level deeper (`Doctor.user`) but
  has no frontend caller — left alone.

---

## 5. THE PENDING TASK (do this next) — Inventory Global-Catalog + Services

**Read these two files in full first** — they are complete and authoritative:
- Spec: `docs/superpowers/specs/2026-07-04-inventory-global-catalog-services-design.md`
- Plan: `docs/superpowers/plans/2026-07-04-inventory-global-catalog-services.md`
  (17 bite-sized tasks, TDD, each with complete backend code and precise
  frontend rework instructions.)

### 5.1 What the user asked for (verbatim intent)
1. **Remove the template feature** just added.
2. **Platform Admin → new "Inventory Items" tab** = a **global** catalog of
   inventory item **names** shared by all hospitals. "+ Add Item" opens a form
   with a single **name** field. Plus **CSV import**.
3. **Hospital Inventory: rename "Catalog Lookup" → "Service Lookup".**
4. In Service Lookup, an **"Add Service"** button opens a form with **service
   name, service charge, and relevant items**.
5. **Relevant items are searched from the global inventory items** (the
   platform-added list), multi-select (more than one).
6. **Purchase form** item-name search also comes from the global list.
7. Notes:
   - **Low-stock alert** when an item hits its min level. **Hospital** tenant:
     alert HOSPITAL_ADMIN + RECEPTIONIST. **Clinic** tenant: alert
     HOSPITAL_ADMIN + RECEPTIONIST + DOCTOR.
   - **Out-of-stock block**: if a service's relevant item is at 0, and the
     doctor tries to add the service during consultation, show a toast
     **"Some items are out of stock"** and don't add it.

### 5.2 The new model (confirmed with the user)
- **Doctor picks only Services during consultation** (never raw items).
- **Everything physical is a global Item; everything billable is a Service.**
  An injectable ampule is just a relevant Item of an "Injection" Service.
- **Item stock quantities live per-hospital** (via Purchases). The global
  list is just **names** — it governs *which item names* a hospital may
  purchase/reference.
- **Service charge is a standalone number on the service form** — it does NOT
  use the Fees tab / `HospitalFee` anymore. Billing creates the charge
  directly from the service.
- Governance trade-off (user accepted): hospitals can only stock/use item
  names the Platform Admin has added globally.

### 5.3 New DB tables (all created idempotently in Task 3)
- `inventory_master_items` (platform-global: `id`, `name`, `created_at`).
- `hospital_services` (per-hospital: `id`, `hospital_id` FK, `name`,
  `charge` decimal(10,2), `is_active`, `created_at`).
- `hospital_service_items` (join: `id`, `service_id` FK, `master_item_id`).
- **KEEP** `hospital_inventory` (per-hospital stock) unchanged.
- **LEAVE** the old `inventory_items` table + rows in the DB (not dropped) —
  code just stops reading it (non-breaking guarantee).

### 5.4 Task order (matters — keeps app compiling + OPD/IPD working every commit)
Backend: T1 global master + platform CRUD/CSV → T2 service entities → T3
migration + schema-full → T4 `HospitalServiceService` → T5
`HospitalServiceController` + `/hospital/inventory-master` → T6 `consumeService`
+ `/low-stock` → T7 switch OPD to `consumeService` → T8 switch IPD → **T9
remove old catalog/`hasOwnStock`/template/duplicate/`consumeChargeableItem`
code** → T10 backend live verify. Frontend: T11 API funcs → T12 platform
"Inventory Items" tab → T13 "Service Lookup" replaces catalog in
`HospitalInventoryTab.jsx` → T14 purchase autocomplete from global master →
T15 `ConsultationModal` service search + out-of-stock toast → T16 low-stock
banner (role/tenant-gated) → T17 full-stack live verify.

### 5.5 Key signatures introduced (so later tasks stay consistent)
- Entity class **`HospitalServiceEntity`** (table `hospital_services`) — named
  `...Entity` to avoid clashing with the many `*Service` Spring beans.
- `HospitalInventoryService.consumeService(Long serviceId, int quantity,
  Long hospitalId) : BigDecimal` — validates every relevant item has ≥ qty
  stock (else throws `IllegalArgumentException("Some items are out of stock:
  ...")` deducting nothing), deducts FEFO, returns `charge × quantity`.
- `HospitalInventoryService.getLowStockItems() : List<HospitalInventory>`.
- `HospitalServiceDTO { id, name, charge, masterItemIds:[Long],
  itemNames:[String] }`.
- New endpoints: `/platform/inventory-items` (GET/POST/DELETE + `/import-csv`,
  SUPER_ADMIN); `/hospital/inventory-master` (GET, hospital roles);
  `/hospital/services` (GET/POST/PUT/DELETE, hospital roles);
  `/hospital/hospital-inventory/low-stock` (GET, hospital roles).
- Consultation + IPD request items become `{ serviceId, quantity }` (was
  `{ stockId, name, quantity }`).

---

## 6. Gotchas that WILL bite you (hard-won this session)

1. **`ddl-auto=update` races `DatabaseMigrationRunner`.** Hibernate creates a
   new entity column *during context startup*, before the
   `ApplicationReadyEvent` migration runs. So an idempotent "add column if
   absent" migration finds the column already present and **skips its DEFAULT
   backfill**, leaving existing rows at MySQL's implicit default (0 for
   bit/boolean). If a new NOT-NULL column must default existing rows to a
   specific value, put the default in the entity via
   `@Column(columnDefinition="tinyint(1) not null default 1")` so Hibernate
   itself creates it WITH the default (MySQL backfills existing rows). The new
   plan's tables are created wholesale (no add-column-to-populated-table), so
   this specific trap doesn't recur there — but remember it.
2. **`open-in-view=false`** → touching a lazy `@ManyToOne` proxy after the
   service method returns throws `LazyInitializationException`. Use a
   `JOIN FETCH` repo query (see `OpdRepository.findByIdWithPatientAndDoctor`)
   when a controller/PDF path reads related entities after the transaction.
3. **Derived delete-by methods (`deleteByServiceId`, etc.) need an active
   transaction.** Annotate the service method `@Transactional`, or you get
   "No EntityManager with actual transaction available." (Hit this on the
   prescription-preset update path.) All the new `consume*`/`create*`/`update*`
   service methods in the plan are already `@Transactional`.
4. **`List.of(...)` is immutable**; the deduction engine sorts stock lists
   in place. In unit tests, wrap stubbed repo returns in
   `new ArrayList<>(List.of(...))` or `.sort()` throws
   `UnsupportedOperationException`. Real JPA queries return mutable lists.
5. **`@WebMvcTest` does NOT load the app's `SecurityConfig`** (it's a plain
   `@Configuration`), so `@PreAuthorize` isn't enforced unless the test adds a
   nested `@TestConfiguration @EnableMethodSecurity` class and `@Import`s it.
   Every controller test this session does this — copy the pattern.
6. **Mockito strict stubbing** (`MockitoExtension`) fails on unused stubs. If
   a validation throws before a stubbed call is reached, either reorder the
   code so the stub is used, or mark that stub `lenient()`. Don't weaken the
   assertion.
7. **Shared dev DB with the other branch.** `hospital_management_ot` is used
   by BOTH `test` and `phase-0-01-discharge-isolation`. The other branch's
   entities add columns (`is_merged`, `is_temporary`, `is_unknown`,
   `merged_to_id`, `blood_group`, `guardian_*`, `preferred_language`, `uhid`
   on `patients`; possibly others) that **this branch's entities don't know
   about**. When Hibernate here inserts, it omits those columns → if any is
   `NOT NULL` with no DB default, the insert fails ("Field 'X' doesn't have a
   default value"). Fix by giving the column a DB default directly
   (`ALTER TABLE ... MODIFY ... DEFAULT ...`) — it's the other branch's
   feature, so don't add it to this branch's code. This already bit
   `patients.is_merged` (fixed).
8. **Frontend has no test runner.** Verify with `npx tsc --noEmit` +
   `npx vite build --mode development` + live Playwright. `tsc` on this JS/JSX
   project won't catch a call to a deleted service function reliably — the
   build or runtime will. When you remove a `hospitalService` function, grep
   for its callers and fix them in the same or next task.
9. **Vite HMR can serve stale code** after big edits — restart `npm run dev`
   before Playwright verification if behavior looks wrong. Same for the
   backend JVM after Java edits (no hot reload).

---

## 7. How to verify like this session did

### Run servers (Git Bash, background + wait)
```bash
# backend
cd e:/Projects/HOSPITAL/backend && (mvn -q spring-boot:run > /tmp/run.log 2>&1 &)
# wait for readiness
grep -q "Started HospitalManagementSystemApplication" /tmp/run.log   # poll until present
# frontend
cd e:/Projects/HOSPITAL/frontend && (npm run dev > /tmp/fe.log 2>&1 &)  # wait for "Local: http://localhost:5173/"
```
Stop a server: find its PID via `netstat -ano | grep ":8080" | grep LISTENING`
then `taskkill //PID <pid> //F`.

### Craft a test JWT (HS256, raw-UTF-8 secret — matches `JwtUtil`)
```bash
node -e "
const crypto=require('crypto');
const S='MwntKXW5stMORKfVPi8U7me51CQNsWFnKGGFlsPMtEBGdT1Yvik2adyaPXdmYVkpDS+n5QMq3+/MoStED1zogw==';
const b=x=>Buffer.from(x).toString('base64').replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,'');
const h={alg:'HS256',typ:'JWT'},n=Math.floor(Date.now()/1000);
const p={userId:1,role:'HOSPITAL_ADMIN',hospitalId:2,modules:['OPD','HOSPITAL_INVENTORY','BILLING'],sub:'admin@example.com',iat:n,exp:n+3600};
const si=b(JSON.stringify(h))+'.'+b(JSON.stringify(p));
console.log(si+'.'+b(crypto.createHmac('sha256',Buffer.from(S,'utf8')).update(si).digest()));
"
```
Change `role`/`hospitalId`/`userId` as needed. For Playwright, inject into
`sessionStorage` as `token` + a `user` JSON object (must include
`role`, `hospitalId`, `modules`, and `hospitalType` for tenant-gated UI), then
navigate to the route.

### Playwright
Already installed in the session scratchpad
(`C:\Users\karti\AppData\Local\Temp\claude\...\scratchpad\node_modules`).
If your harness uses a different scratchpad, run
`npm i playwright && npx playwright install chromium` there. Pattern used
throughout: launch headless Chromium, `sessionStorage.setItem('token'/'user')`,
`page.goto(route)`, screenshot, assert on `body.innerText`. **Known friction**:
synthetic DOCTOR-role JWTs sometimes 401 on unrelated profile/inventory calls
in this dev env and bounce to login — when that blocks a UI check, verify that
path via `curl` with a real DB doctor id instead (the API is the source of
truth; the UI wiring is covered by code review).

### Test-data hygiene
Every feature this session created test rows (patients, services, stock, bills,
OPDs) during live verification and **deleted them afterward** (API delete where
it works, direct SQL otherwise). Do the same.

---

## 8. Conventions to copy (so new code matches the codebase)

- **Entity**: Lombok `@Data @NoArgsConstructor @AllArgsConstructor`,
  `@Table(name="...")`, IDENTITY id, snake_case `@Column(name=...)`,
  `@PrePersist` for `created_at`, `is_active` soft-delete boolean. Small
  reference entities: `HospitalFee`, `ConsultationNotePreset`,
  `PrescriptionPreset`.
- **Repository**: `extends JpaRepository<E, Long>` with derived queries
  (`findByHospitalIdAndIsActiveTrueOrderBy...`, `findByIdAndHospitalId`,
  `existsByNameIgnoreCase`, `deleteByServiceId`).
- **Service**: `@Autowired` field injection, `SecurityContextHelper
  securityHelper` for hospital scoping, validation throws
  `IllegalArgumentException` (→ 400) / `RuntimeException("... not found")`
  (→ 400 via controller try/catch), audit logging via `auditLogService`
  wrapped in try/catch (best-effort), `@Transactional` on multi-write methods.
- **Controller**: `@RestController @RequestMapping("/hospital/...")`,
  `@PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','DOCTOR','RECEPTIONIST')")`,
  `ResponseEntity<?>`, try/catch → `ResponseEntity.badRequest().body(msg)`.
  Platform controllers: `@PreAuthorize("hasRole('SUPER_ADMIN')")` at class
  level (see `PlatformMedicineController` — the CSV-import pattern to mirror).
- **Migration**: mirror `ensureWhatsAppConfigTable()` /
  `ensureInventoryItemHasOwnStockColumn()` in `DatabaseMigrationRunner`;
  append the new `ensure...()` call to `runMigrations()`; update
  `setup/schema-full.sql` to match.
- **Test**: JUnit 5 + Mockito + AssertJ. Service tests:
  `@ExtendWith(MockitoExtension.class)`, `@Mock` collaborators,
  `@InjectMocks service`. Controller tests: `@WebMvcTest(X.class)` +
  the `@EnableMethodSecurity` nested config (Gotcha §5) + `@MockBean`s +
  `@WithMockUser(roles=...)` + `.with(csrf())` on mutating requests.
- **Frontend service fn**: `name: async (args) => { const response = await
  apiClient.X(url, body); return response.data; },` in the relevant
  `*Service.js`. CSV upload uses `FormData` + `multipart/form-data` header
  (see `platformService` medicine import).
- **Toast**: `const { success, error: toastError } = useToast();`.
- **Confirm dialog**: `ConfirmationModal` props `isOpen/title/message/
  onConfirm/onCancel`; some dashboards use an `openConfirmation(...)` helper /
  `confirmState` object.

---

## 9. Full inventory of session artifacts

Specs (`docs/superpowers/specs/`):
- `2026-07-04-patient-date-of-birth-design.md`
- `2026-07-04-treatment-note-presets-design.md`
- `2026-07-04-prescription-presets-design.md`
- `2026-07-04-hospital-inventory-simplification-design.md` (superseded by ↓)
- `2026-07-04-inventory-global-catalog-services-design.md` **(PENDING)**

Plans (`docs/superpowers/plans/`):
- `2026-07-04-patient-date-of-birth.md`
- `2026-07-04-treatment-note-presets.md`
- `2026-07-04-prescription-presets.md`
- `2026-07-04-hospital-inventory-simplification.md` (built; superseded in part)
- `2026-07-04-inventory-global-catalog-services.md` **(PENDING — execute this)**

Prior handoffs (source of truth for pre-2026-07-04 NABH work, the other
branch's phased build, and the per-form recipe):
- `docs/superpowers/2026-07-01-antigravity-handoff.md`
- `docs/superpowers/2026-07-02-antigravity-handoff-2.md`
- This file: `docs/superpowers/2026-07-04-antigravity-handoff-3.md`

Backend test count after the pending Task 1 will be prior-total + 5; the full
suite was **115 passing** at the end of the inventory-simplification feature
(the number before this handoff). Run `cd backend && mvn test -q` to get the
live count.

---

## 10. Exact next action for Antigravity

1. Read the pending spec and plan (paths in §5) in full.
2. Execute the plan **task by task** (subagent-driven or inline — either is
   fine; the plan is self-contained with complete backend code and precise
   frontend instructions). After each task: `mvn -q -o clean compile && mvn
   test -q` (backend tasks) or `npx tsc --noEmit && npx vite build --mode
   development` (frontend tasks), then commit only that task's files.
3. Respect the task ordering in §5.4 — do **not** remove the old catalog code
   (Task 9) until OPD/IPD have been switched to `consumeService` (Tasks 7-8),
   or the app won't compile mid-way.
4. Do the two live-verification tasks (T10, T17) against the running
   backend/DB, including the **out-of-stock toast** and the **role/tenant-gated
   low-stock banner** cases. Clean up test data.
5. When done, use the `finishing-a-development-branch` flow; the user has
   chosen **"keep the branch as-is"** every time — do not merge/push.

Good luck. Everything you need is in the spec + plan; this doc is the map.
