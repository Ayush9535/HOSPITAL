package com.hms.service.hospital;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hms.dto.SaveSurgeryFormRequest;
import com.hms.dto.SurgeryFormView;
import com.hms.entity.IpdAdmission;
import com.hms.entity.Surgery;
import com.hms.entity.SurgeryForm;
import com.hms.exception.UnauthorizedException;
import com.hms.repository.IpdAdmissionRepository;
import com.hms.repository.SurgeryFormRepository;
import com.hms.repository.SurgeryRepository;
import com.hms.security.NurseAccessGuard;
import com.hms.security.SecurityContextHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * SurgeryFormService - the OT/NABH surgery forms store (generic JSON; layouts on the frontend).
 *
 * Forms are scoped to the SURGERY, not the admission. They used to be unique on
 * (admission, formType), so a second procedure in one admission overwrote the first
 * procedure's signed consent and WHO checklist.
 *
 * Once signed a form is immutable: saving again supersedes the row and inserts a new
 * version. Nothing here ever mutates a signed row.
 *
 * HOSPITAL tenant only, OT-gated at the controller.
 */
@Service
public class SurgeryFormService {

    private static final Logger logger = LoggerFactory.getLogger(SurgeryFormService.class);

    /** When resolving from an admission, an active surgery is the natural target. */
    private static final List<String> ACTIVE_STATUSES =
            List.of(Surgery.REQUESTED, Surgery.SCHEDULED, Surgery.IN_PROGRESS);

    @Autowired private SurgeryFormRepository formRepository;
    @Autowired private IpdAdmissionRepository ipdAdmissionRepository;
    @Autowired private SurgeryRepository surgeryRepository;
    @Autowired private SecurityContextHelper securityHelper;
    @Autowired private NurseAccessGuard nurseAccessGuard;
    @Autowired private com.hms.security.NurseWriteAccess nurseWriteAccess;
    @Autowired private com.hms.security.PerformingNurseResolver performingNurseResolver;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private com.hms.service.RealtimeNotifier notifier;

    @Transactional
    public SurgeryFormView save(SaveSurgeryFormRequest req) {
        Long hospitalId = requireHospitalId();
        if (req.getFormType() == null || req.getFormType().trim().isEmpty()) {
            throw new IllegalArgumentException("formType is required");
        }
        Surgery surgery = resolveSurgery(req.getSurgeryId(), req.getIpdAdmissionId(), hospitalId);
        assertCanWriteFor(surgery);

        String formType = req.getFormType().trim();
        SurgeryForm current = formRepository
                .findBySurgeryIdAndFormTypeAndIsCurrentTrue(surgery.getId(), formType)
                .orElse(null);

        SurgeryForm target;
        if (current == null) {
            target = newVersion(surgery, formType, 1);
        } else if (current.getSignedAt() != null) {
            // Immutable once signed: supersede rather than overwrite. isCurrent goes to NULL
            // (never false) so the unique key, which treats NULLs as distinct, admits the row.
            current.setIsCurrent(null);
            formRepository.save(current);
            target = newVersion(surgery, formType, current.getVersion() + 1);
        } else {
            target = current;
        }

        target.setDataJson(writeJson(req.getData()));
        target.setSavedByUserId(securityHelper.getCurrentUserId());
        target.setRecordedByUserId(securityHelper.getCurrentUserId());
        target.setPerformedByNurseId(performingNurseResolver.resolve(req.getPerformedByNurseId()));
        if (Boolean.TRUE.equals(req.getSign())) {
            target.setSignedAt(LocalDateTime.now());
            target.setSignedByUserId(securityHelper.getCurrentUserId());
        }
        SurgeryFormView view = toView(formRepository.save(target));
        // The OT board and the nurse's consent-forms tab both read these -- push the save.
        notifier.refresh(hospitalId);
        return view;
    }

    /** Sign the live version of a form. Signing twice is rejected, not silently ignored. */
    @Transactional
    public SurgeryFormView sign(Long surgeryId, String formType) {
        Long hospitalId = requireHospitalId();
        Surgery surgery = requireSurgery(surgeryId, hospitalId);
        assertCanWriteFor(surgery);

        SurgeryForm form = formRepository.findBySurgeryIdAndFormTypeAndIsCurrentTrue(surgery.getId(), formType)
                .orElseThrow(() -> new IllegalArgumentException("Save the form before signing it"));
        if (form.getSignedAt() != null) {
            throw new IllegalArgumentException("This form is already signed");
        }
        form.setSignedAt(LocalDateTime.now());
        form.setSignedByUserId(securityHelper.getCurrentUserId());
        SurgeryFormView view = toView(formRepository.save(form));
        notifier.refresh(hospitalId);
        return view;
    }

    /** The live form for a procedure + type, or null if never saved. */
    public SurgeryFormView getBySurgery(Long surgeryId, String formType) {
        Long hospitalId = requireHospitalId();
        Surgery surgery = requireSurgery(surgeryId, hospitalId);
        assertCanReadFor(surgery);
        return formRepository.findBySurgeryIdAndFormTypeAndIsCurrentTrue(surgery.getId(), formType)
                .map(this::toView).orElse(null);
    }

    /** Every version of one form, newest first. */
    public List<SurgeryFormView> versions(Long surgeryId, String formType) {
        Long hospitalId = requireHospitalId();
        Surgery surgery = requireSurgery(surgeryId, hospitalId);
        assertCanReadFor(surgery);
        return formRepository.findBySurgeryIdAndFormTypeOrderByVersionDesc(surgery.getId(), formType)
                .stream().map(this::toView).collect(Collectors.toList());
    }

    /** Which form types have a live version for this procedure (drives the "Saved" badges). */
    public List<String> listSavedTypesBySurgery(Long surgeryId) {
        Long hospitalId = requireHospitalId();
        Surgery surgery = requireSurgery(surgeryId, hospitalId);
        assertCanReadFor(surgery);
        return formRepository.findBySurgeryIdAndIsCurrentTrue(surgery.getId()).stream()
                .map(SurgeryForm::getFormType).collect(Collectors.toList());
    }

    // --- admission-scoped entry points (existing callers keep working) ---

    public SurgeryFormView get(Long ipdAdmissionId, String formType) {
        Surgery surgery = resolveSurgery(null, ipdAdmissionId, requireHospitalId());
        return getBySurgery(surgery.getId(), formType);
    }

    public List<String> listSavedTypes(Long ipdAdmissionId) {
        Surgery surgery = resolveSurgery(null, ipdAdmissionId, requireHospitalId());
        return listSavedTypesBySurgery(surgery.getId());
    }

    // --- helpers ---

    /**
     * Prefer an explicit surgeryId. Falling back to the admission is ambiguous once an
     * admission carries several procedures, so take the active one and otherwise the most
     * recent -- and never invent a surgery to hang a consent on.
     */
    private Surgery resolveSurgery(Long surgeryId, Long ipdAdmissionId, Long hospitalId) {
        if (surgeryId != null) return requireSurgery(surgeryId, hospitalId);
        if (ipdAdmissionId == null) throw new IllegalArgumentException("surgeryId or ipdAdmissionId is required");

        IpdAdmission admission = requireAdmission(ipdAdmissionId, hospitalId);
        List<Surgery> active = surgeryRepository.findByIpdAdmissionIdAndStatusIn(admission.getId(), ACTIVE_STATUSES);
        if (!active.isEmpty()) return active.get(0);

        return surgeryRepository.findByIpdAdmissionIdOrderByRequestedAtDesc(admission.getId()).stream()
                .max(Comparator.comparing(Surgery::getRequestedAt))
                .orElseThrow(() -> new IllegalArgumentException(
                        "This patient has no surgery. Request a surgery before filling OT forms."));
    }

    /**
     * A day-care procedure has no admission, so the nurse-assignment guards -- which are
     * keyed on the admission's ward -- cannot apply. Tenant scope and the controller's
     * role check still hold.
     */
    private void assertCanWriteFor(Surgery surgery) {
        if (surgery.getIpdAdmissionId() != null) {
            nurseWriteAccess.assertCanWriteFor(surgery.getIpdAdmissionId());
        }
    }

    private void assertCanReadFor(Surgery surgery) {
        if (surgery.getIpdAdmissionId() != null && "NURSE".equals(securityHelper.getCurrentUserRole())) {
            nurseAccessGuard.assertAssigned(surgery.getIpdAdmissionId());
        }
    }

    private SurgeryForm newVersion(Surgery surgery, String formType, int version) {
        SurgeryForm f = new SurgeryForm();
        f.setHospitalId(surgery.getHospitalId());
        f.setSurgeryId(surgery.getId());
        f.setIpdAdmissionId(surgery.getIpdAdmissionId()); // null for day-care
        f.setFormType(formType);
        f.setVersion(version);
        f.setIsCurrent(Boolean.TRUE);
        return f;
    }

    private SurgeryFormView toView(SurgeryForm f) {
        SurgeryFormView v = new SurgeryFormView();
        v.setFormType(f.getFormType());
        v.setData(readJson(f.getDataJson()));
        v.setSavedAt(f.getUpdatedAt() != null ? f.getUpdatedAt() : f.getCreatedAt());
        v.setSurgeryId(f.getSurgeryId());
        v.setVersion(f.getVersion());
        v.setSignedAt(f.getSignedAt());
        v.setSignedByUserId(f.getSignedByUserId());
        return v;
    }

    private String writeJson(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(data == null ? Collections.emptyMap() : data);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid form data");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(String json) {
        if (json == null || json.isEmpty()) return Collections.emptyMap();
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            logger.warn("Failed to parse surgery form JSON: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    private Surgery requireSurgery(Long surgeryId, Long hospitalId) {
        Surgery s = surgeryRepository.findById(surgeryId)
                .orElseThrow(() -> new IllegalArgumentException("Surgery not found"));
        if (!hospitalId.equals(s.getHospitalId())) {
            throw new UnauthorizedException("Access denied: surgery belongs to another hospital");
        }
        return s;
    }

    private IpdAdmission requireAdmission(Long ipdAdmissionId, Long hospitalId) {
        IpdAdmission a = ipdAdmissionRepository.findById(ipdAdmissionId)
                .orElseThrow(() -> new IllegalArgumentException("IPD admission not found"));
        if (!hospitalId.equals(a.getHospitalId())) {
            throw new UnauthorizedException("Access denied: admission belongs to another hospital");
        }
        return a;
    }

    private Long requireHospitalId() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) throw new UnauthorizedException("Hospital ID not found in context");
        return hospitalId;
    }
}
