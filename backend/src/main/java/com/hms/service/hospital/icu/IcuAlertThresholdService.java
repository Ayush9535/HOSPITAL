package com.hms.service.hospital.icu;

import com.hms.dto.icu.IcuAlertThresholdRequest;
import com.hms.entity.IcuAlertThreshold;
import com.hms.exception.ResourceNotFoundException;
import com.hms.exception.UnauthorizedException;
import com.hms.repository.IcuAlertThresholdRepository;
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

/**
 * IcuAlertThresholdService - the per-hospital alert thresholds (ICU Phase 9).
 *
 * <p><b>No lazy default.</b> Every other config table in this module treats "no row" as enabled;
 * here it means <i>no alert</i>. The difference is deliberate: a default threshold would be the
 * system deciding what a normal MAP is, and ICU records values rather than judging them.
 *
 * <p>Admin-only writes, hospital-scoped reads, foreign tenant 404 — the ICU-7/ICU-8 config
 * pattern, minus the parts a threshold does not need.
 */
@Service
public class IcuAlertThresholdService {

    private static final Logger logger = LoggerFactory.getLogger(IcuAlertThresholdService.class);

    @Autowired private IcuAlertThresholdRepository repository;
    @Autowired private SecurityContextHelper securityHelper;
    @Autowired private AuditLogService auditLogService;
    @Autowired private com.hms.service.RealtimeNotifier notifier;

    // ── reads ────────────────────────────────────────────────────────────────

    /** Every registry metric with its configured bounds, or nulls where none is set. */
    public List<Map<String, Object>> list() {
        Long hospitalId = requireHospitalId();
        Map<String, IcuAlertThreshold> rows = rowsByKey(hospitalId);

        List<Map<String, Object>> out = new ArrayList<>();
        for (AlertMetricRegistry.Metric m : AlertMetricRegistry.METRICS) {
            IcuAlertThreshold r = rows.get(m.key());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("source", AlertMetricRegistry.SOURCE_VITALS);
            item.put("key", m.key());
            item.put("label", m.label());
            item.put("unit", m.unit());
            item.put("minValue", r == null ? null : r.getMinValue());
            item.put("maxValue", r == null ? null : r.getMaxValue());
            // No row means no alert, so an unconfigured metric reads as off rather than on.
            item.put("enabled", r != null && Boolean.TRUE.equals(r.getEnabled()));
            item.put("configured", r != null);
            item.put("publicId", r == null ? null : r.getPublicId());
            out.add(item);
        }
        return out;
    }

    /** The thresholds that may actually fire right now, for the evaluator. */
    public List<IcuAlertThreshold> activeFor(Long hospitalId, String source) {
        return repository.findByHospitalIdAndSourceAndEnabledTrueAndIsActiveTrue(hospitalId, source);
    }

    // ── write (admin only, enforced at the controller) ───────────────────────

    /**
     * Sets or updates the bounds for one metric. Creates the row on first use — the table starts
     * empty precisely because nothing is configured until someone configures it.
     */
    @Transactional
    public IcuAlertThreshold upsert(String metricKey, IcuAlertThresholdRequest req) {
        Long hospitalId = requireHospitalId();
        AlertMetricRegistry.Metric metric = AlertMetricRegistry
                .find(AlertMetricRegistry.SOURCE_VITALS, metricKey)
                .orElseThrow(() -> new ResourceNotFoundException("Alert metric not found"));

        boolean enabled = req.getEnabled() == null || req.getEnabled();
        // A threshold with no bound at all can never fire, so it is a mistake rather than a
        // configuration. Turning the metric off is how you stop alerting on it.
        if (enabled && req.getMinValue() == null && req.getMaxValue() == null) {
            throw new IllegalArgumentException("Set a minimum, a maximum, or both");
        }
        if (req.getMinValue() != null && req.getMaxValue() != null
                && req.getMinValue().compareTo(req.getMaxValue()) > 0) {
            throw new IllegalArgumentException("Minimum cannot be greater than maximum");
        }

        IcuAlertThreshold row = repository
                .findByHospitalIdAndSourceAndMetricKey(
                        hospitalId, AlertMetricRegistry.SOURCE_VITALS, metricKey)
                .orElseGet(() -> {
                    IcuAlertThreshold fresh = new IcuAlertThreshold();
                    fresh.setHospitalId(hospitalId);
                    fresh.setSource(AlertMetricRegistry.SOURCE_VITALS);
                    fresh.setMetricKey(metric.key());
                    return fresh;
                });
        row.setMinValue(req.getMinValue());
        row.setMaxValue(req.getMaxValue());
        row.setEnabled(enabled);
        row.setUpdatedByUserId(securityHelper.getCurrentUserId());
        row.setIsActive(true);

        IcuAlertThreshold saved = repository.save(row);
        audit("ICU_ALERT_THRESHOLD_SET",
                metric.key() + " min=" + saved.getMinValue() + " max=" + saved.getMaxValue()
                        + " enabled=" + saved.getEnabled(),
                hospitalId, saved.getId());
        notifier.refresh(hospitalId);
        return saved;
    }

    // There is deliberately no delete: disabling a threshold keeps the numbers a hospital chose,
    // so turning it back on does not mean typing them again.

    // ── helpers ──────────────────────────────────────────────────────────────

    private Map<String, IcuAlertThreshold> rowsByKey(Long hospitalId) {
        Map<String, IcuAlertThreshold> byKey = new HashMap<>();
        for (IcuAlertThreshold r : repository.findByHospitalIdAndIsActiveTrue(hospitalId)) {
            if (AlertMetricRegistry.SOURCE_VITALS.equals(r.getSource())) {
                byKey.put(r.getMetricKey(), r);
            }
        }
        return byKey;
    }

    private Long requireHospitalId() {
        Long h = securityHelper.getCurrentHospitalId();
        if (h == null) throw new UnauthorizedException("Hospital ID not found in context");
        return h;
    }

    private void audit(String action, String details, Long hospitalId, Long id) {
        try {
            auditLogService.logAction(action, details, securityHelper.getCurrentUserEmail(),
                    hospitalId, "ICU_ALERT_THRESHOLD", String.valueOf(id), null);
        } catch (Exception e) {
            logger.warn("ICU alert threshold audit failed: {}", e.getMessage());
        }
    }
}
