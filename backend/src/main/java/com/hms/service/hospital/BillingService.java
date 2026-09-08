package com.hms.service.hospital;
import com.hms.util.LogSanitizer;

import com.hms.entity.Billing;
import com.hms.entity.Hospital;
import com.hms.entity.Appointment;
import com.hms.repository.BillingRepository;
import com.hms.repository.HospitalRepository;
import com.hms.security.SecurityContextHelper;

import com.hms.exception.ResourceNotFoundException;
import com.hms.exception.UnauthorizedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;

@Service
public class BillingService {
    private static final String BILLING_MODULE = "BILLING";


    private static final Logger logger = LoggerFactory.getLogger(BillingService.class);

    @Autowired
    private BillingRepository billingRepository;

    @Autowired
    private com.hms.repository.BillingItemRepository billingItemRepository;

    @Autowired
    private com.hms.repository.BillingMedicineRepository billingMedicineRepository;

    // Deliberately no OpdRepository: billing does not reach into the clinical record. The OPD's
    // status is owned by the doctor's consultation; this service only moves money.

    @Autowired
    private com.hms.service.AuditLogService auditLogService;

    @Autowired
    private HospitalRepository hospitalRepository;

    @Autowired
    private SecurityContextHelper securityHelper;

    @Autowired
    private com.hms.security.HospitalWebSocketHandler webSocketHandler;

    @Autowired
    private com.hms.repository.BillingPaymentRepository billingPaymentRepository;

    @Autowired
    private com.hms.repository.IpdAdmissionRepository ipdAdmissionRepository;

    @Autowired
    private com.hms.repository.WardRepository wardRepository;

    @Autowired
    private com.hms.repository.MedicalRecordRepository medicalRecordRepository;


    /**
     * Auto-generate a bill for a completed appointment
     */
    @Transactional
    public void autoGenerateOpdBill(Appointment appointment) {
        if (billingRepository.existsByAppointmentId(appointment.getId())) {
            logger.warn("Skipping bill auto-generation: Bill already exists for appointment {}", appointment.getId());
            return;
        }

        Hospital hospital = hospitalRepository.findById(appointment.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException("Hospital not found"));

        // Check module access
        if (hospital.getModules() == null || !hospital.getModules().contains(BILLING_MODULE)) {
            logger.warn("Skipping bill generation for appointment {}. BILLING module disabled.", appointment.getId());
            return;
        }

        // A visit costs the same however the patient arrived: charge the case-paper fee alongside
        // the consultation fee, exactly as the walk-in OPD bill does (createOpdBill). Previously
        // an appointment patient was billed the consultation fee only, silently skipping the
        // hospital's case-paper fee — the same encounter priced two different ways.
        BigDecimal fee = hospital.getConsultationFee();
        BigDecimal caseFee = hospital.getCasePaperFee();
        if (caseFee == null || caseFee.compareTo(BigDecimal.ZERO) < 0) {
            caseFee = BigDecimal.ZERO;
        }
        if (fee != null && fee.compareTo(BigDecimal.ZERO) > 0) {
            final BigDecimal casePaperFee = caseFee;
            BigDecimal total = fee.add(casePaperFee);

            Billing bill = new Billing();
            bill.setHospitalId(hospital.getId());
            bill.setPatientId(appointment.getPatientId());
            bill.setDoctorId(appointment.getDoctorId());
            bill.setAppointmentId(appointment.getId());
            bill.setAmount(total);
            bill.setPaymentStatus("PENDING"); // nothing collected yet
            bill.setDescription(casePaperFee.compareTo(BigDecimal.ZERO) > 0
                    ? "OPD - Case Paper + Consultation"
                    : "Consultation Fee - Auto Generated");

            // Attempt to resolve related opdId from MedicalRecord
            try {
                java.util.Optional<com.hms.entity.MedicalRecord> recordOpt = medicalRecordRepository.findByAppointmentId(appointment.getId());
                recordOpt.ifPresent(r -> bill.setOpdId(r.getOpdId()));
            } catch (Exception e) {
                logger.warn("Could not resolve related opdId for appointment {}", appointment.getId(), e);
            }

            Billing saved = billingRepository.save(bill);

            // Itemised breakdown, so the receipt matches the walk-in OPD bill.
            try {
                if (casePaperFee.compareTo(BigDecimal.ZERO) > 0) {
                    com.hms.entity.BillingItem caseItem = new com.hms.entity.BillingItem();
                    caseItem.setBillingId(saved.getId());
                    caseItem.setHospitalId(hospital.getId());
                    caseItem.setDescription("Case Paper Fee");
                    caseItem.setAmount(casePaperFee);
                    billingItemRepository.save(caseItem);
                }

                com.hms.entity.BillingItem item = new com.hms.entity.BillingItem();
                item.setBillingId(saved.getId());
                item.setHospitalId(hospital.getId());
                item.setDescription("Consultation Fee");
                item.setAmount(fee);
                billingItemRepository.save(item);
            } catch (Exception e) {
                logger.warn("Failed to create billing items for auto-bill {}", saved.getId(), e);
            }


            logger.info("Auto-generated bill for appointment: {} with amount: {}", appointment.getId(), fee);
        } else {
            logger.warn("Skipped bill generation for appointment {}. Fee is null or zero. Hospital Fee: {}",
                    appointment.getId(), fee);
        }
    }

    /**
     * Get all bills for current hospital with optional search
     */
    public Page<Billing> getAllBills(String search, String status, Pageable pageable) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (search != null && !search.isEmpty()) {
            Page<Billing> p = billingRepository.searchBillings(hospitalId, search, pageable);
            if (status != null && !status.isEmpty()) {
                java.util.List<Billing> filtered = new java.util.ArrayList<>();
                for (Billing b : p.getContent()) if (status.equalsIgnoreCase(b.getPaymentStatus())) filtered.add(b);
                return new org.springframework.data.domain.PageImpl<>(filtered, pageable, filtered.size());
            }
            return p;
        }

        if (status != null && !status.isEmpty()) {
            return billingRepository.findByHospitalIdAndPaymentStatus(hospitalId, status, pageable);
        }

        return billingRepository.findByHospitalId(hospitalId, pageable);
    }

    /**
     * Update payment status.
     *
     * <p>Transactional and taken under a row lock on the bill, because marking a bill PAID also
     * back-fills the outstanding remainder into the payment ledger. Reading what has been
     * collected and writing the remainder has to be one serialised step: unlocked, two "Mark as
     * Paid" clicks -- a double-click, or two staff on the same bill -- both read the same
     * collected figure, both computed the same remainder, and both inserted it. The bill then
     * showed twice its own total as collected.
     */
    @Transactional
    public Billing updateStatus(Long id, String status, String paymentMethod, String paymentReference) {
        if (!"PENDING".equalsIgnoreCase(status) && 
            !"PARTIAL".equalsIgnoreCase(status) && 
            !"PAID".equalsIgnoreCase(status) && 
            !"CLOSED".equalsIgnoreCase(status)) {
            throw new IllegalArgumentException("Invalid billing status value: " + status);
        }

        Long hospitalId = securityHelper.getCurrentHospitalId();
        validateBillingAccess(hospitalId);

        Billing bill = billingRepository.findByIdAndHospitalIdForUpdate(id, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Bill not found"));

        bill.setPaymentStatus(status);
        if ("PAID".equalsIgnoreCase(status)) {
            try {
                String userEmail = securityHelper.getCurrentUserEmail();
                String userRole = securityHelper.getCurrentUserRole();
                bill.setMarkedPaidBy(userRole + " (" + userEmail + ")");
            } catch (Exception e) {
                logger.warn("Failed to set markedPaidBy during billing payment status update: {}", e.getMessage());
            }
        }
        if (paymentMethod != null && !paymentMethod.isEmpty()) {
            bill.setPaymentMethod(paymentMethod);
        }
        if (paymentReference != null && !paymentReference.isEmpty()) {
            bill.setPaymentReference(paymentReference);
        }

        Billing saved = billingRepository.save(bill);

        // Create audit log
        try {
            auditLogService.logAction(
                    "BILLING_STATUS_CHANGED",
                    "Bill " + saved.getCustomId() + " status updated to " + status + ".",
                    securityHelper.getCurrentUserEmail(),
                    hospitalId,
                    BILLING_MODULE,
                    saved.getPublicId(),
                    null);
        } catch (Exception e) {
            logger.warn("Failed to create audit log for billing status update", e);
        }

        // If the bill is marked as PAID, ensure that corresponding BillingPayment is recorded so both sections are synchronized
        if ("PAID".equalsIgnoreCase(status)) {
            try {
                java.util.List<com.hms.entity.BillingItem> items = billingItemRepository.findByBillingId(saved.getId());
                java.util.List<com.hms.entity.BillingMedicine> medicines = billingMedicineRepository.findByBillingId(saved.getId());
                java.math.BigDecimal totalAmt = java.math.BigDecimal.ZERO;
                if ((items != null && !items.isEmpty()) || (medicines != null && !medicines.isEmpty())) {
                    if (items != null) {
                        for (com.hms.entity.BillingItem it : items) {
                            if (it.getAmount() != null) {
                                totalAmt = totalAmt.add(it.getAmount());
                            }
                        }
                    }
                    if (medicines != null) {
                        for (com.hms.entity.BillingMedicine med : medicines) {
                            if (med.getAmount() != null) {
                                totalAmt = totalAmt.add(med.getAmount());
                            }
                        }
                    }
                } else {
                    totalAmt = saved.getAmount() != null ? saved.getAmount() : java.math.BigDecimal.ZERO;
                    if (totalAmt.compareTo(java.math.BigDecimal.ZERO) == 0
                            && "IPD".equalsIgnoreCase(saved.getBillingType())
                            && saved.getIpdAdmissionId() != null) {
                        com.hms.entity.IpdAdmission ipd = ipdAdmissionRepository.findById(saved.getIpdAdmissionId()).orElse(null);
                        if (ipd != null && ipd.getWardId() != null) {
                            com.hms.entity.Ward ward = wardRepository.findById(ipd.getWardId()).orElse(null);
                            if (ward != null && ward.getBedPrice() != null) {
                                totalAmt = totalAmt.add(ward.getBedPrice());
                            }
                        }
                    }
                }

                java.util.List<com.hms.entity.BillingPayment> payments = billingPaymentRepository.findByBillingId(saved.getId());
                java.math.BigDecimal paidAmt = java.math.BigDecimal.ZERO;
                for (com.hms.entity.BillingPayment p : payments) {
                    if (p.getAmount() != null) {
                        paidAmt = paidAmt.add(p.getAmount());
                    }
                }

                if (paidAmt.compareTo(totalAmt) < 0) {
                    java.math.BigDecimal remaining = totalAmt.subtract(paidAmt);
                    com.hms.entity.BillingPayment payment = new com.hms.entity.BillingPayment();
                    payment.setBillingId(saved.getId());
                    payment.setHospitalId(saved.getHospitalId());
                    payment.setAmount(remaining);
                    payment.setMode(paymentMethod != null && !paymentMethod.isEmpty() ? paymentMethod : "CASH");
                    payment.setReference(paymentReference);
                    billingPaymentRepository.save(payment);
                }
            } catch (Exception e) {
                logger.warn("Failed to auto-create BillingPayment for PAID status update", e);
            }
        }

        // NOTE: taking payment deliberately does NOT touch the OPD's status.
        //
        // Money and medicine are separate concerns. This used to set the linked OPD to COMPLETED
        // whenever a bill was marked PAID, which meant the cashier closed the patient's clinical
        // encounter. That was wrong in three ways:
        //   1. Paying at the counter is not evidence a doctor saw the patient. With "bill before
        //      OPD" it would have completed the case before the consultation even happened.
        //   2. A patient who owes money is not clinically unfinished — an unpaid balance is a
        //      billing state (PENDING / PARTIAL), not a reason to hold the encounter open.
        //   3. It stranded cases: with "bill before OPD" and no extras, the bill is already PAID
        //      at entry and never re-marked, so this never fired and the OPD sat unfinished.
        //
        // The OPD is now completed by the doctor finishing the consultation (DoctorService), and
        // what is owed lives entirely on the bill.

        try {
            webSocketHandler.broadcast(saved.getHospitalId(), "{\"type\":\"REFRESH_DATA\"}");
        } catch (Exception e) {
            logger.warn("Failed to broadcast WebSocket refresh data from updateStatus", e);
        }

        return saved;
    }

    private void validateBillingAccess(Long hospitalId) {
        Hospital hospital = hospitalRepository.findById(hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Hospital not found"));
        if (hospital.getModules() == null || !hospital.getModules().contains(BILLING_MODULE)) {
            throw new IllegalArgumentException("BILLING module is disabled for your hospital.");
        }
    }

    /**
     * "Payment first" flow: create the OPD bill (case paper + consultation) and mark it PAID
     * right at OPD entry, recording the payment — WITHOUT completing the OPD (the consultation
     * still has to happen). Deliberately does not reuse updateStatus(), which would flip the OPD
     * to COMPLETED on PAID. Returns null when the BILLING module is off.
     */
    @Transactional
    public com.hms.entity.Billing createPaidOpdBillAtEntry(Long opdId, Long patientId, Long doctorId,
                                                           String paymentMethod, String paymentReference) {
        Billing bill = createOpdBill(opdId, patientId, doctorId);
        if (bill == null) return null; // BILLING module disabled

        // Same vocabulary as the mark-as-paid flow: CASH or UPI (UPI carries a UTR reference).
        String method = "UPI".equalsIgnoreCase(paymentMethod) ? "UPI" : "CASH";
        String reference = "UPI".equals(method) ? paymentReference : null;

        bill.setPaymentStatus("PAID");
        bill.setPaymentMethod(method);
        bill.setPaymentReference(reference);
        try {
            bill.setMarkedPaidBy(securityHelper.getCurrentUserRole() + " (" + securityHelper.getCurrentUserEmail() + ")");
        } catch (Exception ignored) { /* best-effort attribution only */ }
        Billing saved = billingRepository.save(bill);

        // The ledger row is the RECORD OF THE MONEY, not a nice-to-have mirror — everything
        // downstream derives from it: the bill's status (recalculateTotal), the "Received"/
        // "Balance" lines on the printed receipt, and the payments view.
        //
        // So this write must NOT be best-effort. If it were swallowed, the bill would be left
        // claiming PAID with an empty ledger, and the next recalculateTotal would see paid=0 and
        // silently flip it back to PENDING — the patient's payment would vanish and the receipt
        // would print "Received 0". Let a failure roll back the whole @Transactional method
        // instead: no bill is better than a bill that lies about money. The caller (OpdService)
        // already treats pay-first billing as best-effort, so the OPD case is still created and
        // the fee is simply collected at the counter instead.
        com.hms.entity.BillingPayment payment = new com.hms.entity.BillingPayment();
        payment.setBillingId(saved.getId());
        payment.setHospitalId(saved.getHospitalId());
        payment.setAmount(saved.getAmount() != null ? saved.getAmount() : java.math.BigDecimal.ZERO);
        payment.setMode(method);
        payment.setReference(reference);
        billingPaymentRepository.save(payment);

        return saved;
    }

    /**
     * Create a combined OPD bill (case paper fee + consultation fee) for an OPD case
     * @param opdId OPD id
     * @param patientId Patient id
     * @param doctorId Doctor id
     */
    public com.hms.entity.Billing createOpdBill(Long opdId, Long patientId, Long doctorId) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        Hospital hospital = hospitalRepository.findById(hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Hospital not found"));

        // Check module access
        if (hospital.getModules() == null || !hospital.getModules().contains(BILLING_MODULE)) {
            logger.warn("Skipping OPD bill generation for OPD {}. BILLING module disabled.", opdId);
            return null;
        }

        // Fees: prefer hospital-configured values, fallback to previous defaults
        java.math.BigDecimal caseFee = hospital.getCasePaperFee();
        if (caseFee == null || caseFee.compareTo(java.math.BigDecimal.ZERO) < 0) {
            caseFee = new java.math.BigDecimal("100.00");
        }

        java.math.BigDecimal consultFee = hospital.getConsultationFee();
        if (consultFee == null || consultFee.compareTo(java.math.BigDecimal.ZERO) <= 0) {
            consultFee = new java.math.BigDecimal("500.00");
        }

        java.math.BigDecimal total = caseFee.add(consultFee);

        Billing bill = new Billing();
        bill.setHospitalId(hospitalId);
        bill.setPatientId(patientId);
        bill.setDoctorId(doctorId != null ? doctorId : 0L);
        bill.setOpdId(opdId);
        bill.setAmount(total);
        bill.setDescription("OPD - Case Paper + Consultation");
        bill.setPaymentStatus("PENDING");
        bill.setAppointmentId(null);

        Billing saved = billingRepository.save(bill);

        // Create billing items for breakdown
        com.hms.entity.BillingItem item1 = new com.hms.entity.BillingItem();
        item1.setBillingId(saved.getId());
        item1.setHospitalId(hospitalId);
        item1.setDescription("Case Paper Fee");
        item1.setAmount(caseFee);
        billingItemRepository.save(item1);

        com.hms.entity.BillingItem item2 = new com.hms.entity.BillingItem();
        item2.setBillingId(saved.getId());
        item2.setHospitalId(hospitalId);
        item2.setDescription("Consultation Fee");
        item2.setAmount(consultFee);
        billingItemRepository.save(item2);

        // Broadcast real-time refresh
        try {
            webSocketHandler.broadcast(hospitalId, "{\"type\":\"REFRESH_DATA\"}");
        } catch (Exception e) {
            logger.warn("Failed to broadcast WebSocket refresh after OPD bill creation", e);
        }

        logger.info("OPD bill generated for OPD {} patient {} with amount {}", opdId, patientId, total);
        return saved;
    }

    /**
     * Centralized utility to recalculate top-level bill amount based on active items.
     * Prevents IPD denormalized totals divergence.
     */
    public void recalculateTotal(Long billingId) {
        Billing bill = billingRepository.findById(billingId).orElse(null);
        if (bill != null) {
            java.util.List<com.hms.entity.BillingItem> items = billingItemRepository.findByBillingId(billingId);
            BigDecimal total = BigDecimal.ZERO;
            for (com.hms.entity.BillingItem item : items) {
                if (item.getAmount() != null) {
                    total = total.add(item.getAmount());
                }
            }
            java.util.List<com.hms.entity.BillingMedicine> medicines = billingMedicineRepository.findByBillingId(billingId);
            for (com.hms.entity.BillingMedicine med : medicines) {
                if (med.getAmount() != null) {
                    total = total.add(med.getAmount());
                }
            }
            bill.setAmount(total);
            syncStatusToLedger(bill, total);
            billingRepository.save(bill);
            logger.info("Recalculated total for bill {}: {} (status {})", LogSanitizer.clean(billingId), total, LogSanitizer.clean(bill.getPaymentStatus()));
        }
    }

    /**
     * Keep a bill's status honest: it is derived from what the ledger has actually collected
     * against the current total, never asserted independently.
     *
     *   paid >= total  -> PAID
     *   0 < paid < total -> PARTIAL   (a balance is owed)
     *   paid == 0      -> PENDING
     *
     * This is what makes "bill before OPD" work: the patient pre-pays the consultation +
     * case-paper fee, then the doctor adds a procedure or in-clinic medicines, the total rises
     * above what was collected, and the bill correctly drops back to PARTIAL with a balance for
     * reception to collect at checkout — instead of silently continuing to read PAID while money
     * is owed. CLOSED (written off / cancelled) is a terminal decision and is never overridden.
     */
    private void syncStatusToLedger(Billing bill, BigDecimal total) {
        if ("CLOSED".equalsIgnoreCase(bill.getPaymentStatus())) return;

        java.util.List<com.hms.entity.BillingPayment> ledger = billingPaymentRepository.findByBillingId(bill.getId());
        BigDecimal paid = BigDecimal.ZERO;
        for (com.hms.entity.BillingPayment p : ledger) {
            if (p.getAmount() != null) paid = paid.add(p.getAmount());
        }

        // A bill marked PAID with no ledger rows at all is a legacy/hand-marked record from
        // before payments were itemised. Treat it as settled rather than "paid nothing" — the
        // printed receipt applies the same rule — otherwise recalculating a total would silently
        // un-pay it. Every bill written today records its money, so this only shields old rows.
        if (ledger.isEmpty() && "PAID".equalsIgnoreCase(bill.getPaymentStatus())) return;

        if (total.compareTo(BigDecimal.ZERO) <= 0) return; // nothing billed yet; leave as-is
        if (paid.compareTo(total) >= 0)          bill.setPaymentStatus("PAID");
        else if (paid.compareTo(BigDecimal.ZERO) > 0) bill.setPaymentStatus("PARTIAL");
        else                                      bill.setPaymentStatus("PENDING");
    }
}

