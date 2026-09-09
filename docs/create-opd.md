OPD "New Patient" — Audit & Implementation Plan

1. Current Flow

Patient Registration Add OPD
───────────────────── ────────────────────────────
PatientModal.jsx OPD modal (3 copies, inline)
↓ hospitalService.addPatient ↓ client-side filter over loaded `patients`
POST /hospital/patients ↓ sets opdForm.patientId = p.id
↓ hospitalService.createOpd
PatientController.addPatient ↓
↓ POST /hospital/opd
PatientService.addPatient ↓
↓ OpdController.createOpd (idempotency wrapper)
PatientRepository.save x2 ↓
OpdService.createOpd @Transactional
↓
opdRepository.save x2 + QueueEntry + pay-first bill
Files

Concern File
Patient form (shared) PatientModal.jsx
API wrappers hospitalService.js:33, :642
Tenant prefix rewrite apiService.js:29-53
OPD modal (reception) ReceptionistDashboard.jsx:2225-2687
OPD modal (admin) HospitalAdminDashboard.jsx:5735-6230
OPD modal (doctor) DoctorDashboard.jsx:2080-2480
Patient API PatientController.java:35-40
Patient logic PatientService.java:135-184
OPD API OpdController.java:94-125
OPD logic OpdService.java:85-239 2. Current Patient Creation
API: POST /hospital/patients (aliased to /clinic/patients, /pharmacy/patients on the same handler; the axios interceptor rewrites the prefix per tenant).
DTO: none — the Patient entity is the request body (@Valid @RequestBody Patient).
Service: PatientService.addPatient(Patient) — not @Transactional.
Validation:
Bean validation on Patient.java: name NotBlank/≤100/NoEmoji, gender NotBlank/^[A-Za-z \-]{1,10}$, phone NotBlank/^[0-9]{10}$, email @Email/≤100, address ≤255/NoEmoji, medicalHistory ≤1000/NoEmoji.
Service-level: phone re-checked as 10 digits; validateDateOfBirth → required, not future, not >120 years ago (deliberately not a DB constraint, see the comment at Patient.java:77-85).
Frontend mirror: validateForm rules name:[required,name] dateOfBirth:[required,dob] gender:[required] phone:[required,phone] email:[email] in validation.js.
Fields collected: name*, phone*, dateOfBirth*, gender*, email, address, medicalHistory. insurance is a UI-only field explicitly stripped before send (PatientModal.jsx:58).
Business rules: hospitalId forced from JWT; publicId = UUID via @PrePersist; customId = "PAT" + id assigned by a second save; status = REGISTERED; isActive = true.
Duplicate handling: none. PatientRepository.findByPhoneAndHospitalIdAndIsActiveTrue exists but is used only by AppointmentService. Registering the same person twice is allowed today.
Side effects: stats-cache evict, PATIENT_CREATED audit, WebSocket REFRESH_DATA — all best-effort in try/catch.
Authorization: hasAnyRole('HOSPITAL_ADMIN','RECEPTIONIST'). Not @RequireModule-gated. 3. Current OPD Creation
API: POST /hospital/opd (+ /clinic/opd, /pharmacy/opd).
DTO: CreateOpdRequest — patientId (NotBlank String, accepts numeric id or publicId), doctorId, visitType NotBlank NEW|FOLLOWUP, vitals (bp pattern, temperature/weight/height ≥0, pulse/spo2 ≥0), customVitals map (unvalidated by design), problem ≤2000/NoEmoji, paymentMethod CASH|UPI, paymentReference, idempotencyKey (optional, ≤100).
Service: OpdService.createOpd — @Transactional. Re-validates vitals server-side (incl. systolic > diastolic, which bean validation does not cover), resolves patient tenant-scoped (findByIdAndHospitalIdAndIsActiveTrue / findByPublicIdAndHospitalIdAndIsActiveTrue), sets receptionist from the authenticated user (client receptionistId ignored), tenant-filters the doctor, drops vitals the hospital disabled, saves, sets caseId = "OPD-" + id, creates a QueueEntry only when a doctor is set, runs pay-first billing best-effort, audits, broadcasts.
Entity: Opd / table opd. No hospital_id column — tenancy is derived through the patient FK, and all reads go through findByIdAndHospitalIdWithPatientAndDoctor (enforced by OpdRepositoryScopingArchTest).
Required: patientId, visitType. Doctor is optional at the API level (the admin modal requires it client-side; the receptionist modal does not).
Idempotency: claimed in the controller, outside the service transaction, via OpdIdempotencyService + opd_idempotency. Replay returns the original OPD; an in-flight duplicate gets a ConflictException; a failure releases the key. No frontend currently sends idempotencyKey — the only client using this pattern in the repo is DispenseModal.jsx:37.
Authorization: hasAnyRole('HOSPITAL_ADMIN','DOCTOR','RECEPTIONIST'). Not @RequireModule-gated. 4. Existing Permissions
Action Roles
Create patient HOSPITAL_ADMIN, RECEPTIONIST
Create OPD HOSPITAL_ADMIN, DOCTOR, RECEPTIONIST
Intersection (can do the new flow) HOSPITAL_ADMIN, RECEPTIONIST
DOCTOR can create an OPD but cannot create a patient. The New Patient option must therefore not be offered on the doctor's OPD modal. No new role or permission is needed.

5. Proposed New Flow

Create OPD Entry modal
├── [Existing Patient] ← default, pre-selected
│ └── current search → select → opdForm.patientId (UNCHANGED)
│
└── [New Patient] ← visible only to HOSPITAL_ADMIN / RECEPTIONIST
└── "Add Patient" button → opens existing <PatientModal/> nested
└── PatientModal runs its existing validation + POST /hospital/patients
└── on success: opdForm.patientId = created.id
patientSearchText = "Name (phone) [PAT12]"
mode flips back to Existing, patient locked in
└── user completes vitals/doctor/payment as today
└── POST /hospital/opd (UNCHANGED endpoint & payload)
└── success toast + existing `createdOpd` print path
Two sequential calls to two unchanged endpoints. No new backend endpoint, no new DTO, no combined transaction.

6. Minimal File Changes
   Backend: none.

File Change
frontend/src/components/PatientModal.jsx Pass the created patient to the callback — onSuccess(result) instead of onSuccess() (backward compatible; all three existing callers ignore arguments).
frontend/src/pages/hospital/ReceptionistDashboard.jsx Add an Existing/New segmented control above the patient search in the OPD modal, plus a nested <PatientModal/> whose onSuccess sets opdForm.patientId and the search label.
frontend/src/pages/hospital/HospitalAdminDashboard.jsx Same addition in the admin OPD modal (adminOpdForm / adminOpdPatientSearch).
(optional, recommended) both dashboards above Generate one idempotencyKey per OPD-modal open (useRef(crypto.randomUUID())) and include it in the createOpd payload — the field and its server handling already exist.
(new) frontend/src/pages/hospital/ReceptionistDashboard.newPatientOpd.test.jsx Cover the New Patient → OPD sequence and the unchanged existing-patient path.
DoctorDashboard.jsx is deliberately not touched (no create-patient permission for DOCTOR).

7. Reuse Strategy
   Reused as-is Where
   PatientModal — every field, label, and client rule rendered nested inside the OPD modal
   validateForm rules (name/dob/gender/phone/email) inside PatientModal, untouched
   hospitalService.addPatient → POST /hospital/patients no new API
   PatientController.addPatient / PatientService.addPatient no new service, no duplicated customId/audit/tenant logic
   hospitalService.createOpd → POST /hospital/opd no new API
   OpdService.createOpd + OpdIdempotencyService unchanged
   Existing tenant-prefix rewrite in apiService.js clinic/pharmacy tenants work automatically
   Nothing is duplicated. Note the anti-pattern to avoid: AppointmentService (AppointmentService.java:98-148) auto-creates patients by calling patientRepository.save directly, which skips customId assignment, skips DOB validation (defaults to today), forces address = "Walk-in", and audits before saving with a null entity id. This plan does not copy that approach.

8. Transaction & Failure Handling
   Recommendation: two sequential client-side calls, no server-side transaction spanning both.

Scenario Outcome Handling
Patient created, OPD fails Patient exists, no OPD Exactly the state the current two-step workflow leaves behind. Keep the modal open with the patient still selected and toast: "Patient saved. OPD could not be created — retry." User retries without re-typing.
Patient created, OPD succeeds Normal Existing createdOpd / print path fires unchanged.
Duplicate patient attempted Both rows are created No server-side dup check exists today; adding one would change existing registration behavior — out of scope (see §10).
Double-submit of the patient form Blocked PatientModal's existing isSubmitting disables the button.
Double-submit of the OPD form Blocked (once idempotencyKey is wired) opdSubmitting guard already exists in the receptionist modal; sending the existing idempotencyKey makes it durable against retries too. The admin modal currently has no submitting guard — the key covers it.
Why not a single transactional backend endpoint: PatientService.addPatient is not @Transactional and commits twice (the customId needs the generated id), and its audit/WebSocket/cache side effects are fire-and-forget — a wrapping transaction would not undo them. A combined endpoint would mean a new controller, a new DTO, and a second patient-creation path to keep in sync, for a rollback guarantee the current architecture cannot actually deliver. Sequential calls keep both existing paths as the single source of truth.

9. Security / Tenant / Permission Impact
   No backend surface changes — the two endpoints, their @PreAuthorize lists, and their tenant checks are untouched.
   Tenant isolation: hospitalId still comes from the JWT in both services; the client never supplies it. OpdService still resolves the patient with findByIdAndHospitalIdAndIsActiveTrue, so a patient id from another tenant is rejected exactly as today — including the freshly created one.
   Authorization: the New Patient control is gated in the UI to HOSPITAL_ADMIN / RECEPTIONIST. This is convenience only — a DOCTOR who forced the call would still get 403 from PatientController.
   Ownership: Opd remains tenant-scoped through its patient FK; OpdRepositoryScopingArchTest still holds.
   Multi-tenant aliasing: clinic and pharmacy sessions get the flow for free via the existing prefix rewrite, with no new alias to register.
   No security boundary is weakened, moved, or bypassed.

10. Test Plan
    Backend — no production change, so this is regression + boundary confirmation:

Extend PatientApiTest.java: RECEPTIONIST token → POST /hospital/patients then POST /hospital/opd with the returned id → 2xx and a caseId; a DOCTOR token → POST /hospital/patients → 403.
Run existing suites unchanged as regression: PatientApiTest, PatientServiceDateOfBirthTest, OpdIdempotencyTest, OpdTenancyTest, OpdRepositoryScopingArchTest, CrossTenantIsolationTest.
No new backend test file is warranted.
Frontend — new ReceptionistDashboard.newPatientOpd.test.jsx (mirroring the style of DispenseModal.test.jsx):

Existing Patient is the default mode and the search/select path is byte-for-byte unchanged.
New Patient → fills the form → addPatient called once, then createOpd called with patientId equal to the created id.
addPatient rejects → createOpd is never called and the OPD modal stays open.
Two submits reuse the same idempotencyKey (if that change is included).
A mirrored, smaller test for the admin modal. 11. Risks / Open Questions
Duplicate patients get easier. There is no server-side duplicate check on POST /hospital/patients, and the New Patient path lets a receptionist skip the search entirely. Do you want a client-side "a patient with this phone already exists" warning inside the OPD modal (using the already-loaded list or searchPatients), or leave behavior identical to today?
DoctorDashboard already renders an "Add Patient" button (DoctorDashboard.jsx:1158) even though POST /hospital/patients denies DOCTOR — it works only for single-doctor HOSPITAL_ADMIN users and 403s for real doctors. Pre-existing; I propose leaving it alone and not adding the new flow there. Confirm.
Wiring idempotencyKey is a behavior change. Today a double-clicked OPD submit can create two OPDs (and two pay-first bills). Sending the key changes that to one OPD, with an in-flight duplicate receiving a 409. It's the fix the backend was built for, but it is technically new user-visible behavior — confirm you want it in this change or split out.
Three duplicated OPD modals. The receptionist and admin modals differ (doctor required vs. optional; ≥3-char name/phone/customId search vs. ≥2-char name-only; submitting guard present vs. absent). The plan changes two of them separately rather than extracting a shared component — extraction would be a refactor of the existing OPD flow, which is out of scope.
PatientModal.onSuccess() signature. It currently takes no argument and discards the created patient. Adding the argument is the one unavoidable touch to shared code; it is purely additive.
Checkpoint — stopping here. No files were modified. Say the word on questions 1–3 and I'll implement. Want this written up as a shareable page as well?
