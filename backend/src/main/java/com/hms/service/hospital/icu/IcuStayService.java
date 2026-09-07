package com.hms.service.hospital.icu;

import com.hms.entity.IcuStay;
import com.hms.entity.IpdAdmission;
import com.hms.entity.Ward;
import com.hms.exception.ConflictException;
import com.hms.exception.ResourceNotFoundException;
import com.hms.repository.DoctorRepository;
import com.hms.repository.IcuStayRepository;
import com.hms.repository.WardRepository;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * IcuStayService - opens and closes the ICU stay that shadows an IPD movement (ICU Phase 3).
 *
 * <p><b>This service never moves a patient.</b> Admission, transfer and discharge stay entirely
 * with {@code IpdAdmissionService}; this only records that a movement crossed into or out of
 * critical care. There is no ICU movement API and no second occupancy model.
 *
 * <p><b>Why MANDATORY.</b> The stay is critical domain state, not a side effect: a patient lying
 * in an ICU bed with no stay record breaks the module's central biconditional silently, loses the
 * admission time permanently, and cannot be detected afterwards because nothing recorded that it
 * should exist. So it must commit with the movement or not at all. {@code MANDATORY} throws when
 * no transaction is active, which turns "someone called this outside a movement" into a loud
 * failure instead of an orphan row. E1 delivered the transaction on {@code admitFromOpd} that
 * makes this safe; before that, this propagation would have thrown on every admission.
 */
@Service
public class IcuStayService {

    private static final Logger logger = LoggerFactory.getLogger(IcuStayService.class);

    private static final List<String> SOURCES = List.of(
            IcuStay.SRC_EMERGENCY, IcuStay.SRC_OPD, IcuStay.SRC_WARD,
            IcuStay.SRC_OT_RECOVERY, IcuStay.SRC_ICU_TRANSFER, IcuStay.SRC_EXTERNAL_REFERRAL);

    private static final List<String> DISPOSITIONS = List.of(
            IcuStay.DISP_WARD, IcuStay.DISP_HOME, IcuStay.DISP_LAMA,
            IcuStay.DISP_REFERRED_OUT, IcuStay.DISP_EXPIRED, IcuStay.DISP_ANOTHER_ICU);

    @Autowired private IcuStayRepository icuStayRepository;
    @Autowired private WardRepository wardRepository;
    @Autowired private DoctorRepository doctorRepository;
    @Autowired private com.hms.service.RealtimeNotifier notifier;
    @Autowired private SecurityContextHelper securityHelper;
    @Autowired private AuditLogService auditLogService;

    // ── lifecycle, driven by IPD movement ────────────────────────────────────

    /**
     * Called after an IPD movement has settled the admission's ward.
     *
     * <p>Handles every transition in one place because they are one question — "is the patient in
     * critical care now, and were they before?":
     * <ul>
     *   <li>general → critical: open</li>
     *   <li>critical → general: close</li>
     *   <li>critical → different critical ward: close, then open (ICU readmission is real)</li>
     *   <li>critical → same critical ward (bed change only): nothing; the stay is bounded by the
     *       ward, and {@code ipd_bed_history} already records the bed move</li>
     * </ul>
     *
     * @param source      how the patient arrived, when this opens a stay
     * @param sourceRefId provenance for that source; may be null
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onWardSettled(IpdAdmission admission, Long previousWardId,
                              String source, Long sourceRefId, String admissionReason) {
        Long hospitalId = admission.getHospitalId();
        boolean nowCritical = isCriticalCare(admission.getWardId(), hospitalId);
        Optional<IcuStay> open = activeStay(admission.getId(), hospitalId);

        if (!nowCritical) {
            open.ifPresent(stay -> close(stay, IcuStay.DISP_WARD));
            return;
        }

        if (open.isPresent()) {
            IcuStay stay = open.get();
            if (stay.getWardId().equals(admission.getWardId())) {
                return; // same unit, different bed — not a new episode
            }
            // Moved to a different critical-care unit: the old episode ended and a new one began.
            close(stay, IcuStay.DISP_ANOTHER_ICU);
            openStay(admission, IcuStay.SRC_ICU_TRANSFER, stay.getId(), admissionReason);
            return;
        }

        openStay(admission, source != null ? source : resolveEntrySource(previousWardId),
                sourceRefId != null ? sourceRefId : previousWardId, admissionReason);
    }

    /** Closes the active stay, if any, when the admission is discharged. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onDischarged(IpdAdmission admission, String disposition) {
        activeStay(admission.getId(), admission.getHospitalId())
                .ifPresent(stay -> close(stay, disposition == null ? IcuStay.DISP_HOME : disposition));
    }

    // ── the two writes ───────────────────────────────────────────────────────

    private IcuStay openStay(IpdAdmission admission, String source, Long sourceRefId, String reason) {
        if (!SOURCES.contains(source)) {
            throw new IllegalArgumentException("Unknown ICU stay source: " + source);
        }
        // Belt and braces: the DB unique index is the real guarantee, but a clean 409 beats a
        // driver-level constraint error reaching the user.
        if (icuStayRepository.existsByIpdAdmissionIdAndHospitalIdAndStatus(
                admission.getId(), admission.getHospitalId(), IcuStay.ACTIVE)) {
            throw new ConflictException("This admission already has an active ICU stay.");
        }

        IcuStay stay = new IcuStay();
        stay.setHospitalId(admission.getHospitalId());
        stay.setIpdAdmissionId(admission.getId());
        stay.setPatientId(admission.getPatientId());
        stay.setWardId(admission.getWardId());
        stay.setStatus(IcuStay.ACTIVE);
        stay.setSource(source);
        stay.setSourceRefId(sourceRefId);
        stay.setAdmittedAt(LocalDateTime.now());
        stay.setAdmissionReason(reason);
        stay.setAdmittedByUserId(safeUserId());
        stay.setActiveMarker(admission.getId()); // NULL once closed — see IcuStay
        IcuStay saved = icuStayRepository.save(stay);

        audit("ICU_STAY_OPENED", "ICU stay opened (" + source + ") for admission "
                + admission.getIpdNumber(), admission.getHospitalId(), saved.getId());
        return saved;
    }

    private void close(IcuStay stay, String disposition) {
        if (!DISPOSITIONS.contains(disposition)) {
            throw new IllegalArgumentException("Unknown ICU disposition: " + disposition);
        }
        stay.setStatus(IcuStay.CLOSED);
        stay.setDisposition(disposition);
        stay.setDischargedAt(LocalDateTime.now());
        stay.setDischargedByUserId(safeUserId());
        stay.setActiveMarker(null); // frees the admission for a later stay
        icuStayRepository.save(stay);

        audit("ICU_STAY_CLOSED", "ICU stay closed (" + disposition + ")",
                stay.getHospitalId(), stay.getId());
    }

    // ── reads ────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public IcuStay getByPublicId(String publicId) {
        return icuStayRepository.findByPublicIdAndHospitalId(publicId, requireHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException("ICU stay not found"));
    }

    /** Full history for an admission, newest first. Closed stays remain readable forever. */
    @Transactional(readOnly = true)
    public List<IcuStay> historyFor(Long ipdAdmissionId) {
        return icuStayRepository.findByIpdAdmissionIdAndHospitalIdOrderByAdmittedAtDesc(
                ipdAdmissionId, requireHospitalId());
    }

    /** Active stays for a set of admissions — the ICU board's batched read. */
    @Transactional(readOnly = true)
    public List<IcuStay> activeStaysFor(Long hospitalId, java.util.Collection<Long> admissionIds) {
        if (admissionIds == null || admissionIds.isEmpty()) return List.of();
        return icuStayRepository.findByHospitalIdAndStatusAndIpdAdmissionIdIn(
                hospitalId, IcuStay.ACTIVE, admissionIds);
    }

    // ── narrow mutations: one field each, ACTIVE stays only ──────────────────

    /** @param doctorId the intensivist, or null to clear. Must belong to the caller's hospital. */
    @Transactional
    public IcuStay setIntensivist(String publicId, Long doctorId) {
        IcuStay stay = requireActive(publicId);
        if (doctorId != null) {
            // The only caller-supplied foreign id in the module, so the highest-risk IDOR
            // surface: resolve it scoped, and answer 404 exactly like a missing doctor.
            doctorRepository.findByIdAndHospitalIdAndIsActiveTrue(doctorId, stay.getHospitalId())
                    .orElseThrow(() -> new ResourceNotFoundException("Doctor not found"));
        }
        stay.setIntensivistDoctorId(doctorId);
        IcuStay saved = icuStayRepository.save(stay);
        audit("ICU_INTENSIVIST_ASSIGNED", "Intensivist set to " + doctorId,
                stay.getHospitalId(), stay.getId());
        // The bed board prints the intensivist, so another tab holding the board is now stale.
        // Only the narrow mutations push: the MANDATORY lifecycle methods run inside the IPD
        // movement transaction, whose caller already refreshes, and pushing twice for one
        // movement would be noise.
        notifier.refresh(stay.getHospitalId());
        return saved;
    }

    @Transactional
    public IcuStay setAdmissionReason(String publicId, String reason) {
        IcuStay stay = requireActive(publicId);
        stay.setAdmissionReason(reason);
        IcuStay saved = icuStayRepository.save(stay);
        audit("ICU_REASON_UPDATED", "ICU admission reason updated",
                stay.getHospitalId(), stay.getId());
        notifier.refresh(stay.getHospitalId());
        return saved;
    }

    // ── view helpers: resolve the intensivist name once, tenant-scoped ───────

    @Transactional(readOnly = true)
    public com.hms.dto.icu.IcuStayDTO viewByPublicId(String publicId) {
        return toDto(getByPublicId(publicId));
    }

    @Transactional(readOnly = true)
    public List<com.hms.dto.icu.IcuStayDTO> viewHistoryFor(Long ipdAdmissionId) {
        return historyFor(ipdAdmissionId).stream().map(this::toDto).toList();
    }

    @Transactional
    public com.hms.dto.icu.IcuStayDTO setIntensivistAndView(String publicId, Long doctorId) {
        return toDto(setIntensivist(publicId, doctorId));
    }

    @Transactional
    public com.hms.dto.icu.IcuStayDTO setAdmissionReasonAndView(String publicId, String reason) {
        return toDto(setAdmissionReason(publicId, reason));
    }

    /** A deleted or foreign doctor renders as null rather than failing the read. */
    public com.hms.dto.icu.IcuStayDTO toDto(IcuStay stay) {
        String name = null;
        if (stay.getIntensivistDoctorId() != null) {
            name = doctorRepository
                    .findByIdAndHospitalIdAndIsActiveTrue(stay.getIntensivistDoctorId(), stay.getHospitalId())
                    .map(com.hms.entity.Doctor::getName).orElse(null);
        }
        return com.hms.dto.icu.IcuStayDTO.of(stay, name);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** A closed stay is a clinical record: terminal, and not editable by anyone. */
    private IcuStay requireActive(String publicId) {
        IcuStay stay = getByPublicId(publicId);
        if (!stay.isActive()) {
            throw new ConflictException("This ICU stay is closed and can no longer be changed.");
        }
        return stay;
    }

    private Optional<IcuStay> activeStay(Long ipdAdmissionId, Long hospitalId) {
        return icuStayRepository.findByIpdAdmissionIdAndHospitalIdAndStatus(
                ipdAdmissionId, hospitalId, IcuStay.ACTIVE);
    }

    private boolean isCriticalCare(Long wardId, Long hospitalId) {
        if (wardId == null) return false;
        return wardRepository.findByWardIdAndHospitalId(wardId, hospitalId)
                .map(Ward::getUnitType)
                .map(CareUnitRegistry::isCriticalCare)
                .orElse(false);
    }

    /** A step-up from a known ward is WARD; anything else is a direct entry. */
    private String resolveEntrySource(Long previousWardId) {
        return previousWardId != null ? IcuStay.SRC_WARD : IcuStay.SRC_EXTERNAL_REFERRAL;
    }

    private void audit(String action, String details, Long hospitalId, Long stayId) {
        try {
            auditLogService.logAction(action, details, securityHelper.getCurrentUserEmail(),
                    hospitalId, "ICU_STAY", String.valueOf(stayId), null);
        } catch (Exception e) {
            logger.warn("ICU stay audit failed: {}", e.getMessage());
        }
    }

    private Long safeUserId() {
        try { return securityHelper.getCurrentUserId(); } catch (Exception e) { return null; }
    }

    private Long requireHospitalId() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) throw new com.hms.exception.UnauthorizedException("Hospital ID not found in context");
        return hospitalId;
    }
}
