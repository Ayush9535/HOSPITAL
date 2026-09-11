package com.hms.service.hospital.icu;

import com.hms.dto.icu.IcuVentilatorParameterRequest;
import com.hms.entity.IcuVentilatorParameter;
import com.hms.exception.ResourceNotFoundException;
import com.hms.exception.UnauthorizedException;
import com.hms.repository.IcuVentilatorParameterRepository;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * VentilatorParameterService - the per-hospital ventilator parameter catalogue (ICU Phase 7, D-5).
 *
 * <p>Follows the OPD vitals configuration pattern: the table holds overrides and custom
 * definitions only, and a built-in with no row is enabled. No seeding, and no migration when the
 * registry grows.
 *
 * <p><b>Configuration is not clinical history.</b> Nothing in this service writes to
 * {@code icu_ventilator_setting}. Disabling, renaming, re-uniting or re-categorising a parameter
 * changes what may be charted next and nothing that was charted before — the clinical row stores
 * {@code param_key}, and that is the one field here that never changes.
 *
 * <p>Two deviations in the vitals implementation are deliberately not copied: a foreign tenant
 * gets 404 rather than 401, and refresh goes through {@code RealtimeNotifier} rather than calling
 * the WebSocket handler directly.
 */
@Service
public class VentilatorParameterService {

    private static final Logger logger = LoggerFactory.getLogger(VentilatorParameterService.class);

    @Autowired private IcuVentilatorParameterRepository repository;
    @Autowired private SecurityContextHelper securityHelper;
    @Autowired private AuditLogService auditLogService;
    @Autowired private com.hms.service.RealtimeNotifier notifier;

    // ── reads ────────────────────────────────────────────────────────────────

    /** Every parameter, built-ins first, each with its effective enabled flag. */
    public List<Map<String, Object>> list() {
        return listFor(requireHospitalId());
    }

    /** Only what may be charted now. Drives the entry form and the server-side write filter. */
    public List<Map<String, Object>> enabledParameters() {
        return list().stream()
                .filter(p -> Boolean.TRUE.equals(p.get("enabled")))
                .collect(Collectors.toList());
    }

    /** Keys that may be charted right now, for the write-path filter. */
    public Set<String> enabledKeys(Long hospitalId) {
        return listFor(hospitalId).stream()
                .filter(p -> Boolean.TRUE.equals(p.get("enabled")))
                .map(p -> (String) p.get("key"))
                .collect(Collectors.toSet());
    }

    /** The definition a key resolves to right now, or empty when nothing defines it. */
    public java.util.Optional<Map<String, Object>> definitionOf(Long hospitalId, String key) {
        return listFor(hospitalId).stream()
                .filter(p -> key.equals(p.get("key")))
                .findFirst();
    }

    /**
     * Labels for the keys a historical record holds, resolved at READ time.
     *
     * <p>Current config, then the registry, then the raw key. Never stored on the clinical row —
     * storing a label there would make configuration part of the observation, and a later rename
     * would leave two contradicting names for one recorded value.
     *
     * <p>Covers disabled parameters on purpose: a value charted while a parameter was on stays
     * readable, and named, after it is switched off.
     */
    public Map<String, Map<String, Object>> labelMapFor(Long hospitalId, Set<String> keys) {
        Map<String, IcuVentilatorParameter> rows = rowsByKey(hospitalId);
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (String key : keys) {
            IcuVentilatorParameter row = rows.get(key);
            if (row != null) {
                out.put(key, describe(row.getParamKey(), row.getDisplayName(), row.getUnit(),
                        row.getCategory(), row.getValueType(),
                        Boolean.TRUE.equals(row.getIsCustom()),
                        Boolean.TRUE.equals(row.getEnabled()), row.getPublicId()));
                continue;
            }
            java.util.Optional<VentilatorParameterRegistry.Parameter> builtIn =
                    VentilatorParameterRegistry.find(key);
            if (builtIn.isPresent()) {
                VentilatorParameterRegistry.Parameter b = builtIn.get();
                out.put(key, describe(b.key(), b.displayName(), b.unit(),
                        b.category(), b.valueType(), false, true, null));
            } else {
                // A key nothing defines any more: show it rather than hide the value.
                out.put(key, describe(key, key, null,
                        IcuVentilatorParameter.SETTING, IcuVentilatorParameter.TEXT,
                        true, false, null));
            }
        }
        return out;
    }

    private List<Map<String, Object>> listFor(Long hospitalId) {
        Map<String, IcuVentilatorParameter> rows = rowsByKey(hospitalId);
        List<Map<String, Object>> out = new ArrayList<>();

        for (VentilatorParameterRegistry.Parameter b : VentilatorParameterRegistry.BUILT_INS) {
            IcuVentilatorParameter r = rows.get(b.key());
            // Lazy default: no row means enabled, with the registry's own label and category.
            out.add(r == null
                    ? describe(b.key(), b.displayName(), b.unit(), b.category(), b.valueType(),
                            false, true, null)
                    : describe(r.getParamKey(), r.getDisplayName(), r.getUnit(), r.getCategory(),
                            r.getValueType(), false, Boolean.TRUE.equals(r.getEnabled()),
                            r.getPublicId()));
        }
        rows.values().stream()
                .filter(r -> Boolean.TRUE.equals(r.getIsCustom()))
                .sorted(Comparator.comparing(r -> r.getSortOrder() == null ? 0 : r.getSortOrder()))
                .forEach(r -> out.add(describe(r.getParamKey(), r.getDisplayName(), r.getUnit(),
                        r.getCategory(), r.getValueType(), true,
                        Boolean.TRUE.equals(r.getEnabled()), r.getPublicId())));
        return out;
    }

    // ── writes (admin only, enforced at the controller) ──────────────────────

    /**
     * Toggles and/or edits a parameter. {@code paramKey} is the address and is never rewritten —
     * that is what lets a display name change without breaking a single historical value.
     */
    @Transactional
    public IcuVentilatorParameter update(String paramKey, IcuVentilatorParameterRequest req) {
        Long hospitalId = requireHospitalId();
        IcuVentilatorParameter row = repository.findByHospitalIdAndParamKey(hospitalId, paramKey)
                .orElse(null);

        if (row == null) {
            // First override for a built-in: materialise it from the registry, then apply.
            // An unknown key 404s, and so does another tenant's custom parameter, because the
            // lookup above is already scoped to this hospital.
            VentilatorParameterRegistry.Parameter b = VentilatorParameterRegistry.find(paramKey)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Ventilator parameter not found"));
            row = new IcuVentilatorParameter();
            row.setHospitalId(hospitalId);
            row.setParamKey(b.key());
            row.setDisplayName(b.displayName());
            row.setUnit(b.unit());
            row.setCategory(b.category());
            row.setValueType(b.valueType());
            row.setIsCustom(false);
        }

        if (req.getEnabled() != null) row.setEnabled(req.getEnabled());
        if (req.getDisplayName() != null && !req.getDisplayName().isBlank()) {
            row.setDisplayName(req.getDisplayName().trim());
        }
        if (req.getUnit() != null) {
            row.setUnit(req.getUnit().isBlank() ? null : req.getUnit().trim());
        }
        if (req.getCategory() != null) {
            if (!VentilatorParameterRegistry.isValidCategory(req.getCategory())) {
                throw new IllegalArgumentException("Unknown category: " + req.getCategory());
            }
            row.setCategory(req.getCategory());
        }
        // valueType is deliberately not editable: changing how a value is validated once values
        // exist would leave stored values that the new type would have rejected.

        IcuVentilatorParameter saved = repository.save(row);
        audit("ICU_VENT_PARAM_UPDATED", paramKey + " enabled=" + saved.getEnabled(),
                hospitalId, saved.getId());
        notifier.refresh(hospitalId);
        return saved;
    }

    @Transactional
    public IcuVentilatorParameter addCustom(IcuVentilatorParameterRequest req) {
        Long hospitalId = requireHospitalId();
        if (req.getDisplayName() == null || req.getDisplayName().isBlank()) {
            throw new IllegalArgumentException("Parameter name is required");
        }
        // Derived ONCE, here. A later rename changes the label and leaves this alone.
        String key = VentilatorParameterRegistry.toKey(req.getDisplayName());
        if (key.isEmpty()) throw new IllegalArgumentException("Parameter name is required");
        if (VentilatorParameterRegistry.isBuiltIn(key)) {
            throw new IllegalArgumentException("A built-in parameter with that name already exists");
        }
        if (repository.findByHospitalIdAndParamKey(hospitalId, key).isPresent()) {
            throw new IllegalArgumentException("That parameter already exists");
        }
        String category = req.getCategory() == null
                ? IcuVentilatorParameter.SETTING : req.getCategory();
        if (!VentilatorParameterRegistry.isValidCategory(category)) {
            throw new IllegalArgumentException("Unknown category: " + category);
        }
        String valueType = req.getValueType() == null
                ? IcuVentilatorParameter.NUMBER : req.getValueType();
        if (!VentilatorParameterRegistry.isValidCustomValueType(valueType)) {
            // MODE is reserved for the built-in mode parameter so its values stay controlled.
            throw new IllegalArgumentException("Custom parameters must be NUMBER or TEXT");
        }

        int nextOrder = repository.findByHospitalId(hospitalId).stream()
                .filter(r -> Boolean.TRUE.equals(r.getIsCustom()))
                .mapToInt(r -> r.getSortOrder() == null ? 0 : r.getSortOrder())
                .max().orElse(0) + 1;

        IcuVentilatorParameter p = new IcuVentilatorParameter();
        p.setHospitalId(hospitalId);
        p.setParamKey(key);
        p.setDisplayName(req.getDisplayName().trim());
        p.setUnit(req.getUnit() == null || req.getUnit().isBlank() ? null : req.getUnit().trim());
        p.setCategory(category);
        p.setValueType(valueType);
        p.setEnabled(true);
        p.setIsCustom(true);
        p.setSortOrder(nextOrder);

        IcuVentilatorParameter saved = repository.save(p);
        audit("ICU_VENT_PARAM_ADDED", saved.getDisplayName() + " (" + key + ")",
                hospitalId, saved.getId());
        notifier.refresh(hospitalId);
        return saved;
    }

    // There is deliberately no delete. Disabling hides a parameter from charting while keeping its
    // name resolvable, so every value ever recorded can still be shown with a label.

    // ── helpers ──────────────────────────────────────────────────────────────

    private Map<String, IcuVentilatorParameter> rowsByKey(Long hospitalId) {
        Map<String, IcuVentilatorParameter> byKey = new HashMap<>();
        for (IcuVentilatorParameter r : repository.findByHospitalId(hospitalId)) {
            byKey.put(r.getParamKey(), r);
        }
        return byKey;
    }

    private Map<String, Object> describe(String key, String displayName, String unit,
                                         String category, String valueType, boolean isCustom,
                                         boolean enabled, String publicId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("displayName", displayName);
        m.put("unit", unit);
        m.put("category", category);
        m.put("valueType", valueType);
        m.put("isCustom", isCustom);
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
                    hospitalId, "ICU_VENT_PARAM", String.valueOf(id), null);
        } catch (Exception e) {
            logger.warn("ICU ventilator parameter audit failed: {}", e.getMessage());
        }
    }

    /** The mode catalogue, for the entry form. Values stay fixed even though names are not. */
    public List<VentilatorModeRegistry.Mode> modes() {
        return new ArrayList<>(VentilatorModeRegistry.MODES);
    }
}
