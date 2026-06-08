# HMS Billing & Sales — Scenario-Based Workflows

This document details the exact, step-by-step technical billing workflows, database state transitions, and itemized calculations for the various clinical and retail scenarios in the hospital.

---

## System Overview & Core Entities

The HMS billing architecture consists of two primary billing layers: **Hospital Core Billing** (OPD & IPD invoices) and the **Pharmacy Module** (Retail & Prescription sales). 

### 1. Hospital Core Billing Schema
* **`billing`**: Tracks a single clinical invoice. Linked to `patient_id`, `doctor_id`, and optionally `appointment_id`, `opd_id`, or `ipd_admission_id`.
  * `billing_type`: `OPD` or `IPD`
  * `payment_status`: `PENDING` ➔ `PARTIAL` ➔ `PAID` ➔ `CLOSED`
  * `amount`: Grand total (synchronized dynamically with the sum of billing items).
* **`billing_items`**: Tracks line-item breakdowns (e.g., Consultation Fee, Bed Price, Follow-up Consult, Consumables, Bed Upgrades).
* **`billing_payments`**: Stores ledger records of cash/UPI payments received (linked to UTR references for online receipts).

### 2. Pharmacy Module Schema
* **`pharmacy_sales`**: Tracks pharmacy sales, completely isolated from main hospital clinical invoices unless flagged as IPD.
  * `sale_type`: `WALK-IN`, `PRESCRIPTION`, or `IPD`
  * `payment_status`: `PAID`, `PENDING`, or `CANCELLED`
  * `payment_method`: `CASH`, `CARD`, `UPI`, or `IPD_BILL`
  * `is_ipd_bill`: Set to `true` if integrated into an inpatient's general hospital invoice.
* **`pharmacy_sale_items`**: Individual batch rows showing sold quantity, unit price, tax (GST), and discounts.

---

## Scenario A — Walk-In Consultation Patient (OPD)
*A patient arrives at the hospital without an appointment, registers at the desk, receives a doctor consultation, and pays at checkout.*

```
 [Receptionist]             [Queue]                 [Doctor]                    [Checkout Counter]
 Register OPD Case  ───►  Queue Position #1  ───►  Complete Consultation  ───►  Collect Payment & Complete OPD
 (Status: QUEUED)                                 (Create OPD Bill)            (OPD Status: COMPLETED)
```

### Detailed Flow & State Transitions:
1. **Registration & Check-In**:
   - The receptionist registers the patient under a **new OPD / Case**.
   - **Database Insertion**:
     * Inserts an `opd` row (status = `QUEUED`, visitType = `NEW` or `FOLLOWUP`).
     * Inserts a `queue_entry` row linked to the assigned doctor.
   - **UI Behavior**: The patient appears dynamically in the doctor’s queue table.

2. **Clinical Consultation**:
   - The doctor selects the patient from the queue, records symptoms, diagnoses, prescriptions, and clicks **Complete Consultation**.
   - **Database Mutations**:
     * Inserts a `medical_records` row linking `patientId`, `doctorId`, and `opdId`.
     * Removes the corresponding `queue_entry` row.
     * Updates `opd` status from `QUEUED` to `CONSULTED`.

3. **Invoicing Trigger**:
   - `DoctorService` calls `billingService.createOpdBill(opdId, patientId, doctorId)`.
   - The system reads the hospital’s operational settings for `casePaperFee` (e.g., ₹100.00) and `consultationFee` (e.g., ₹500.00).
   - **Database Insertion**:
     * Inserts a `billing` row:
       * `billingType` = `"OPD"`
       * `amount` = ₹600.00
       * `paymentStatus` = `"PENDING"`
       * `opdId` = `opdId`, `patientId` = `patientId`, `doctorId` = `doctorId`
     * Inserts `billing_items` row #1: "Case Paper Fee" (₹100.00)
     * Inserts `billing_items` row #2: "Consultation Fee" (₹500.00)

4. **Payment Collection & OPD Closure**:
   - The patient approaches the checkout desk. The receptionist opens the **Billing** console and clicks **Mark Paid**.
   - The React `PaymentModal` opens, displaying the outstanding amount of ₹600.00.
   - The receptionist selects **CASH** or **UPI** (prompts for UTR transaction reference).
   - **Payment Mutations**:
     * Inserts a `billing_payments` row (amount = ₹600.00, mode = CASH/UPI, reference = UTR).
     * Updates `billing` paymentStatus to `PAID`.
     * **Completion Callback**: Because `billing.opdId` is set and status becomes `PAID`, the backend automatically transitions the associated `opd` record to `COMPLETED`.

---

## Scenario B — Scheduled Appointment-Based Patient
*A patient books an appointment online or via the desk, arrives on the scheduled date, receives treatment, and completes the billing loop.*

```
 [Booking Console]           [Arrival at Hospital]       [Consultation Console]         [Checkout Desk]
 Schedule Appointment  ───►  Check-In Patient  ───►  Doctor Consultation  ───►  Mark PAID & Complete OPD
 (Status: SCHEDULED)         (Status: SCHEDULED)       (Medical Record Created)     (Auto-bill status: PAID)
```

### Detailed Flow & State Transitions:
1. **Booking**:
   - A patient books a slot. The database inserts an `appointments` row (status = `SCHEDULED`, customId = `APTXXXX`).

2. **Arrival & Consultation**:
   - The patient arrives. The doctor opens the **ConsultationModal** with the `appointment` details, files medical notes, and clicks **Complete Consultation**.
   - **Database Mutations**:
     * Inserts a `medical_records` row containing `appointmentId` and `visitType = "OPD"`.
     * Updates the `appointments` status to `COMPLETED`.

3. **Invoicing Trigger (Safe Duplicate Prevention)**:
   - The transition of the appointment to `COMPLETED` calls `AppointmentService.updateDetails()`, triggering `billingService.autoGenerateOpdBill(appointment)`.
   - **Safeguard Checks**:
     * The system queries `existsByAppointmentId(appointmentId)` to verify an invoice doesn't already exist (blocks duplicate billing).
     * Resolves the related `opdId` by querying `medicalRecordRepository.findByAppointmentId(appointmentId)`.
   - **Invoice Creation**:
     * Inserts a `billing` row (amount = `consultationFee`, paymentStatus = `"PENDING"`, linking `appointmentId`, `opdId`, `patientId`).
     * Inserts a `billing_items` row with description "Consultation Fee" (amount = `consultationFee`).

4. **Payment Collection**:
   - The receptionist records the payment in `PaymentModal` (CASH/UPI).
   - **Database Mutations**:
     * Inserts `billing_payments` record.
     * Updates `billing` paymentStatus to `PAID`.
     * Cascades `opd` status linked to `opdId` to `COMPLETED`.

---

## Scenario C — Patient Admitted in the IPD
*An OPD consultation patient requires hospitalization. They undergo admission, bed changes, daily follow-ups, medication additions, and final discharge.*

```
 [OPD Consult]             [IPD Admission]            [Stay Adjustments]             [Discharge Loop]
 Doctor admits patient ──► Bed marked OCCUPIED ──► Add follow-up, rx & bed change ──► Verify Balance = 0 ──► status: CLOSED
                           (IPD Bill: PENDING)     (recalculateTotal sums items)                             (Bed Released)
```

### Detailed Flow & State Transitions:
1. **OPD to IPD Conversion**:
   - In the OPD consultation drawer, the doctor clicks **Admit to IPD**.
   - The receptionist selects an available ward (e.g., ICU) and bed (e.g., Bed 101).
   - **Database Mutations (`admitFromOpd`)**:
     * Inserts an `ipd_admissions` row (status = `ADMITTED`, ipdNumber = `IPDXXXX`).
     * Updates the selected `bed` status to `occupied` and links `currentIpdAdmissionId`.
     * Transitions `opd` status to `IN_IPD`.
     * **Consolidated Inpatient Invoice Creation**:
       * Inserts a `billing` row (type = `IPD`, amount = bedPrice, paymentStatus = `PENDING`, `ipdAdmissionId` set).
       * Inserts a `billing_items` row for "Bed Price: Bed 101 (ICU)" (amount = ICU daily ward rate).

2. **Stay Adjustments & Clinical Accumulation**:
   - **Daily Doctor Follow-Up**:
     * The ward doctor conducts round examinations and writes a follow-up medical record.
     * Inserts `medical_records` (visitType = "IPD", `ipdAdmissionId` set).
     * Inserts a `billing_items` row: "Doctor Consultation Fee (IPD Follow-up)" (amount = `consultationFee`).
     * Triggers `billingService.recalculateTotal(billId)` to sum all itemized charges and update `billing.amount`.
   - **Daily Inpatient Medication (IPD Prescriptions)**:
     * The ward doctor adds daily prescription batches.
     * Inserts `prescriptions` row (status = `ACTIVE`, `ipdAdmissionId` set).
     * When medicines are dispensed by the ward pharmacy (Scenario D, Case 2), the system adds a line item: "Pharmacy Inpatient Charge - PHB-XXX" (amount = dispensed total).
     * Triggers `billingService.recalculateTotal(billId)`.

3. **Inpatient Bed Transfer (ICU to Deluxe Room)**:
   - As the patient stabilizes, they are moved from ICU to a Deluxe room.
   - **Database Mutations (`changeBed`)**:
     * Frees the old ICU bed (status = `available`, nulls `currentIpdAdmissionId`).
     * Occupies the Deluxe Bed (status = `occupied`, links `currentIpdAdmissionId`).
     * Inserts a `billing_items` row: "Room Transfer Charge: Bed ICU to Deluxe" (amount = calculated occupancy rate difference or Deluxe rate going forward).
     * Triggers `billingService.recalculateTotal(billId)`.

4. **Discharge Clearance & Closure**:
   - **Planning**: The doctor logs that clinical goals are met, setting the admission status to `DISCHARGE_PLANNED`.
   - **Financial Clearance check**:
     * The receptionist clicks **Confirm Discharge** in the checkout console.
     * The backend queries the database:
       $$\text{Outstanding Balance} = \sum(\text{billing\_items.amount}) - \sum(\text{billing\_payments.amount})$$
     * **Blocker**: If the balance is greater than 0, the system blocks the action and throws a balance validation exception.
     * The family pays the outstanding balance. The receptionist logs the settlement in `PaymentModal`.
   - **Discharge Confirm Mutations (`confirmDischarge`)**:
     * Sets `ipd_admissions` status to `DISCHARGED` and records `dischargeDatetime`.
     * Automatically completes all active IPD prescriptions (sets status to `COMPLETED`).
     * Releases the Deluxe bed (sets status to `available`).
     * Updates `billing` paymentStatus to `CLOSED` (gray badge in UI, payments locked).

---

## Scenario D — Pharmacy Prescription Dispensing
*An OPD, Appointment, or IPD patient arrives at the pharmacy counter to collect medicines prescribed by their doctor.*

### Case 1: Prescription from an Outpatient Consultation (OPD or Appointment)
*Outpatients pay directly at the pharmacy register before receiving their medication.*

```
 [Patient Arrives]        [Select Batches & Lock]        [Collect Payment]          [Deduct Stock & Dispense]
 Load Prescription ID ──► Row lock MedicineBatch  ──► CASH/UPI (PHB Invoice) ──► Record InventoryTransaction
                          (Verify Expiry Safety)      (payment_status: PAID)      (Prescription: DISPENSED)
```

* **Step 1: Invoice Preparation**:
  - The pharmacist enters the Prescription ID or searches for the patient.
  - The system loads all active prescribed items, calculating quantities, unit prices, taxes (GST), and discount percentages.
* **Step 2: Concurrency & Expiry Checks**:
  - When the pharmacist clicks **Dispense & Collect Payment**, the backend loops through each requested item:
    * **Pessimistic Write Lock**: Executes `batchRepository.findByIdAndHospitalIdForUpdate()` acquiring a `SELECT ... FOR UPDATE` row lock on `MedicineBatch` to prevent stock double-allocation.
    * **Expiry Validation**: Checks `expiryDate.isAfter(today)`. If a batch is expired, the transaction is rejected.
    * **Stock Validation**: Verifies `currentQuantity >= requestedQuantity`.
* **Step 3: Database Mutations**:
  - Deducts the quantity from `MedicineBatch.currentQuantity`.
  - Inserts `inventory_transaction` (type = `SALE`, quantity = negative).
  - Inserts `pharmacy_sales` (saleType = `PRESCRIPTION`, paymentStatus = `PAID`, netAmount, paymentMethod = CASH/UPI).
  - Inserts `pharmacy_sale_items` for each dispensed item.
  - **Status Update**: Transitions the `prescription` status to `DISPENSED`.

---

### Case 2: Prescription from an Inpatient Ward Stay (IPD)
*Inpatients receive medicines in the ward; their pharmacy costs are consolidated onto their final IPD hospital bill rather than paid at the counter.*

```
 [Doctor Prescribes]       [Nurse Triggers Order]       [Batch Locking & Deduction]     [Integrated Hospital Bill]
 Enter Rx in IPD Ward ──► Request Inpatient Dispense ──► Row Lock MedicineBatch     ──► Create IPD Billing Item:
                          (saleType: IPD)                 (paymentMethod: IPD_BILL)      "Pharmacy Inpatient Charge"
                                                                                         (recalculateTotal invoked)
```

* **Step 1: Clinical Order Entry**:
  - The ward doctor enters a prescription during inpatient rounds. A `prescriptions` row is inserted (status = `ACTIVE`, `ipdAdmissionId` populated).
* **Step 2: Inpatient Dispensing & Lock**:
  - The ward nurse triggers the dispense request. The pharmacy backend loads the inpatient's active prescription.
  - For each batch, the system acquires a **Pessimistic Write Lock** (`SELECT ... FOR UPDATE`), validates expiry safety, and confirms inventory availability.
* **Step 3: Database Mutations**:
  - Deducts stock from `MedicineBatch.currentQuantity`.
  - Inserts `inventory_transaction` (type = `SALE`, quantity = negative).
  - Inserts a `pharmacy_sales` record:
    * `saleType` = `"IPD"`
    * `paymentStatus` = `"PAID"`
    * `paymentMethod` = `"IPD_BILL"`
    * `isIpdBill` = `true`
    * `ipdAdmissionId` = `ipdAdmissionId`
  - Inserts `pharmacy_sale_items` tracking the detailed item sale.
* **Step 4: Clinical Bill Consolidation**:
  - The pharmacy service calls the core billing service:
    `billingService.addIpdBillingItem(ipdAdmissionId, description, totalDispenseAmount)`.
  - **Database Insertion**: Inserts a `billing_items` row under the patient's active IPD bill:
    * `description` = "Pharmacy Inpatient Charge - Invoice PHB-XXXX"
    * `amount` = `pharmacySale.netAmount`
  - **Total Sync**: Invokes `billingService.recalculateTotal(ipdBillId)` to instantly update the overall inpatient balance.
  - Transitions the inpatient `prescription` status to `DISPENSED`.

---

## Scenario E — Retail Pharmacy Sales

### Case 1: Walk-In Retail Customer (Non-registered)
*A retail buyer walks up to the pharmacy to purchase medications directly without a doctor prescription or a registered hospital record.*

```
 [Manual Search]            [Pessimistic Lock]          [Cash/UPI Checkout]          [Deduct Stock]
 Add Medicines manually ──► Lock MedicineBatch row  ──► Collect CASH/UPI        ──► Record InventoryTransaction
                            (Verify Expiry Safety)      (patientName: "Walk-in")     (Sale payment: PAID)
```

* **Workflow**:
  - The pharmacist manually searches the database for requested medicine names and selects available batches.
  - The pharmacist enters `patientName = "Walk-in Retail Customer"`. The `patientId` and `prescriptionId` remain `null`.
  - **Database Mutations**:
    * Acquires a row-level `SELECT ... FOR UPDATE` lock on the selected `MedicineBatch` rows.
    * Deducts stock and inserts `inventory_transaction` logs.
    * Inserts `pharmacy_sales` (saleType = `WALK-IN`, netAmount, paymentStatus = `PAID`, paymentMethod = CASH/UPI).
    * Inserts corresponding `pharmacy_sale_items` rows.

---

### Case 2: Registered Hospital Patient (OTC Purchase)
*A registered patient walks to the pharmacy counter to purchase over-the-counter (OTC) medicines without an active prescription.*

* **Workflow**:
  - The pharmacist searches the hospital directory and selects the patient's profile.
  - The system automatically links the transaction to `patientId` (demographics and history are pre-filled).
  - **Database Mutations**:
    * Pessimistically locks medicine batch rows, verifies expiry safety, and deducts inventory.
    * Inserts `pharmacy_sales` (saleType = `WALK-IN`, netAmount, `patientId` = `patient.getId()`, paymentStatus = `PAID`).
    * Inserts `pharmacy_sale_items` rows.
  - **Benefit**: The OTC purchase is compiled under the patient's longitudinal record, allowing doctors to review self-medication history during clinical consultations.

---

## Scenario F — Advanced Real-World Edge Cases

### Edge Case 1: Partial Deposit Payments During Inpatient Stay
*A patient admitted to the ICU accumulates charges quickly. The family is asked to pay periodic deposits to offset the running balance.*

```
 [Inpatient Active]           [Deposit Paid]               [Ledger Mutation]              [Dynamic UI Sync]
 Running Bill: ₹50,000  ───►  Pay Deposit: ₹20,000  ───►   Insert billing_payments  ───►  Total: ₹50,000 | Paid: ₹20,000
                                                           (status: PARTIAL)              Outstanding Balance: ₹30,000
```

* **Billing Flow**:
  - Patient has an active IPD invoice with ₹50,000 in accumulated bed and pharmacy charges.
  - The receptionist records a deposit payment of ₹20,000 using the cash counter API.
  - **Database Mutations**:
    * Inserts a `billing_payments` row (amount = ₹20,000, mode = CASH/UPI).
    * The system evaluates payment limits:
      $$\text{Total Paid} < \text{Total Billing Items Sum} \quad (\text{₹20,000} < \text{₹50,000})$$
    * Updates `billing` paymentStatus to `"PARTIAL"`.
  - **UI Render**: The dashboard updates dynamically:
    * **Status Badge**: Blue `PARTIAL` badge.
    * **Financial Summary**: Displays `Total: ₹50,000`, `Paid: ₹20,000` (green text), and `Balance Due: ₹30,000` (bold red text).

---

### Edge Case 2: Inpatient Bed Transfer / Downgrade Logic
*A patient stabilizes and is downgraded from a critical ICU bed to a standard General Ward bed. We must calculate accurate occupancy hours.*

```
 [ICU Bed Occupied]          [Change Bed Trigger]          [Rate Calculation]             [IPD Bill Recalculated]
 Rate: ₹5,000/day      ───►  Release ICU Bed         ───►  ICU duration billed.     ───►  recalculateTotal runs.
                             Occupy General Bed            General rate applied           Total reflects accurate
                             (Rate: ₹1,500/day)            going forward.                 occupancy rates.
```

* **Billing Flow**:
  - The receptionist transfers the patient using `IpdAdmissionService.changeBed(admissionId, newBedId)`.
  - **Database Mutations**:
    * Bed A (ICU, ₹5,000/day) status is updated to `available` and `currentIpdAdmissionId` is cleared.
    * Bed B (General Ward, ₹1,500/day) status is updated to `occupied` and links `currentIpdAdmissionId`.
    * **Duration & Pricing Logic**:
      * The system calculates the exact decimal days or hours the patient occupied the ICU bed.
      * Inserts a `billing_items` adjustment row: "ICU Bed Occupancy Charge (X days)" (amount = rate * duration).
      * Adds a pending charge row for the new General Ward bed going forward.
      * Invokes `billingService.recalculateTotal(billId)` to safely align the final invoice.

---

### Edge Case 3: Patient Stock Return & Expiry Safety
*A discharged inpatient returns unused, sealed medication strips to the pharmacy counter for a refund.*

```
 [Original Invoice]          [Critical Expiry Check]          [Restock Flag Check]          [Refund Ledger logged]
 Load Sale Invoice PHB ──►  Is batch expired today? ──►  If TRUE: restock batch row  ──► Cash Payout: unitPrice * Qty
                            (If YES: Block return)       If FALSE: waste transaction     Original Invoice linked
```

* **Billing Flow**:
  - The pharmacist loads the patient's original `PharmacySale` invoice and selects the medicine batch and quantity to return.
  - **Strict Expiry Check**:
    * System checks `batch.getExpiryDate().isBefore(today)`.
    * **Safety Blocker**: If the medicine has expired, the return is blocked (prevents expired stock return fraud).
  - **Stock Restocking Logic**:
    * **If Restock = True** (sealed, unexpired strips):
      * System acquires a `SELECT ... FOR UPDATE` lock on `MedicineBatch`.
      * Increments `currentQuantity` by the returned amount.
      * Inserts `inventory_transaction` (type = `RETURN`, quantity = positive).
    * **If Restock = False** (unsealed or damaged strips slated for disposal):
      * Inserts `inventory_transaction` (type = `RETURN`, quantity = 0, remarks = "Disposed / Medical Waste").
  - **Financial Ledger**:
    * Calculates refund: `refundAmount = unitPrice * returnedQty`.
    * Deducts the amount from the pharmacy's daily register ledger and returns the cash/credits to the patient.

---

### Edge Case 4: Insurance-Backed (TPA) Co-payment Billing
*A patient has a health insurance policy. The insurance company (TPA) approves a partial amount of the IPD bill. The patient must pay the co-pay balance.*

```
 [IPD Stay Finished]        [TPA Pre-Auth Entered]         [Patient Co-pay paid]         [TPA Claim Settled]
 Final Bill: ₹80,000  ───►  Approved Limit: ₹60,000 ───►  Patient Pays: ₹20,000  ───►  TPA sends ₹60,000 (UPI)
                            Co-pay Due: ₹20,000           (status: PARTIAL ➔ PAID)      Invoice status: CLOSED
```

* **Billing Flow**:
  - Inpatient finishes treatment with a consolidated bill of ₹80,000.
  - The receptionist inputs the TPA Pre-Authorization code and sets the Approved Limit to ₹60,000.
  - **Database Mutations**:
    * Inserts a `billing_items` row: "TPA Approved Amount - [Company Name]" (₹-60,000.00). This temporary ledger credit reduces the active due amount from the patient to a co-pay of ₹20,000.
    * Triggers `billingService.recalculateTotal(billId)`.
  - **Patient Co-payment**:
    * The patient pays the ₹20,000 co-payment at checkout.
    * Inserts `billing_payments` row (amount = ₹20,000, mode = CASH/UPI, payer = "PATIENT_COPAY").
    * Updates `billing` paymentStatus to `PARTIAL` (since ₹60,000 TPA share is still technically outstanding from the insurance company).
    * Patient is clinically discharged (`confirmDischarge` checks that patient balance is ₹0).
  - **Insurance Settlement**:
    * 30 days later, the insurance company dispatches the wire transfer of ₹60,000.
    * The billing manager enters the payment: `POST /hospital/billing/{billId}/settle-tpa` with transaction reference UTR.
    * Inserts `billing_payments` row (amount = ₹60,000, mode = UPI, payer = "INSURANCE_TPA").
    * Removes the temporary credit item.
    * Checks: $\sum(\text{billing\_payments}) == \sum(\text{billing\_items})$.
    * Transitions the billing invoice to `CLOSED` (all settlement completed).

---

### Edge Case 5: Emergency Room (ER) Triage to Inpatient Transition
*A trauma patient arrives via ambulance at the Emergency Room, receives immediate resuscitation, diagnostics, and ER consumables, and is then rushed to the ICU.*

```
 [ER Patient Arrives]       [ER Medical Procedures]        [Admitted to ICU]             [Consolidated Invoice]
 Auto-create ER Case ───►  Enter Triage Fees & Labs  ───►  Migrate active ER Bill  ───►  All ER items appended
 (status: ER_ACTIVE)        (ER Bill: PENDING)             into new IPD Admission        to single Inpatient Bill
                                                           (Bed: ICU Bed occupied)       (recalculateTotal runs)
```

* **Billing Flow**:
  - The patient enters the ER. The ER nurse registers an Emergency Case. The system inserts an `opd` row (visitType = `EMERGENCY`, status = `ER_ACTIVE`).
  - **Database Insertion**:
    * Inserts `billing` row (type = `OPD`, description = "Emergency Triage Invoice", status = `PENDING`).
    * Inserts `billing_items`: "ER Triage Fee" (₹1,500.00), "Emergency Ambulance Charge" (₹2,000.00), and "IV Cannula & ER Consumables" (₹500.00).
  - **ICU Transfer (IPD Admission)**:
    * The physician notes that the patient requires ICU admission.
    * The receptionist admits the patient using `IpdAdmissionService.admitFromEr(erOpdId, bedId)`.
  - **Database Consolidation**:
    * Inserts `ipd_admissions` row (status = `ADMITTED`).
    * Converts the active ER `billing` record into an `IPD` billing record (`billingType` = `IPD`, links `ipdAdmissionId`).
    * Appends the new ICU "ICU Bed Price" (e.g., ₹5,000) as a new `billing_items` row.
    * Triggers `billingService.recalculateTotal(billId)`.
  - **Benefit**: Rather than generating separate bills for ER care and ICU stays, all procedures, pharmacy items, and bed charges are consolidated under a single, patient-friendly inpatient invoice.

---

### Edge Case 6: Package-Based Fixed Price Surgery Billing (with Extra Consumables)
*A patient opts for a predefined "Cesarean Delivery Package" for a fixed price of ₹45,000. We must bundle standard services but charge extra for non-package consumables.*

```
 [Select Package]             [Bundle Standard Items]        [Log Extra Items]              [Consolidated Billing]
 "C-Section Package" ───► Standard bed, nursing, fees  ───► Add non-package items     ───► Bill total: ₹48,000
 (Rate: ₹45,000)          billed as ₹0 (Included)            (e.g., Special drugs)          (Package ₹45k + Extra ₹3k)
```

* **Billing Flow**:
  - During IPD admission, the receptionist selects the **C-Section Maternity Package** (fixed rate ₹45,000).
  - **Database Insertion**:
    * Inserts a `billing` row (type = `IPD`, amount = ₹45,000, status = `PENDING`).
    * Inserts `billing_items`: "Fixed Package: Cesarean Delivery" (amount = ₹45,000.00).
  - **Consumable Allocation**:
    * Standard bed occupancy (up to 3 days), standard nursing fees, and doctor fees are flagged as `packageIncluded = true`. The system logs them as `billing_items` with a rate of ₹0.00 (showing the patient they were bundled).
    * On Day 4, the patient experiences a complication requiring a specialized medication not included in the C-Section Package.
    * The pharmacist dispenses the specialized drug. The backend checks the package master exclusions list and identifies this drug is an extra-package item.
    * Inserts a `billing_items` row: "Excluded Consumable: Special Drug" (amount = ₹3,000.00).
    * Triggers `billingService.recalculateTotal(billId)`.
  - **Total**: The patient's invoice updates to ₹48,000 (₹45,000 package + ₹3,000 extra items), providing complete itemized clarity.

---

### Edge Case 7: Multi-Department OPD Consultations & Diagnostics
*A patient visits the hospital for a comprehensive check-up. They consult a General Physician, visit a Cardiologist, get an ECG, and receive a Blood Panel test.*

```
 [Reception Desk]           [Clinical Audits]              [Diagnostic Complete]          [Unified Invoice Checkout]
 Register consultations ──► Physician consultation ───► Lab technician logs ECG ───► Single OPD Bill: ₹4,000
 & diagnostic requests      Cardiologist consultation   & Blood Panel completions      (status: PENDING ➔ PAID)
```

* **Billing Flow**:
  - The receptionist registers the patient's comprehensive visits at check-in.
  - **Database Insertion**:
    * Inserts a single `billing` record (type = `OPD`, status = `PENDING`).
    * Inserts `billing_items` rows:
      * "General Physician Consultation" (₹500.00)
      * "Cardiology Department Consultation" (₹1,500.00)
      * "Diagnostic Test: Electrocardiogram (ECG)" (₹800.00)
      * "Lab Panel: Complete Blood Count (CBC)" (₹1,200.00)
  - **Workflow**:
    * The patient visits the Physician queue, gets examined (marked `CONSULTED`).
    * The patient visits the Cardiologist queue, gets examined (marked `CONSULTED`).
    * The patient goes to the lab. The lab technician performs the tests and logs results, updating `LabOrder` status to `COMPLETED`.
  - **Payment Collection**:
    * At checkout, the patient pays ₹4,000.
    * Inserts `billing_payments` row (amount = ₹4,000, mode = UPI).
    * Updates `billing` paymentStatus to `PAID`.
    * Automatically closes the linked OPD clinical file.
