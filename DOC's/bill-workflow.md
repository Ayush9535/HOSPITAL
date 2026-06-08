# HMS Billing & Pharmacy Module Workflow

This document provides a complete overview of the billing system and the pharmacy module, including the recent architectural fixes, standardizations, and the pharmacy sale inventory lifecycle.

---

## 1. Billing Data Model

```
billing                       ← one record per bill (OPD or IPD)
  ├── billing_items           ← line items (consultation fee, bed price, pharmacy items, adjustments)
  └── billing_payments        ← partial/full payments recorded separately (CASH/UPI with UTR references)

billing.payment_status values: PENDING | PARTIAL | PAID | CLOSED
billing.billing_type values:   OPD | IPD
```

### Key Fields on `Billing`
| Field             | Description                                              |
|-------------------|----------------------------------------------------------|
| `appointmentId`   | Links to an appointment (if auto-billed)                 |
| `opdId`           | Links to an OPD consultation                             |
| `ipdAdmissionId`  | Links to an IPD admission                                |
| `amount`          | Grand total (strictly synchronized with sum of items)     |
| `paymentStatus`   | Lifecycle status (PENDING -> PARTIAL -> PAID -> CLOSED)   |
| `paymentMethod`   | Payment channel (CASH / UPI)                             |
| `paymentReference`| Transaction reference / UTR number for UPI receipts      |

---

## 2. Invoicing Workflows (OPD / IPD)

There are four paths to create a bill, now fully secured against duplicate generation and data drift:

### Path 1 — Appointment Completion (Auto-bill)
```
Staff marks appointment → COMPLETED
  └─ AppointmentService.updateStatus() / updateDetails()
       ├─ Guards state change (only triggers if transition is from a non-completed state)
       └─ BillingService.autoGenerateOpdBill(appointment)
            ├─ Checks BILLING module is enabled and consultation fee is > 0
            ├─ Checks if a bill already exists for the appointment (blocks duplicate generation)
            ├─ Resolves opdId via MedicalRecord (to link consultation and checkout)
            ├─ Creates Billing row (status=PENDING)
            └─ Creates BillingItem: "Consultation Fee" (for granular breakdowns)
```

### Path 2 — Doctor Completes Consultation (OPD Queue)
```
Doctor submits consultation details
  └─ DoctorService.completeConsultation()
       └─ BillingService.createOpdBill(opdId, patientId, doctorId)
            ├─ Checks BILLING module is enabled
            ├─ Reads casePaperFee + consultationFee
            ├─ Creates Billing row (status=PENDING, opdId=opdId)
            └─ Creates two BillingItems: "Case Paper Fee" and "Consultation Fee"
```

### Path 3 — IPD Admission & Bed Upgrade
```
Receptionist admits patient from OPD
  └─ IpdAdmissionService.admitFromOpd()
       ├─ Creates Billing row (type=IPD, status=PENDING, opdId=sourceOpdId, bedPrice=wardRate)
       └─ Creates BillingItem: "Bed Price"

During Admission (Doctor adds follow-ups/prescriptions):
  ├─ IpdAdmissionService.addIpdFollowup()
  │    ├─ Creates BillingItem: "Doctor Consultation Fee (IPD Follow-up)"
  │    └─ Invokes BillingService.recalculateTotal() (synchronizes denormalized grand total)
  │
  ├─ IpdAdmissionService.addIpdPrescription()
  │    ├─ Creates BillingItem: [Medicine Name] (computed as Unit Price * Frequency * Duration)
  │    └─ Invokes BillingService.recalculateTotal()
  │
  └─ IpdAdmissionService.changeBed() (Receptionist upgrades Ward)
       ├─ Creates BillingItem: "Bed Upgrade Price Adjustment"
       └─ Invokes BillingService.recalculateTotal()
```

### Path 4 — Manual Payment Recording (Vite Dashboards)
```
Staff clicks "Mark Paid" on PENDING/PARTIAL bill
  └─ Opens interactive PaymentModal (Admin, Receptionist, and Doctor Dashboards)
       ├─ Selects Payment Method: CASH or Online/UPI
       ├─ If UPI, prompts for UTR transaction reference
       └─ Calls BillingService.updateStatus(id, "PAID", method, reference)
            ├─ Validates status value is in allowed set
            ├─ Saves payment details
            ├─ Records matching BillingPayment record
            └─ Automatically transitions related OPD status to COMPLETED
```

---

## 3. IPD Discharge Lifecycle

```
Doctor plans discharge (sets status to DISCHARGE_PLANNED)
  └─ Receptionist confirms discharge via confirmDischarge()
       ├─ Fetches total of all BillingItems vs sum of BillingPayments
       ├─ Blocks discharge if outstanding balance > 0
       ├─ Automatically terminates all active IPD prescriptions (sets to COMPLETED)
       ├─ Releases bed (sets status to available, nulls currentIpdAdmissionId)
       ├─ Updates paymentStatus to CLOSED for all bills linked to the IPD admission
       └─ StatusBadge styles CLOSED bills in gray; actions ("Mark Paid") are hidden
```

---

## 4. Pharmacy Module & Inventory Workflow

The pharmacy module operates as a separate billing and stock management layer, fully isolated by hospital ID and protected from concurrency issues via database row locking.

```
                   ┌───────────────────────────────────────────────┐
                   │           Pharmacy Sale Workflow              │
                   └───────────────────────────────────────────────┘

Pharmacist dispenses medicines
  └─ PharmacySaleService.createSale(PharmacySaleRequest)
       ├─ Validates quantity > 0 (prevents negative stock transactions)
       ├─ Pessimistically locks MedicineBatch row (prevents race conditions)
       ├─ Validates batch.currentQuantity >= request.quantity
       ├─ Deducts quantity from MedicineBatch
       ├─ Records InventoryTransaction (type=SALE, quantity=negative)
       ├─ Inserts PharmacySale and PharmacySaleItem rows
       └─ Auto-updates related Prescription status to DISPENSED
```

### Pharmacy Sales Lifecycle Fields
| Field              | Description                                                          |
|--------------------|----------------------------------------------------------------------|
| `saleType`         | Type of transaction: WALK-IN, PRESCRIPTION, or IPD                   |
| `postingStatus`    | Posting state (defaults to POSTED)                                   |
| `paymentStatus`    | Defaults to PAID upon collection at counter                          |
| `isIpdBill`        | Links to IPD profiles if charged during inpatient stays              |

### Patient Stock Returns & Expiry Safeguards
```
Patient returns unused/correct medicines
  └─ PharmacySaleService.processPatientReturn(saleId, returnItems)
       ├─ Verifies batch was purchased in the original invoice
       ├─ Validates returnQty <= originalPurchasedQty
       ├─ Expiry Check: Blocks return if batch expiryDate is before today
       ├─ Calculates refund amount based on Unit Price * returnQty
       └─ Restock Flag processing:
            ├─ If restock=true:
            │    ├─ Pessimistically locks batch row
            │    ├─ Adds returned quantity back to currentQuantity
            │    └─ Records InventoryTransaction (type=RETURN, quantity=positive)
            │
            └─ If restock=false (disposal/waste):
                 └─ Records InventoryTransaction (type=RETURN, quantity=0, logs disposal)
```

---

## 5. Security & Verification Checks

### Backend Robustness
- **Validation**: Strict checking of payment status transitions (`PENDING`, `PARTIAL`, `PAID`, `CLOSED`).
- **Isolation**: Every transaction (IPD, Billing, Pharmacy Sale, Stock Return) includes hospital ID constraints to maintain multi-tenant isolation.
- **Race Condition Prevention**: Concurrency operations on stock batches use `SELECT ... FOR UPDATE` (Pessimistic Write Locks) to maintain inventory integrity during simultaneous checkouts.

### Frontend Usability
- **Modals**: Elimination of raw browser prompts. React modals capture payment methods and UPI references seamlessly across Admin, Receptionist, and Doctor consoles.
- **Badge Routing**: Clean color mapping of statuses in `StatusBadge` (Success -> Green, Warning -> Yellow, Partial -> Blue, Neutral -> Gray for CLOSED/INACTIVE).
