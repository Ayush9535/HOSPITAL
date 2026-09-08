package com.hms.service.hospital;

import com.hms.dto.MedicationAdminRequest;
import com.hms.entity.IpdAdmission;
import com.hms.entity.MedicationAdministration;
import com.hms.entity.Prescription;
import com.hms.exception.UnauthorizedException;
import com.hms.repository.IpdAdmissionRepository;
import com.hms.repository.MedicationAdministrationRepository;
import com.hms.repository.PrescriptionRepository;
import com.hms.security.NurseAccessGuard;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * MedicationAdministrationService - records nurse administration against the
 * doctor's ACTIVE prescriptions (Phase 1 Nurse module). The prescription is the
 * single source of truth for what to give; this only records what happened.
 * Corrections are appended as new records (no destructive edit).
 */
@Service
public class MedicationAdministrationService {

    private static final Logger logger = LoggerFactory.getLogger(MedicationAdministrationService.class);
    private static final Set<String> STATUSES = Set.of("GIVEN", "SKIPPED", "DELAYED", "REFUSED", "NOT_AVAILABLE");
    private static final Set<String> TIME_REQUIRED = Set.of("GIVEN", "DELAYED");

    /**
     * How close together two identical entries have to be to count as one double-submitted act
     * rather than two real ones. Wide enough to absorb a double click and a client retry, far
     * short of any real dosing interval.
     */
    private static final int DUPLICATE_WINDOW_SECONDS = 60;

    @Autowired private MedicationAdministrationRepository marRepository;
    @Autowired private PrescriptionRepository prescriptionRepository;
    @Autowired private IpdAdmissionRepository ipdAdmissionRepository;
    @Autowired private SecurityContextHelper securityHelper;
    @Autowired private NurseAccessGuard nurseAccessGuard;
    @Autowired private com.hms.security.NurseWriteAccess nurseWriteAccess;
    @Autowired private com.hms.security.PerformingNurseResolver performingNurseResolver;
    @Autowired private AuditLogService auditLogService;
    @Autowired private com.hms.service.RealtimeNotifier notifier;
    @Autowired private MedicineStockService medicineStockService;
    @Autowired private com.hms.repository.StockMovementRepository stockMovementRepository;

    /**
     * ACTIVE prescriptions for an admission — the medicine list the nurse
     * records against. Nurses must be assigned; doctors/admins may read.
     */
    public List<Prescription> getActivePrescriptions(Long ipdAdmissionId) {
        Long hospitalId = requireHospitalId();
        IpdAdmission admission = requireAdmission(ipdAdmissionId, hospitalId);
        if ("NURSE".equals(securityHelper.getCurrentUserRole())) {
            nurseAccessGuard.assertAssigned(admission.getId());
        }
        return prescriptionRepository.findByIpdAdmissionIdAndStatus(ipdAdmissionId, "ACTIVE");
    }

    /**
     * The full medication chart for an admission: every prescription (ACTIVE,
     * STOPPED, COMPLETED — never deleted) enriched with course progress and
     * today's administration state, so the nurse sees history and pending doses.
     */
    public java.util.List<com.hms.dto.MedicationChartItemDTO> getMedicationChart(Long ipdAdmissionId) {
        Long hospitalId = requireHospitalId();
        IpdAdmission admission = requireAdmission(ipdAdmissionId, hospitalId);
        if ("NURSE".equals(securityHelper.getCurrentUserRole())) {
            nurseAccessGuard.assertAssigned(admission.getId());
        }

        java.time.LocalDate today = java.time.LocalDate.now();
        java.util.List<Prescription> all = prescriptionRepository.findByIpdAdmissionIdOrderByStartDate(ipdAdmissionId);
        java.util.List<com.hms.dto.MedicationChartItemDTO> result = new java.util.ArrayList<>();

        for (Prescription p : all) {
            com.hms.dto.MedicationChartItemDTO d = new com.hms.dto.MedicationChartItemDTO();
            d.setPrescriptionId(p.getId());
            d.setMedicineName(p.getMedicineName());
            d.setDosage(p.getDosage());
            d.setFrequency(p.getFrequency());
            d.setDuration(p.getDuration());
            d.setRoute(p.getRoute());
            d.setType(p.getType());
            d.setInstructions(p.getInstructions());
            d.setFoodTiming(p.getFoodTiming());
            d.setStatus(p.getStatus());
            d.setStartDate(p.getStartDate());
            d.setDurationDays(p.getDurationDays());

            boolean active = "ACTIVE".equalsIgnoreCase(p.getStatus());
            boolean withinWindow = true;
            if (p.getStartDate() != null) {
                long elapsed = java.time.temporal.ChronoUnit.DAYS.between(p.getStartDate(), today); // 0 on start day
                d.setDayOfCourse((int) elapsed + 1);
                if (p.getDurationDays() != null && p.getDurationDays() > 0) {
                    withinWindow = (elapsed + 1) <= p.getDurationDays();
                }
            }
            boolean courseActive = active && withinWindow;
            d.setCourseActive(courseActive);

            // Today's administration state (any GIVEN record dated today).
            java.util.List<MedicationAdministration> mars =
                    marRepository.findByPrescriptionIdAndIsActiveTrueOrderByCreatedAtDesc(p.getId());
            boolean administeredToday = mars.stream().anyMatch(m ->
                    "GIVEN".equalsIgnoreCase(m.getStatus())
                    && m.getAdministeredTime() != null
                    && m.getAdministeredTime().toLocalDate().equals(today));
            d.setAdministeredToday(administeredToday);
            d.setPending(courseActive && !administeredToday);
            if (!mars.isEmpty()) {
                MedicationAdministration last = mars.get(0);
                d.setLastAdministeredAt(last.getAdministeredTime());
                d.setLastAdministeredStatus(last.getStatus());
            }
            annotateInventory(d, p, hospitalId);
            result.add(d);
        }
        return result;
    }

    /**
     * Say what is known about this order's stock, without ever hiding the order.
     *
     * <p>The bug this replaces: an order the inventory could not account for was shown as though
     * the facility held none of it. A nurse reading "0 in stock" against a live prescription
     * concludes the drug is unavailable, when in truth nobody had linked the order to a stock row
     * -- a data-entry gap reported as a clinical one. Absent and empty are different answers and
     * are now given different names.
     *
     * <p>Failures here are swallowed on purpose. This is advisory decoration on a clinical
     * record; an inventory problem must not be able to blank the medication chart.
     */
    private void annotateInventory(com.hms.dto.MedicationChartItemDTO d, Prescription p, Long hospitalId) {
        d.setMedicineId(p.getMedicineId());
        try {
            d.setQuantityDispensed(stockMovementRepository.dispensedForPrescription(hospitalId, p.getId()));
        } catch (Exception e) {
            logger.warn("Could not read dispensing history for prescription {}: {}", p.getId(), e.getMessage());
        }
        if (p.getMedicineId() == null) {
            d.setInventoryStatus("UNLINKED");
            return;
        }
        try {
            int available = medicineStockService.availableQuantityFor(p.getMedicineId(), hospitalId);
            d.setAvailableQuantity(available);
            d.setEarliestExpiry(medicineStockService.earliestUsableExpiry(p.getMedicineId(), hospitalId));
            d.setInventoryStatus(available <= 0 ? "LINKED_NO_STOCK" : "LINKED_AVAILABLE");
        } catch (Exception e) {
            // Including the case where the linked medicine has since been removed from inventory.
            logger.warn("Could not read stock for prescription {}: {}", p.getId(), e.getMessage());
            d.setInventoryStatus("UNLINKED");
        }
    }

    @Transactional
    public MedicationAdministration record(MedicationAdminRequest req) {
        Long hospitalId = requireHospitalId();
        if (req.getIpdAdmissionId() == null || req.getPrescriptionId() == null) {
            throw new IllegalArgumentException("ipdAdmissionId and prescriptionId are required");
        }
        IpdAdmission admission = requireAdmission(req.getIpdAdmissionId(), hospitalId);
        nurseWriteAccess.assertCanWriteFor(admission.getId());

        String status = req.getStatus() == null ? null : req.getStatus().toUpperCase();
        if (!STATUSES.contains(status)) {
            throw new IllegalArgumentException("Invalid administration status");
        }

        // The prescription must be one of the admission's current ACTIVE orders.
        List<Prescription> active = prescriptionRepository.findByIpdAdmissionIdAndStatus(req.getIpdAdmissionId(), "ACTIVE");
        Prescription prescription = active.stream()
                .filter(p -> p.getId().equals(req.getPrescriptionId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Prescription is not an active order for this admission"));
        if (!hospitalId.equals(prescription.getHospitalId())) {
            throw new UnauthorizedException("Access denied: prescription belongs to another hospital");
        }

        LocalDateTime administeredTime = req.getAdministeredTime();
        if (TIME_REQUIRED.contains(status)) {
            if (administeredTime == null) administeredTime = LocalDateTime.now();
        }
        if (administeredTime != null && administeredTime.isAfter(LocalDateTime.now().plusMinutes(1))) {
            throw new IllegalArgumentException("Administered time cannot be in the future");
        }

        // A double-clicked "Given" is not two doses.
        //
        // Nothing stopped the same administration being written twice: the second click created a
        // second row, and the chart then showed a patient having received two doses when they had
        // received one. It is not fixed with a unique key -- giving the same drug again later in
        // the day is normal and must stay recordable -- so what is refused is specifically a
        // repeat of the same order, with the same outcome, moments after the first.
        LocalDateTime repeatCutoff = LocalDateTime.now().minusSeconds(DUPLICATE_WINDOW_SECONDS);
        List<MedicationAdministration> recent = marRepository
                .findByPrescriptionIdAndStatusAndIsActiveTrueAndCreatedAtAfter(
                        prescription.getId(), status, repeatCutoff);
        if (!recent.isEmpty()) {
            throw new com.hms.exception.ConflictException(
                    prescription.getMedicineName() + " was already recorded as " + status
                    + " a moment ago. Refresh the chart before recording it again.");
        }

        MedicationAdministration mar = new MedicationAdministration();
        mar.setHospitalId(hospitalId);
        mar.setIpdAdmissionId(admission.getId());
        mar.setPrescriptionId(prescription.getId());
        mar.setPatientId(admission.getPatientId());
        mar.setNurseUserId(securityHelper.getCurrentUserId());
        mar.setScheduledTime(req.getScheduledTime());
        mar.setAdministeredTime(administeredTime);
        mar.setStatus(status);
        mar.setRemarks(req.getRemarks());
        mar.setPerformedByNurseId(performingNurseResolver.resolve(req.getPerformedByNurseId()));
        mar.setIsActive(true);
        MedicationAdministration saved = marRepository.save(mar);

        auditAndNotify("MEDICATION_ADMINISTERED",
                status + " - " + prescription.getMedicineName() + " (IPD " + admission.getIpdNumber() + ")",
                hospitalId, admission.getId());
        return saved;
    }

    public List<MedicationAdministration> getByAdmission(Long ipdAdmissionId) {
        Long hospitalId = requireHospitalId();
        IpdAdmission admission = requireAdmission(ipdAdmissionId, hospitalId);
        if ("NURSE".equals(securityHelper.getCurrentUserRole())) {
            nurseAccessGuard.assertAssigned(admission.getId());
        }
        return marRepository.findByIpdAdmissionIdAndIsActiveTrueOrderByCreatedAtDesc(ipdAdmissionId);
    }

    // --- helpers ---

    private IpdAdmission requireAdmission(Long ipdAdmissionId, Long hospitalId) {
        IpdAdmission admission = ipdAdmissionRepository.findById(ipdAdmissionId)
                .orElseThrow(() -> new IllegalArgumentException("IPD admission not found"));
        if (!hospitalId.equals(admission.getHospitalId())) {
            throw new UnauthorizedException("Access denied: admission belongs to another hospital");
        }
        return admission;
    }

    private Long requireHospitalId() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            throw new UnauthorizedException("Hospital ID not found in context");
        }
        return hospitalId;
    }

    /**
     * Records the write in the audit trail AND pushes it to the tenant's open tabs.
     *
     * These two always go together: every write path here ends by calling this, and each one is a
     * change a doctor may be staring at right now on the same IPD case. Keeping the broadcast here
     * rather than at each call site means a future write path cannot silently ship without one --
     * which is exactly how these records ended up non-realtime in the first place.
     */
    private void auditAndNotify(String action, String details, Long hospitalId, Long admissionId) {
        try {
            auditLogService.logAction(action, details, securityHelper.getCurrentUserEmail(), hospitalId,
                    "IPD", admissionId != null ? admissionId.toString() : null, null);
        } catch (Exception e) {
            logger.warn("Failed to write audit log for {}", action, e);
        }
        notifier.refresh(hospitalId);
    }
}
