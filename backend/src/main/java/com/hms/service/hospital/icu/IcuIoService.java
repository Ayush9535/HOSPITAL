package com.hms.service.hospital.icu;

import com.hms.dto.icu.IcuIoBalanceDTO;
import com.hms.dto.icu.IcuIoRequest;
import com.hms.entity.IcuIoEntry;
import com.hms.entity.IpdAdmission;
import com.hms.exception.ResourceNotFoundException;
import com.hms.exception.UnauthorizedException;
import com.hms.repository.IcuIoEntryRepository;
import com.hms.repository.IcuStayRepository;
import com.hms.repository.IpdAdmissionRepository;
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

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * IcuIoService - the fluid intake/output event stream and its balance (ICU Phase 5).
 *
 * <p><b>Source of truth (D-2).</b> {@code icu_io_entry} is authoritative for ICU fluid balance and
 * for the NABH I/O chart. {@code VitalsRecord.urine_output_ml} is an independent point-in-time
 * observation: this service never reads it, never writes it, and never turns one into an entry.
 * They are different clinical statements and are kept apart on purpose.
 *
 * <p><b>Transactions.</b> Recording fluid is not a movement. This service never joins the IPD
 * movement transaction and never calls {@code IcuStayService}'s MANDATORY lifecycle methods, so a
 * failed I/O write can never roll back an admission, a bed move or an ICU stay. It only READS the
 * stay window, exactly as ICU-4 does.
 *
 * <p><b>Authorisation.</b> The existing {@code IO_CHART} Files &amp; Access gate, plus the same
 * recording-nurse and edit-window amendment rules ICU-4 settled on. Nothing widens.
 */
@Service
public class IcuIoService {

    private static final Logger logger = LoggerFactory.getLogger(IcuIoService.class);

    /** Same window vitals uses, so amendment rules stay consistent across the chart. */
    private static final Duration EDIT_WINDOW = Duration.ofHours(12);

    private static final String FORM_KEY = "IO_CHART";

    @Autowired private IcuIoEntryRepository ioRepository;
    @Autowired private IcuStayRepository icuStayRepository;
    @Autowired private IpdAdmissionRepository ipdAdmissionRepository;
    @Autowired private SecurityContextHelper securityHelper;
    @Autowired private NurseAccessGuard nurseAccessGuard;
    @Autowired private NurseWriteAccess nurseWriteAccess;
    @Autowired private PerformingNurseResolver performingNurseResolver;
    @Autowired private FormAccessService formAccessService;
    @Autowired private AuditLogService auditLogService;
    @Autowired private com.hms.service.RealtimeNotifier notifier;

    // ── write ────────────────────────────────────────────────────────────────

    @Transactional
    public IcuIoEntry record(IcuIoRequest req) {
        Long hospitalId = requireHospitalId();
        formAccessService.assertCanEdit(FORM_KEY);
        if (req.getIpdAdmissionId() == null) {
            throw new IllegalArgumentException("ipdAdmissionId is required");
        }
        IpdAdmission admission = requireAdmission(req.getIpdAdmissionId(), hospitalId);
        nurseWriteAccess.assertCanWriteFor(admission.getId());
        validate(req);

        IcuIoEntry e = new IcuIoEntry();
        e.setHospitalId(hospitalId);
        e.setIpdAdmissionId(admission.getId());
        e.setPatientId(admission.getPatientId());
        apply(e, req);
        e.setRecordedByUserId(securityHelper.getCurrentUserId());
        e.setPerformedByNurseId(performingNurseResolver.resolve(req.getPerformedByNurseId()));
        e.setIsActive(true);
        IcuIoEntry saved = ioRepository.save(e);

        audit("ICU_IO_RECORDED",
                saved.getDirection() + " " + saved.getRoute() + " " + saved.getVolumeMl() + "ml",
                hospitalId, admission.getId());
        notifier.refresh(hospitalId);
        return saved;
    }

    /**
     * Corrects an entry by writing a NEW row that supersedes it. The original is neither modified
     * nor deactivated: it stays in the chart, rendered as superseded. Hiding it would recreate the
     * loss the append-only model exists to prevent.
     *
     * <p>Authorisation is identical to recording plus the ICU-4 amendment rules — the same nurse,
     * inside the same window. Correcting is a different door, not a wider one.
     */
    @Transactional
    public IcuIoEntry correct(String publicId, IcuIoRequest req) {
        Long hospitalId = requireHospitalId();
        formAccessService.assertCanEdit(FORM_KEY);

        IcuIoEntry original = ioRepository.findByPublicIdAndHospitalId(publicId, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("I/O entry not found"));

        assertMayAmend(original);
        validate(req);

        IcuIoEntry correction = new IcuIoEntry();
        correction.setHospitalId(hospitalId);
        correction.setIpdAdmissionId(original.getIpdAdmissionId());
        correction.setPatientId(original.getPatientId());
        apply(correction, req);
        // A correction describes the SAME event, so it keeps the original's time unless the
        // caller is explicitly correcting the time itself.
        if (req.getOccurredAt() == null) correction.setOccurredAt(original.getOccurredAt());
        correction.setRecordedByUserId(securityHelper.getCurrentUserId());
        correction.setPerformedByNurseId(performingNurseResolver.resolve(req.getPerformedByNurseId()));
        correction.setSupersedesIoEntryId(original.getId());
        correction.setIsActive(true);
        IcuIoEntry saved = ioRepository.save(correction);

        audit("ICU_IO_CORRECTED",
                "Corrected I/O entry " + original.getPublicId() + " with " + saved.getPublicId(),
                hospitalId, original.getIpdAdmissionId());
        notifier.refresh(hospitalId);
        return saved;
    }

    // ── read ─────────────────────────────────────────────────────────────────

    /** Every entry for the admission, newest first, INCLUDING superseded ones. */
    public List<IcuIoEntry> getByAdmission(Long ipdAdmissionId) {
        Long hospitalId = requireHospitalId();
        IpdAdmission admission = requireAdmission(ipdAdmissionId, hospitalId);
        if ("NURSE".equals(securityHelper.getCurrentUserRole())) {
            nurseAccessGuard.assertAssigned(admission.getId());
        }
        return ioRepository.findByIpdAdmissionIdAndHospitalIdAndIsActiveTrueOrderByOccurredAtDesc(
                ipdAdmissionId, hospitalId);
    }

    /**
     * Fluid balance over an optional range.
     *
     * <p>Totals are summed from the entries here and nowhere else; a superseded entry is excluded
     * so a correction replaces its original rather than adding to it. Arithmetic only - no target,
     * no threshold, no verdict.
     */
    public IcuIoBalanceDTO balance(Long ipdAdmissionId, LocalDateTime from, LocalDateTime to) {
        Long hospitalId = requireHospitalId();
        IpdAdmission admission = requireAdmission(ipdAdmissionId, hospitalId);
        if ("NURSE".equals(securityHelper.getCurrentUserRole())) {
            nurseAccessGuard.assertAssigned(admission.getId());
        }

        List<IcuIoEntry> entries = (from != null && to != null)
                ? ioRepository
                    .findByIpdAdmissionIdAndHospitalIdAndIsActiveTrueAndOccurredAtBetweenOrderByOccurredAtDesc(
                        ipdAdmissionId, hospitalId, from, to)
                : ioRepository.findByIpdAdmissionIdAndHospitalIdAndIsActiveTrueOrderByOccurredAtDesc(
                        ipdAdmissionId, hospitalId);

        Set<Long> superseded = new HashSet<>(ioRepository.findSupersededIds(ipdAdmissionId, hospitalId));

        IcuIoBalanceDTO dto = new IcuIoBalanceDTO();
        int intake = 0;
        int output = 0;
        int counted = 0;
        for (IcuIoEntry e : entries) {
            if (superseded.contains(e.getId())) continue;
            counted++;
            if (IcuIoEntry.INTAKE.equals(e.getDirection())) intake += e.getVolumeMl();
            else if (IcuIoEntry.OUTPUT.equals(e.getDirection())) output += e.getVolumeMl();
        }
        dto.setTotalIntakeMl(intake);
        dto.setTotalOutputMl(output);
        dto.setNetBalanceMl(intake - output);
        dto.setEntryCount(counted);
        return dto;
    }

    /** Ids superseded by a correction, so the UI can strike them through. */
    public List<Long> supersededIds(Long ipdAdmissionId) {
        Long hospitalId = requireHospitalId();
        requireAdmission(ipdAdmissionId, hospitalId);
        return ioRepository.findSupersededIds(ipdAdmissionId, hospitalId);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void apply(IcuIoEntry e, IcuIoRequest req) {
        e.setDirection(req.getDirection());
        e.setRoute(req.getRoute());
        e.setVolumeMl(req.getVolumeMl());
        e.setOccurredAt(req.getOccurredAt() != null ? req.getOccurredAt() : LocalDateTime.now());
        e.setNotes(req.getNotes());
    }

    private void validate(IcuIoRequest req) {
        String direction = req.getDirection();
        if (!IcuIoEntry.INTAKE.equals(direction) && !IcuIoEntry.OUTPUT.equals(direction)) {
            throw new IllegalArgumentException("Direction must be INTAKE or OUTPUT");
        }
        if (!IcuIoEntry.routeMatchesDirection(direction, req.getRoute())) {
            throw new IllegalArgumentException(
                    "Route " + req.getRoute() + " is not valid for " + direction);
        }
        if (req.getVolumeMl() == null || req.getVolumeMl() <= 0) {
            throw new IllegalArgumentException("Volume must be greater than zero");
        }
        if (req.getOccurredAt() != null
                && req.getOccurredAt().isAfter(LocalDateTime.now().plusMinutes(1))) {
            throw new IllegalArgumentException("Recorded time cannot be in the future");
        }
    }

    /** The ICU-4 amendment rules, unchanged: only the recorder, and only inside the window. */
    private void assertMayAmend(IcuIoEntry e) {
        if (e.getRecordedByUserId() == null
                || !e.getRecordedByUserId().equals(securityHelper.getCurrentUserId())) {
            throw new AccessDeniedException("Only the person who recorded this entry can correct it");
        }
        if (Duration.between(e.getCreatedAt().atZone(java.time.ZoneId.systemDefault()),
                java.time.ZonedDateTime.now()).compareTo(EDIT_WINDOW) > 0) {
            throw new IllegalArgumentException("Edit window has passed for this I/O entry");
        }
    }

    /** Read-only use of the ICU-3 stay record; the lifecycle is never touched from here. */
    public boolean isInIcuAt(Long ipdAdmissionId, Long hospitalId, LocalDateTime at) {
        return icuStayRepository.existsCoveringInstant(ipdAdmissionId, hospitalId, at);
    }

    private IpdAdmission requireAdmission(Long ipdAdmissionId, Long hospitalId) {
        IpdAdmission admission = ipdAdmissionRepository.findById(ipdAdmissionId)
                .orElseThrow(() -> new ResourceNotFoundException("IPD admission not found"));
        if (!hospitalId.equals(admission.getHospitalId())) {
            // A tenant check, not a permission check: another hospital's admission must be
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
        } catch (Exception ex) {
            logger.warn("ICU I/O audit failed: {}", ex.getMessage());
        }
    }
}
