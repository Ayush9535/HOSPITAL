package com.hms.service.hospital.ot;

import com.hms.entity.OtWorkflowPolicy;
import com.hms.exception.UnauthorizedException;
import com.hms.repository.OtWorkflowPolicyRepository;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OtPolicyService - resolves and administers a hospital's workflow policies.
 *
 * Lazy defaults (as FormAccessService and VitalSettingsService): a hospital with no rows
 * uses OtPolicies.defaultValue(key), so nothing is seeded and existing hospitals are
 * unaffected. Resolution prefers a row for the case's priority scope, then ANY, then the
 * built-in default -- which is how an emergency can waive approval without a second flow.
 */
@Service
public class OtPolicyService {

    private static final Logger logger = LoggerFactory.getLogger(OtPolicyService.class);

    @Autowired private OtWorkflowPolicyRepository repository;
    @Autowired private SecurityContextHelper securityHelper;
    @Autowired private AuditLogService auditLogService;

    /** The value of one policy for a case of the given priority. Safe to call outside a request. */
    public String resolve(Long hospitalId, String key, String priority) {
        if (hospitalId == null) return OtPolicies.defaultValue(key);
        String scope = OtPolicies.SCOPE_EMERGENCY.equalsIgnoreCase(priority)
                ? OtPolicies.SCOPE_EMERGENCY : OtPolicies.SCOPE_ELECTIVE;

        String scoped = null, any = null;
        for (OtWorkflowPolicy p : repository.findByHospitalIdAndPolicyKey(hospitalId, key)) {
            if (scope.equals(p.getPriorityScope())) scoped = p.getValue();
            else if (OtPolicies.SCOPE_ANY.equals(p.getPriorityScope())) any = p.getValue();
        }
        if (scoped != null) return scoped;
        if (any != null) return any;
        return OtPolicies.defaultValue(key);
    }

    /** The ANY-scope matrix a hospital sees in Settings: key -> value (default when unset). */
    public Map<String, String> effectiveDefaults() {
        Long hospitalId = requireHospitalId();
        Map<String, String> out = new LinkedHashMap<>();
        for (OtPolicies.Policy p : OtPolicies.all()) {
            out.put(p.key(), resolve(hospitalId, p.key(), OtPolicies.SCOPE_ELECTIVE));
        }
        return out;
    }

    /** Emergency overrides currently in force (only where they differ from the elective value). */
    public Map<String, String> emergencyOverrides() {
        Long hospitalId = requireHospitalId();
        Map<String, String> out = new LinkedHashMap<>();
        for (OtPolicies.Policy p : OtPolicies.all()) {
            String elective = resolve(hospitalId, p.key(), OtPolicies.SCOPE_ELECTIVE);
            String emergency = resolve(hospitalId, p.key(), OtPolicies.SCOPE_EMERGENCY);
            if (!java.util.Objects.equals(elective, emergency)) out.put(p.key(), emergency);
        }
        return out;
    }

    @Transactional
    public Map<String, String> updateDefaults(Map<String, String> values) {
        Long hospitalId = requireHospitalId();
        validate(values);
        // Rewrite only the ANY-scope rows; emergency-scope overrides are left untouched.
        List<OtWorkflowPolicy> keep = new ArrayList<>();
        for (OtWorkflowPolicy p : repository.findByHospitalId(hospitalId)) {
            if (!OtPolicies.SCOPE_ANY.equals(p.getPriorityScope())) keep.add(p);
        }
        repository.deleteByHospitalId(hospitalId);
        List<OtWorkflowPolicy> rows = new ArrayList<>(keep);
        values.forEach((k, v) -> rows.add(row(hospitalId, k, OtPolicies.SCOPE_ANY, v)));
        repository.saveAll(rows);
        audit(hospitalId, "OT policies updated");
        return effectiveDefaults();
    }

    /** One-click archetype: bulk-write the ANY-scope rows plus the standard emergency waivers. */
    @Transactional
    public Map<String, String> applyArchetype(String name) {
        Long hospitalId = requireHospitalId();
        Map<String, String> preset = OtPolicies.archetype(name);
        if (preset == null) throw new IllegalArgumentException("Unknown archetype: " + name);

        repository.deleteByHospitalId(hospitalId);
        List<OtWorkflowPolicy> rows = new ArrayList<>();
        preset.forEach((k, v) -> rows.add(row(hospitalId, k, OtPolicies.SCOPE_ANY, v)));
        // Emergencies waive approval and financial clearance and relax the checklist, for any
        // archetype: a life-threatening case starts before the paperwork exists.
        rows.add(row(hospitalId, OtPolicies.APPROVAL_MODE, OtPolicies.SCOPE_EMERGENCY, "NONE"));
        rows.add(row(hospitalId, OtPolicies.FINANCIAL_CLEARANCE, OtPolicies.SCOPE_EMERGENCY, "OFF"));
        rows.add(row(hospitalId, OtPolicies.WHO_CHECKLIST_MODE, OtPolicies.SCOPE_EMERGENCY, "ADVISORY"));
        repository.saveAll(rows);
        audit(hospitalId, "OT archetype applied: " + name);
        return effectiveDefaults();
    }

    @Transactional
    public Map<String, String> resetToDefaults() {
        Long hospitalId = requireHospitalId();
        repository.deleteByHospitalId(hospitalId);
        return effectiveDefaults();
    }

    private void validate(Map<String, String> values) {
        if (values == null) throw new IllegalArgumentException("Policies are required");
        values.forEach((k, v) -> {
            if (!OtPolicies.isValidKey(k)) throw new IllegalArgumentException("Unknown policy: " + k);
            if (!OtPolicies.isValidValue(k, v)) {
                throw new IllegalArgumentException("Invalid value '" + v + "' for policy " + k);
            }
        });
    }

    private OtWorkflowPolicy row(Long hospitalId, String key, String scope, String value) {
        OtWorkflowPolicy p = new OtWorkflowPolicy();
        p.setHospitalId(hospitalId);
        p.setPolicyKey(key);
        p.setPriorityScope(scope);
        p.setValue(value);
        return p;
    }

    private void audit(Long hospitalId, String detail) {
        try {
            auditLogService.logAction("OT_POLICY_UPDATED", detail, securityHelper.getCurrentUserEmail(),
                    hospitalId, "OT_POLICY", String.valueOf(hospitalId), null);
        } catch (Exception e) {
            logger.warn("Failed to audit OT policy change: {}", e.getMessage());
        }
    }

    private Long requireHospitalId() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) throw new UnauthorizedException("Hospital ID not found in context");
        return hospitalId;
    }
}
