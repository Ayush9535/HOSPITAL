package com.hms.service.hospital;

import com.hms.exception.ResourceNotFoundException;

import com.hms.dto.NursingNoteRequest;
import com.hms.entity.IpdAdmission;
import com.hms.entity.NursingNote;
import com.hms.exception.UnauthorizedException;
import com.hms.repository.IpdAdmissionRepository;
import com.hms.repository.NursingNoteRepository;
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
 * NursingNoteService - running observation notes for an IPD admission (Phase 1
 * Nurse module). Writes are nurse-only and assignment-gated; reads also open to
 * doctors/admins. Edit/soft-delete restricted to the author within a window.
 */
@Service
public class NursingNoteService {

    private static final Logger logger = LoggerFactory.getLogger(NursingNoteService.class);
    private static final Duration EDIT_WINDOW = Duration.ofHours(12);
    private static final int MAX_LEN = 2000;

    @Autowired private NursingNoteRepository noteRepository;
    @Autowired private IpdAdmissionRepository ipdAdmissionRepository;
    @Autowired private SecurityContextHelper securityHelper;
    @Autowired private NurseAccessGuard nurseAccessGuard;
    @Autowired private com.hms.security.NurseWriteAccess nurseWriteAccess;
    @Autowired private com.hms.security.PerformingNurseResolver performingNurseResolver;
    @Autowired private AuditLogService auditLogService;
    @Autowired private com.hms.service.RealtimeNotifier notifier;
    @Autowired private FormAccessService formAccessService;

    @Transactional
    public NursingNote create(NursingNoteRequest req) {
        formAccessService.assertCanEdit("NOTES");
        Long hospitalId = requireHospitalId();
        if (req.getIpdAdmissionId() == null) {
            throw new IllegalArgumentException("ipdAdmissionId is required");
        }
        IpdAdmission admission = requireAdmission(req.getIpdAdmissionId(), hospitalId);
        nurseWriteAccess.assertCanWriteFor(admission.getId());
        String text = validateText(req.getNoteText());

        NursingNote n = new NursingNote();
        n.setHospitalId(hospitalId);
        n.setIpdAdmissionId(admission.getId());
        n.setPatientId(admission.getPatientId());
        n.setNurseUserId(securityHelper.getCurrentUserId());
        n.setNoteText(text);
        n.setOrders(req.getOrders());
        n.setCategory(req.getCategory());
        n.setSurgeryId(req.getSurgeryId());
        n.setRecordedAt(LocalDateTime.now());
        n.setPerformedByNurseId(performingNurseResolver.resolve(req.getPerformedByNurseId()));
        n.setIsActive(true);
        NursingNote saved = noteRepository.save(n);

        auditAndNotify("NURSING_NOTE_ADDED", "Added nursing note for IPD " + admission.getIpdNumber(),
                hospitalId, admission.getId());
        return saved;
    }

    public List<NursingNote> getByAdmission(Long ipdAdmissionId) {
        Long hospitalId = requireHospitalId();
        IpdAdmission admission = requireAdmission(ipdAdmissionId, hospitalId);
        if ("NURSE".equals(securityHelper.getCurrentUserRole())) {
            nurseAccessGuard.assertAssigned(admission.getId());
        }
        return noteRepository.findByIpdAdmissionIdAndIsActiveTrueOrderByRecordedAtDesc(ipdAdmissionId);
    }

    public java.util.List<com.hms.entity.NursingNote> getBySurgery(Long surgeryId) {
        Long hospitalId = requireHospitalId();
        // Scope by hospital: surgeryId is a guessable sequential id, so filtering by
        // it alone would leak another tenant's OT notes.
        return noteRepository.findBySurgeryIdAndHospitalIdAndIsActiveTrueOrderByRecordedAtDesc(surgeryId, hospitalId);
    }

    @Transactional
    public NursingNote update(String publicId, NursingNoteRequest req) {
        formAccessService.assertCanEdit("NOTES");
        NursingNote n = requireEditableOwnNote(publicId);
        n.setNoteText(validateText(req.getNoteText()));
        if (req.getOrders() != null) n.setOrders(req.getOrders());
        if (req.getCategory() != null) n.setCategory(req.getCategory());
        NursingNote saved = noteRepository.save(n);
        auditAndNotify("NURSING_NOTE_UPDATED", "Edited nursing note " + n.getPublicId(), n.getHospitalId(), n.getIpdAdmissionId());
        return saved;
    }

    @Transactional
    public void softDelete(String publicId) {
        formAccessService.assertCanEdit("NOTES");
        NursingNote n = requireEditableOwnNote(publicId);
        n.setIsActive(false);
        noteRepository.save(n);
        auditAndNotify("NURSING_NOTE_DELETED", "Deleted nursing note " + n.getPublicId(), n.getHospitalId(), n.getIpdAdmissionId());
    }

    // --- helpers ---

    private NursingNote requireEditableOwnNote(String publicId) {
        Long hospitalId = requireHospitalId();
        NursingNote n = noteRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("Nursing note not found"));
        if (!hospitalId.equals(n.getHospitalId())) {
            throw new UnauthorizedException("Access denied: note belongs to another hospital");
        }
        if (!n.getNurseUserId().equals(securityHelper.getCurrentUserId())) {
            throw new AccessDeniedException("Only the author can modify this note");
        }
        if (Duration.between(n.getCreatedAt().atZone(java.time.ZoneId.systemDefault()),
                java.time.ZonedDateTime.now()).compareTo(EDIT_WINDOW) > 0) {
            throw new IllegalArgumentException("Edit window has passed for this note");
        }
        return n;
    }

    private String validateText(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("Note text is required");
        }
        String trimmed = text.trim();
        if (trimmed.length() > MAX_LEN) {
            throw new IllegalArgumentException("Note text must be at most " + MAX_LEN + " characters");
        }
        return trimmed;
    }

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
