package com.hms.service.hospital.icu;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hms.dto.icu.IcuVentilatorRequest;
import com.hms.entity.IcuStay;
import com.hms.entity.IcuVentilatorParameter;
import com.hms.entity.IcuVentilatorSetting;
import com.hms.entity.IpdAdmission;
import com.hms.exception.ResourceNotFoundException;
import com.hms.exception.UnauthorizedException;
import com.hms.repository.IcuStayRepository;
import com.hms.repository.IcuVentilatorSettingRepository;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * IcuVentilatorService - timed ventilator snapshots and their history (ICU Phase 7).
 *
 * <p><b>Recording appends, never edits.</b> The setting in force at any instant is the latest
 * non-superseded row observed at or before it, so "what was the vent set to at 4 a.m.?" stays
 * answerable after the patient is extubated.
 *
 * <p><b>Configuration and clinical record stay apart (D-5).</b> A row stores parameter values
 * keyed by {@code param_key} and nothing else from the catalogue — no display name, no unit, no
 * category. Labels are resolved at read time, so disabling or renaming a parameter changes what
 * the chart is captioned with and never what it recorded.
 *
 * <p><b>Disabled parameters are refused on the way in, preserved on the way out.</b> A value for a
 * parameter the hospital has switched off is dropped before persisting; values recorded while it
 * was on stay readable forever.
 *
 * <p><b>No clinical calculation.</b> No P/F ratio, no compliance, no ventilator-day arithmetic, no
 * threshold, no alert. {@code ventilationStatus} is always recorded by a person and never inferred
 * from whether values are present.
 *
 * <p><b>Transactions.</b> Recording a ventilator setting is not a movement. This service never
 * joins the IPD movement transaction and never calls {@code IcuStayService}'s MANDATORY methods,
 * so a failed write cannot roll back an admission, a bed move or an ICU stay.
 */
@Service
public class IcuVentilatorService {

    private static final Logger logger = LoggerFactory.getLogger(IcuVentilatorService.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The window vitals, I/O and infusions use, so amendment rules stay consistent. */
    private static final Duration EDIT_WINDOW = Duration.ofHours(12);

    private static final String FORM_KEY = "VENTILATOR";

    @Autowired private IcuVentilatorSettingRepository settingRepository;
    @Autowired private IpdAdmissionRepository ipdAdmissionRepository;
    @Autowired private IcuStayRepository icuStayRepository;
    @Autowired private VentilatorParameterService parameterService;
    @Autowired private SecurityContextHelper securityHelper;
    @Autowired private NurseAccessGuard nurseAccessGuard;
    @Autowired private NurseWriteAccess nurseWriteAccess;
    @Autowired private PerformingNurseResolver performingNurseResolver;
    @Autowired private FormAccessService formAccessService;
    @Autowired private AuditLogService auditLogService;
    @Autowired private com.hms.service.RealtimeNotifier notifier;

    // ── writes ───────────────────────────────────────────────────────────────

    /** Records a snapshot. Every call appends; nothing earlier is rewritten. */
    @Transactional
    public IcuVentilatorSetting record(IcuVentilatorRequest req) {
        Long hospitalId = requireHospitalId();
        formAccessService.assertCanEdit(FORM_KEY);
        if (req.getIpdAdmissionId() == null) {
            throw new IllegalArgumentException("ipdAdmissionId is required");
        }
        IpdAdmission admission = requireAdmission(req.getIpdAdmissionId(), hospitalId);
        nurseWriteAccess.assertCanWriteFor(admission.getId());

        String status = requireStatus(req.getVentilationStatus());
        LocalDateTime at = observedOrNow(req);
        Map<String, Object> values = acceptableValues(hospitalId, req.getValues());

        IcuVentilatorSetting s = new IcuVentilatorSetting();
        s.setHospitalId(hospitalId);
        s.setIpdAdmissionId(admission.getId());
        s.setPatientId(admission.getPatientId());
        s.setIcuStayId(coveringStayId(admission.getId(), hospitalId, at));
        s.setVentilationStatus(status);
        s.setValuesJson(writeValues(values));
        s.setObservedAt(at);
        s.setRecordedByUserId(securityHelper.getCurrentUserId());
        s.setPerformedByNurseId(performingNurseResolver.resolve(req.getPerformedByNurseId()));
        s.setNote(req.getNote());
        s.setIsActive(true);
        IcuVentilatorSetting saved = settingRepository.save(s);

        audit("ICU_VENTILATOR_RECORDED", status + " with " + values.size() + " parameter(s)",
                hospitalId, admission.getId());
        notifier.refresh(hospitalId);
        return saved;
    }

    /**
     * Corrects a snapshot by appending a row that supersedes it. The original stays readable,
     * exactly as ICU-4 vitals, ICU-5 I/O and ICU-6 rates do.
     *
     * <p>Authorisation is the ICU-4 rule, unchanged: same recorder, same window.
     */
    @Transactional
    public IcuVentilatorSetting correct(String publicId, IcuVentilatorRequest req) {
        Long hospitalId = requireHospitalId();
        formAccessService.assertCanEdit(FORM_KEY);

        IcuVentilatorSetting original = requireSetting(publicId, hospitalId);
        assertMayAmend(original);
        nurseWriteAccess.assertCanWriteFor(original.getIpdAdmissionId());

        String status = requireStatus(req.getVentilationStatus() != null
                ? req.getVentilationStatus() : original.getVentilationStatus());
        // A correction restates the SAME moment unless the time itself is being corrected.
        LocalDateTime at = req.getObservedAt() != null
                ? req.getObservedAt() : original.getObservedAt();
        assertNotFuture(at);
        Map<String, Object> values = acceptableValues(hospitalId, req.getValues());

        IcuVentilatorSetting correction = new IcuVentilatorSetting();
        correction.setHospitalId(hospitalId);
        correction.setIpdAdmissionId(original.getIpdAdmissionId());
        correction.setPatientId(original.getPatientId());
        correction.setIcuStayId(coveringStayId(original.getIpdAdmissionId(), hospitalId, at));
        correction.setVentilationStatus(status);
        correction.setValuesJson(writeValues(values));
        correction.setObservedAt(at);
        correction.setRecordedByUserId(securityHelper.getCurrentUserId());
        correction.setPerformedByNurseId(
                performingNurseResolver.resolve(req.getPerformedByNurseId()));
        correction.setSupersedesSettingId(original.getId());
        correction.setNote(req.getNote() != null ? req.getNote() : original.getNote());
        correction.setIsActive(true);
        IcuVentilatorSetting saved = settingRepository.save(correction);

        audit("ICU_VENTILATOR_CORRECTED",
                "Corrected " + original.getPublicId() + " with " + saved.getPublicId(),
                hospitalId, original.getIpdAdmissionId());
        notifier.refresh(hospitalId);
        return saved;
    }

    // ── reads ────────────────────────────────────────────────────────────────

    /** Full history, newest first, INCLUDING rows a correction superseded. */
    public List<IcuVentilatorSetting> getByAdmission(Long ipdAdmissionId) {
        Long hospitalId = requireHospitalId();
        IpdAdmission admission = requireAdmission(ipdAdmissionId, hospitalId);
        if ("NURSE".equals(securityHelper.getCurrentUserRole())) {
            nurseAccessGuard.assertAssigned(admission.getId());
        }
        return settingRepository
                .findByIpdAdmissionIdAndHospitalIdAndIsActiveTrueOrderByObservedAtDescIdDesc(
                        ipdAdmissionId, hospitalId);
    }

    /**
     * The history plus everything needed to render it: the parsed values per row, and a label map
     * resolved from the CURRENT catalogue for every key any row holds.
     *
     * <p>The label map is why a disabled or renamed parameter's history still reads properly, and
     * why nothing about the catalogue has to be copied into a clinical row.
     */
    public Map<String, Object> chartFor(Long ipdAdmissionId) {
        Long hospitalId = requireHospitalId();
        List<IcuVentilatorSetting> rows = getByAdmission(ipdAdmissionId);

        Set<String> keys = new java.util.LinkedHashSet<>();
        List<Map<String, Object>> views = new ArrayList<>();
        for (IcuVentilatorSetting s : rows) {
            Map<String, Object> values = readValues(s.getValuesJson());
            keys.addAll(values.keySet());
            views.add(view(s, values));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entries", views);
        out.put("parameters", parameterService.labelMapFor(hospitalId, keys));
        out.put("supersededIds", supersededIds(ipdAdmissionId));
        return out;
    }

    /** The setting in force now, or null when nothing was ever recorded. */
    public IcuVentilatorSetting current(Long ipdAdmissionId) {
        return settingAt(ipdAdmissionId, LocalDateTime.now());
    }

    /**
     * The setting in force at {@code at}: the latest non-superseded row observed at or before it.
     * Null before the first recording.
     */
    public IcuVentilatorSetting settingAt(Long ipdAdmissionId, LocalDateTime at) {
        Long hospitalId = requireHospitalId();
        Set<Long> superseded = new HashSet<>(
                settingRepository.findSupersededIds(ipdAdmissionId, hospitalId));
        // Newest first, so the first row at or before `at` is the one in force.
        for (IcuVentilatorSetting s : getByAdmission(ipdAdmissionId)) {
            if (superseded.contains(s.getId())) continue;
            if (!s.getObservedAt().isAfter(at)) return s;
        }
        return null;
    }

    /** Ids replaced by a correction, so the UI can strike them through. */
    public List<Long> supersededIds(Long ipdAdmissionId) {
        Long hospitalId = requireHospitalId();
        requireAdmission(ipdAdmissionId, hospitalId);
        return settingRepository.findSupersededIds(ipdAdmissionId, hospitalId);
    }

    /** Parsed values of one row, for callers that already hold the entity. */
    public Map<String, Object> valuesOf(IcuVentilatorSetting s) {
        return readValues(s.getValuesJson());
    }

    // ── validation ───────────────────────────────────────────────────────────

    /**
     * Keeps only what this hospital may chart right now, and validates each value against its
     * declared type.
     *
     * <p>A disabled key and an unknown key are both DROPPED rather than rejected: the catalogue can
     * change between the form loading and the nurse pressing save, and failing the whole snapshot
     * would lose the values that are still valid. A value of the wrong TYPE is a different matter
     * and is rejected — it is a malformed entry, not a stale one.
     */
    private Map<String, Object> acceptableValues(Long hospitalId, Map<String, Object> submitted) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (submitted == null || submitted.isEmpty()) return out;

        Set<String> enabled = parameterService.enabledKeys(hospitalId);
        for (Map.Entry<String, Object> e : submitted.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            if (key == null || value == null) continue;
            if (value instanceof String str && str.isBlank()) continue;
            if (!enabled.contains(key)) continue; // disabled or undefined — dropped, not stored

            String valueType = parameterService.definitionOf(hospitalId, key)
                    .map(d -> (String) d.get("valueType"))
                    .orElse(IcuVentilatorParameter.TEXT);
            out.put(key, coerce(key, value, valueType));
        }
        return out;
    }

    /**
     * Structural validation only. A number must be a number and a mode must be a known mode;
     * whether a value is clinically sensible is a judgement ICU records rather than makes, so
     * there is no range, no maximum and no appropriateness check anywhere here.
     */
    private Object coerce(String key, Object value, String valueType) {
        if (IcuVentilatorParameter.MODE.equals(valueType)) {
            String mode = String.valueOf(value);
            if (!VentilatorModeRegistry.isValid(mode)) {
                throw new IllegalArgumentException("Unknown ventilator mode: " + mode);
            }
            return mode;
        }
        if (IcuVentilatorParameter.NUMBER.equals(valueType)) {
            if (value instanceof Number n) return n;
            try {
                return new java.math.BigDecimal(String.valueOf(value).trim());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException(key + " must be a number");
            }
        }
        return String.valueOf(value);
    }

    private String requireStatus(String status) {
        // Mandatory by decision (D-1) and never inferred: an empty value map does not mean OFF,
        // it means a nurse charted a status without settings.
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("Ventilation status is required");
        }
        if (!IcuVentilatorSetting.isValidStatus(status)) {
            throw new IllegalArgumentException("Unknown ventilation status: " + status);
        }
        return status;
    }

    private LocalDateTime observedOrNow(IcuVentilatorRequest req) {
        LocalDateTime at = req.getObservedAt() != null ? req.getObservedAt() : LocalDateTime.now();
        assertNotFuture(at);
        return at;
    }

    private void assertNotFuture(LocalDateTime at) {
        if (at.isAfter(LocalDateTime.now().plusMinutes(1))) {
            throw new IllegalArgumentException("Recorded time cannot be in the future");
        }
    }

    /** The ICU-4 amendment rules, unchanged: only the recorder, only inside the window. */
    private void assertMayAmend(IcuVentilatorSetting s) {
        if (s.getRecordedByUserId() == null
                || !s.getRecordedByUserId().equals(securityHelper.getCurrentUserId())) {
            throw new AccessDeniedException(
                    "Only the person who recorded this entry can correct it");
        }
        if (Duration.between(s.getCreatedAt().atZone(java.time.ZoneId.systemDefault()),
                java.time.ZonedDateTime.now()).compareTo(EDIT_WINDOW) > 0) {
            throw new IllegalArgumentException("Edit window has passed for this entry");
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Read-only use of the ICU-3 stay record; the lifecycle is never touched from here (D-2). */
    private Long coveringStayId(Long ipdAdmissionId, Long hospitalId, LocalDateTime at) {
        List<IcuStay> covering =
                icuStayRepository.findCoveringInstant(ipdAdmissionId, hospitalId, at);
        return covering.isEmpty() ? null : covering.get(0).getId();
    }

    private String writeValues(Map<String, Object> values) {
        if (values == null || values.isEmpty()) return null;
        try {
            return JSON.writeValueAsString(values);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not store the ventilator values");
        }
    }

    private Map<String, Object> readValues(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            return JSON.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (Exception e) {
            logger.warn("Unreadable ventilator values JSON: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private Map<String, Object> view(IcuVentilatorSetting s, Map<String, Object> values) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("publicId", s.getPublicId());
        m.put("ventilationStatus", s.getVentilationStatus());
        m.put("values", values);
        m.put("observedAt", s.getObservedAt());
        m.put("recordedByUserId", s.getRecordedByUserId());
        m.put("performedByNurseId", s.getPerformedByNurseId());
        m.put("supersedesSettingId", s.getSupersedesSettingId());
        m.put("icuStayId", s.getIcuStayId());
        m.put("note", s.getNote());
        m.put("createdAt", s.getCreatedAt());
        return m;
    }

    private IcuVentilatorSetting requireSetting(String publicId, Long hospitalId) {
        return settingRepository.findByPublicIdAndHospitalId(publicId, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Ventilator entry not found"));
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
        } catch (Exception e) {
            logger.warn("ICU ventilator audit failed: {}", e.getMessage());
        }
    }
}
