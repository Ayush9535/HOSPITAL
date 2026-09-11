package com.hms.service.hospital.icu;

import com.hms.dto.icu.IcuBedCountsDTO;
import com.hms.dto.icu.IcuBedRowDTO;
import com.hms.dto.icu.IcuDashboardDTO;
import com.hms.dto.icu.IcuUnitSummaryDTO;
import com.hms.entity.Bed;
import com.hms.entity.BedStatus;
import com.hms.entity.Doctor;
import com.hms.entity.IpdAdmission;
import com.hms.entity.Patient;
import com.hms.entity.VitalsRecord;
import com.hms.entity.Ward;
import com.hms.exception.UnauthorizedException;
import com.hms.repository.BedRepository;
import com.hms.repository.DoctorRepository;
import com.hms.repository.IpdAdmissionRepository;
import com.hms.repository.PatientNurseAssignmentRepository;
import com.hms.repository.PatientRepository;
import com.hms.repository.NurseProfileRepository;
import com.hms.repository.VitalsRecordRepository;
import com.hms.repository.WardRepository;
import com.hms.security.NurseInchargeGuard;
import com.hms.security.SecurityContextHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * IcuBoardService - the read model behind the ICU dashboard and bed board (ICU Phase 2).
 *
 * <p><b>Strictly read-only.</b> This service writes nothing: no bed status, no admission, no
 * ICU-owned row. Occupancy is READ from the records that already own it —
 * {@code beds.status} for the bed's own state and {@code ipd_admission} for who is in it — so
 * ICU introduces no second representation of a bed being occupied.
 *
 * <p>The whole board is assembled inside ONE read-only transaction. Bed rows and admission rows
 * are separate tables; reading them in separate transactions would let a transfer land between
 * the two and produce counts that disagree with the rows beneath them. One transaction means
 * one snapshot, so the headline numbers, the per-unit rows and the bed grid are always the same
 * set of facts.
 *
 * <p>Counts are computed live and are deliberately NOT added to the {@code hospitalStats} cache:
 * that cache is evicted on patient writes, not on bed status changes, so an ICU occupancy figure
 * served from it would go stale the moment a bed was vacated.
 */
@Service
public class IcuBoardService {

    /** An admission occupies a bed while it is in one of these states. */
    private static final List<String> ACTIVE_ADMISSION_STATUSES = List.of("ADMITTED", "DISCHARGE_PLANNED");

    @Autowired private WardRepository wardRepository;
    @Autowired private BedRepository bedRepository;
    @Autowired private IpdAdmissionRepository ipdAdmissionRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private DoctorRepository doctorRepository;
    @Autowired private VitalsRecordRepository vitalsRecordRepository;
    @Autowired private NurseProfileRepository nurseProfileRepository;
    @Autowired private PatientNurseAssignmentRepository patientNurseAssignmentRepository;
    @Autowired private NurseInchargeGuard nurseInchargeGuard;
    @Autowired private com.hms.service.hospital.NurseCoverageService coverageService;
    @Autowired private IcuStayService icuStayService;
    @Autowired private SecurityContextHelper securityHelper;

    /** Units and their counts, without the bed grid. Backs the dashboard's lighter poll. */
    @Transactional(readOnly = true)
    public IcuDashboardDTO getSummary() {
        return build(false);
    }

    /** The full board: totals, units and every bed row. */
    @Transactional(readOnly = true)
    public IcuDashboardDTO getBoard() {
        return build(true);
    }

    // ── assembly ──────────────────────────────────────────────────────────────

    private IcuDashboardDTO build(boolean includeBeds) {
        Long hospitalId = requireHospitalId();
        IcuDashboardDTO dto = new IcuDashboardDTO();

        // Every critical-care ward in the tenant — used to answer "is ICU set up at all?"
        // separately from "may this caller see any of it?", so a nurse with no ICU ward gets an
        // empty board rather than a misleading "configure ICU" prompt.
        List<Ward> tenantUnits =
                wardRepository.findByHospitalIdAndUnitTypeIn(hospitalId, CareUnitRegistry.criticalCareKeys());
        dto.setHasCriticalCareUnits(!tenantUnits.isEmpty());

        List<Ward> units = applyRoleScope(tenantUnits);
        if (units.isEmpty()) {
            return dto;
        }

        List<Long> wardIds = units.stream().map(Ward::getWardId).toList();
        List<Bed> beds = bedRepository.findByHospitalIdAndWardIdIn(hospitalId, wardIds);
        List<IpdAdmission> admissions = ipdAdmissionRepository
                .findByHospitalIdAndStatusInAndWardIdIn(hospitalId, ACTIVE_ADMISSION_STATUSES, wardIds);

        // bedId -> the admission that claims it. A second admission claiming the same bed is
        // data corruption, not a display case: keep the first and let the row report a mismatch.
        Map<Long, IpdAdmission> admissionByBed = new HashMap<>();
        for (IpdAdmission a : admissions) {
            if (a.getBedId() != null) {
                admissionByBed.putIfAbsent(a.getBedId(), a);
            }
        }
        Set<Long> bedIdsInScope = new HashSet<>();
        for (Bed b : beds) bedIdsInScope.add(b.getBedId());

        Map<Long, List<Bed>> bedsByWard = new HashMap<>();
        for (Bed b : beds) bedsByWard.computeIfAbsent(b.getWardId(), k -> new ArrayList<>()).add(b);

        Map<Long, List<IpdAdmission>> admissionsByWard = new HashMap<>();
        for (IpdAdmission a : admissions) {
            admissionsByWard.computeIfAbsent(a.getWardId(), k -> new ArrayList<>()).add(a);
        }

        // Referenced rows, batched and tenant-scoped. Resolved only when the bed grid is asked
        // for — the summary endpoint needs none of them.
        Map<Long, Patient> patients = includeBeds ? loadPatients(hospitalId, admissions) : Map.of();
        Map<Long, Doctor> doctors = includeBeds ? loadDoctors(hospitalId, admissions) : Map.of();
        Map<Long, VitalsRecord> vitals = includeBeds ? loadLatestVitals(admissions) : Map.of();
        // ICU Phase 3: the stay slot that has been present-but-null since ICU-2.
        Map<Long, com.hms.dto.icu.IcuStayDTO> stays = includeBeds
                ? loadActiveStays(hospitalId, admissions) : Map.of();

        DetailScope scope = resolveDetailScope(hospitalId);
        LocalDate today = LocalDate.now();

        for (Ward ward : units) {
            IcuUnitSummaryDTO unit = new IcuUnitSummaryDTO();
            unit.setWardId(ward.getWardId());
            unit.setWardName(ward.getWardName());
            String unitType = unitTypeOf(ward);
            unit.setUnitType(unitType);
            unit.setUnitTypeLabel(CareUnitRegistry.labelOf(unitType));
            unit.setInchargeNurseId(ward.getInchargeNurseId());

            IcuBedCountsDTO counts = unit.getCounts();
            List<Bed> wardBeds = bedsByWard.getOrDefault(ward.getWardId(), List.of());
            List<IpdAdmission> wardAdmissions = admissionsByWard.getOrDefault(ward.getWardId(), List.of());

            for (Bed bed : wardBeds) {
                IpdAdmission admission = admissionByBed.get(bed.getBedId());
                countBed(counts, bed);
                String mismatch = occupancyMismatch(bed, admission);
                if (mismatch != null) counts.setOccupancyMismatches(counts.getOccupancyMismatches() + 1);

                if (includeBeds) {
                    dto.getBeds().add(toRow(ward, unitType, bed, admission, mismatch,
                            patients, doctors, vitals, stays, scope));
                }
            }

            // The admission record is authoritative for WHO is in the unit, so patients are
            // counted from admissions rather than from occupied beds. An admission whose bed no
            // longer resolves still counts as a patient here, and is reported as a mismatch.
            counts.setPatients(wardAdmissions.size());
            for (IpdAdmission a : wardAdmissions) {
                if (a.getAdmissionDatetime() != null && today.equals(a.getAdmissionDatetime().toLocalDate())) {
                    counts.setNewAdmissionsToday(counts.getNewAdmissionsToday() + 1);
                }
                if (!Boolean.TRUE.equals(a.getAdmissionConfirmed())) {
                    counts.setPendingConfirmation(counts.getPendingConfirmation() + 1);
                }
                if (a.getBedId() == null || !bedIdsInScope.contains(a.getBedId())) {
                    counts.setOccupancyMismatches(counts.getOccupancyMismatches() + 1);
                }
            }

            dto.getUnits().add(unit);
            addInto(dto.getTotals(), counts);
        }

        return dto;
    }

    private void countBed(IcuBedCountsDTO counts, Bed bed) {
        counts.setTotalBeds(counts.getTotalBeds() + 1);
        String status = bed.getStatus() == null ? "" : bed.getStatus();
        switch (status) {
            case BedStatus.OCCUPIED -> counts.setOccupied(counts.getOccupied() + 1);
            case BedStatus.AVAILABLE -> counts.setAvailable(counts.getAvailable() + 1);
            case BedStatus.CLEANING -> {
                counts.setCleaning(counts.getCleaning() + 1);
                counts.setAwaitingCleaning(counts.getAwaitingCleaning() + 1);
            }
            case BedStatus.MAINTENANCE -> counts.setMaintenance(counts.getMaintenance() + 1);
            default -> { /* an unknown status is counted in totalBeds only */ }
        }
    }

    /**
     * Whether the bed's own status and the admission records agree, and why not.
     *
     * <p>Reported rather than reconciled. The board shows what the records actually say, so a
     * genuine inconsistency reaches the ward instead of being smoothed over by whichever row the
     * view happened to prefer.
     */
    private String occupancyMismatch(Bed bed, IpdAdmission admission) {
        boolean markedOccupied = BedStatus.OCCUPIED.equalsIgnoreCase(bed.getStatus());
        if (markedOccupied && admission == null) {
            return "Bed is marked occupied but has no active admission";
        }
        if (!markedOccupied && admission != null) {
            return "Active admission " + admission.getIpdNumber()
                    + " is on a bed marked " + bed.getStatus();
        }
        return null;
    }

    private Map<Long, com.hms.dto.icu.IcuStayDTO> loadActiveStays(
            Long hospitalId, List<IpdAdmission> admissions) {
        Set<Long> ids = new HashSet<>();
        for (IpdAdmission a : admissions) ids.add(a.getId());
        if (ids.isEmpty()) return Map.of();
        Map<Long, com.hms.dto.icu.IcuStayDTO> out = new HashMap<>();
        for (com.hms.entity.IcuStay s : icuStayService.activeStaysFor(hospitalId, ids)) {
            out.putIfAbsent(s.getIpdAdmissionId(), icuStayService.toDto(s));
        }
        return out;
    }

    private IcuBedRowDTO toRow(Ward ward, String unitType, Bed bed, IpdAdmission admission,
                               String mismatch, Map<Long, Patient> patients,
                               Map<Long, Doctor> doctors, Map<Long, VitalsRecord> vitals,
                               Map<Long, com.hms.dto.icu.IcuStayDTO> stays,
                               DetailScope scope) {
        IcuBedRowDTO row = new IcuBedRowDTO();
        row.setBedId(bed.getBedId());
        row.setBedCode(bed.getBedCode());
        row.setWardId(ward.getWardId());
        row.setWardName(ward.getWardName());
        row.setUnitType(unitType);
        row.setUnitTypeLabel(CareUnitRegistry.labelOf(unitType));
        row.setStatus(bed.getStatus());
        row.setOccupancyConsistent(mismatch == null);
        row.setOccupancyNote(mismatch);

        if (admission == null) return row;

        boolean maySeeIdentity = scope.maySeeIdentity(admission);
        boolean maySeeClinical = scope.maySeeClinicalDetail(admission);

        // The admission id is always present so the row can link to the patient workspace; the
        // workspace applies its own access rules on open.
        row.setIpdAdmissionId(admission.getId());
        row.setAdmissionConfirmed(admission.getAdmissionConfirmed());

        if (!maySeeIdentity) return row;

        row.setIcuStay(stays.get(admission.getId()));
        row.setIpdNumber(admission.getIpdNumber());
        row.setAdmittedAt(admission.getAdmissionDatetime());
        Patient p = patients.get(admission.getPatientId());
        if (p != null) {
            row.setPatientName(p.getName());
            row.setAge(p.getAge());
            row.setGender(p.getGender());
        }
        Doctor d = admission.getDoctorId() == null ? null : doctors.get(admission.getDoctorId());
        if (d != null) row.setConsultantName(d.getName());

        if (!maySeeClinical) return row;

        row.setPrimaryDiagnosis(admission.getPrimaryDiagnosis());
        VitalsRecord v = vitals.get(admission.getId());
        if (v != null) {
            row.setLatestSpo2(v.getSpo2());
            row.setLatestRespiratoryRate(v.getRespiratoryRate());
            row.setVitalsRecordedAt(v.getRecordedAt());
        }
        return row;
    }

    private void addInto(IcuBedCountsDTO totals, IcuBedCountsDTO unit) {
        totals.setTotalBeds(totals.getTotalBeds() + unit.getTotalBeds());
        totals.setOccupied(totals.getOccupied() + unit.getOccupied());
        totals.setAvailable(totals.getAvailable() + unit.getAvailable());
        totals.setCleaning(totals.getCleaning() + unit.getCleaning());
        totals.setMaintenance(totals.getMaintenance() + unit.getMaintenance());
        totals.setPatients(totals.getPatients() + unit.getPatients());
        totals.setNewAdmissionsToday(totals.getNewAdmissionsToday() + unit.getNewAdmissionsToday());
        totals.setPendingConfirmation(totals.getPendingConfirmation() + unit.getPendingConfirmation());
        totals.setAwaitingCleaning(totals.getAwaitingCleaning() + unit.getAwaitingCleaning());
        totals.setOccupancyMismatches(totals.getOccupancyMismatches() + unit.getOccupancyMismatches());
    }

    // ── batched, tenant-scoped resolution of referenced rows ──────────────────

    private Map<Long, Patient> loadPatients(Long hospitalId, List<IpdAdmission> admissions) {
        Set<Long> ids = new HashSet<>();
        for (IpdAdmission a : admissions) if (a.getPatientId() != null) ids.add(a.getPatientId());
        if (ids.isEmpty()) return Map.of();
        Map<Long, Patient> out = new HashMap<>();
        for (Patient p : patientRepository.findByHospitalIdAndIdIn(hospitalId, ids)) out.put(p.getId(), p);
        return out;
    }

    private Map<Long, Doctor> loadDoctors(Long hospitalId, List<IpdAdmission> admissions) {
        Set<Long> ids = new HashSet<>();
        for (IpdAdmission a : admissions) if (a.getDoctorId() != null) ids.add(a.getDoctorId());
        if (ids.isEmpty()) return Map.of();
        Map<Long, Doctor> out = new HashMap<>();
        for (Doctor d : doctorRepository.findByHospitalIdAndIdIn(hospitalId, ids)) out.put(d.getId(), d);
        return out;
    }

    private Map<Long, VitalsRecord> loadLatestVitals(List<IpdAdmission> admissions) {
        Set<Long> ids = new HashSet<>();
        for (IpdAdmission a : admissions) ids.add(a.getId());
        if (ids.isEmpty()) return Map.of();
        Map<Long, VitalsRecord> out = new HashMap<>();
        for (VitalsRecord v : vitalsRecordRepository.findLatestForAdmissions(ids)) {
            out.putIfAbsent(v.getIpdAdmissionId(), v);
        }
        return out;
    }

    // ── access scope, reusing the existing guards ─────────────────────────────

    /**
     * Narrows the tenant's critical-care wards to the ones this caller may see.
     *
     * <p>No new rule is invented: admins and the clinical roles that already work across the
     * hospital see every unit, a Nurse Incharge is limited to the wards
     * {@link NurseInchargeGuard} already governs, and a staff nurse sees their own ward.
     */
    private List<Ward> applyRoleScope(List<Ward> criticalCareWards) {
        if (criticalCareWards.isEmpty()) return List.of();
        String role = securityHelper.getCurrentUserRole();
        if (role == null) return List.of();

        switch (role) {
            case "HOSPITAL_ADMIN", "DOCTOR", "RECEPTIONIST" -> {
                return criticalCareWards;
            }
            case "NURSE_INCHARGE" -> {
                Set<Long> mine = new HashSet<>(nurseInchargeGuard.myWardIds());
                return criticalCareWards.stream().filter(w -> mine.contains(w.getWardId())).toList();
            }
            case "NURSE" -> {
                Long myWardId = nurseProfileRepository.findByUserId(securityHelper.getCurrentUserId())
                        .map(com.hms.entity.NurseProfile::getWardId).orElse(null);
                if (myWardId == null) return List.of();
                return criticalCareWards.stream().filter(w -> myWardId.equals(w.getWardId())).toList();
            }
            default -> {
                return List.of();
            }
        }
    }

    /** Per-role limits on how much of a patient a board row may reveal. */
    private DetailScope resolveDetailScope(Long hospitalId) {
        String role = securityHelper.getCurrentUserRole();
        if ("DOCTOR".equals(role)) {
            Long doctorId = doctorRepository
                    .findByEmailAndHospitalId(securityHelper.getCurrentUserEmail(), hospitalId)
                    .map(Doctor::getId).orElse(null);
            return new DetailScope(role, doctorId, null, patientNurseAssignmentRepository, coverageService);
        }
        if ("NURSE".equals(role)) {
            return new DetailScope(role, null, securityHelper.getCurrentUserId(),
                    patientNurseAssignmentRepository, coverageService);
        }
        return new DetailScope(role, null, null, patientNurseAssignmentRepository, coverageService);
    }

    /**
     * How much of a patient one caller may see on a board row.
     *
     * <p>Identity is the patient's name, age, IPD number and consultant. Clinical detail is the
     * diagnosis and the latest recorded vitals. Reception runs admissions and already works with
     * patient identities, but has no clinical role, so it sees identity and no clinical detail.
     * A staff nurse sees only the patients assigned to them — the same rule
     * {@code NurseAccessGuard} enforces on every other nursing screen, applied here as a
     * non-throwing predicate because a board filters rows rather than rejecting a request.
     */
    private record DetailScope(String role, Long doctorId, Long nurseUserId,
                               PatientNurseAssignmentRepository assignments,
                               com.hms.service.hospital.NurseCoverageService coverage) {

        boolean maySeeIdentity(IpdAdmission admission) {
            if ("NURSE".equals(role)) return isAssigned(admission);
            return true;
        }

        boolean maySeeClinicalDetail(IpdAdmission admission) {
            return switch (role == null ? "" : role) {
                case "HOSPITAL_ADMIN", "NURSE_INCHARGE" -> true;
                case "DOCTOR" -> doctorId != null && doctorId.equals(admission.getDoctorId());
                case "NURSE" -> isAssigned(admission);
                default -> false; // RECEPTIONIST and anything else
            };
        }

        /**
         * The staff-nurse rule, matching {@link com.hms.security.NurseAccessGuard} exactly:
         * a direct active assignment OR currently covering the nurse who holds one.
         *
         * <p>The coverage branch was missing when this board was first written, so a nurse
         * standing in for a colleague could open the patient from their dashboard yet saw
         * "not in your scope" for the same patient's bed here. Re-implementing the rule instead
         * of deferring to the guard is what allowed the two to drift.
         */
        private boolean isAssigned(IpdAdmission admission) {
            if (nurseUserId == null) return false;
            if (assignments.existsByIpdAdmissionIdAndNurseUserIdAndIsActiveTrue(
                    admission.getId(), nurseUserId)) {
                return true;
            }
            return coverage.coversAdmission(nurseUserId, admission.getId(), java.time.LocalDate.now());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String unitTypeOf(Ward ward) {
        return ward.getUnitType() == null ? CareUnitRegistry.GENERAL : ward.getUnitType();
    }

    private Long requireHospitalId() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            throw new UnauthorizedException("Hospital ID not found in context");
        }
        return hospitalId;
    }

    /** The unit-type catalogue, for the ward settings form. */
    public List<CareUnitRegistry.UnitType> unitTypes() {
        return CareUnitRegistry.UNIT_TYPES;
    }
}
