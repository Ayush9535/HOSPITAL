package com.hms.service.hospital;

import com.hms.dto.FormAccessRequest;
import com.hms.entity.HospitalFormAccess;
import com.hms.exception.UnauthorizedException;
import com.hms.repository.HospitalFormAccessRepository;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * FormAccessService - per-hospital form availability + edit access (Files &
 * Access, Phase 1). A missing row means enabled + BOTH, so the table only holds
 * overrides. effectiveForRole() maps each form to HIDDEN / READ_ONLY / EDITABLE.
 */
@Service
public class FormAccessService {
    @Autowired private HospitalFormAccessRepository repository;
    @Autowired private SecurityContextHelper securityHelper;
    @Autowired private AuditLogService auditLogService;
    @Autowired private com.hms.security.HospitalWebSocketHandler webSocketHandler;

    // DOCTOR / NURSE / RECEPTION / BOTH. NURSE is the editor in a nursing hospital; RECEPTION
    // is the editor in a non-nursing hospital (the IPD Forms setting). BOTH means "doctor plus
    // whichever of nurse/reception applies", so the same stored value works in either context.
    private static final Set<String> ROLES = Set.of("DOCTOR", "NURSE", "RECEPTION", "BOTH");

    public List<Map<String, Object>> list() {
        Long hospitalId = requireHospitalId();
        Map<String, HospitalFormAccess> byKey = new HashMap<>();
        for (HospitalFormAccess r : repository.findByHospitalId(hospitalId)) byKey.put(r.getFormKey(), r);

        List<Map<String, Object>> out = new ArrayList<>();
        for (FormRegistry.Form f : FormRegistry.FORMS) {
            HospitalFormAccess r = byKey.get(f.key());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", f.key());
            m.put("label", f.label());
            m.put("category", f.category());
            m.put("enabled", r == null || Boolean.TRUE.equals(r.getEnabled()));
            m.put("accessRole", r == null ? "BOTH" : r.getAccessRole());
            out.add(m);
        }
        return out;
    }

    public Map<String, String> effectiveForRole(String role) {
        Long hospitalId = requireHospitalId();
        String norm = normalizeRole(role);
        Map<String, HospitalFormAccess> byKey = new HashMap<>();
        for (HospitalFormAccess r : repository.findByHospitalId(hospitalId)) byKey.put(r.getFormKey(), r);

        Map<String, String> out = new LinkedHashMap<>();
        for (FormRegistry.Form f : FormRegistry.FORMS) {
            HospitalFormAccess r = byKey.get(f.key());
            boolean enabled = r == null || Boolean.TRUE.equals(r.getEnabled());
            String access = r == null ? "BOTH" : r.getAccessRole();
            if (!enabled) { out.put(f.key(), "HIDDEN"); continue; }
            boolean canEdit = "BOTH".equals(access) || access.equals(norm);
            out.put(f.key(), canEdit ? "EDITABLE" : "READ_ONLY");
        }
        return out;
    }

    @Transactional
    public HospitalFormAccess update(String formKey, FormAccessRequest req) {
        Long hospitalId = requireHospitalId();
        if (!FormRegistry.isValidKey(formKey)) throw new IllegalArgumentException("Unknown form: " + formKey);
        String role = req.getAccessRole() == null ? null : req.getAccessRole().toUpperCase();
        if (role == null || !ROLES.contains(role)) throw new IllegalArgumentException("Invalid access role");
        boolean enabled = req.getEnabled() == null || req.getEnabled();

        HospitalFormAccess row = repository.findByHospitalIdAndFormKey(hospitalId, formKey)
                .orElseGet(() -> {
                    HospitalFormAccess n = new HospitalFormAccess();
                    n.setHospitalId(hospitalId);
                    n.setFormKey(formKey);
                    return n;
                });
        row.setEnabled(enabled);
        row.setAccessRole(role);
        HospitalFormAccess saved = repository.save(row);
        audit("FORM_ACCESS_UPDATED", formKey + " enabled=" + enabled + " access=" + role, hospitalId, saved.getId());
        broadcastRefresh(hospitalId);
        return saved;
    }

    /** Throws 403 unless the current user's role may edit this form (Files & Access). */
    public void assertCanEdit(String formKey) {
        String verdict = effectiveForRole(securityHelper.getCurrentUserRole()).get(formKey);
        if (!"EDITABLE".equals(verdict)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "You do not have edit access to this form");
        }
    }

    private String normalizeRole(String role) {
        if (role == null) return "";
        if ("NURSE_INCHARGE".equals(role) || "NURSE".equals(role)) return "NURSE";
        if ("RECEPTIONIST".equals(role)) return "RECEPTION";
        if ("HOSPITAL_ADMIN".equals(role)) return "BOTH";
        return role;
    }

    private Long requireHospitalId() {
        Long h = securityHelper.getCurrentHospitalId();
        if (h == null) throw new UnauthorizedException("Hospital ID not found");
        return h;
    }

    private void audit(String a, String d, Long h, Long id) {
        try {
            auditLogService.logAction(a, d, securityHelper.getCurrentUserEmail(), h, "FORM_ACCESS", String.valueOf(id), null);
        } catch (Exception e) { /* best-effort */ }
    }

    private void broadcastRefresh(Long hospitalId) {
        try { webSocketHandler.broadcast(hospitalId, "{\"type\":\"REFRESH_DATA\"}"); } catch (Exception e) { /* best-effort */ }
    }
}
