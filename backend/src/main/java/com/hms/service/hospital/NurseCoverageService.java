package com.hms.service.hospital;

import com.hms.entity.NurseProfile;
import com.hms.entity.NurseSubstitution;
import com.hms.entity.NurseWardAssignment;
import com.hms.exception.UnauthorizedException;
import com.hms.repository.NurseProfileRepository;
import com.hms.repository.NurseSubstitutionRepository;
import com.hms.repository.NurseWardAssignmentRepository;
import com.hms.repository.PatientNurseAssignmentRepository;
import com.hms.security.NurseInchargeGuard;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * NurseCoverageService - temporary ward assignments + nurse substitutions
 * (Nursing Mgmt Phase F). Date-ranged and auto-reverting; the primary ward /
 * assignment is never modified. Resolvers here are consumed by ward-nurse
 * lists, getMyPatients, and NurseAccessGuard. With no coverage records the
 * resolvers reduce to the base ward-membership behaviour (backward compatible).
 */
@Service
public class NurseCoverageService {
    @Autowired private NurseWardAssignmentRepository wardAssignmentRepository;
    @Autowired private NurseSubstitutionRepository substitutionRepository;
    @Autowired private NurseProfileRepository nurseProfileRepository;
    @Autowired private PatientNurseAssignmentRepository patientAssignmentRepository;
    @Autowired private NurseInchargeGuard nurseInchargeGuard;
    @Autowired private SecurityContextHelper securityHelper;
    @Autowired private AuditLogService auditLogService;
    @Autowired private com.hms.security.HospitalWebSocketHandler webSocketHandler;

    // ---- resolvers ----

    /** Nurses effectively staffing {@code wardId} on {@code date}: primary staff
     *  minus those temporarily assigned out, plus those temporarily assigned in. */
    public List<NurseProfile> effectiveWardNurses(Long wardId, LocalDate date) {
        Map<Long, NurseProfile> out = new LinkedHashMap<>();
        for (NurseProfile p : nurseProfileRepository.findByWardIdAndIsInchargeFalseAndIsActiveTrue(wardId)) {
            boolean tempOut = wardAssignmentRepository
                    .findByNurseProfileIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(p.getId(), date, date)
                    .stream().anyMatch(w -> !wardId.equals(w.getTempWardId()));
            if (!tempOut) out.put(p.getId(), p);
        }
        for (NurseWardAssignment w : wardAssignmentRepository
                .findByTempWardIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(wardId, date, date)) {
            nurseProfileRepository.findById(w.getNurseProfileId())
                    .filter(p -> Boolean.TRUE.equals(p.getIsActive()) && !Boolean.TRUE.equals(p.getIsIncharge()))
                    .ifPresent(p -> out.putIfAbsent(p.getId(), p));
        }
        return new ArrayList<>(out.values());
    }

    public Long effectiveWardId(Long nurseProfileId, LocalDate date) {
        return wardAssignmentRepository
                .findByNurseProfileIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(nurseProfileId, date, date)
                .stream().findFirst().map(NurseWardAssignment::getTempWardId)
                .orElseGet(() -> nurseProfileRepository.findById(nurseProfileId).map(NurseProfile::getWardId).orElse(null));
    }

    /** User ids of the primary nurses the given replacement user currently covers. */
    public Set<Long> coveredUserIds(Long replacementUserId, LocalDate date) {
        Long replProfileId = nurseProfileRepository.findByUserId(replacementUserId).map(NurseProfile::getId).orElse(null);
        if (replProfileId == null) return Set.of();
        Set<Long> userIds = new HashSet<>();
        for (NurseSubstitution s : substitutionRepository
                .findByReplacementNurseProfileIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(replProfileId, date, date)) {
            nurseProfileRepository.findById(s.getPrimaryNurseProfileId())
                    .map(NurseProfile::getUserId).ifPresent(u -> { if (u != null) userIds.add(u); });
        }
        return userIds;
    }

    public boolean coversAdmission(Long userId, Long ipdAdmissionId, LocalDate date) {
        for (Long primaryUserId : coveredUserIds(userId, date)) {
            if (patientAssignmentRepository.existsByIpdAdmissionIdAndNurseUserIdAndIsActiveTrue(ipdAdmissionId, primaryUserId)) {
                return true;
            }
        }
        return false;
    }

    // ---- CRUD ----

    @Transactional
    public NurseWardAssignment createTempAssignment(Long nurseProfileId, Long tempWardId, LocalDate from, LocalDate to, String reason) {
        Long hospitalId = requireHospitalId();
        NurseProfile p = requireNurse(nurseProfileId, hospitalId);
        if (from == null || to == null || to.isBefore(from)) throw new IllegalArgumentException("Valid from/to dates required");
        nurseInchargeGuard.assertWardAccess(tempWardId);
        // A new range [from,to] overlaps an existing row iff existing.fromDate<=to AND existing.toDate>=from.
        boolean overlap = !wardAssignmentRepository
                .findByNurseProfileIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(nurseProfileId, to, from).isEmpty();
        if (overlap) throw new IllegalArgumentException("Nurse already has a temporary assignment in that period");
        NurseWardAssignment w = new NurseWardAssignment();
        w.setHospitalId(hospitalId);
        w.setNurseProfileId(nurseProfileId);
        w.setTempWardId(tempWardId);
        w.setFromDate(from);
        w.setToDate(to);
        w.setReason(reason);
        w.setCreatedByUserId(securityHelper.getCurrentUserId());
        NurseWardAssignment saved = wardAssignmentRepository.save(w);
        audit("TEMP_WARD_ASSIGNED", p.getName() + " -> ward " + tempWardId + " " + from + ".." + to, hospitalId, saved.getId());
        broadcastRefresh(hospitalId);
        return saved;
    }

    @Transactional
    public void removeTempAssignment(String publicId) {
        Long hospitalId = requireHospitalId();
        NurseWardAssignment w = wardAssignmentRepository.findByPublicId(publicId)
                .orElseThrow(() -> new IllegalArgumentException("Temporary assignment not found"));
        if (!hospitalId.equals(w.getHospitalId())) throw new UnauthorizedException("Belongs to another hospital");
        nurseInchargeGuard.assertWardAccess(w.getTempWardId());
        wardAssignmentRepository.delete(w);
        audit("TEMP_WARD_REMOVED", publicId, hospitalId, w.getId());
        broadcastRefresh(hospitalId);
    }

    @Transactional
    public NurseSubstitution createSubstitution(Long primaryId, Long replacementId, LocalDate from, LocalDate to, String reason) {
        Long hospitalId = requireHospitalId();
        if (Objects.equals(primaryId, replacementId)) throw new IllegalArgumentException("Primary and replacement must differ");
        if (from == null || to == null || to.isBefore(from)) throw new IllegalArgumentException("Valid from/to dates required");
        NurseProfile primary = requireNurse(primaryId, hospitalId);
        requireNurse(replacementId, hospitalId);
        nurseInchargeGuard.assertWardAccess(primary.getWardId());
        NurseSubstitution s = new NurseSubstitution();
        s.setHospitalId(hospitalId);
        s.setPrimaryNurseProfileId(primaryId);
        s.setReplacementNurseProfileId(replacementId);
        s.setFromDate(from);
        s.setToDate(to);
        s.setReason(reason);
        s.setCreatedByUserId(securityHelper.getCurrentUserId());
        NurseSubstitution saved = substitutionRepository.save(s);
        audit("NURSE_SUBSTITUTION_CREATED", primaryId + " covered by " + replacementId + " " + from + ".." + to, hospitalId, saved.getId());
        broadcastRefresh(hospitalId);
        return saved;
    }

    @Transactional
    public void removeSubstitution(String publicId) {
        Long hospitalId = requireHospitalId();
        NurseSubstitution s = substitutionRepository.findByPublicId(publicId)
                .orElseThrow(() -> new IllegalArgumentException("Substitution not found"));
        if (!hospitalId.equals(s.getHospitalId())) throw new UnauthorizedException("Belongs to another hospital");
        nurseProfileRepository.findById(s.getPrimaryNurseProfileId())
                .ifPresent(p -> nurseInchargeGuard.assertWardAccess(p.getWardId()));
        substitutionRepository.delete(s);
        audit("NURSE_SUBSTITUTION_REMOVED", publicId, hospitalId, s.getId());
        broadcastRefresh(hospitalId);
    }

    /** Active + upcoming temp assignments for the hospital (UI list). */
    public List<NurseWardAssignment> listTempAssignments() {
        return wardAssignmentRepository.findByHospitalIdAndToDateGreaterThanEqualOrderByFromDateAsc(requireHospitalId(), LocalDate.now());
    }

    public List<NurseSubstitution> listSubstitutions() {
        return substitutionRepository.findByHospitalIdAndToDateGreaterThanEqualOrderByFromDateAsc(requireHospitalId(), LocalDate.now());
    }

    /** Active substitutions where the current user is the replacement (nurse banner). */
    public List<NurseSubstitution> myActiveCoverage() {
        Long profileId = nurseProfileRepository.findByUserId(securityHelper.getCurrentUserId()).map(NurseProfile::getId).orElse(null);
        if (profileId == null) return List.of();
        LocalDate today = LocalDate.now();
        return substitutionRepository.findByReplacementNurseProfileIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(profileId, today, today);
    }

    private NurseProfile requireNurse(Long id, Long hospitalId) {
        NurseProfile p = nurseProfileRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Nurse not found"));
        if (!hospitalId.equals(p.getHospitalId())) throw new UnauthorizedException("Nurse belongs to another hospital");
        return p;
    }

    private Long requireHospitalId() {
        Long h = securityHelper.getCurrentHospitalId();
        if (h == null) throw new UnauthorizedException("Hospital ID not found");
        return h;
    }

    private void audit(String a, String d, Long h, Long id) {
        try {
            auditLogService.logAction(a, d, securityHelper.getCurrentUserEmail(), h, "NURSE_COVERAGE", String.valueOf(id), null);
        } catch (Exception e) { /* best-effort */ }
    }

    private void broadcastRefresh(Long hospitalId) {
        try {
            webSocketHandler.broadcast(hospitalId, "{\"type\":\"REFRESH_DATA\"}");
        } catch (Exception e) { /* best-effort */ }
    }
}
