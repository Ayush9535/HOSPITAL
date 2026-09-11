package com.hms.service.hospital;

import com.hms.exception.ResourceNotFoundException;

import com.hms.dto.SugarChartRequest;
import com.hms.entity.IpdAdmission;
import com.hms.entity.SugarChartEntry;
import com.hms.exception.UnauthorizedException;
import com.hms.repository.IpdAdmissionRepository;
import com.hms.repository.SugarChartEntryRepository;
import com.hms.security.NurseAccessGuard;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * SugarChartService - blood-sugar chart entries for an IPD admission (Phase 1
 * Nurse module). Writes are nurse-only and assignment-gated; reads also open to
 * doctors/admins. Edit/soft-delete restricted to the author within a window.
 */
@Service
public class SugarChartService {

    private static final Logger logger = LoggerFactory.getLogger(SugarChartService.class);
    private static final Duration EDIT_WINDOW = Duration.ofHours(12);

    @Autowired private SugarChartEntryRepository repository;
    @Autowired private IpdAdmissionRepository ipdAdmissionRepository;
    @Autowired private SecurityContextHelper securityHelper;
    @Autowired private NurseAccessGuard nurseAccessGuard;
    @Autowired private com.hms.security.NurseWriteAccess nurseWriteAccess;
    @Autowired private com.hms.security.PerformingNurseResolver performingNurseResolver;
    @Autowired private AuditLogService auditLogService;
    @Autowired private com.hms.service.RealtimeNotifier notifier;
    @Autowired private FormAccessService formAccessService;

    @Transactional
    public SugarChartEntry create(SugarChartRequest req) {
        Long hospitalId = requireHospitalId();
        formAccessService.assertCanEdit("SUGAR_CHART");
        if (req.getIpdAdmissionId() == null) {
            throw new IllegalArgumentException("ipdAdmissionId is required");
        }
        IpdAdmission admission = requireAdmission(req.getIpdAdmissionId(), hospitalId);
        nurseWriteAccess.assertCanWriteFor(admission.getId());
        validate(req);

        SugarChartEntry e = new SugarChartEntry();
        e.setHospitalId(hospitalId);
        e.setIpdAdmissionId(admission.getId());
        e.setPatientId(admission.getPatientId());
        e.setNurseUserId(securityHelper.getCurrentUserId());
        e.setBloodSugar(trim(req.getBloodSugar()));
        e.setTreatment(trim(req.getTreatment()));
        e.setRecordedAt(LocalDateTime.now());
        e.setPerformedByNurseId(performingNurseResolver.resolve(req.getPerformedByNurseId()));
        e.setIsActive(true);
        SugarChartEntry saved = repository.save(e);

        auditAndNotify("SUGAR_CHART_ADDED", "Added sugar chart entry for IPD " + admission.getIpdNumber(), hospitalId, admission.getId());
        return saved;
    }

    public List<SugarChartEntry> getByAdmission(Long ipdAdmissionId) {
        Long hospitalId = requireHospitalId();
        IpdAdmission admission = requireAdmission(ipdAdmissionId, hospitalId);
        if ("NURSE".equals(securityHelper.getCurrentUserRole())) {
            nurseAccessGuard.assertAssigned(admission.getId());
        }
        return repository.findByIpdAdmissionIdAndIsActiveTrueOrderByRecordedAtDesc(ipdAdmissionId);
    }

    @Transactional
    public SugarChartEntry update(String publicId, SugarChartRequest req) {
        formAccessService.assertCanEdit("SUGAR_CHART");
        SugarChartEntry e = requireEditableOwn(publicId);
        validate(req);
        e.setBloodSugar(trim(req.getBloodSugar()));
        e.setTreatment(trim(req.getTreatment()));
        SugarChartEntry saved = repository.save(e);
        auditAndNotify("SUGAR_CHART_UPDATED", "Edited sugar chart entry " + e.getPublicId(), e.getHospitalId(), e.getIpdAdmissionId());
        return saved;
    }

    @Transactional
    public void softDelete(String publicId) {
        formAccessService.assertCanEdit("SUGAR_CHART");
        SugarChartEntry e = requireEditableOwn(publicId);
        e.setIsActive(false);
        repository.save(e);
        auditAndNotify("SUGAR_CHART_DELETED", "Deleted sugar chart entry " + e.getPublicId(), e.getHospitalId(), e.getIpdAdmissionId());
    }

    // --- helpers ---

    private SugarChartEntry requireEditableOwn(String publicId) {
        Long hospitalId = requireHospitalId();
        SugarChartEntry e = repository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("Sugar chart entry not found"));
        if (!hospitalId.equals(e.getHospitalId())) {
            throw new UnauthorizedException("Access denied: entry belongs to another hospital");
        }
        if (!e.getNurseUserId().equals(securityHelper.getCurrentUserId())) {
            throw new AccessDeniedException("Only the author can modify this entry");
        }
        if (Duration.between(e.getCreatedAt().atZone(java.time.ZoneId.systemDefault()),
                java.time.ZonedDateTime.now()).compareTo(EDIT_WINDOW) > 0) {
            throw new IllegalArgumentException("Edit window has passed for this entry");
        }
        return e;
    }

    private void validate(SugarChartRequest req) {
        boolean hasSugar = req.getBloodSugar() != null && !req.getBloodSugar().trim().isEmpty();
        boolean hasTreatment = req.getTreatment() != null && !req.getTreatment().trim().isEmpty();
        if (!hasSugar && !hasTreatment) {
            throw new IllegalArgumentException("Enter a blood sugar value or treatment");
        }
    }

    private String trim(String s) { return s == null ? null : s.trim(); }

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
        if (hospitalId == null) throw new UnauthorizedException("Hospital ID not found in context");
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
