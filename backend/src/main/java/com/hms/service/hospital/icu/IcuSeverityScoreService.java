package com.hms.service.hospital.icu;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hms.dto.icu.IcuSeverityScoreRequest;
import com.hms.entity.IcuSeverityScore;
import com.hms.entity.IcuStay;
import com.hms.entity.IpdAdmission;
import com.hms.exception.ResourceNotFoundException;
import com.hms.exception.UnauthorizedException;
import com.hms.repository.IcuSeverityScoreRepository;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * IcuSeverityScoreService - timed severity scores and their history (ICU Phase 8).
 *
 * <p><b>This documents a score. It does not score the patient.</b> Nothing here reads a lab, a
 * vitals row, an infusion or a ventilator setting; nothing maps a creatinine to a renal subscore,
 * classifies a vasopressor, derives PaO2/FiO2 or estimates mortality. The clinician judges each
 * component and enters it.
 *
 * <p>The one arithmetic operation is summing components the clinician entered — the precedent
 * {@code VitalsService.gcsTotalOf} set for E+V+M and {@code RecoveryObservation} set for Aldrete.
 * Adding up given numbers is not interpretation; grading a raw measurement would be.
 *
 * <p><b>Recording appends, never edits.</b> The score in force at any instant is the latest
 * non-superseded row of that type at or before it, so "what was the SOFA on Monday?" stays
 * answerable after the patient has left the unit.
 *
 * <p><b>D-6: the total is stored, not recomputed.</b> Unlike ICU-5's fluid balance, a total is
 * part of what was charted at that moment — the number that went in the notes.
 *
 * <p><b>Transactions.</b> Recording a score is not a movement. This service never joins the IPD
 * movement transaction and never calls {@code IcuStayService}'s MANDATORY methods, so a failed
 * score write cannot roll back an admission, a bed move or an ICU stay.
 */
@Service
public class IcuSeverityScoreService {

    private static final Logger logger = LoggerFactory.getLogger(IcuSeverityScoreService.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The window vitals, I/O, infusions and ventilator use, so amendment rules stay consistent. */
    private static final Duration EDIT_WINDOW = Duration.ofHours(12);

    private static final String FORM_KEY = "SEVERITY_SCORE";

    @Autowired private IcuSeverityScoreRepository scoreRepository;
    @Autowired private IpdAdmissionRepository ipdAdmissionRepository;
    @Autowired private IcuStayRepository icuStayRepository;
    @Autowired private ScoreTypeSettingService scoreTypeSettingService;
    @Autowired private SecurityContextHelper securityHelper;
    @Autowired private NurseAccessGuard nurseAccessGuard;
    @Autowired private NurseWriteAccess nurseWriteAccess;
    @Autowired private PerformingNurseResolver performingNurseResolver;
    @Autowired private FormAccessService formAccessService;
    @Autowired private AuditLogService auditLogService;
    @Autowired private com.hms.service.RealtimeNotifier notifier;

    // ── writes ───────────────────────────────────────────────────────────────

    /** Records a scoring. Every call appends; nothing earlier is rewritten. */
    @Transactional
    public IcuSeverityScore record(IcuSeverityScoreRequest req) {
        Long hospitalId = requireHospitalId();
        formAccessService.assertCanEdit(FORM_KEY);
        if (req.getIpdAdmissionId() == null) {
            throw new IllegalArgumentException("ipdAdmissionId is required");
        }
        IpdAdmission admission = requireAdmission(req.getIpdAdmissionId(), hospitalId);
        nurseWriteAccess.assertCanWriteFor(admission.getId());

        SeverityScoreRegistry.ScoreType type = requireEnabledType(req.getScoreType(), hospitalId);
        LocalDateTime at = scoredOrNow(req);
        Map<String, Object> components = acceptableComponents(type, req.getComponents());
        Integer total = totalFor(type, components, req.getTotalScore());

        IcuSeverityScore s = new IcuSeverityScore();
        s.setHospitalId(hospitalId);
        s.setIpdAdmissionId(admission.getId());
        s.setPatientId(admission.getPatientId());
        s.setIcuStayId(coveringStayId(admission.getId(), hospitalId, at));
        s.setScoreType(type.key());
        s.setComponentsJson(writeComponents(components));
        s.setTotalScore(total);
        s.setScoredAt(at);
        s.setRecordedByUserId(securityHelper.getCurrentUserId());
        s.setPerformedByNurseId(performingNurseResolver.resolve(req.getPerformedByNurseId()));
        s.setNote(req.getNote());
        s.setIsActive(true);
        IcuSeverityScore saved = scoreRepository.save(s);

        audit("ICU_SEVERITY_SCORE_RECORDED", type.key() + " total " + total,
                hospitalId, admission.getId());
        notifier.refresh(hospitalId);
        return saved;
    }

    /**
     * Corrects a scoring by appending a row that supersedes it. The original stays readable,
     * exactly as ICU-4 vitals, ICU-5 I/O, ICU-6 rates and ICU-7 ventilator entries do.
     *
     * <p>Authorisation is the ICU-4 rule, unchanged: same recorder, same window.
     */
    @Transactional
    public IcuSeverityScore correct(String publicId, IcuSeverityScoreRequest req) {
        Long hospitalId = requireHospitalId();
        formAccessService.assertCanEdit(FORM_KEY);

        IcuSeverityScore original = requireScore(publicId, hospitalId);
        assertMayAmend(original);
        nurseWriteAccess.assertCanWriteFor(original.getIpdAdmissionId());

        // A correction restates the same scoring, so the type comes from the original: changing
        // SOFA into APACHE II would not be a correction, it would be a different observation.
        SeverityScoreRegistry.ScoreType type = SeverityScoreRegistry.find(original.getScoreType())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown score type: " + original.getScoreType()));
        LocalDateTime at = req.getScoredAt() != null ? req.getScoredAt() : original.getScoredAt();
        assertNotFuture(at);
        Map<String, Object> components = acceptableComponents(type, req.getComponents());
        Integer total = totalFor(type, components, req.getTotalScore());

        IcuSeverityScore correction = new IcuSeverityScore();
        correction.setHospitalId(hospitalId);
        correction.setIpdAdmissionId(original.getIpdAdmissionId());
        correction.setPatientId(original.getPatientId());
        correction.setIcuStayId(coveringStayId(original.getIpdAdmissionId(), hospitalId, at));
        correction.setScoreType(type.key());
        correction.setComponentsJson(writeComponents(components));
        correction.setTotalScore(total);
        correction.setScoredAt(at);
        correction.setRecordedByUserId(securityHelper.getCurrentUserId());
        correction.setPerformedByNurseId(
                performingNurseResolver.resolve(req.getPerformedByNurseId()));
        correction.setSupersedesScoreId(original.getId());
        correction.setNote(req.getNote() != null ? req.getNote() : original.getNote());
        correction.setIsActive(true);
        IcuSeverityScore saved = scoreRepository.save(correction);

        audit("ICU_SEVERITY_SCORE_CORRECTED",
                "Corrected " + original.getPublicId() + " with " + saved.getPublicId(),
                hospitalId, original.getIpdAdmissionId());
        notifier.refresh(hospitalId);
        return saved;
    }

    // ── reads ────────────────────────────────────────────────────────────────

    /** Full history, newest first, INCLUDING rows a correction superseded. */
    public List<IcuSeverityScore> getByAdmission(Long ipdAdmissionId) {
        Long hospitalId = requireHospitalId();
        IpdAdmission admission = requireAdmission(ipdAdmissionId, hospitalId);
        if ("NURSE".equals(securityHelper.getCurrentUserRole())) {
            nurseAccessGuard.assertAssigned(admission.getId());
        }
        return scoreRepository
                .findByIpdAdmissionIdAndHospitalIdAndIsActiveTrueOrderByScoredAtDescIdDesc(
                        ipdAdmissionId, hospitalId);
    }

    /**
     * The history plus what is needed to render it: parsed components per row, the registry as it
     * stands, and the superseded ids.
     *
     * <p>The registry travels with the data so the panel never holds a component list of its own,
     * and a score recorded under a type the hospital has since disabled still renders with proper
     * labels.
     */
    public Map<String, Object> chartFor(Long ipdAdmissionId) {
        List<IcuSeverityScore> rows = getByAdmission(ipdAdmissionId);

        List<Map<String, Object>> views = new ArrayList<>();
        for (IcuSeverityScore s : rows) views.add(view(s, readComponents(s.getComponentsJson())));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entries", views);
        out.put("types", scoreTypeSettingService.list());
        out.put("supersededIds", supersededIds(ipdAdmissionId));
        out.put("latest", latestByType(ipdAdmissionId));
        return out;
    }

    /** The most recent non-superseded score of each type that has one. */
    public Map<String, Map<String, Object>> latestByType(Long ipdAdmissionId) {
        Long hospitalId = requireHospitalId();
        Set<Long> superseded = new HashSet<>(
                scoreRepository.findSupersededIds(ipdAdmissionId, hospitalId));
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        // Newest first, so the first surviving row per type is that type's latest.
        for (IcuSeverityScore s : getByAdmission(ipdAdmissionId)) {
            if (superseded.contains(s.getId())) continue;
            out.putIfAbsent(s.getScoreType(), view(s, readComponents(s.getComponentsJson())));
        }
        return out;
    }

    /**
     * The score of this type in force at {@code at}: the latest non-superseded row of that type
     * scored at or before it. Null before the first scoring.
     */
    public IcuSeverityScore scoreAt(Long ipdAdmissionId, String scoreType, LocalDateTime at) {
        Long hospitalId = requireHospitalId();
        requireAdmission(ipdAdmissionId, hospitalId);
        Set<Long> superseded = new HashSet<>(
                scoreRepository.findSupersededIds(ipdAdmissionId, hospitalId));
        for (IcuSeverityScore s : scoreRepository
                .findByIpdAdmissionIdAndHospitalIdAndScoreTypeAndIsActiveTrueOrderByScoredAtDescIdDesc(
                        ipdAdmissionId, hospitalId, scoreType)) {
            if (superseded.contains(s.getId())) continue;
            if (!s.getScoredAt().isAfter(at)) return s;
        }
        return null;
    }

    /** Ids replaced by a correction, so the UI can strike them through. */
    public List<Long> supersededIds(Long ipdAdmissionId) {
        Long hospitalId = requireHospitalId();
        requireAdmission(ipdAdmissionId, hospitalId);
        return scoreRepository.findSupersededIds(ipdAdmissionId, hospitalId);
    }

    /** Parsed components of one row, for callers that already hold the entity. */
    public Map<String, Object> componentsOf(IcuSeverityScore s) {
        return readComponents(s.getComponentsJson());
    }

    /** The enabled score types with their components and ranges, for the entry form. */
    public List<Map<String, Object>> enabledTypes() {
        return scoreTypeSettingService.enabledTypes();
    }

    // ── validation ───────────────────────────────────────────────────────────

    private SeverityScoreRegistry.ScoreType requireEnabledType(String scoreType, Long hospitalId) {
        SeverityScoreRegistry.ScoreType type = SeverityScoreRegistry.find(scoreType)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown severity score: " + scoreType));
        if (!scoreTypeSettingService.isEnabled(hospitalId, type.key())) {
            throw new IllegalArgumentException(
                    type.label() + " is not enabled for this hospital");
        }
        return type;
    }

    /**
     * Keeps only components this score type defines, each inside its declared range.
     *
     * <p>An unknown key is DROPPED rather than rejected, matching ICU-7: a form can be a moment
     * out of date, and failing the whole scoring would lose the components that are still valid.
     * A value outside its range is a different matter and is rejected — that is a malformed entry,
     * not a stale one, and it is a bound on an input field rather than a clinical judgement.
     */
    private Map<String, Object> acceptableComponents(SeverityScoreRegistry.ScoreType type,
                                                     Map<String, Object> submitted) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (type.isTotalOnly() || submitted == null || submitted.isEmpty()) return out;

        for (Map.Entry<String, Object> e : submitted.entrySet()) {
            String key = e.getKey();
            Object raw = e.getValue();
            if (key == null || raw == null) continue;
            if (raw instanceof String str && str.isBlank()) continue;

            SeverityScoreRegistry.Component c = type.component(key).orElse(null);
            if (c == null) continue; // not part of this score — dropped, not stored

            int value;
            try {
                value = Integer.parseInt(String.valueOf(raw).trim());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException(c.label() + " must be a whole number");
            }
            if (value < c.min() || value > c.max()) {
                throw new IllegalArgumentException(
                        c.label() + " must be between " + c.min() + " and " + c.max());
            }
            out.put(key, value);
        }
        return out;
    }

    /**
     * The total: the sum of the entered components, or the figure the clinician entered for a
     * total-only score.
     *
     * <p>Summing given numbers, and nothing more. There is no weighting, no band and no second
     * source — for a component score the submitted total is deliberately ignored so a typed total
     * can never disagree with the parts it is supposed to be made of.
     */
    private Integer totalFor(SeverityScoreRegistry.ScoreType type, Map<String, Object> components,
                             Integer submittedTotal) {
        if (type.isTotalOnly()) {
            if (submittedTotal == null) {
                throw new IllegalArgumentException(type.label() + " total score is required");
            }
            assertTotalInRange(type, submittedTotal);
            return submittedTotal;
        }
        if (components.isEmpty()) {
            throw new IllegalArgumentException("Enter at least one " + type.label() + " component");
        }
        int sum = 0;
        for (Object v : components.values()) sum += ((Number) v).intValue();
        assertTotalInRange(type, sum);
        return sum;
    }

    private void assertTotalInRange(SeverityScoreRegistry.ScoreType type, int total) {
        if (total < type.totalMin() || total > type.totalMax()) {
            throw new IllegalArgumentException(type.label() + " total must be between "
                    + type.totalMin() + " and " + type.totalMax());
        }
    }

    private LocalDateTime scoredOrNow(IcuSeverityScoreRequest req) {
        LocalDateTime at = req.getScoredAt() != null ? req.getScoredAt() : LocalDateTime.now();
        assertNotFuture(at);
        return at;
    }

    private void assertNotFuture(LocalDateTime at) {
        if (at.isAfter(LocalDateTime.now().plusMinutes(1))) {
            throw new IllegalArgumentException("Recorded time cannot be in the future");
        }
    }

    /** The ICU-4 amendment rules, unchanged: only the recorder, only inside the window. */
    private void assertMayAmend(IcuSeverityScore s) {
        if (s.getRecordedByUserId() == null
                || !s.getRecordedByUserId().equals(securityHelper.getCurrentUserId())) {
            throw new AccessDeniedException(
                    "Only the person who recorded this score can correct it");
        }
        if (Duration.between(s.getCreatedAt().atZone(java.time.ZoneId.systemDefault()),
                java.time.ZonedDateTime.now()).compareTo(EDIT_WINDOW) > 0) {
            throw new IllegalArgumentException("Edit window has passed for this score");
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Read-only use of the ICU-3 stay record; the lifecycle is never touched from here. */
    private Long coveringStayId(Long ipdAdmissionId, Long hospitalId, LocalDateTime at) {
        List<IcuStay> covering =
                icuStayRepository.findCoveringInstant(ipdAdmissionId, hospitalId, at);
        return covering.isEmpty() ? null : covering.get(0).getId();
    }

    private String writeComponents(Map<String, Object> components) {
        if (components == null || components.isEmpty()) return null;
        try {
            return JSON.writeValueAsString(components);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not store the score components");
        }
    }

    private Map<String, Object> readComponents(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            return JSON.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (Exception e) {
            logger.warn("Unreadable severity score components JSON: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private Map<String, Object> view(IcuSeverityScore s, Map<String, Object> components) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("publicId", s.getPublicId());
        m.put("scoreType", s.getScoreType());
        m.put("components", components);
        m.put("totalScore", s.getTotalScore());
        m.put("scoredAt", s.getScoredAt());
        m.put("recordedByUserId", s.getRecordedByUserId());
        m.put("performedByNurseId", s.getPerformedByNurseId());
        m.put("supersedesScoreId", s.getSupersedesScoreId());
        m.put("icuStayId", s.getIcuStayId());
        m.put("note", s.getNote());
        m.put("createdAt", s.getCreatedAt());
        return m;
    }

    private IcuSeverityScore requireScore(String publicId, Long hospitalId) {
        return scoreRepository.findByPublicIdAndHospitalId(publicId, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Severity score not found"));
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
            logger.warn("ICU severity score audit failed: {}", e.getMessage());
        }
    }
}
