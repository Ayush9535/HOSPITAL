package com.hms.service.hospital.icu;

import com.hms.dto.icu.IcuScoreTypeSettingRequest;
import com.hms.entity.IcuScoreTypeSetting;
import com.hms.exception.ResourceNotFoundException;
import com.hms.exception.UnauthorizedException;
import com.hms.repository.IcuScoreTypeSettingRepository;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ScoreTypeSettingService - which severity scores a hospital uses (ICU Phase 8, D-2).
 *
 * <p>Deliberately the thinnest configuration service in the module. A hospital chooses whether it
 * runs SOFA and APACHE II, and that is all: the components, their labels and their ranges come
 * from {@link SeverityScoreRegistry} and are not editable, because a renamed SOFA component is no
 * longer comparable to anyone else's SOFA.
 *
 * <p>Lazy default, as everywhere in this codebase: a registry type with no row is enabled, so the
 * table holds overrides only and nothing needs seeding.
 *
 * <p><b>Configuration is not clinical history.</b> Nothing here writes to
 * {@code icu_severity_score}. Disabling a score stops it being recorded next; every score already
 * recorded stays exactly as it was and stays readable.
 */
@Service
public class ScoreTypeSettingService {

    private static final Logger logger = LoggerFactory.getLogger(ScoreTypeSettingService.class);

    @Autowired private IcuScoreTypeSettingRepository repository;
    @Autowired private SecurityContextHelper securityHelper;
    @Autowired private AuditLogService auditLogService;
    @Autowired private com.hms.service.RealtimeNotifier notifier;

    // ── reads ────────────────────────────────────────────────────────────────

    /** Every registry type with its effective enabled flag, components and ranges. */
    public List<Map<String, Object>> list() {
        return listFor(requireHospitalId());
    }

    /** Only what may be recorded now. Drives the entry form and the server-side write filter. */
    public List<Map<String, Object>> enabledTypes() {
        return list().stream()
                .filter(t -> Boolean.TRUE.equals(t.get("enabled")))
                .collect(Collectors.toList());
    }

    public Set<String> enabledTypeKeys(Long hospitalId) {
        return listFor(hospitalId).stream()
                .filter(t -> Boolean.TRUE.equals(t.get("enabled")))
                .map(t -> (String) t.get("key"))
                .collect(Collectors.toSet());
    }

    public boolean isEnabled(Long hospitalId, String scoreType) {
        return enabledTypeKeys(hospitalId).contains(scoreType);
    }

    private List<Map<String, Object>> listFor(Long hospitalId) {
        Map<String, IcuScoreTypeSetting> rows = new HashMap<>();
        for (IcuScoreTypeSetting r : repository.findByHospitalId(hospitalId)) {
            rows.put(r.getScoreType(), r);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (SeverityScoreRegistry.ScoreType t : SeverityScoreRegistry.TYPES) {
            IcuScoreTypeSetting r = rows.get(t.key());
            out.add(describe(t, r == null || Boolean.TRUE.equals(r.getEnabled()),
                    r == null ? null : r.getPublicId()));
        }
        return out;
    }

    // ── write (admin only, enforced at the controller) ───────────────────────

    @Transactional
    public IcuScoreTypeSetting toggle(String scoreType, IcuScoreTypeSettingRequest req) {
        Long hospitalId = requireHospitalId();
        SeverityScoreRegistry.ScoreType type = SeverityScoreRegistry.find(scoreType)
                .orElseThrow(() -> new ResourceNotFoundException("Severity score not found"));

        IcuScoreTypeSetting row = repository.findByHospitalIdAndScoreType(hospitalId, type.key())
                .orElseGet(() -> {
                    // First override: materialise the row, since the default is "no row at all".
                    IcuScoreTypeSetting fresh = new IcuScoreTypeSetting();
                    fresh.setHospitalId(hospitalId);
                    fresh.setScoreType(type.key());
                    return fresh;
                });
        row.setEnabled(req.getEnabled() == null || req.getEnabled());

        IcuScoreTypeSetting saved = repository.save(row);
        audit("ICU_SCORE_TYPE_TOGGLED", type.key() + " enabled=" + saved.getEnabled(),
                hospitalId, saved.getId());
        notifier.refresh(hospitalId);
        return saved;
    }

    // There is deliberately no add and no delete: a hospital cannot invent a severity score.

    // ── helpers ──────────────────────────────────────────────────────────────

    private Map<String, Object> describe(SeverityScoreRegistry.ScoreType t, boolean enabled,
                                         String publicId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", t.key());
        m.put("label", t.label());
        m.put("totalOnly", t.isTotalOnly());
        m.put("totalMin", t.totalMin());
        m.put("totalMax", t.totalMax());
        m.put("components", t.components().stream().map(c -> {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("key", c.key());
            cm.put("label", c.label());
            cm.put("min", c.min());
            cm.put("max", c.max());
            return cm;
        }).collect(Collectors.toList()));
        m.put("enabled", enabled);
        m.put("publicId", publicId);
        return m;
    }

    private Long requireHospitalId() {
        Long h = securityHelper.getCurrentHospitalId();
        if (h == null) throw new UnauthorizedException("Hospital ID not found in context");
        return h;
    }

    private void audit(String action, String details, Long hospitalId, Long id) {
        try {
            auditLogService.logAction(action, details, securityHelper.getCurrentUserEmail(),
                    hospitalId, "ICU_SCORE_TYPE", String.valueOf(id), null);
        } catch (Exception e) {
            logger.warn("ICU score type audit failed: {}", e.getMessage());
        }
    }
}
