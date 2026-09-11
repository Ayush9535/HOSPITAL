package com.hms.service.hospital;

import com.hms.entity.*;
import com.hms.exception.UnauthorizedException;
import com.hms.repository.*;
import com.hms.security.NurseAccessGuard;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * AdmissionFormService - the nurse fills an admission form to complete an IPD
 * admission (Phase 1 Nurse module). Draft is pre-filled from known data; nurse
 * edits + saves; after collecting the signed printed form the nurse marks the
 * patient admitted (confirms the admission).
 */
@Service
public class AdmissionFormService {

    private static final Logger logger = LoggerFactory.getLogger(AdmissionFormService.class);
    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final DateTimeFormatter T = DateTimeFormatter.ofPattern("hh:mm a");

    @Autowired private AdmissionFormRepository formRepository;
    @Autowired private IpdAdmissionRepository ipdAdmissionRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private DoctorRepository doctorRepository;
    @Autowired private WardRepository wardRepository;
    @Autowired private BedRepository bedRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private SecurityContextHelper securityHelper;
    @Autowired private NurseAccessGuard nurseAccessGuard;
    @Autowired private AuditLogService auditLogService;
    @Autowired private com.hms.service.RealtimeNotifier notifier;

    /**
     * Return the saved form, or a pre-filled (unsaved) draft if none exists yet.
     */
    public com.hms.dto.AdmissionFormView getOrDraft(Long ipdAdmissionId) {
        Long hospitalId = requireHospitalId();
        IpdAdmission ipd = requireAdmission(ipdAdmissionId, hospitalId);
        nurseAccessGuard.assertAssigned(ipdAdmissionId);

        AdmissionForm form = formRepository.findByIpdAdmissionId(ipdAdmissionId)
                .orElseGet(() -> prefillDraft(ipd, hospitalId));
        return withBranding(form, hospitalId, ipd);
    }

    @Transactional
    public com.hms.dto.AdmissionFormView save(Long ipdAdmissionId, AdmissionForm incoming) {
        Long hospitalId = requireHospitalId();
        IpdAdmission ipd = requireAdmission(ipdAdmissionId, hospitalId);
        nurseAccessGuard.assertAssigned(ipdAdmissionId);

        AdmissionForm form = formRepository.findByIpdAdmissionId(ipdAdmissionId)
                .orElseGet(AdmissionForm::new);
        // Server owns identity/scoping; copy only the editable payload.
        form.setHospitalId(hospitalId);
        form.setIpdAdmissionId(ipdAdmissionId);
        copyEditable(incoming, form);
        AdmissionForm saved = formRepository.save(form);

        audit("ADMISSION_FORM_SAVED", "Saved admission form for IPD admission " + ipdAdmissionId, hospitalId, ipdAdmissionId);
        return withBranding(saved, hospitalId, ipd);
    }

    /** Wrap the form with live hospital branding (name/logo/address) for printing. */
    private com.hms.dto.AdmissionFormView withBranding(AdmissionForm form, Long hospitalId, IpdAdmission ipd) {
        com.hms.entity.Hospital h = hospitalRepository.findById(hospitalId).orElse(null);
        return new com.hms.dto.AdmissionFormView(
                form,
                h != null ? h.getName() : null,
                h != null ? h.getLogoUrl() : null,
                h != null ? h.getAddress() : null,
                h != null ? h.getCustomId() : null,
                ipd != null ? ipd.getWardId() : null);
    }

    /**
     * Confirm the patient as admitted after the signed form is collected.
     * Requires the form to have been saved first.
     */
    @Transactional
    public IpdAdmission markAdmitted(Long ipdAdmissionId) {
        Long hospitalId = requireHospitalId();
        IpdAdmission ipd = requireAdmission(ipdAdmissionId, hospitalId);
        nurseAccessGuard.assertAssigned(ipdAdmissionId);

        if (formRepository.findByIpdAdmissionId(ipdAdmissionId).isEmpty()) {
            throw new IllegalArgumentException("Fill and save the admission form before marking the patient admitted");
        }
        ipd.setAdmissionConfirmed(true);
        ipd.setAdmissionConfirmedAt(LocalDateTime.now());
        IpdAdmission saved = ipdAdmissionRepository.save(ipd);

        audit("ADMISSION_CONFIRMED", "Marked patient admitted for IPD " + ipd.getIpdNumber(), hospitalId, ipdAdmissionId);
        return saved;
    }

    // --- helpers ---

    private AdmissionForm prefillDraft(IpdAdmission ipd, Long hospitalId) {
        AdmissionForm f = new AdmissionForm();
        f.setHospitalId(hospitalId);
        f.setIpdAdmissionId(ipd.getId());
        f.setIpdRegistrationNo(ipd.getIpdNumber());
        f.setProvDiagnosis1(ipd.getPrimaryDiagnosis());
        if (ipd.getAdmissionDatetime() != null) {
            f.setAdmittedDate(ipd.getAdmissionDatetime().format(D));
            f.setAdmittedTime(ipd.getAdmissionDatetime().format(T));
        }
        patientRepository.findById(ipd.getPatientId()).ifPresent(p -> {
            f.setPrnNo(p.getCustomId());
            f.setPatientFirstName(p.getName());
            f.setPatientAddress(p.getAddress());
            try { if (p.getAge() != null) f.setAge(String.valueOf(p.getAge())); } catch (Exception ignored) {}
            f.setSex(p.getGender());
            f.setTelephone(p.getPhone());
        });
        // The admission's doctor is the OPD consulting doctor → both "under care
        // of" and "Ref. Dr" on the form.
        doctorRepository.findById(ipd.getDoctorId()).ifPresent(d -> {
            f.setUnderCareOfDr(d.getName());
            f.setRefDr(d.getName());
        });
        // Receptionist who assigned the ward/bed (performed the admission).
        if (ipd.getAdmittedByUserId() != null) {
            userRepository.findById(ipd.getAdmittedByUserId()).ifPresent(u -> f.setReceptionistName(u.getName()));
        }
        if (ipd.getWardId() != null) {
            wardRepository.findById(ipd.getWardId()).ifPresent(w -> {
                f.setCategory(w.getWardName());
                f.setDepartment(w.getWardName());
            });
        }
        if (ipd.getBedId() != null) {
            bedRepository.findById(ipd.getBedId()).ifPresent(b -> f.setBedNo(b.getBedCode()));
        }
        return f;
    }

    private void copyEditable(AdmissionForm in, AdmissionForm out) {
        out.setPrnNo(in.getPrnNo());
        out.setBedNo(in.getBedNo());
        out.setCategory(in.getCategory());
        out.setPatientSurname(in.getPatientSurname());
        out.setPatientFirstName(in.getPatientFirstName());
        out.setHusbandFatherName(in.getHusbandFatherName());
        out.setPatientAddress(in.getPatientAddress());
        out.setAge(in.getAge());
        out.setSex(in.getSex());
        out.setOccupation(in.getOccupation());
        out.setPatientCategory(in.getPatientCategory());
        out.setMediclaim(in.getMediclaim());
        out.setTpaName(in.getTpaName());
        out.setRelativeName(in.getRelativeName());
        out.setEmail(in.getEmail());
        out.setTelephone(in.getTelephone());
        out.setReceptionistName(in.getReceptionistName());
        out.setRefDr(in.getRefDr());
        out.setIpdRegistrationNo(in.getIpdRegistrationNo());
        out.setDepartment(in.getDepartment());
        out.setUnderCareOfDr(in.getUnderCareOfDr());
        out.setAdmittedDate(in.getAdmittedDate());
        out.setAdmittedTime(in.getAdmittedTime());
        out.setProvDiagnosis1(in.getProvDiagnosis1());
        out.setProvDiagnosis2(in.getProvDiagnosis2());
        out.setHypersensitivityHistory(in.getHypersensitivityHistory());
        out.setRelativeAddress(in.getRelativeAddress());
        out.setRelativePhone(in.getRelativePhone());
    }

    private IpdAdmission requireAdmission(Long ipdAdmissionId, Long hospitalId) {
        IpdAdmission ipd = ipdAdmissionRepository.findById(ipdAdmissionId)
                .orElseThrow(() -> new IllegalArgumentException("IPD admission not found"));
        if (!hospitalId.equals(ipd.getHospitalId())) {
            throw new UnauthorizedException("Access denied: admission belongs to another hospital");
        }
        return ipd;
    }

    private Long requireHospitalId() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) throw new UnauthorizedException("Hospital ID not found in context");
        return hospitalId;
    }

    /** Audits the write and pushes it: reception and nursing both watch the admission list. */
    private void audit(String action, String details, Long hospitalId, Long admissionId) {
        notifier.refresh(hospitalId);
        try {
            auditLogService.logAction(action, details, securityHelper.getCurrentUserEmail(), hospitalId,
                    "IPD", admissionId != null ? admissionId.toString() : null, null);
        } catch (Exception e) {
            logger.warn("Failed to write audit log for {}", action, e);
        }
    }
}
