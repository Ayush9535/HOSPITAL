package com.hms.service.hospital;

import com.hms.dto.VitalSettingRequest;
import com.hms.entity.HospitalVital;
import com.hms.exception.UnauthorizedException;
import com.hms.repository.HospitalVitalRepository;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * VitalSettingsService - per-hospital OPD vitals config (built-in overrides +
 * hospital-defined custom vitals). A built-in with no row is enabled by default,
 * so hospital_vitals only stores overrides and custom definitions.
 */
@Service
public class VitalSettingsService {
    @Autowired private HospitalVitalRepository repository;
    @Autowired private SecurityContextHelper securityHelper;
    @Autowired private AuditLogService auditLogService;
    @Autowired private com.hms.security.HospitalWebSocketHandler webSocketHandler;

    /** Every vital (built-ins first, then customs) with its effective enabled flag. */
    public List<Map<String, Object>> list() {
        return listFor(requireHospitalId());
    }

    /** Enabled vitals for an explicit hospital — used by case-paper PDF generation. */
    public List<Map<String, Object>> enabledVitalsFor(Long hospitalId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> v : listFor(hospitalId)) {
            if (Boolean.TRUE.equals(v.get("enabled"))) out.add(v);
        }
        return out;
    }

    private List<Map<String, Object>> listFor(Long hospitalId) {
        Map<String, HospitalVital> byKey = rowsByKey(hospitalId);

        List<Map<String, Object>> out = new ArrayList<>();
        for (VitalRegistry.Vital v : VitalRegistry.BUILT_INS) {
            HospitalVital r = byKey.get(v.key());
            out.add(view(v.key(), v.label(), v.unit(), v.type(), false,
                    r == null || Boolean.TRUE.equals(r.getEnabled()), r == null ? null : r.getPublicId()));
        }
        byKey.values().stream()
                .filter(r -> Boolean.TRUE.equals(r.getIsCustom()))
                .sorted(Comparator.comparing(r -> r.getSortOrder() == null ? 0 : r.getSortOrder()))
                .forEach(r -> out.add(view(r.getVitalKey(), r.getLabel(), r.getUnit(), "TEXT", true,
                        Boolean.TRUE.equals(r.getEnabled()), r.getPublicId())));
        return out;
    }

    /** Only the enabled vitals — drives the OPD form, consultation strip and case paper. */
    public List<Map<String, Object>> enabledVitals() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> v : list()) {
            if (Boolean.TRUE.equals(v.get("enabled"))) out.add(v);
        }
        return out;
    }

    /** Keys of the built-in vitals that are currently enabled (used to skip validation). */
    public java.util.Set<String> enabledBuiltInKeys() {
        Long hospitalId = requireHospitalId();
        Map<String, HospitalVital> byKey = rowsByKey(hospitalId);
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (VitalRegistry.Vital v : VitalRegistry.BUILT_INS) {
            HospitalVital r = byKey.get(v.key());
            if (r == null || Boolean.TRUE.equals(r.getEnabled())) keys.add(v.key());
        }
        return keys;
    }

    /** Keys of the enabled custom vitals (used to filter the submitted custom map). */
    public java.util.Set<String> enabledCustomKeys() {
        Long hospitalId = requireHospitalId();
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (HospitalVital r : repository.findByHospitalId(hospitalId)) {
            if (Boolean.TRUE.equals(r.getIsCustom()) && Boolean.TRUE.equals(r.getEnabled())) keys.add(r.getVitalKey());
        }
        return keys;
    }

    @Transactional
    public HospitalVital toggle(String vitalKey, VitalSettingRequest req) {
        Long hospitalId = requireHospitalId();
        boolean enabled = req.getEnabled() == null || req.getEnabled();
        HospitalVital row = repository.findByHospitalIdAndVitalKey(hospitalId, vitalKey).orElse(null);

        if (row == null) {
            if (!VitalRegistry.isBuiltIn(vitalKey)) throw new IllegalArgumentException("Unknown vital: " + vitalKey);
            VitalRegistry.Vital v = VitalRegistry.BUILT_INS.stream()
                    .filter(b -> b.key().equals(vitalKey)).findFirst().orElseThrow();
            row = new HospitalVital();
            row.setHospitalId(hospitalId);
            row.setVitalKey(v.key());
            row.setLabel(v.label());
            row.setUnit(v.unit());
            row.setIsCustom(false);
        }
        row.setEnabled(enabled);
        HospitalVital saved = repository.save(row);
        audit("VITAL_TOGGLED", vitalKey + " enabled=" + enabled, hospitalId, saved.getId());
        broadcastRefresh(hospitalId);
        return saved;
    }

    @Transactional
    public HospitalVital addCustom(VitalSettingRequest req) {
        Long hospitalId = requireHospitalId();
        if (req.getName() == null || req.getName().trim().isEmpty()) {
            throw new IllegalArgumentException("Vital name is required");
        }
        String key = VitalRegistry.toKey(req.getName());
        if (key.isEmpty()) throw new IllegalArgumentException("Vital name is required");
        if (VitalRegistry.isBuiltIn(key)) throw new IllegalArgumentException("A built-in vital with that name already exists");
        if (repository.findByHospitalIdAndVitalKey(hospitalId, key).isPresent()) {
            throw new IllegalArgumentException("That vital already exists");
        }

        int nextOrder = repository.findByHospitalId(hospitalId).stream()
                .filter(r -> Boolean.TRUE.equals(r.getIsCustom()))
                .mapToInt(r -> r.getSortOrder() == null ? 0 : r.getSortOrder())
                .max().orElse(0) + 1;

        HospitalVital v = new HospitalVital();
        v.setHospitalId(hospitalId);
        v.setVitalKey(key);
        v.setLabel(req.getName().trim());
        v.setUnit(req.getUnit() == null ? null : req.getUnit().trim());
        v.setEnabled(true);
        v.setIsCustom(true);
        v.setSortOrder(nextOrder);
        HospitalVital saved = repository.save(v);
        audit("VITAL_ADDED", saved.getLabel() + " (" + key + ")", hospitalId, saved.getId());
        broadcastRefresh(hospitalId);
        return saved;
    }

    /** Deletes the definition only; values already recorded on past OPDs are left alone. */
    @Transactional
    public void deleteCustom(String publicId) {
        Long hospitalId = requireHospitalId();
        HospitalVital v = repository.findByPublicId(publicId)
                .orElseThrow(() -> new IllegalArgumentException("Vital not found"));
        if (!hospitalId.equals(v.getHospitalId())) throw new UnauthorizedException("Belongs to another hospital");
        if (!Boolean.TRUE.equals(v.getIsCustom())) {
            throw new IllegalArgumentException("Built-in vitals cannot be deleted — turn them off instead");
        }
        repository.delete(v);
        audit("VITAL_DELETED", v.getLabel() + " (" + v.getVitalKey() + ")", hospitalId, v.getId());
        broadcastRefresh(hospitalId);
    }

    private Map<String, HospitalVital> rowsByKey(Long hospitalId) {
        Map<String, HospitalVital> byKey = new HashMap<>();
        for (HospitalVital r : repository.findByHospitalId(hospitalId)) byKey.put(r.getVitalKey(), r);
        return byKey;
    }

    private Map<String, Object> view(String key, String label, String unit, String type,
                                     boolean isCustom, boolean enabled, String publicId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("unit", unit);
        m.put("type", type);
        m.put("isCustom", isCustom);
        m.put("enabled", enabled);
        m.put("publicId", publicId);
        return m;
    }

    private Long requireHospitalId() {
        Long h = securityHelper.getCurrentHospitalId();
        if (h == null) throw new UnauthorizedException("Hospital ID not found");
        return h;
    }

    private void audit(String a, String d, Long h, Long id) {
        try {
            auditLogService.logAction(a, d, securityHelper.getCurrentUserEmail(), h, "HOSPITAL_VITAL", String.valueOf(id), null);
        } catch (Exception e) { /* best-effort */ }
    }

    private void broadcastRefresh(Long hospitalId) {
        try { webSocketHandler.broadcast(hospitalId, "{\"type\":\"REFRESH_DATA\"}"); } catch (Exception e) { /* best-effort */ }
    }
}
