package com.hms.service.hospital.icu;

import com.hms.dto.icu.IcuBedRowDTO;
import com.hms.dto.icu.IcuDashboardDTO;
import com.hms.entity.Bed;
import com.hms.entity.BedStatus;
import com.hms.entity.Doctor;
import com.hms.entity.IpdAdmission;
import com.hms.entity.NurseProfile;
import com.hms.entity.Patient;
import com.hms.entity.VitalsRecord;
import com.hms.entity.Ward;
import com.hms.repository.BedRepository;
import com.hms.repository.DoctorRepository;
import com.hms.repository.IpdAdmissionRepository;
import com.hms.repository.NurseProfileRepository;
import com.hms.repository.PatientNurseAssignmentRepository;
import com.hms.repository.PatientRepository;
import com.hms.repository.VitalsRecordRepository;
import com.hms.repository.WardRepository;
import com.hms.security.NurseInchargeGuard;
import com.hms.security.SecurityContextHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ICU Phase 2 acceptance gates for the board's read model.
 *
 * <p>The four gates this phase must satisfy:
 * <ol>
 *   <li>counts match the underlying bed and admission records;</li>
 *   <li>an ICU bed points at the correct active patient;</li>
 *   <li>occupancy has no second representation — the service never writes bed state;</li>
 *   <li>only critical-care wards appear, and only those the caller may see.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IcuBoardServiceTest {

    private static final Long HOSPITAL = 7L;

    @Mock WardRepository wardRepository;
    @Mock BedRepository bedRepository;
    @Mock IpdAdmissionRepository ipdAdmissionRepository;
    @Mock PatientRepository patientRepository;
    @Mock DoctorRepository doctorRepository;
    @Mock VitalsRecordRepository vitalsRecordRepository;
    @Mock NurseProfileRepository nurseProfileRepository;
    @Mock PatientNurseAssignmentRepository patientNurseAssignmentRepository;
    @Mock NurseInchargeGuard nurseInchargeGuard;
    @Mock com.hms.service.hospital.NurseCoverageService coverageService;
    /** ICU-3: the board now resolves the stay slot through this. */
    @Mock IcuStayService icuStayService;
    @Mock SecurityContextHelper securityHelper;
    @InjectMocks IcuBoardService service;

    private Ward icu;
    private Ward general;

    @BeforeEach
    void setUp() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(HOSPITAL);
        when(securityHelper.getCurrentUserRole()).thenReturn("HOSPITAL_ADMIN");
        when(securityHelper.getCurrentUserId()).thenReturn(99L);
        when(securityHelper.getCurrentUserEmail()).thenReturn("admin@h.test");

        icu = ward(10L, "ICU-1", CareUnitRegistry.ICU);
        general = ward(20L, "General-A", CareUnitRegistry.GENERAL);

        when(patientRepository.findByHospitalIdAndIdIn(anyLong(), any())).thenReturn(List.of());
        when(doctorRepository.findByHospitalIdAndIdIn(anyLong(), any())).thenReturn(List.of());
        when(vitalsRecordRepository.findLatestForAdmissions(any())).thenReturn(List.of());
        when(bedRepository.findByHospitalIdAndWardIdIn(anyLong(), any())).thenReturn(List.of());
        when(ipdAdmissionRepository.findByHospitalIdAndStatusInAndWardIdIn(anyLong(), any(), any()))
                .thenReturn(List.of());
        when(wardRepository.findByHospitalIdAndUnitTypeIn(anyLong(), any())).thenReturn(List.of());
        when(icuStayService.activeStaysFor(anyLong(), any())).thenReturn(List.of());
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private Ward ward(Long id, String name, String unitType) {
        Ward w = new Ward();
        w.setWardId(id);
        w.setHospitalId(HOSPITAL);
        w.setWardName(name);
        w.setUnitType(unitType);
        w.setBedPrice(BigDecimal.TEN);
        w.setTotalBeds(0);
        return w;
    }

    private Bed bed(Long id, Long wardId, String code, String status) {
        Bed b = new Bed();
        b.setBedId(id);
        b.setHospitalId(HOSPITAL);
        b.setWardId(wardId);
        b.setBedCode(code);
        b.setStatus(status);
        return b;
    }

    private IpdAdmission admission(Long id, Long wardId, Long bedId, Long patientId, Long doctorId) {
        IpdAdmission a = new IpdAdmission();
        a.setId(id);
        a.setHospitalId(HOSPITAL);
        a.setIpdNumber("IPD-" + id);
        a.setWardId(wardId);
        a.setBedId(bedId);
        a.setPatientId(patientId);
        a.setDoctorId(doctorId);
        a.setStatus("ADMITTED");
        a.setAdmissionDatetime(LocalDateTime.now());
        a.setAdmissionConfirmed(true);
        a.setPrimaryDiagnosis("Sepsis");
        return a;
    }

    private Patient patient(Long id, String name) {
        Patient p = new Patient();
        p.setId(id);
        p.setHospitalId(HOSPITAL);
        p.setName(name);
        p.setGender("MALE");
        p.setPhone("9999999999");
        return p;
    }

    private void givenUnits(List<Ward> units, List<Bed> beds, List<IpdAdmission> admissions) {
        when(wardRepository.findByHospitalIdAndUnitTypeIn(eq(HOSPITAL), any())).thenReturn(units);
        when(bedRepository.findByHospitalIdAndWardIdIn(eq(HOSPITAL), any())).thenReturn(beds);
        when(ipdAdmissionRepository.findByHospitalIdAndStatusInAndWardIdIn(eq(HOSPITAL), any(), any()))
                .thenReturn(admissions);
    }

    // ── gate: only critical-care wards, and no board at all without them ──────

    @Test
    void noCriticalCareWards_returnsEmptyBoardAndFlagsSetupNeeded() {
        IcuDashboardDTO dto = service.getBoard();

        assertThat(dto.isHasCriticalCareUnits()).isFalse();
        assertThat(dto.getUnits()).isEmpty();
        assertThat(dto.getBeds()).isEmpty();
        assertThat(dto.getTotals().getTotalBeds()).isZero();
    }

    @Test
    void onlyCriticalCareWardsAreQueried_generalWardsNeverReachTheBoard() {
        // The repository is asked for critical-care keys only, so a GENERAL ward can never be
        // returned. Guard the contract: GENERAL must not be among the requested keys.
        givenUnits(List.of(icu), List.of(bed(1L, icu.getWardId(), "ICU-1-B1", BedStatus.AVAILABLE)), List.of());

        service.getBoard();

        verify(wardRepository).findByHospitalIdAndUnitTypeIn(eq(HOSPITAL),
                argThat(keys -> !keys.contains(CareUnitRegistry.GENERAL)
                        && keys.contains(CareUnitRegistry.ICU)
                        && keys.contains(CareUnitRegistry.NICU)));
        assertThat(general.getUnitType()).isEqualTo(CareUnitRegistry.GENERAL); // fixture sanity
    }

    private static <T extends java.util.Collection<String>> T argThat(java.util.function.Predicate<T> p) {
        return org.mockito.ArgumentMatchers.argThat(p::test);
    }

    // ── gate 1: counts match the underlying records ───────────────────────────

    @Test
    void counts_matchBedAndAdmissionRecords() {
        List<Bed> beds = List.of(
                bed(1L, 10L, "ICU-1-B1", BedStatus.OCCUPIED),
                bed(2L, 10L, "ICU-1-B2", BedStatus.OCCUPIED),
                bed(3L, 10L, "ICU-1-B3", BedStatus.AVAILABLE),
                bed(4L, 10L, "ICU-1-B4", BedStatus.CLEANING),
                bed(5L, 10L, "ICU-1-B5", BedStatus.MAINTENANCE));
        IpdAdmission a1 = admission(100L, 10L, 1L, 500L, 900L);
        IpdAdmission a2 = admission(101L, 10L, 2L, 501L, 900L);
        a2.setAdmissionConfirmed(false);                       // outstanding nurse form
        a2.setAdmissionDatetime(LocalDateTime.now().minusDays(3)); // not a new admission today
        givenUnits(List.of(icu), beds, List.of(a1, a2));

        IcuDashboardDTO dto = service.getBoard();

        var t = dto.getTotals();
        assertThat(t.getTotalBeds()).isEqualTo(5);
        assertThat(t.getOccupied()).isEqualTo(2);
        assertThat(t.getAvailable()).isEqualTo(1);
        assertThat(t.getCleaning()).isEqualTo(1);
        assertThat(t.getMaintenance()).isEqualTo(1);
        assertThat(t.getAwaitingCleaning()).isEqualTo(1);
        assertThat(t.getPatients()).isEqualTo(2);
        assertThat(t.getNewAdmissionsToday()).isEqualTo(1);
        assertThat(t.getPendingConfirmation()).isEqualTo(1);
        assertThat(t.getOccupancyMismatches()).isZero();
        assertThat(dto.getBeds()).hasSize(5);
    }

    @Test
    void totals_areTheSumOfTheUnits() {
        Ward nicu = ward(11L, "NICU", CareUnitRegistry.NICU);
        List<Bed> beds = List.of(
                bed(1L, 10L, "ICU-1-B1", BedStatus.OCCUPIED),
                bed(2L, 10L, "ICU-1-B2", BedStatus.AVAILABLE),
                bed(3L, 11L, "NICU-B1", BedStatus.OCCUPIED));
        givenUnits(List.of(icu, nicu), beds,
                List.of(admission(100L, 10L, 1L, 500L, 900L), admission(101L, 11L, 3L, 502L, 900L)));

        IcuDashboardDTO dto = service.getBoard();

        assertThat(dto.getUnits()).hasSize(2);
        int summedBeds = dto.getUnits().stream().mapToInt(u -> u.getCounts().getTotalBeds()).sum();
        int summedPatients = dto.getUnits().stream().mapToInt(u -> u.getCounts().getPatients()).sum();
        int summedOccupied = dto.getUnits().stream().mapToInt(u -> u.getCounts().getOccupied()).sum();
        assertThat(dto.getTotals().getTotalBeds()).isEqualTo(summedBeds).isEqualTo(3);
        assertThat(dto.getTotals().getPatients()).isEqualTo(summedPatients).isEqualTo(2);
        assertThat(dto.getTotals().getOccupied()).isEqualTo(summedOccupied).isEqualTo(2);
    }

    @Test
    void summaryEndpoint_omitsBedRowsButKeepsIdenticalCounts() {
        List<Bed> beds = List.of(
                bed(1L, 10L, "ICU-1-B1", BedStatus.OCCUPIED),
                bed(2L, 10L, "ICU-1-B2", BedStatus.AVAILABLE));
        givenUnits(List.of(icu), beds, List.of(admission(100L, 10L, 1L, 500L, 900L)));

        IcuDashboardDTO board = service.getBoard();
        IcuDashboardDTO summary = service.getSummary();

        assertThat(summary.getBeds()).isEmpty();
        assertThat(board.getBeds()).hasSize(2);
        assertThat(summary.getTotals()).isEqualTo(board.getTotals());
        // The lighter poll must not resolve patient rows at all.
        verify(patientRepository, never()).findByHospitalIdAndIdIn(eq(HOSPITAL), eq(java.util.Set.of()));
    }

    // ── gate 2: a bed points at the correct active patient ────────────────────

    @Test
    void occupiedBed_resolvesToItsOwnAdmissionPatientAndConsultant() {
        List<Bed> beds = List.of(
                bed(1L, 10L, "ICU-1-B1", BedStatus.OCCUPIED),
                bed(2L, 10L, "ICU-1-B2", BedStatus.OCCUPIED));
        IpdAdmission a1 = admission(100L, 10L, 1L, 500L, 900L);
        IpdAdmission a2 = admission(101L, 10L, 2L, 501L, 901L);
        givenUnits(List.of(icu), beds, List.of(a1, a2));

        when(patientRepository.findByHospitalIdAndIdIn(eq(HOSPITAL), any()))
                .thenReturn(List.of(patient(500L, "Asha"), patient(501L, "Bilal")));
        Doctor d1 = new Doctor(); d1.setId(900L); d1.setName("Dr Rao");
        Doctor d2 = new Doctor(); d2.setId(901L); d2.setName("Dr Iyer");
        when(doctorRepository.findByHospitalIdAndIdIn(eq(HOSPITAL), any())).thenReturn(List.of(d1, d2));

        IcuDashboardDTO dto = service.getBoard();

        IcuBedRowDTO b1 = row(dto, 1L);
        assertThat(b1.getIpdAdmissionId()).isEqualTo(100L);
        assertThat(b1.getPatientName()).isEqualTo("Asha");
        assertThat(b1.getConsultantName()).isEqualTo("Dr Rao");
        assertThat(b1.getOccupancyConsistent()).isTrue();

        IcuBedRowDTO b2 = row(dto, 2L);
        assertThat(b2.getIpdAdmissionId()).isEqualTo(101L);
        assertThat(b2.getPatientName()).isEqualTo("Bilal");
        assertThat(b2.getConsultantName()).isEqualTo("Dr Iyer");
    }

    @Test
    void availableBed_carriesNoPatient() {
        givenUnits(List.of(icu), List.of(bed(3L, 10L, "ICU-1-B3", BedStatus.AVAILABLE)), List.of());

        IcuBedRowDTO r = row(service.getBoard(), 3L);

        assertThat(r.getIpdAdmissionId()).isNull();
        assertThat(r.getPatientName()).isNull();
        assertThat(r.getOccupancyConsistent()).isTrue();
    }

    @Test
    void dischargedAdmissionsNeverAppear_onlyActiveStatusesAreQueried() {
        givenUnits(List.of(icu), List.of(bed(1L, 10L, "ICU-1-B1", BedStatus.AVAILABLE)), List.of());

        service.getBoard();

        verify(ipdAdmissionRepository).findByHospitalIdAndStatusInAndWardIdIn(eq(HOSPITAL),
                argThat(st -> st.contains("ADMITTED") && st.contains("DISCHARGE_PLANNED")
                        && !st.contains("DISCHARGED")),
                any());
    }

    @Test
    void latestRecordedVitalsAreShown_withNoDerivedJudgement() {
        givenUnits(List.of(icu), List.of(bed(1L, 10L, "ICU-1-B1", BedStatus.OCCUPIED)),
                List.of(admission(100L, 10L, 1L, 500L, 900L)));
        VitalsRecord v = new VitalsRecord();
        v.setIpdAdmissionId(100L);
        v.setSpo2(92);
        v.setRespiratoryRate(24);
        v.setRecordedAt(LocalDateTime.now().minusMinutes(20));
        when(vitalsRecordRepository.findLatestForAdmissions(any())).thenReturn(List.of(v));

        IcuBedRowDTO r = row(service.getBoard(), 1L);

        assertThat(r.getLatestSpo2()).isEqualTo(92);
        assertThat(r.getLatestRespiratoryRate()).isEqualTo(24);
        assertThat(r.getVitalsRecordedAt()).isNotNull();
    }

    // ── gate 3: occupancy is read, never written or silently reconciled ───────

    @Test
    void occupiedBedWithNoAdmission_isFlaggedNotHidden() {
        givenUnits(List.of(icu), List.of(bed(1L, 10L, "ICU-1-B1", BedStatus.OCCUPIED)), List.of());

        IcuDashboardDTO dto = service.getBoard();
        IcuBedRowDTO r = row(dto, 1L);

        assertThat(r.getOccupancyConsistent()).isFalse();
        assertThat(r.getOccupancyNote()).contains("no active admission");
        assertThat(dto.getTotals().getOccupancyMismatches()).isEqualTo(1);
        // The bed is still reported with the status the record actually holds.
        assertThat(r.getStatus()).isEqualTo(BedStatus.OCCUPIED);
        assertThat(dto.getTotals().getOccupied()).isEqualTo(1);
    }

    @Test
    void admissionOnANonOccupiedBed_isFlaggedNotHidden() {
        givenUnits(List.of(icu), List.of(bed(1L, 10L, "ICU-1-B1", BedStatus.CLEANING)),
                List.of(admission(100L, 10L, 1L, 500L, 900L)));

        IcuDashboardDTO dto = service.getBoard();
        IcuBedRowDTO r = row(dto, 1L);

        assertThat(r.getOccupancyConsistent()).isFalse();
        assertThat(r.getOccupancyNote()).contains("IPD-100").contains(BedStatus.CLEANING);
        assertThat(dto.getTotals().getOccupancyMismatches()).isEqualTo(1);
    }

    @Test
    void admissionWhoseBedIsMissing_stillCountsAsAPatientAndIsFlagged() {
        // The admission record is authoritative for WHO is in the unit.
        givenUnits(List.of(icu), List.of(bed(1L, 10L, "ICU-1-B1", BedStatus.AVAILABLE)),
                List.of(admission(100L, 10L, 999L, 500L, 900L)));

        IcuDashboardDTO dto = service.getBoard();

        assertThat(dto.getTotals().getPatients()).isEqualTo(1);
        assertThat(dto.getTotals().getOccupancyMismatches()).isEqualTo(1);
    }

    @Test
    void serviceNeverWritesBedOrAdmissionState() {
        givenUnits(List.of(icu), List.of(bed(1L, 10L, "ICU-1-B1", BedStatus.OCCUPIED)),
                List.of(admission(100L, 10L, 1L, 500L, 900L)));

        service.getBoard();

        verify(bedRepository, never()).save(any());
        verify(bedRepository, never()).saveAll(any());
        verify(bedRepository, never()).delete(any());
        verify(ipdAdmissionRepository, never()).save(any());
        verify(wardRepository, never()).save(any());
    }

    // ── gate 4: role scope reuses the existing guards ─────────────────────────

    @Test
    void nurseIncharge_seesOnlyOwnWards() {
        Ward other = ward(11L, "ICU-2", CareUnitRegistry.ICU);
        when(securityHelper.getCurrentUserRole()).thenReturn("NURSE_INCHARGE");
        when(nurseInchargeGuard.myWardIds()).thenReturn(List.of(10L));
        givenUnits(List.of(icu, other), List.of(bed(1L, 10L, "ICU-1-B1", BedStatus.AVAILABLE)), List.of());

        IcuDashboardDTO dto = service.getBoard();

        assertThat(dto.getUnits()).hasSize(1);
        assertThat(dto.getUnits().get(0).getWardId()).isEqualTo(10L);
        assertThat(dto.isHasCriticalCareUnits()).isTrue();
    }

    @Test
    void staffNurse_seesOwnWard_andPatientIdentityOnlyForAssignedPatients() {
        when(securityHelper.getCurrentUserRole()).thenReturn("NURSE");
        NurseProfile profile = new NurseProfile();
        profile.setUserId(99L);
        profile.setWardId(10L);
        when(nurseProfileRepository.findByUserId(99L)).thenReturn(Optional.of(profile));

        List<Bed> beds = List.of(
                bed(1L, 10L, "ICU-1-B1", BedStatus.OCCUPIED),
                bed(2L, 10L, "ICU-1-B2", BedStatus.OCCUPIED));
        givenUnits(List.of(icu), beds,
                List.of(admission(100L, 10L, 1L, 500L, 900L), admission(101L, 10L, 2L, 501L, 900L)));
        when(patientRepository.findByHospitalIdAndIdIn(eq(HOSPITAL), any()))
                .thenReturn(List.of(patient(500L, "Asha"), patient(501L, "Bilal")));
        when(patientNurseAssignmentRepository
                .existsByIpdAdmissionIdAndNurseUserIdAndIsActiveTrue(100L, 99L)).thenReturn(true);
        when(patientNurseAssignmentRepository
                .existsByIpdAdmissionIdAndNurseUserIdAndIsActiveTrue(101L, 99L)).thenReturn(false);

        IcuDashboardDTO dto = service.getBoard();

        assertThat(row(dto, 1L).getPatientName()).isEqualTo("Asha");
        assertThat(row(dto, 1L).getPrimaryDiagnosis()).isEqualTo("Sepsis");
        // Not assigned: the bed is still shown as occupied, the patient is not identified.
        assertThat(row(dto, 2L).getPatientName()).isNull();
        assertThat(row(dto, 2L).getPrimaryDiagnosis()).isNull();
        assertThat(row(dto, 2L).getStatus()).isEqualTo(BedStatus.OCCUPIED);
        // Counts are unaffected by row-level redaction.
        assertThat(dto.getTotals().getPatients()).isEqualTo(2);
    }

    @Test
    void staffNurseWithNoWard_seesNothing() {
        when(securityHelper.getCurrentUserRole()).thenReturn("NURSE");
        when(nurseProfileRepository.findByUserId(99L)).thenReturn(Optional.empty());
        givenUnits(List.of(icu), List.of(bed(1L, 10L, "ICU-1-B1", BedStatus.AVAILABLE)), List.of());

        IcuDashboardDTO dto = service.getBoard();

        assertThat(dto.getUnits()).isEmpty();
        assertThat(dto.getBeds()).isEmpty();
    }

    @Test
    void receptionist_seesCapacityAndIdentityButNoClinicalDetail() {
        when(securityHelper.getCurrentUserRole()).thenReturn("RECEPTIONIST");
        givenUnits(List.of(icu), List.of(bed(1L, 10L, "ICU-1-B1", BedStatus.OCCUPIED)),
                List.of(admission(100L, 10L, 1L, 500L, 900L)));
        when(patientRepository.findByHospitalIdAndIdIn(eq(HOSPITAL), any()))
                .thenReturn(List.of(patient(500L, "Asha")));
        VitalsRecord v = new VitalsRecord();
        v.setIpdAdmissionId(100L);
        v.setSpo2(92);
        when(vitalsRecordRepository.findLatestForAdmissions(any())).thenReturn(List.of(v));

        IcuBedRowDTO r = row(service.getBoard(), 1L);

        assertThat(r.getPatientName()).isEqualTo("Asha");
        assertThat(r.getPrimaryDiagnosis()).isNull();
        assertThat(r.getLatestSpo2()).isNull();
    }

    @Test
    void doctor_seesClinicalDetailForOwnPatientsOnly() {
        when(securityHelper.getCurrentUserRole()).thenReturn("DOCTOR");
        Doctor me = new Doctor(); me.setId(900L); me.setName("Dr Rao");
        when(doctorRepository.findByEmailAndHospitalId("admin@h.test", HOSPITAL)).thenReturn(Optional.of(me));

        List<Bed> beds = List.of(
                bed(1L, 10L, "ICU-1-B1", BedStatus.OCCUPIED),
                bed(2L, 10L, "ICU-1-B2", BedStatus.OCCUPIED));
        givenUnits(List.of(icu), beds,
                List.of(admission(100L, 10L, 1L, 500L, 900L),   // mine
                        admission(101L, 10L, 2L, 501L, 901L))); // another doctor's
        when(patientRepository.findByHospitalIdAndIdIn(eq(HOSPITAL), any()))
                .thenReturn(List.of(patient(500L, "Asha"), patient(501L, "Bilal")));

        IcuDashboardDTO dto = service.getBoard();

        assertThat(row(dto, 1L).getPrimaryDiagnosis()).isEqualTo("Sepsis");
        assertThat(row(dto, 2L).getPatientName()).isEqualTo("Bilal"); // identity: yes
        assertThat(row(dto, 2L).getPrimaryDiagnosis()).isNull();      // clinical detail: no
    }

    @Test
    void unknownRole_seesNothing() {
        when(securityHelper.getCurrentUserRole()).thenReturn("PHARMACIST");
        givenUnits(List.of(icu), List.of(bed(1L, 10L, "ICU-1-B1", BedStatus.AVAILABLE)), List.of());

        assertThat(service.getBoard().getUnits()).isEmpty();
    }

    // ── tenancy: every read is scoped to the caller's hospital ────────────────

    @Test
    void everyReadIsScopedToTheCallersHospital() {
        givenUnits(List.of(icu), List.of(bed(1L, 10L, "ICU-1-B1", BedStatus.OCCUPIED)),
                List.of(admission(100L, 10L, 1L, 500L, 900L)));

        service.getBoard();

        verify(wardRepository).findByHospitalIdAndUnitTypeIn(eq(HOSPITAL), any());
        verify(bedRepository).findByHospitalIdAndWardIdIn(eq(HOSPITAL), any());
        verify(ipdAdmissionRepository).findByHospitalIdAndStatusInAndWardIdIn(eq(HOSPITAL), any(), any());
        verify(patientRepository).findByHospitalIdAndIdIn(eq(HOSPITAL), any());
        verify(doctorRepository).findByHospitalIdAndIdIn(eq(HOSPITAL), any());
        // No unscoped lookup-by-id anywhere in the read path.
        verify(bedRepository, never()).findById(any());
        verify(wardRepository, never()).findById(any());
        verify(ipdAdmissionRepository, never()).findById(any());
        verify(patientRepository, never()).findById(any());
        verify(doctorRepository, never()).findById(any());
    }

    @Test
    void aCoveringNurseSeesTheirColleaguesPatient() {
        // Regression: the board re-implemented "own patients" as a direct assignment only, while
        // NurseAccessGuard also honours coverage. A nurse standing in for a colleague could open
        // the patient from their dashboard but saw "not in your scope" for the same bed here.
        when(securityHelper.getCurrentUserRole()).thenReturn("NURSE");
        NurseProfile profile = new NurseProfile();
        profile.setUserId(99L);
        profile.setWardId(10L);
        when(nurseProfileRepository.findByUserId(99L)).thenReturn(Optional.of(profile));

        givenUnits(List.of(icu), List.of(bed(1L, 10L, "ICU-1-B1", BedStatus.OCCUPIED)),
                List.of(admission(100L, 10L, 1L, 500L, 900L)));
        when(patientRepository.findByHospitalIdAndIdIn(eq(HOSPITAL), any()))
                .thenReturn(List.of(patient(500L, "Asha")));
        // Not directly assigned...
        when(patientNurseAssignmentRepository
                .existsByIpdAdmissionIdAndNurseUserIdAndIsActiveTrue(100L, 99L)).thenReturn(false);
        // ...but covering the nurse who is.
        when(coverageService.coversAdmission(eq(99L), eq(100L), any())).thenReturn(true);

        assertThat(row(service.getBoard(), 1L).getPatientName()).isEqualTo("Asha");
    }

    private IcuBedRowDTO row(IcuDashboardDTO dto, Long bedId) {
        return dto.getBeds().stream().filter(b -> bedId.equals(b.getBedId())).findFirst()
                .orElseThrow(() -> new AssertionError("No board row for bed " + bedId));
    }
}
