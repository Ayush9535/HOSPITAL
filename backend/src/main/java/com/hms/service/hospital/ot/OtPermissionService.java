package com.hms.service.hospital.ot;

import com.hms.entity.RolePermission;
import com.hms.exception.UnauthorizedException;
import com.hms.repository.RolePermissionRepository;
import com.hms.security.OtPermissions;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OtPermissionService - resolves and administers a hospital's OT permission matrix.
 *
 * Lazy defaults, the pattern already used by FormAccessService and VitalSettingsService:
 * a hospital with no role_permissions rows uses OtPermissions.defaultsFor(role), so
 * nothing needs seeding and existing hospitals behave exactly as before. The first
 * save materialises the entire matrix, after which the rows are the only truth --
 * otherwise revoking every permission from a role would be indistinguishable from
 * never having configured it.
 *
 * The first class of the OT bounded context (service/hospital/ot).
 */
@Service
public class OtPermissionService {

    private static final Logger logger = LoggerFactory.getLogger(OtPermissionService.class);

    @Autowired private RolePermissionRepository repository;
    @Autowired private SecurityContextHelper securityHelper;
    @Autowired private AuditLogService auditLogService;

    /** Effective permissions for one role at one hospital. Safe to call outside a request. */
    public Set<String> effectiveFor(Long hospitalId, String role) {
        if (hospitalId == null || role == null) return Set.of();
        if (repository.countByHospitalId(hospitalId) == 0) {
            return OtPermissions.defaultsFor(role);
        }
        Set<String> granted = new LinkedHashSet<>();
        for (RolePermission rp : repository.findByHospitalIdAndRole(hospitalId, role)) {
            granted.add(rp.getPermissionCode());
        }
        return granted;
    }

    /** The caller's own effective permissions, for the frontend to render by capability. */
    public Set<String> myPermissions() {
        return effectiveFor(securityHelper.getCurrentHospitalId(), securityHelper.getCurrentUserRole());
    }

    /** The full matrix: role -> granted codes. Used by the admin screen. */
    public Map<String, Set<String>> matrix() {
        Long hospitalId = requireHospitalId();
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (String role : OtPermissions.ROLES) {
            out.put(role, effectiveFor(hospitalId, role));
        }
        return out;
    }

    /**
     * Replace the hospital's whole matrix. Whole-matrix, not per-role, because a partial
     * write cannot express "this role now has nothing" while zero rows still means
     * "use the defaults".
     */
    @Transactional
    public Map<String, Set<String>> updateMatrix(Map<String, List<String>> requested) {
        Long hospitalId = requireHospitalId();
        if (requested == null) throw new IllegalArgumentException("A permission matrix is required");

        for (Map.Entry<String, List<String>> e : requested.entrySet()) {
            if (!OtPermissions.ROLES.contains(e.getKey())) {
                throw new IllegalArgumentException("Unknown role: " + e.getKey());
            }
            for (String code : e.getValue() == null ? List.<String>of() : e.getValue()) {
                if (!OtPermissions.isValid(code)) throw new IllegalArgumentException("Unknown permission: " + code);
            }
        }

        repository.deleteByHospitalId(hospitalId);
        List<RolePermission> rows = new ArrayList<>();
        for (String role : OtPermissions.ROLES) {
            List<String> codes = requested.getOrDefault(role, List.of());
            for (String code : new LinkedHashSet<>(codes == null ? List.<String>of() : codes)) {
                RolePermission rp = new RolePermission();
                rp.setHospitalId(hospitalId);
                rp.setRole(role);
                rp.setPermissionCode(code);
                rows.add(rp);
            }
        }
        // A hospital that grants nothing to anyone must still count as "configured", or the
        // next read would silently fall back to the defaults. Keep a single marker row.
        if (rows.isEmpty()) {
            RolePermission marker = new RolePermission();
            marker.setHospitalId(hospitalId);
            marker.setRole("HOSPITAL_ADMIN");
            marker.setPermissionCode(OtPermissions.OT_SETTINGS);
            rows.add(marker);
            logger.warn("OT permission matrix for hospital {} granted nothing; kept OT_SETTINGS for "
                    + "HOSPITAL_ADMIN so the matrix stays reachable", hospitalId);
        }
        repository.saveAll(rows);

        try {
            auditLogService.logAction("OT_PERMISSIONS_UPDATED", "OT permission matrix updated",
                    securityHelper.getCurrentUserEmail(), hospitalId, "OT_PERMISSIONS",
                    String.valueOf(hospitalId), null);
        } catch (Exception e) {
            logger.warn("Failed to audit OT permission update: {}", e.getMessage());
        }
        return matrix();
    }

    /** Reset to the built-in defaults by removing every override. */
    @Transactional
    public Map<String, Set<String>> resetToDefaults() {
        Long hospitalId = requireHospitalId();
        repository.deleteByHospitalId(hospitalId);
        return matrix();
    }

    private Long requireHospitalId() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) throw new UnauthorizedException("Hospital ID not found in context");
        return hospitalId;
    }
}
