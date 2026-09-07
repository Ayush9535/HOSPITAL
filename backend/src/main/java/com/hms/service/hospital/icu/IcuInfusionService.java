package com.hms.service.hospital.icu;

import com.hms.dto.icu.IcuInfusionRequest;
import com.hms.entity.IcuInfusion;
import com.hms.entity.IcuInfusionRate;
import com.hms.entity.IpdAdmission;
import com.hms.entity.Prescription;
import com.hms.exception.ResourceNotFoundException;
import com.hms.exception.UnauthorizedException;
import com.hms.repository.IcuInfusionRateRepository;
import com.hms.repository.IcuInfusionRepository;
import com.hms.repository.IpdAdmissionRepository;
import com.hms.repository.MedicalRecordRepository;
import com.hms.repository.PrescriptionRepository;
import com.hms.security.NurseAccessGuard;
import com.hms.security.NurseWriteAccess;
import com.hms.security.PerformingNurseResolver;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import com.hms.service.hospital.FormAccessService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * IcuInfusionService - continuous infusions and their rate history (ICU Phase 6).
 *
 * <p><b>A titration appends, never edits.</b> The rate in force at any instant is the latest
 * non-superseded rate row at or before it, so "what was it running at when the BP dropped?"
 * stays answerable after the infusion has stopped.
 *
 * <p><b>Separate from fluid balance (D-1).</b> This service never reads, writes or derives an
 * {@code icu_io_entry}. An infusion is a drug-delivery record; the fluid balance is its own event
 * stream, and nothing here contributes to it. Same separation-of-meaning rule ICU-5 D-2 set for
 * urine output.
 *
 * <p><b>No clinical calculation.</b> Rates are stored in the unit entered and never converted:
 * MCG_KG_MIN to ML_HR needs a concentration and a body weight the system does not hold. No
 * maximum, no appropriateness check, no alert.
 *
 * <p><b>Transactions.</b> Recording an infusion is not a movement. This service never joins the
 * IPD movement transaction and never calls {@code IcuStayService}'s MANDATORY methods, so a failed
 * infusion write cannot roll back an admission, a bed move or an ICU stay.
 *
 * <p><b>Authorisation.</b> The existing {@code MEDICATION} Files &amp; Access gate (D-3) — an
 * infusion is medication administration — plus the ICU-4/5 amendment rules on correction.
 * No new role, no new permission.
 */
@Service
public class IcuInfusionService {

    private static final Logger logger = LoggerFactory.getLogger(IcuInfusionService.class);

    /** Same window vitals and I/O use, so amendment rules stay consistent across the chart. */
    private static final Duration EDIT_WINDOW = Duration.ofHours(12);

    private static final String FORM_KEY = "MEDICATION";

    @Autowired private IcuInfusionRepository infusionRepository;
    @Autowired private IcuInfusionRateRepository rateRepository;
    @Autowired private IpdAdmissionRepository ipdAdmissionRepository;
    @Autowired private PrescriptionRepository prescriptionRepository;
    @Autowired private MedicalRecordRepository medicalRecordRepository;
    @Autowired private SecurityContextHelper securityHelper;
    @Autowired private NurseAccessGuard nurseAccessGuard;
    @Autowired private NurseWriteAccess nurseWriteAccess;
    @Autowired private PerformingNurseResolver performingNurseResolver;
    @Autowired private FormAccessService formAccessService;
    @Autowired private AuditLogService auditLogService;
    @Autowired private com.hms.service.RealtimeNotifier notifier;

    // ── lifecycle ────────────────────────────────────────────────────────────

    /** Starts an infusion and records its first rate. */
    @Transactional
    public IcuInfusion start(IcuInfusionRequest req) {
        Long hospitalId = requireHospitalId();
        formAccessService.assertCanEdit(FORM_KEY);
        if (req.getIpdAdmissionId() == null) {
            throw new IllegalArgumentException("ipdAdmissionId is required");
        }
        IpdAdmission admission = requireAdmission(req.getIpdAdmissionId(), hospitalId);
        nurseWriteAccess.assertCanWriteFor(admission.getId());

        if (req.getMedicineName() == null || req.getMedicineName().isBlank()) {
            throw new IllegalArgumentException("Medicine name is required");
        }
        validateRate(req);
        Long prescriptionId = resolvePrescription(req.getPrescriptionId(), admission, hospitalId);

        IcuInfusion inf = new IcuInfusion();
        inf.setHospitalId(hospitalId);
        inf.setIpdAdmissionId(admission.getId());
        inf.setPatientId(admission.getPatientId());
        inf.setPrescriptionId(prescriptionId);
        inf.setMedicineName(req.getMedicineName().trim());
        inf.setStartedAt(effectiveOrNow(req));
        inf.setStartedByUserId(securityHelper.getCurrentUserId());
        inf.setPerformedByNurseId(performingNurseResolver.resolve(req.getPerformedByNurseId()));
        inf.setIsActive(true);
        IcuInfusion saved = infusionRepository.save(inf);

        appendRate(saved, req, null);

        audit("ICU_INFUSION_STARTED",
                saved.getMedicineName() + " at " + req.getRateValue() + " "
                        + InfusionRateUnitRegistry.labelOf(req.getRateUnit()),
                hospitalId, admission.getId());
        notifier.refresh(hospitalId);
        return saved;
    }

    /**
     * Titrates a running infusion by APPENDING a new rate. The previous rate row is untouched and
     * stays in the history — that history is the point of the phase.
     */
    @Transactional
    public IcuInfusionRate titrate(String publicId, IcuInfusionRequest req) {
        Long hospitalId = requireHospitalId();
        formAccessService.assertCanEdit(FORM_KEY);
        IcuInfusion inf = requireInfusion(publicId, hospitalId);
        if (!inf.isRunning()) {
            throw new IllegalArgumentException("This infusion has been stopped and cannot be titrated");
        }
        validateRate(req);

        IcuInfusionRate rate = appendRate(inf, req, null);
        audit("ICU_INFUSION_TITRATED",
                inf.getMedicineName() + " to " + req.getRateValue() + " "
                        + InfusionRateUnitRegistry.labelOf(req.getRateUnit()),
                hospitalId, inf.getIpdAdmissionId());
        notifier.refresh(hospitalId);
        return rate;
    }

    /** Closes the span. The infusion and its whole rate history remain readable. */
    @Transactional
    public IcuInfusion stop(String publicId, IcuInfusionRequest req) {
        Long hospitalId = requireHospitalId();
        formAccessService.assertCanEdit(FORM_KEY);
        IcuInfusion inf = requireInfusion(publicId, hospitalId);
        if (!inf.isRunning()) {
            throw new IllegalArgumentException("This infusion has already been stopped");
        }
        LocalDateTime at = effectiveOrNow(req);
        if (at.isBefore(inf.getStartedAt())) {
            throw new IllegalArgumentException("Stop time cannot be before the infusion started");
        }
        inf.setStoppedAt(at);
        inf.setStopReason(req.getStopReason());
        IcuInfusion saved = infusionRepository.save(inf);

        audit("ICU_INFUSION_STOPPED", inf.getMedicineName(), hospitalId, inf.getIpdAdmissionId());
        notifier.refresh(hospitalId);
        return saved;
    }

    /**
     * Corrects a recorded rate by appending a row that supersedes it. The original stays readable,
     * exactly as ICU-4 vitals and ICU-5 I/O do.
     *
     * <p>A correction is not a titration: a titration says the rate changed, a correction says the
     * recorded rate was wrong. Authorisation is the ICU-4 rule — same recorder, same window.
     */
    @Transactional
    public IcuInfusionRate correctRate(String ratePublicId, IcuInfusionRequest req) {
        Long hospitalId = requireHospitalId();
        formAccessService.assertCanEdit(FORM_KEY);

        IcuInfusionRate original = rateRepository.findByPublicIdAndHospitalId(ratePublicId, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Infusion rate not found"));
        assertMayAmend(original);
        validateRate(req);

        IcuInfusion inf = infusionRepository.findById(original.getIcuInfusionId())
                .filter(i -> hospitalId.equals(i.getHospitalId()))
                .orElseThrow(() -> new ResourceNotFoundException("Infusion not found"));

        IcuInfusionRate correction = new IcuInfusionRate();
        correction.setHospitalId(hospitalId);
        correction.setIcuInfusionId(inf.getId());
        correction.setRateValue(req.getRateValue());
        correction.setRateUnit(req.getRateUnit());
        // A correction restates the SAME moment unless the time itself is being corrected.
        correction.setEffectiveFrom(
                req.getEffectiveFrom() != null ? req.getEffectiveFrom() : original.getEffectiveFrom());
        correction.setRecordedByUserId(securityHelper.getCurrentUserId());
        correction.setPerformedByNurseId(performingNurseResolver.resolve(req.getPerformedByNurseId()));
        correction.setSupersedesRateId(original.getId());
        correction.setIsActive(true);
        IcuInfusionRate saved = rateRepository.save(correction);

        audit("ICU_INFUSION_RATE_CORRECTED",
                "Corrected rate " + original.getPublicId() + " with " + saved.getPublicId(),
                hospitalId, inf.getIpdAdmissionId());
        notifier.refresh(hospitalId);
        return saved;
    }

    // ── reads ────────────────────────────────────────────────────────────────

    public List<IcuInfusion> getByAdmission(Long ipdAdmissionId) {
        Long hospitalId = requireHospitalId();
        IpdAdmission admission = requireAdmission(ipdAdmissionId, hospitalId);
        if ("NURSE".equals(securityHelper.getCurrentUserRole())) {
            nurseAccessGuard.assertAssigned(admission.getId());
        }
        return infusionRepository
                .findByIpdAdmissionIdAndHospitalIdAndIsActiveTrueOrderByStartedAtDesc(
                        ipdAdmissionId, hospitalId);
    }

    public List<IcuInfusion> getRunning(Long ipdAdmissionId) {
        Long hospitalId = requireHospitalId();
        IpdAdmission admission = requireAdmission(ipdAdmissionId, hospitalId);
        if ("NURSE".equals(securityHelper.getCurrentUserRole())) {
            nurseAccessGuard.assertAssigned(admission.getId());
        }
        return infusionRepository
                .findByIpdAdmissionIdAndHospitalIdAndIsActiveTrueAndStoppedAtIsNullOrderByStartedAtDesc(
                        ipdAdmissionId, hospitalId);
    }

    /** Full rate history, newest first, INCLUDING rows a correction superseded. */
    public List<IcuInfusionRate> rateHistory(String publicId) {
        Long hospitalId = requireHospitalId();
        IcuInfusion inf = requireInfusion(publicId, hospitalId);
        return rateRepository
                .findByIcuInfusionIdAndHospitalIdAndIsActiveTrueOrderByEffectiveFromDescIdDesc(
                        inf.getId(), hospitalId);
    }

    /** The rate running now, or null when none was ever recorded. */
    public IcuInfusionRate currentRate(String publicId) {
        Long hospitalId = requireHospitalId();
        IcuInfusion inf = requireInfusion(publicId, hospitalId);
        return rateAt(inf, hospitalId, LocalDateTime.now());
    }

    /**
     * The rate in force at {@code at}: the latest non-superseded row effective at or before it.
     * Null when the infusion had not started, or nothing was recorded yet.
     */
    public IcuInfusionRate rateAt(String publicId, LocalDateTime at) {
        Long hospitalId = requireHospitalId();
        IcuInfusion inf = requireInfusion(publicId, hospitalId);
        return rateAt(inf, hospitalId, at);
    }

    private IcuInfusionRate rateAt(IcuInfusion inf, Long hospitalId, LocalDateTime at) {
        Set<Long> superseded = new HashSet<>(
                rateRepository.findSupersededRateIds(inf.getId(), hospitalId));
        // Newest first, so the first row at or before `at` is the one in force.
        for (IcuInfusionRate r : rateRepository
                .findByIcuInfusionIdAndHospitalIdAndIsActiveTrueOrderByEffectiveFromDescIdDesc(
                        inf.getId(), hospitalId)) {
            if (superseded.contains(r.getId())) continue;
            if (!r.getEffectiveFrom().isAfter(at)) return r;
        }
        return null;
    }

    /** Rate ids replaced by a correction, so the UI can strike them through. */
    public List<Long> supersededRateIds(String publicId) {
        Long hospitalId = requireHospitalId();
        IcuInfusion inf = requireInfusion(publicId, hospitalId);
        return rateRepository.findSupersededRateIds(inf.getId(), hospitalId);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private IcuInfusionRate appendRate(IcuInfusion inf, IcuInfusionRequest req, Long supersedesId) {
        IcuInfusionRate rate = new IcuInfusionRate();
        rate.setHospitalId(inf.getHospitalId());
        rate.setIcuInfusionId(inf.getId());
        rate.setRateValue(req.getRateValue());
        rate.setRateUnit(req.getRateUnit());
        rate.setEffectiveFrom(effectiveOrNow(req));
        rate.setRecordedByUserId(securityHelper.getCurrentUserId());
        rate.setPerformedByNurseId(performingNurseResolver.resolve(req.getPerformedByNurseId()));
        rate.setSupersedesRateId(supersedesId);
        rate.setIsActive(true);
        return rateRepository.save(rate);
    }

    private void validateRate(IcuInfusionRequest req) {
        if (req.getRateValue() == null || req.getRateValue().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Rate must be greater than zero");
        }
        if (!InfusionRateUnitRegistry.isValid(req.getRateUnit())) {
            throw new IllegalArgumentException("Unknown rate unit: " + req.getRateUnit());
        }
        if (req.getEffectiveFrom() != null
                && req.getEffectiveFrom().isAfter(LocalDateTime.now().plusMinutes(1))) {
            throw new IllegalArgumentException("Recorded time cannot be in the future");
        }
    }

    /**
     * A supplied prescription must belong to the SAME admission and tenant, or it could attach one
     * patient's infusion to another patient's order.
     */
    private Long resolvePrescription(Long prescriptionId, IpdAdmission admission, Long hospitalId) {
        if (prescriptionId == null) return null; // D-2: standing alone is legitimate
        Prescription p = prescriptionRepository.findById(prescriptionId)
                .filter(x -> hospitalId.equals(x.getHospitalId()))
                .orElseThrow(() -> new ResourceNotFoundException("Prescription not found"));
        boolean sameAdmission = medicalRecordRepository.findById(p.getMedicalRecordId())
                .map(mr -> admission.getId().equals(mr.getIpdAdmissionId()))
                .orElse(false);
        if (!sameAdmission) {
            throw new IllegalArgumentException("Prescription does not belong to this admission");
        }
        return p.getId();
    }

    /** The ICU-4 amendment rules, unchanged: only the recorder, only inside the window. */
    private void assertMayAmend(IcuInfusionRate r) {
        if (r.getRecordedByUserId() == null
                || !r.getRecordedByUserId().equals(securityHelper.getCurrentUserId())) {
            throw new AccessDeniedException("Only the person who recorded this rate can correct it");
        }
        if (Duration.between(r.getCreatedAt().atZone(java.time.ZoneId.systemDefault()),
                java.time.ZonedDateTime.now()).compareTo(EDIT_WINDOW) > 0) {
            throw new IllegalArgumentException("Edit window has passed for this rate");
        }
    }

    private LocalDateTime effectiveOrNow(IcuInfusionRequest req) {
        return req.getEffectiveFrom() != null ? req.getEffectiveFrom() : LocalDateTime.now();
    }

    private IcuInfusion requireInfusion(String publicId, Long hospitalId) {
        return infusionRepository.findByPublicIdAndHospitalId(publicId, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Infusion not found"));
    }

    private IpdAdmission requireAdmission(Long ipdAdmissionId, Long hospitalId) {
        IpdAdmission admission = ipdAdmissionRepository.findById(ipdAdmissionId)
                .orElseThrow(() -> new ResourceNotFoundException("IPD admission not found"));
        if (!hospitalId.equals(admission.getHospitalId())) {
            // Tenant check, not a permission check: another hospital's admission must be
            // indistinguishable from a missing one.
            throw new ResourceNotFoundException("IPD admission not found");
        }
        return admission;
    }

    private Long requireHospitalId() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) throw new UnauthorizedException("Hospital ID not found in context");
        return hospitalId;
    }

    private void audit(String action, String details, Long hospitalId, Long admissionId) {
        try {
            auditLogService.logAction(action, details, securityHelper.getCurrentUserEmail(),
                    hospitalId, "IPD", String.valueOf(admissionId), null);
        } catch (Exception e) {
            logger.warn("ICU infusion audit failed: {}", e.getMessage());
        }
    }

    /** The unit catalogue, for the infusion form. */
    public List<InfusionRateUnitRegistry.RateUnit> rateUnits() {
        return new ArrayList<>(InfusionRateUnitRegistry.UNITS);
    }
}
