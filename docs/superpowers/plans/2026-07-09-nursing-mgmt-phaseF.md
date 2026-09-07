# Nursing Management — Phase F Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development or superpowers:executing-plans. Checkboxes (`- [ ]`) track steps.

**Goal:** Temporary ward assignment + nurse substitution — date-ranged, auto-reverting coverage that (a) makes a temporarily-assigned nurse count as staff of the temp ward's incharge, and (b) lets a replacement nurse see + record care for the primary nurse's patients. The primary ward/assignment is never modified.

**Architecture:** Two date-ranged tables + a `NurseCoverageService` with resolvers. Resolvers are wired into ward-nurse lookups, `getMyPatients`, and `NurseAccessGuard` — all changes are no-ops when no coverage records exist (backward compatible).

**Tech Stack:** Spring Boot 3.3.5 / Java 17 / Maven / MySQL, JUnit 5 + Mockito + AssertJ. React 18 / Vite.

## Conventions
- Tenant scope `SecurityContextHelper.getCurrentHospitalId()`. Gate `@RequireModule("NURSING")`. Migrations: `ensureXxxTable()` (copy `ensureNurseAttendanceTable`), call from `runMigrations()`, mirror in `setup/schema-full.sql`. Audit via `AuditLogService`. Build: `mvn -o test` / `npx vite build --mode development`. Commit at milestone boundaries.
- Existing: `NurseProfile.getId/getName/getUserId/getWardId/getHospitalId/getIsIncharge/getIsActive`; `NurseProfileRepository.findById/findByUserId/findByWardIdAndIsInchargeFalseAndIsActiveTrue`. `NurseInchargeGuard.assertWardAccess(Long)`. `PatientNurseAssignmentRepository.existsByIpdAdmissionIdAndNurseUserIdAndIsActiveTrue(Long,Long)`, `findByNurseUserIdAndIsActiveTrue(Long)`. `NurseAccessGuard` currently: `@Autowired PatientNurseAssignmentRepository assignmentRepository; SecurityContextHelper securityHelper;` with `assertAssigned(Long)`.

---

# Milestone F1 — Coverage entities + service

### Task 1: Entities + repositories + migration
Create `entity/NurseWardAssignment.java`:
```java
package com.hms.entity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDate; import java.time.LocalDateTime;
@Entity @Table(name = "nurse_ward_assignments")
@Data @NoArgsConstructor @AllArgsConstructor
public class NurseWardAssignment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true) private String publicId;
    @Column(name = "hospital_id", nullable = false) private Long hospitalId;
    @Column(name = "nurse_profile_id", nullable = false) private Long nurseProfileId;
    @Column(name = "temp_ward_id", nullable = false) private Long tempWardId;
    @Column(name = "from_date", nullable = false) private LocalDate fromDate;
    @Column(name = "to_date", nullable = false) private LocalDate toDate;
    @Column(length = 255) private String reason;
    @Column(name = "created_by_user_id") private Long createdByUserId;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
    @PrePersist public void pre() { if (publicId == null) publicId = java.util.UUID.randomUUID().toString(); }
}
```
Create `entity/NurseSubstitution.java` (same shape; fields `primaryNurseProfileId` (`primary_nurse_profile_id`), `replacementNurseProfileId` (`replacement_nurse_profile_id`), `fromDate`, `toDate`, `reason`, `createdByUserId`; table `nurse_substitutions`).

Repositories:
```java
public interface NurseWardAssignmentRepository extends JpaRepository<NurseWardAssignment, Long> {
    java.util.Optional<NurseWardAssignment> findByPublicId(String publicId);
    java.util.List<NurseWardAssignment> findByNurseProfileIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(Long nurseProfileId, java.time.LocalDate d1, java.time.LocalDate d2);
    java.util.List<NurseWardAssignment> findByTempWardIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(Long tempWardId, java.time.LocalDate d1, java.time.LocalDate d2);
    java.util.List<NurseWardAssignment> findByHospitalIdAndToDateGreaterThanEqualOrderByFromDateAsc(Long hospitalId, java.time.LocalDate today);
}
public interface NurseSubstitutionRepository extends JpaRepository<NurseSubstitution, Long> {
    java.util.Optional<NurseSubstitution> findByPublicId(String publicId);
    java.util.List<NurseSubstitution> findByReplacementNurseProfileIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(Long replId, java.time.LocalDate d1, java.time.LocalDate d2);
    java.util.List<NurseSubstitution> findByHospitalIdAndToDateGreaterThanEqualOrderByFromDateAsc(Long hospitalId, java.time.LocalDate today);
}
```
Migrations `ensureNurseWardAssignmentsTable()` + `ensureNurseSubstitutionsTable()` (columns above; UNIQUE public_id; KEY on nurse/hospital; FK hospital_id→hospitals CASCADE). Call from `runMigrations()`; mirror in schema-full.sql.
- [ ] Compile clean.

### Task 2: `NurseCoverageService` (TDD)
Test `NurseCoverageServiceTest`:
```java
package com.hms.service.hospital;
import com.hms.entity.*; import com.hms.repository.*;
import com.hms.security.*; import com.hms.service.AuditLogService;
import org.junit.jupiter.api.Test; import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*; import org.mockito.junit.jupiter.MockitoExtension;
import java.time.LocalDate; import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*; import static org.mockito.Mockito.*;
@ExtendWith(MockitoExtension.class)
class NurseCoverageServiceTest {
    @Mock NurseWardAssignmentRepository wardAssignmentRepository;
    @Mock NurseSubstitutionRepository substitutionRepository;
    @Mock NurseProfileRepository nurseProfileRepository;
    @Mock PatientNurseAssignmentRepository patientAssignmentRepository;
    @Mock NurseInchargeGuard nurseInchargeGuard;
    @Mock SecurityContextHelper securityHelper;
    @Mock AuditLogService auditLogService;
    @InjectMocks NurseCoverageService service;
    private NurseProfile p(Long id, Long ward) { NurseProfile n = new NurseProfile(); n.setId(id); n.setHospitalId(7L); n.setWardId(ward); n.setIsIncharge(false); n.setIsActive(true); return n; }

    @Test void effectiveWardNurses_excludesTempOut_includesTempIn() {
        LocalDate d = LocalDate.of(2026,7,12);
        NurseProfile a = p(11L, 3L), b = p(12L, 3L), c = p(13L, 9L);
        when(nurseProfileRepository.findByWardIdAndIsInchargeFalseAndIsActiveTrue(3L)).thenReturn(List.of(a, b));
        // a is temp-assigned OUT to ward 9
        NurseWardAssignment outA = new NurseWardAssignment(); outA.setNurseProfileId(11L); outA.setTempWardId(9L);
        when(wardAssignmentRepository.findByNurseProfileIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(11L, d, d)).thenReturn(List.of(outA));
        when(wardAssignmentRepository.findByNurseProfileIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(12L, d, d)).thenReturn(List.of());
        // c is temp-assigned INTO ward 3
        NurseWardAssignment inC = new NurseWardAssignment(); inC.setNurseProfileId(13L); inC.setTempWardId(3L);
        when(wardAssignmentRepository.findByTempWardIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(3L, d, d)).thenReturn(List.of(inC));
        when(nurseProfileRepository.findById(13L)).thenReturn(Optional.of(c));

        List<NurseProfile> eff = service.effectiveWardNurses(3L, d);
        assertThat(eff).extracting(NurseProfile::getId).containsExactlyInAnyOrder(12L, 13L);
    }

    @Test void coversAdmission_trueWhenSubstitutingAssignedPrimary() {
        LocalDate d = LocalDate.of(2026,7,12);
        NurseProfile repl = p(20L, 3L); repl.setUserId(200L);
        when(nurseProfileRepository.findByUserId(200L)).thenReturn(Optional.of(repl));
        NurseSubstitution sub = new NurseSubstitution(); sub.setPrimaryNurseProfileId(11L); sub.setReplacementNurseProfileId(20L);
        when(substitutionRepository.findByReplacementNurseProfileIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(20L, d, d)).thenReturn(List.of(sub));
        NurseProfile primary = p(11L, 3L); primary.setUserId(100L);
        when(nurseProfileRepository.findById(11L)).thenReturn(Optional.of(primary));
        when(patientAssignmentRepository.existsByIpdAdmissionIdAndNurseUserIdAndIsActiveTrue(500L, 100L)).thenReturn(true);

        assertThat(service.coversAdmission(200L, 500L, d)).isTrue();
    }
}
```
Implement `service/hospital/NurseCoverageService.java`:
```java
package com.hms.service.hospital;

import com.hms.entity.*;
import com.hms.exception.UnauthorizedException;
import com.hms.repository.*;
import com.hms.security.NurseInchargeGuard;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * NurseCoverageService - temporary ward assignments + nurse substitutions
 * (Nursing Mgmt Phase F). Date-ranged and auto-reverting; the primary ward /
 * assignment is never modified. Resolvers here are consumed by ward-nurse
 * lists, getMyPatients, and NurseAccessGuard.
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

    // ---- resolvers ----
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
        nurseInchargeGuard.assertWardAccess(tempWardId);            // incharge of the temp ward
        boolean overlap = wardAssignmentRepository
                .findByNurseProfileIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(nurseProfileId, to, from).size() > 0;
        // NOTE: overlap check uses (to, from) — a range overlaps if existing.from<=to AND existing.to>=from
        if (overlap) throw new IllegalArgumentException("Nurse already has a temporary assignment in that period");
        NurseWardAssignment w = new NurseWardAssignment();
        w.setHospitalId(hospitalId); w.setNurseProfileId(nurseProfileId); w.setTempWardId(tempWardId);
        w.setFromDate(from); w.setToDate(to); w.setReason(reason);
        w.setCreatedByUserId(securityHelper.getCurrentUserId());
        NurseWardAssignment saved = wardAssignmentRepository.save(w);
        audit("TEMP_WARD_ASSIGNED", p.getName() + " -> ward " + tempWardId + " " + from + ".." + to, hospitalId, saved.getId());
        return saved;
    }

    @Transactional
    public void removeTempAssignment(String publicId) {
        Long hospitalId = requireHospitalId();
        NurseWardAssignment w = wardAssignmentRepository.findByPublicId(publicId).orElseThrow(() -> new IllegalArgumentException("Not found"));
        if (!hospitalId.equals(w.getHospitalId())) throw new UnauthorizedException("Another hospital");
        nurseInchargeGuard.assertWardAccess(w.getTempWardId());
        wardAssignmentRepository.delete(w);
        audit("TEMP_WARD_REMOVED", publicId, hospitalId, w.getId());
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
        s.setHospitalId(hospitalId); s.setPrimaryNurseProfileId(primaryId); s.setReplacementNurseProfileId(replacementId);
        s.setFromDate(from); s.setToDate(to); s.setReason(reason);
        s.setCreatedByUserId(securityHelper.getCurrentUserId());
        NurseSubstitution saved = substitutionRepository.save(s);
        audit("NURSE_SUBSTITUTION_CREATED", primaryId + " covered by " + replacementId + " " + from + ".." + to, hospitalId, saved.getId());
        return saved;
    }

    @Transactional
    public void removeSubstitution(String publicId) {
        Long hospitalId = requireHospitalId();
        NurseSubstitution s = substitutionRepository.findByPublicId(publicId).orElseThrow(() -> new IllegalArgumentException("Not found"));
        if (!hospitalId.equals(s.getHospitalId())) throw new UnauthorizedException("Another hospital");
        nurseProfileRepository.findById(s.getPrimaryNurseProfileId()).ifPresent(p -> nurseInchargeGuard.assertWardAccess(p.getWardId()));
        substitutionRepository.delete(s);
        audit("NURSE_SUBSTITUTION_REMOVED", publicId, hospitalId, s.getId());
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
        return substitutionRepository.findByReplacementNurseProfileIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(profileId, LocalDate.now(), LocalDate.now());
    }

    private NurseProfile requireNurse(Long id, Long hospitalId) {
        NurseProfile p = nurseProfileRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Nurse not found"));
        if (!hospitalId.equals(p.getHospitalId())) throw new UnauthorizedException("Nurse belongs to another hospital");
        return p;
    }
    private Long requireHospitalId() { Long h = securityHelper.getCurrentHospitalId(); if (h == null) throw new UnauthorizedException("Hospital ID not found"); return h; }
    private void audit(String a, String d, Long h, Long id) { try { auditLogService.logAction(a, d, securityHelper.getCurrentUserEmail(), h, "NURSE_COVERAGE", String.valueOf(id), null); } catch (Exception e) {} }
}
```
NOTE the overlap check: pass `(to, from)` to `findByNurseProfileIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(nurseProfileId, to, from)` — an existing row overlaps the new [from,to] iff `existing.fromDate <= to AND existing.toDate >= from`. Confirm the derived query maps d1→`fromDate<=`, d2→`toDate>=` and pass args accordingly.
- [ ] Run `NurseCoverageServiceTest` → PASS; `mvn -o test` → green.

### Task 3: Controller `NurseCoverageController` (`/hospital/nurse-coverage`, `@RequireModule("NURSING")`)
- `POST /temp-assignments` `{nurseProfileId, tempWardId, fromDate, toDate, reason}`, `DELETE /temp-assignments/{publicId}`, `GET /temp-assignments` — `hasAnyRole('HOSPITAL_ADMIN','NURSE_INCHARGE')`.
- `POST /substitutions` `{primaryNurseProfileId, replacementNurseProfileId, fromDate, toDate, reason}`, `DELETE /substitutions/{publicId}`, `GET /substitutions` — same roles.
- `GET /my-coverage` — `hasAnyRole('NURSE','NURSE_INCHARGE','HOSPITAL_ADMIN')` → `myActiveCoverage()`.
DTOs `TempWardAssignmentRequest`, `SubstitutionRequest`.
- [ ] `mvn -o test` green. **Commit F1.**

---

# Milestone F2 — Wire resolvers
- [ ] `NurseWorkspaceService.getWardStaffNurses(wardId)` → return `coverageService.effectiveWardNurses(wardId, LocalDate.now())` mapped to `{id,name}`. Inject `NurseCoverageService`.
- [ ] `NurseWorkspaceService.getInchargeDashboard` nurse total → `effectiveWardNurses(wardId, today).size()`.
- [ ] `PatientAssignmentService.onAdmission` → replace `nurseProfileRepository.findByWardIdAndIsInchargeFalseAndIsActiveTrue(wardId)` with `coverageService.effectiveWardNurses(wardId, LocalDate.now())` (filter `getUserId()!=null`). Inject `NurseCoverageService`.
- [ ] `NurseWorkspaceService.getMyPatients` → after building the nurse's own list, union admissions for `coverageService.coveredUserIds(currentUserId, today)`: for each covered userId, `assignmentRepository.findByNurseUserIdAndIsActiveTrue(userId)` → build the same MyPatientDTO (dedup by ipdAdmissionId; skip DISCHARGED). Optionally set a `coveredFor` note — add `private String coveredFor;` to `MyPatientDTO` and populate with the primary nurse's name.
- [ ] `NurseAccessGuard.assertAssigned(admissionId)` → `if (assigned) return; if (coverageService.coversAdmission(currentUserId, admissionId, LocalDate.now())) return; throw AccessDenied`. Inject `@Autowired NurseCoverageService coverageService;` (new field). This is additive; existing tests mocking `NurseAccessGuard` are unaffected.
- [ ] Fix tests: `PatientAssignmentServiceTest` now calls `coverageService.effectiveWardNurses` instead of the profile repo — add `@Mock NurseCoverageService coverageService;` and stub it to return the same profiles the old `findByWardIdAndIsInchargeFalseAndIsActiveTrue` stub returned; drop the now-unused stub. Any test with `@InjectMocks NurseWorkspaceService` that exercises getMyPatients/getWardStaffNurses may need `@Mock NurseCoverageService` (stub `effectiveWardNurses`→list, `coveredUserIds`→empty set). Keep assertions intact.
- [ ] `mvn -o test` green. **Commit F2.**

---

# Milestone F3 — Frontend
- [ ] `services/nurseService.js`: `getTempAssignments`, `createTempAssignment(payload)`, `removeTempAssignment(publicId)`, `getSubstitutions`, `createSubstitution(payload)`, `removeSubstitution(publicId)`, `getMyCoverage()`.
- [ ] `pages/hospital/nurse-incharge/CoverageView.jsx`: two sections. **Temp Ward Assignments** — table (Nurse, Temp Ward, From, To, Reason, Active?/Upcoming, Remove) + add form (nurse from a ward's `getWardStaffNurses`, ward from `getMyWards`, dates, reason). **Substitutions** — table (Primary, Replacement, From, To, Reason, Remove) + add form (both nurses from ward staff, dates, reason). Toast + reload.
- [ ] Add a **"Coverage"** tab to `NurseInchargeDashboard.jsx` rendering `<CoverageView />`.
- [ ] Nurse banner: in `NurseDashboard.jsx`, call `getMyCoverage()`; if non-empty, show a banner "You are covering <n> nurse(s) until <latest toDate>" above the content. Covered patients already appear in My Patients.
- [ ] `npx vite build` → `✓ built`. **Commit F3.**

### Task: verification
- [ ] `mvn -o test` + `npx vite build`. Manual: temp-assign nurse A (primary ward 3) into ward 9 for a window → A shows under ward 9's incharge lists/schedule/attendance and not ward 3 for the window, auto-reverts after; substitute A with B → B's My Patients shows A's patients and B can record vitals for them, reverts after.

## Self-Review
- Coverage: temp ward (effective set) → F1 resolver + F2 wiring; substitution (patients + guard) → F1 resolver + F2 getMyPatients/guard. Auto-revert = date-range reads (no cron). Primary untouched (separate tables). Backward-compat: empty coverage ⇒ resolvers return base ⇒ A–E behavior unchanged. Tests: F1 service test + F2 test fixes. UI: F3.
- Flagged: overlap-check argument order (`to,from`); adding `NurseCoverageService` to `NurseAccessGuard` (verify no bean cycle — coverage service does not depend on the guard); `MyPatientDTO.coveredFor` new nullable field.
