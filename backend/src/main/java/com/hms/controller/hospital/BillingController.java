package com.hms.controller.hospital;

import com.hms.exception.ResourceNotFoundException;

import com.hms.entity.Billing;
import com.hms.entity.BillingItem;
import com.hms.entity.BillingPayment;
import com.hms.service.hospital.BillingService;

import com.hms.validation.NoEmoji;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import com.hms.entity.Hospital;
import com.hms.entity.Patient;
import com.hms.repository.HospitalRepository;
import com.hms.service.hospital.PatientService;
import com.hms.service.PdfService;

@RestController
@RequestMapping({"/hospital/billing", "/clinic/billing", "/pharmacy/billing"})
public class BillingController {
    private static final String DOCTOR_ROLE = "DOCTOR";
    private static final String AMOUNT = "amount";


    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(BillingController.class);

    @Autowired
    private BillingService billingService;

    @Autowired
    private com.hms.repository.BillingPaymentRepository billingPaymentRepository;

    @Autowired
    private com.hms.repository.HospitalSettingRepository hospitalSettingRepository;

    private void validateBillingAccess() {
        String role = securityHelper.getCurrentUserRole();
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            throw new org.springframework.security.access.AccessDeniedException("Invalid hospital context");
        }

        // Read the hospital's settings, falling back to an unsaved defaults object.
        //
        // This used to INSERT the missing row here, on what is otherwise a read. hospital_settings
        // is unique on hospital_id, so a hospital that had never opened Settings -- a freshly
        // onboarded one -- failed as soon as two billing requests arrived together: the first
        // inserted, the rest hit the unique index and the whole request was rejected with a
        // constraint error. Two people opening the billing screen at once was enough.
        //
        // Nothing is lost by not writing: every field on HospitalSetting carries its default as a
        // field initialiser, so a transient instance answers exactly what the persisted row would
        // have. The row is created for real when someone saves Settings.
        com.hms.entity.HospitalSetting settings = hospitalSettingRepository.findByHospital_Id(hospitalId)
                .orElseGet(com.hms.entity.HospitalSetting::new);

        // Enforce settings
        if ("ROLE_DOCTOR".equalsIgnoreCase(role) || DOCTOR_ROLE.equalsIgnoreCase(role)) {
            if (!DOCTOR_ROLE.equalsIgnoreCase(settings.getBillingHandler()) && 
                !"BOTH".equalsIgnoreCase(settings.getBillingHandler()) &&
                !"SOLO".equalsIgnoreCase(settings.getReceptionMode())) {
                throw new org.springframework.security.access.AccessDeniedException("Billing management is restricted to receptionists.");
            }
        } else if ("ROLE_RECEPTIONIST".equalsIgnoreCase(role) || "RECEPTIONIST".equalsIgnoreCase(role)) {
            if (!"RECEPTIONIST".equalsIgnoreCase(settings.getBillingHandler()) && 
                !"BOTH".equalsIgnoreCase(settings.getBillingHandler())) {
                throw new org.springframework.security.access.AccessDeniedException("Billing management is restricted to doctors.");
            }
            if ("SOLO".equalsIgnoreCase(settings.getReceptionMode())) {
                throw new org.springframework.security.access.AccessDeniedException("Receptionist access is restricted under Solo Doctor mode.");
            }
        }
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'RECEPTIONIST', 'DOCTOR')")
    public ResponseEntity<?> getAllBills(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        validateBillingAccess();
        Pageable pageable = PageRequest.of(page, size, org.springframework.data.domain.Sort.by("createdAt").descending());
        // Fetch page of Billing
        org.springframework.data.domain.Page<com.hms.entity.Billing> billsPage = billingService.getAllBills(search, status, pageable);

        java.util.List<com.hms.entity.Billing> content = billsPage.getContent();
        java.util.List<Long> billIds = content.stream()
                .map(com.hms.entity.Billing::getId)
                .collect(java.util.stream.Collectors.toList());

        java.util.List<Long> patientIds = content.stream()
                .map(com.hms.entity.Billing::getPatientId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(java.util.stream.Collectors.toList());

        // Batch fetch
        java.util.List<com.hms.entity.BillingItem> allItems = billIds.isEmpty() ? 
                java.util.Collections.emptyList() : billingItemRepository.findByBillingIdIn(billIds);
        java.util.List<com.hms.entity.BillingMedicine> allMedicines = billIds.isEmpty() ? 
                java.util.Collections.emptyList() : billingMedicineRepository.findByBillingIdIn(billIds);
        java.util.List<com.hms.entity.BillingPayment> allPayments = billIds.isEmpty() ? 
                java.util.Collections.emptyList() : billingPaymentRepository.findByBillingIdIn(billIds);
        java.util.List<com.hms.entity.Patient> allPatients = patientIds.isEmpty() ? 
                java.util.Collections.emptyList() : patientService.getPatientsByIds(patientIds);

        // Group by billing ID in memory
        java.util.Map<Long, java.util.List<com.hms.entity.BillingItem>> itemsByBillId = allItems.stream()
                .collect(java.util.stream.Collectors.groupingBy(com.hms.entity.BillingItem::getBillingId));
        java.util.Map<Long, java.util.List<com.hms.entity.BillingMedicine>> medicinesByBillId = allMedicines.stream()
                .collect(java.util.stream.Collectors.groupingBy(com.hms.entity.BillingMedicine::getBillingId));
        java.util.Map<Long, java.util.List<com.hms.entity.BillingPayment>> paymentsByBillId = allPayments.stream()
                .collect(java.util.stream.Collectors.groupingBy(com.hms.entity.BillingPayment::getBillingId));
        java.util.Map<Long, com.hms.entity.Patient> patientById = allPatients.stream()
                .collect(java.util.stream.Collectors.toMap(com.hms.entity.Patient::getId, java.util.function.Function.identity(), (a, b) -> a));

        // Group IPD Admissions and Wards if any
        java.util.List<Long> ipdAdmissionIds = content.stream()
                .map(com.hms.entity.Billing::getIpdAdmissionId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(java.util.stream.Collectors.toList());
        java.util.Map<Long, com.hms.entity.IpdAdmission> ipdByAdmissionId = java.util.Collections.emptyMap();
        java.util.Map<Long, com.hms.entity.Ward> wardById = java.util.Collections.emptyMap();
        if (!ipdAdmissionIds.isEmpty()) {
            java.util.List<com.hms.entity.IpdAdmission> admissions = ipdAdmissionRepository.findAllById(ipdAdmissionIds);
            ipdByAdmissionId = admissions.stream()
                    .collect(java.util.stream.Collectors.toMap(com.hms.entity.IpdAdmission::getId, java.util.function.Function.identity(), (a, b) -> a));
            java.util.List<Long> wardIds = admissions.stream()
                    .map(com.hms.entity.IpdAdmission::getWardId)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .collect(java.util.stream.Collectors.toList());
            if (!wardIds.isEmpty()) {
                wardById = wardRepository.findAllById(wardIds).stream()
                        .collect(java.util.stream.Collectors.toMap(com.hms.entity.Ward::getWardId, java.util.function.Function.identity(), (a, b) -> a));
            }
        }

        // Map list
        java.util.List<java.util.Map<String, Object>> mapped = new java.util.ArrayList<>();
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        mapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        for (com.hms.entity.Billing b : content) {
            java.util.List<com.hms.entity.BillingItem> items = itemsByBillId.getOrDefault(b.getId(), java.util.Collections.emptyList());
            java.util.List<com.hms.entity.BillingMedicine> medicines = medicinesByBillId.getOrDefault(b.getId(), java.util.Collections.emptyList());
            
            java.math.BigDecimal totalAmt = java.math.BigDecimal.ZERO;
            if (items != null && !items.isEmpty()) {
                for (com.hms.entity.BillingItem it : items) {
                    if (it.getAmount() != null) {
                        totalAmt = totalAmt.add(it.getAmount());
                    }
                }
            }
            if (medicines != null && !medicines.isEmpty()) {
                for (com.hms.entity.BillingMedicine med : medicines) {
                    if (med.getAmount() != null) {
                        totalAmt = totalAmt.add(med.getAmount());
                    }
                }
            }
            if (totalAmt.compareTo(java.math.BigDecimal.ZERO) == 0 && (items == null || items.isEmpty()) && (medicines == null || medicines.isEmpty())) {
                totalAmt = b.getAmount() != null ? b.getAmount() : java.math.BigDecimal.ZERO;
                if (totalAmt.compareTo(java.math.BigDecimal.ZERO) == 0 && "IPD".equalsIgnoreCase(b.getBillingType())) {
                    if (b.getIpdAdmissionId() != null) {
                        com.hms.entity.IpdAdmission ipd = ipdByAdmissionId.get(b.getIpdAdmissionId());
                        if (ipd != null && ipd.getWardId() != null) {
                            com.hms.entity.Ward ward = wardById.get(ipd.getWardId());
                            if (ward != null && ward.getBedPrice() != null) {
                                totalAmt = totalAmt.add(ward.getBedPrice());
                            }
                        }
                    }
                }
            }

            java.math.BigDecimal paidAmt = java.math.BigDecimal.ZERO;
            java.util.List<com.hms.entity.BillingPayment> payments = paymentsByBillId.getOrDefault(b.getId(), java.util.Collections.emptyList());
            for (com.hms.entity.BillingPayment pay : payments) {
                if (pay.getAmount() != null) {
                    paidAmt = paidAmt.add(pay.getAmount());
                }
            }

            java.util.Map<String, Object> asMap = mapper.convertValue(b, java.util.Map.class);
            asMap.put("items", items);
            asMap.put("medicines", medicines);
            asMap.put(AMOUNT, totalAmt);
            asMap.put("paidAmount", paidAmt);
            asMap.put("balance", totalAmt.subtract(paidAmt));
            
            if (b.getPatientId() != null) {
                com.hms.entity.Patient p = patientById.get(b.getPatientId());
                if (p != null) {
                    asMap.put("patientName", p.getName());
                }
            }
            mapped.add(asMap);
        }

        org.springframework.data.domain.Page<java.util.Map<String, Object>> result = new org.springframework.data.domain.PageImpl<>(mapped, pageable, billsPage.getTotalElements());
        return ResponseEntity.ok(result);
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'RECEPTIONIST', 'DOCTOR')")
    public ResponseEntity<?> updateStatus(
            @PathVariable Long id,
            @RequestParam String status,
            @RequestParam(required = false) String paymentMethod,
            @RequestParam(required = false) String paymentReference) {
        validateBillingAccess();
        Billing updated = billingService.updateStatus(id, status, paymentMethod, paymentReference);
        return ResponseEntity.ok(updated);
    }

    /**
     * Replace a bill's line items.
     *
     * <p>Transactional and lock-taking. This method empties the bill and rebuilds it: without a
     * transaction, a failure part-way through -- a rejected value, a dropped connection -- left
     * the bill stripped of the items it had and never rebuilt, so charges simply vanished. The
     * lock additionally serialises editing against taking payment, so a payment can never be
     * accepted against a total that is mid-rewrite.
     */
    @org.springframework.transaction.annotation.Transactional
    @PutMapping("/{id}/items")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'RECEPTIONIST', 'DOCTOR')")
    public ResponseEntity<?> updateBillItems(@PathVariable Long id, @RequestBody java.util.List<com.hms.dto.HospitalFeeDTO> items) {
        validateBillingAccess();
        Long hospitalId = securityHelper.getCurrentHospitalId();
        Billing billing = billingRepository.findByIdAndHospitalIdForUpdate(id, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Bill not found"));

        if ("PAID".equalsIgnoreCase(billing.getPaymentStatus()) || "CLOSED".equalsIgnoreCase(billing.getPaymentStatus())) {
            throw new IllegalArgumentException("Cannot edit items of a paid or closed bill");
        }

        // Delete existing billing items
        java.util.List<BillingItem> existing = billingItemRepository.findByBillingId(id);
        billingItemRepository.deleteAll(existing);

        // Add new billing items
        if (items != null) {
            for (com.hms.dto.HospitalFeeDTO itemDto : items) {
                if (itemDto.getName() == null || itemDto.getName().trim().isEmpty()) {
                    continue;
                }
                BigDecimal amt = itemDto.getDefaultAmount() != null ? itemDto.getDefaultAmount() : BigDecimal.ZERO;

                BillingItem item = new BillingItem();
                item.setBillingId(id);
                item.setHospitalId(hospitalId);
                item.setDescription(itemDto.getName().trim());
                item.setAmount(amt);
                billingItemRepository.save(item);
            }
        }

        // Recalculate bill total
        billingService.recalculateTotal(id);

        // Audit logging
        try {
            auditLogService.logAction(
                    "BILLING_ITEMS_UPDATED",
                    "Billing items updated for bill " + billing.getCustomId() + ".",
                    securityHelper.getCurrentUserEmail(),
                    hospitalId,
                    "BILLING",
                    billing.getPublicId(),
                    null);
        } catch (Exception e) {
            logger.warn("Failed to create audit log for billing items update", e);
        }

        // Broadcast refresh
        try {
            webSocketHandler.broadcast(hospitalId, "{\"type\":\"REFRESH_DATA\"}");
        } catch (Exception e) {
            logger.warn("Failed to broadcast WebSocket refresh after billing items update", e);
        }

        return ResponseEntity.ok("Bill items updated successfully");
    }

    @Autowired
    private com.hms.security.HospitalWebSocketHandler webSocketHandler;

    @Autowired
    private com.hms.service.AuditLogService auditLogService;

    @Autowired
    private PdfService pdfService;

    @Autowired
    private HospitalRepository hospitalRepository;

    @Autowired
    private PatientService patientService;

    @Autowired
    private com.hms.repository.BillingRepository billingRepository;
    
    @Autowired
    private com.hms.repository.BillingItemRepository billingItemRepository;

    @Autowired
    private com.hms.repository.BillingMedicineRepository billingMedicineRepository;

    @Autowired
    private com.hms.repository.IpdAdmissionRepository ipdAdmissionRepository;

    @Autowired
    private com.hms.repository.WardRepository wardRepository;

    @Autowired
    private com.hms.security.SecurityContextHelper securityHelper;

    @GetMapping("/{id}/pdf")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'RECEPTIONIST', 'DOCTOR')")
    public ResponseEntity<?> downloadReceipt(@PathVariable Long id) {
        Billing billing = billingRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Bill not found"));

        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null || !hospitalId.equals(billing.getHospitalId())) {
            throw new org.springframework.security.access.AccessDeniedException("Access denied");
        }

        Hospital hospital = hospitalRepository.findById(billing.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException("Hospital not found"));

        Patient patient = patientService.getPatientById(billing.getPatientId());

        java.io.ByteArrayInputStream pdf = pdfService.generateBillingReceiptPdf(hospital, patient, billing);

        HttpHeaders headers = new HttpHeaders();
        headers.add("Content-Disposition", "inline; filename=receipt_" + billing.getCustomId() + ".pdf");

        return ResponseEntity
                .ok()
                .headers(headers)
                .contentType(MediaType.APPLICATION_PDF)
                .body(new InputStreamResource(pdf));
    }

    @GetMapping("/ipd/{ipdId}/bill")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'RECEPTIONIST', 'DOCTOR')")
    public ResponseEntity<?> getIpdBill(@PathVariable Long ipdId) {
        validateBillingAccess();
        // findByIpdAdmissionId is not tenant-scoped, so without this check any hospital could
        // read another hospital's IPD bill — items, payments and patient — by admission id.
        Long ipdBillHospitalId = securityHelper.getCurrentHospitalId();
        List<Billing> bills = billingRepository.findByIpdAdmissionId(ipdId);
        if (bills == null || bills.isEmpty()) return ResponseEntity.notFound().build();
        Billing bill = bills.get(0);
        if (bill.getHospitalId() == null || !bill.getHospitalId().equals(ipdBillHospitalId)) {
            return ResponseEntity.notFound().build();
        }

        List<BillingItem> items = billingItemRepository.findByBillingId(bill.getId());
        List<com.hms.entity.BillingMedicine> medicines = billingMedicineRepository.findByBillingId(bill.getId());
        List<BillingPayment> payments = billingPaymentRepository.findByBillingId(bill.getId());

        BigDecimal total = BigDecimal.ZERO;
        if (items != null && !items.isEmpty()) {
            for (BillingItem it : items) {
                if (it.getAmount() != null) total = total.add(it.getAmount());
            }
        }
        if (medicines != null && !medicines.isEmpty()) {
            for (com.hms.entity.BillingMedicine med : medicines) {
                if (med.getAmount() != null) total = total.add(med.getAmount());
            }
        }
        if (total.compareTo(BigDecimal.ZERO) == 0 && (items == null || items.isEmpty()) && (medicines == null || medicines.isEmpty())) {
            total = bill.getAmount() != null ? bill.getAmount() : BigDecimal.ZERO;
            if (total.compareTo(BigDecimal.ZERO) == 0) {
                try {
                    com.hms.entity.IpdAdmission ipd = ipdAdmissionRepository.findById(ipdId).orElse(null);
                    if (ipd != null && ipd.getWardId() != null) {
                        com.hms.entity.Ward ward = wardRepository.findById(ipd.getWardId()).orElse(null);
                        if (ward != null && ward.getBedPrice() != null) {
                            total = total.add(ward.getBedPrice());
                        }
                    }
                    } catch (Exception e) {
                        logger.warn("Failed to retrieve ward bed price for IPD billing calculation", e);
                    }
            }
        }

        BigDecimal paid = BigDecimal.ZERO;
        for (BillingPayment pay : payments) {
            if (pay.getAmount() != null) paid = paid.add(pay.getAmount());
        }

        Map<String,Object> resp = new HashMap<>();
        resp.put("billingId", bill.getId());
        resp.put("totalAmount", total);
        resp.put("paidAmount", paid);
        resp.put("balance", total.subtract(paid));
        java.util.List<Map<String,Object>> list = new java.util.ArrayList<>();
        for (BillingItem it : items) {
            Map<String,Object> m = new HashMap<>();
            m.put("description", it.getDescription());
            m.put(AMOUNT, it.getAmount());
            list.add(m);
        }
        resp.put("items", list);

        java.util.List<Map<String,Object>> medList = new java.util.ArrayList<>();
        for (com.hms.entity.BillingMedicine med : medicines) {
            Map<String,Object> m = new HashMap<>();
            m.put("medicineName", med.getMedicineName());
            m.put("quantity", med.getQuantity());
            m.put("unitPrice", med.getUnitPrice());
            m.put(AMOUNT, med.getAmount());
            medList.add(m);
        }
        resp.put("medicines", medList);

        return ResponseEntity.ok(resp);
    }

    public static class PayRequest {
        @NotNull(message = "Payment amount is required")
        @DecimalMin(value = "0.0", message = "Payment amount cannot be negative")
        public BigDecimal amount;

        @Size(max = 30, message = "Payment mode is too long")
        @NoEmoji
        public String mode;

        @Size(max = 100, message = "Payment reference is too long")
        @NoEmoji
        public String reference;
    }

    /**
     * Record a payment against a bill.
     *
     * <p>Transactional and taken under a row lock on the bill. Reading what has already been
     * collected and inserting the new payment must be one serialised step: previously they were
     * not, so two concurrent calls -- a double-clicked "Paid" button, or a retry after a timeout
     * -- both read the same collected figure, both found the amount fitted inside the outstanding
     * balance, and both inserted. The patient was charged twice with no error shown.
     */
    @org.springframework.transaction.annotation.Transactional
    @PostMapping("/{billingId}/pay")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'RECEPTIONIST', 'DOCTOR')")
    public ResponseEntity<?> payBilling(@PathVariable Long billingId, @Valid @RequestBody PayRequest req) {
        validateBillingAccess();
        String role = securityHelper.getCurrentUserRole();
        if (!"RECEPTIONIST".equalsIgnoreCase(role) && !"HOSPITAL_ADMIN".equalsIgnoreCase(role) && !DOCTOR_ROLE.equalsIgnoreCase(role)) {
            return ResponseEntity.status(403).body("Not allowed");
        }

        // Scope the lookup to the caller's hospital. Without this filter any hospital could
        // settle another hospital's bill by guessing its (sequential) numeric id — a
        // cross-tenant financial write. The sibling endpoints above already filter this way.
        Long payHospitalId = securityHelper.getCurrentHospitalId();
        Billing bill = billingRepository.findByIdAndHospitalIdForUpdate(billingId, payHospitalId)
                .orElse(null);
        if (bill == null) return ResponseEntity.notFound().build();

        if (req.amount == null || req.amount.compareTo(BigDecimal.ZERO) <= 0) {
            return ResponseEntity.badRequest().body("Invalid amount");
        }

        // Calculate remaining balance before saving new payment
        {
            List<BillingItem> itemsVal = billingItemRepository.findByBillingId(billingId);
            List<com.hms.entity.BillingMedicine> medicinesVal = billingMedicineRepository.findByBillingId(billingId);
            BigDecimal totalVal = BigDecimal.ZERO;
            if (itemsVal != null && !itemsVal.isEmpty()) {
                for (BillingItem it : itemsVal) {
                    if (it.getAmount() != null) totalVal = totalVal.add(it.getAmount());
                }
            }
            if (medicinesVal != null && !medicinesVal.isEmpty()) {
                for (com.hms.entity.BillingMedicine med : medicinesVal) {
                    if (med.getAmount() != null) totalVal = totalVal.add(med.getAmount());
                }
            }
            if (totalVal.compareTo(BigDecimal.ZERO) == 0 && (itemsVal == null || itemsVal.isEmpty()) && (medicinesVal == null || medicinesVal.isEmpty())) {
                totalVal = bill.getAmount() != null ? bill.getAmount() : BigDecimal.ZERO;
            }

            List<BillingPayment> paymentsVal = billingPaymentRepository.findByBillingId(billingId);
            BigDecimal paidVal = BigDecimal.ZERO;
            for (BillingPayment p : paymentsVal) if (p.getAmount() != null) paidVal = paidVal.add(p.getAmount());

            BigDecimal balanceVal = totalVal.subtract(paidVal);
            // A state conflict, not a malformed request: the amount was valid, the bill simply
            // does not owe it any more. Raised as ConflictException so it returns the canonical
            // 409 body rather than a bare string, and so a duplicate submission that slips past
            // the lock is reported honestly instead of silently posting.
            if (req.amount.compareTo(balanceVal) > 0) {
                throw new com.hms.exception.ConflictException(
                        "The remaining balance is " + balanceVal + "; cannot accept a payment of " + req.amount + ".");
            }
        }

        BillingPayment payment = new BillingPayment();
        payment.setBillingId(billingId);
        payment.setHospitalId(bill.getHospitalId());
        payment.setAmount(req.amount);
        payment.setMode(req.mode);
        payment.setReference(req.reference);
        billingPaymentRepository.save(payment);

        // Audit logging
        try {
            auditLogService.logAction(
                    "BILLING_PAYMENT_RECORDED",
                    "Recorded payment of " + req.amount + " (" + req.mode + ") for bill " + bill.getCustomId() + ".",
                    securityHelper.getCurrentUserEmail(),
                    bill.getHospitalId(),
                    "BILLING",
                    bill.getPublicId(),
                    null);
        } catch (Exception e) {
            logger.warn("Failed to create audit log for billing payment", e);
        }

        // recalc totals
        List<BillingItem> items = billingItemRepository.findByBillingId(billingId);
        List<com.hms.entity.BillingMedicine> medicines = billingMedicineRepository.findByBillingId(billingId);
        BigDecimal total = BigDecimal.ZERO;
        if (items != null && !items.isEmpty()) {
            for (BillingItem it : items) {
                if (it.getAmount() != null) total = total.add(it.getAmount());
            }
        }
        if (medicines != null && !medicines.isEmpty()) {
            for (com.hms.entity.BillingMedicine med : medicines) {
                if (med.getAmount() != null) total = total.add(med.getAmount());
            }
        }
        if (total.compareTo(BigDecimal.ZERO) == 0 && (items == null || items.isEmpty()) && (medicines == null || medicines.isEmpty())) {
            total = bill.getAmount() != null ? bill.getAmount() : BigDecimal.ZERO;
        }

        List<BillingPayment> payments = billingPaymentRepository.findByBillingId(billingId);
        BigDecimal paid = BigDecimal.ZERO;
        for (BillingPayment p : payments) if (p.getAmount() != null) paid = paid.add(p.getAmount());

        if (paid.compareTo(total) >= 0) {
            bill.setPaymentStatus("PAID");
            bill.setPaymentMethod(req.mode);
            bill.setPaymentReference(req.reference);
            try {
                String userEmail = securityHelper.getCurrentUserEmail();
                String userRole = securityHelper.getCurrentUserRole();
                bill.setMarkedPaidBy(userRole + " (" + userEmail + ")");
            } catch (Exception e) {
                logger.warn("Failed to resolve current user for markedPaidBy field", e);
            }
        } else {
            bill.setPaymentStatus("PARTIAL");
            bill.setPaymentMethod(req.mode);
        }
        billingRepository.save(bill);

        Map<String,Object> out = new HashMap<>();
        out.put("billingId", bill.getId());
        out.put("totalAmount", total);
        out.put("paidAmount", paid);
        out.put("balance", total.subtract(paid));
        return ResponseEntity.ok(out);
    }

    @GetMapping("/patient/{patientPublicId}")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'RECEPTIONIST', 'DOCTOR')")
    public ResponseEntity<?> getPatientBills(@PathVariable String patientPublicId) {
        validateBillingAccess();
        Long hospitalId = securityHelper.getCurrentHospitalId();
        com.hms.entity.Patient patient = patientService.getPatientByPublicId(patientPublicId);
        if (patient == null || !patient.getHospitalId().equals(hospitalId)) {
            throw new com.hms.exception.ResourceNotFoundException("Patient not found or unauthorized");
        }

        java.util.List<Billing> patientBills = billingRepository.findByPatientIdOrderByCreatedAtDesc(patient.getId());
        java.util.List<Long> billIds = patientBills.stream().map(Billing::getId).collect(java.util.stream.Collectors.toList());

        java.util.List<com.hms.entity.BillingItem> allItems = billIds.isEmpty() ? java.util.Collections.emptyList() : billingItemRepository.findByBillingIdIn(billIds);
        java.util.List<com.hms.entity.BillingMedicine> allMedicines = billIds.isEmpty() ? java.util.Collections.emptyList() : billingMedicineRepository.findByBillingIdIn(billIds);
        java.util.List<BillingPayment> allPayments = billIds.isEmpty() ? java.util.Collections.emptyList() : billingPaymentRepository.findByBillingIdIn(billIds);

        java.util.Map<Long, java.util.List<com.hms.entity.BillingItem>> itemsByBillId = allItems.stream().collect(java.util.stream.Collectors.groupingBy(com.hms.entity.BillingItem::getBillingId));
        java.util.Map<Long, java.util.List<com.hms.entity.BillingMedicine>> medicinesByBillId = allMedicines.stream().collect(java.util.stream.Collectors.groupingBy(com.hms.entity.BillingMedicine::getBillingId));
        java.util.Map<Long, java.util.List<com.hms.entity.BillingPayment>> paymentsByBillId = allPayments.stream().collect(java.util.stream.Collectors.groupingBy(com.hms.entity.BillingPayment::getBillingId));

        java.util.List<java.util.Map<String, Object>> mapped = new java.util.ArrayList<>();
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        mapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        for (Billing b : patientBills) {
            java.util.Map<String, Object> asMap = mapper.convertValue(b, java.util.Map.class);

            java.util.List<com.hms.entity.BillingItem> items = itemsByBillId.getOrDefault(b.getId(), java.util.Collections.emptyList());
            java.util.List<com.hms.entity.BillingMedicine> medicines = medicinesByBillId.getOrDefault(b.getId(), java.util.Collections.emptyList());
            java.util.List<com.hms.entity.BillingPayment> payments = paymentsByBillId.getOrDefault(b.getId(), java.util.Collections.emptyList());

            BigDecimal totalAmt = BigDecimal.ZERO;
            for (com.hms.entity.BillingItem it : items) if (it.getAmount() != null) totalAmt = totalAmt.add(it.getAmount());
            for (com.hms.entity.BillingMedicine med : medicines) if (med.getAmount() != null) totalAmt = totalAmt.add(med.getAmount());

            if (totalAmt.compareTo(BigDecimal.ZERO) == 0) {
                totalAmt = b.getAmount() != null ? b.getAmount() : BigDecimal.ZERO;
            }

            BigDecimal paidAmt = BigDecimal.ZERO;
            for (BillingPayment pay : payments) if (pay.getAmount() != null) paidAmt = paidAmt.add(pay.getAmount());

            asMap.put("items", items);
            asMap.put("medicines", medicines);
            asMap.put("payments", payments);
            asMap.put(AMOUNT, totalAmt);
            asMap.put("paidAmount", paidAmt);
            asMap.put("balance", totalAmt.subtract(paidAmt));
            asMap.put("patientName", patient.getName());
            mapped.add(asMap);
        }
        return ResponseEntity.ok(mapped);
    }
}

