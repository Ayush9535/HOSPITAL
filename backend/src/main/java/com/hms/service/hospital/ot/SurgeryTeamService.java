package com.hms.service.hospital.ot;

import com.hms.entity.CaseRole;
import com.hms.entity.Surgery;
import com.hms.entity.SurgeryTeamMember;
import com.hms.exception.UnauthorizedException;
import com.hms.repository.CaseRoleRepository;
import com.hms.repository.SurgeryRepository;
import com.hms.repository.SurgeryTeamMemberRepository;
import com.hms.security.SecurityContextHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SurgeryTeamService - the surgical team on a case, plus a hospital's custom case roles.
 *
 * A team member holds a role code that is either built-in (CaseRoles) or a hospital custom
 * row (case_roles). A new specialty role is therefore a data entry, not a code change: the
 * transplant centre's HARVEST_SURGEON and the cardiac centre's PERFUSIONIST both work
 * without touching this class.
 */
@Service
public class SurgeryTeamService {

    @Autowired private SurgeryTeamMemberRepository teamRepository;
    @Autowired private CaseRoleRepository caseRoleRepository;
    @Autowired private SurgeryRepository surgeryRepository;
    @Autowired private SecurityContextHelper securityHelper;

    /** Built-in roles merged with the hospital's custom ones, for the team picker. */
    public List<Map<String, Object>> availableRoles() {
        Long hospitalId = requireHospitalId();
        List<Map<String, Object>> out = new ArrayList<>();
        for (CaseRoles.Role r : CaseRoles.builtIns()) {
            out.add(roleView(r.code(), r.label(), false));
        }
        for (CaseRole r : caseRoleRepository.findByHospitalIdAndIsActiveTrueOrderByLabelAsc(hospitalId)) {
            out.add(roleView(r.getCode(), r.getLabel(), true));
        }
        return out;
    }

    /** Add a custom role. Rejects a code that collides with a built-in or an existing custom. */
    @Transactional
    public CaseRole addCustomRole(String label) {
        Long hospitalId = requireHospitalId();
        if (label == null || label.trim().isEmpty()) throw new IllegalArgumentException("Role name is required");
        String code = CaseRoles.toCode(label);
        if (code == null || code.isEmpty()) throw new IllegalArgumentException("Invalid role name");
        if (CaseRoles.isBuiltIn(code)) throw new IllegalArgumentException("That is already a built-in role");
        if (caseRoleRepository.existsByHospitalIdAndCode(hospitalId, code)) {
            throw new IllegalArgumentException("That role already exists");
        }
        CaseRole r = new CaseRole();
        r.setHospitalId(hospitalId);
        r.setCode(code);
        r.setLabel(label.trim());
        return caseRoleRepository.save(r);
    }

    public List<SurgeryTeamMember> team(Long surgeryId) {
        Long hospitalId = requireHospitalId();
        requireSurgery(surgeryId, hospitalId);
        return teamRepository.findBySurgeryIdOrderByIdAsc(surgeryId);
    }

    /** Assign someone to a case. Exactly one of userId / externalName is required. */
    @Transactional
    public SurgeryTeamMember assign(Long surgeryId, String caseRoleCode, Long userId, String externalName) {
        Long hospitalId = requireHospitalId();
        Surgery surgery = requireSurgery(surgeryId, hospitalId);
        assertRoleExists(hospitalId, caseRoleCode);

        boolean hasUser = userId != null;
        boolean hasName = externalName != null && !externalName.trim().isEmpty();
        if (hasUser == hasName) {
            throw new IllegalArgumentException("Provide either a staff member or an external name, not both");
        }

        SurgeryTeamMember m = new SurgeryTeamMember();
        m.setHospitalId(hospitalId);
        m.setSurgeryId(surgery.getId());
        m.setCaseRoleCode(caseRoleCode);
        m.setUserId(userId);
        m.setExternalName(hasName ? externalName.trim() : null);
        return teamRepository.save(m);
    }

    @Transactional
    public void remove(Long surgeryId, Long memberId) {
        Long hospitalId = requireHospitalId();
        requireSurgery(surgeryId, hospitalId);
        SurgeryTeamMember m = teamRepository.findById(memberId)
                .orElseThrow(() -> new IllegalArgumentException("Team member not found"));
        if (!hospitalId.equals(m.getHospitalId()) || !surgeryId.equals(m.getSurgeryId())) {
            throw new UnauthorizedException("Access denied");
        }
        teamRepository.delete(m);
    }

    private void assertRoleExists(Long hospitalId, String code) {
        if (code == null) throw new IllegalArgumentException("A role is required");
        if (CaseRoles.isBuiltIn(code)) return;
        if (caseRoleRepository.findByHospitalIdAndCode(hospitalId, code).isPresent()) return;
        throw new IllegalArgumentException("Unknown role: " + code);
    }

    private Map<String, Object> roleView(String code, String label, boolean custom) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("label", label);
        m.put("custom", custom);
        return m;
    }

    private Surgery requireSurgery(Long surgeryId, Long hospitalId) {
        Surgery s = surgeryRepository.findById(surgeryId)
                .orElseThrow(() -> new IllegalArgumentException("Surgery not found"));
        if (!hospitalId.equals(s.getHospitalId())) {
            throw new UnauthorizedException("Access denied: surgery belongs to another hospital");
        }
        return s;
    }

    private Long requireHospitalId() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) throw new UnauthorizedException("Hospital ID not found in context");
        return hospitalId;
    }
}
