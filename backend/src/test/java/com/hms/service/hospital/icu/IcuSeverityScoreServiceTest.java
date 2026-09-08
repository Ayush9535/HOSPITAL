package com.hms.service.hospital.icu;

import com.hms.dto.icu.IcuIoRequest;
import com.hms.dto.icu.IcuScoreTypeSettingRequest;
import com.hms.dto.icu.IcuSeverityScoreRequest;
import com.hms.entity.Hospital;
import com.hms.entity.IcuIoEntry;
import com.hms.entity.IcuSeverityScore;
import com.hms.entity.IcuStay;
import com.hms.entity.IpdAdmission;
import com.hms.entity.Patient;
import com.hms.entity.Ward;
import com.hms.repository.HospitalRepository;
import com.hms.repository.IcuIoEntryRepository;
import com.hms.repository.IcuScoreTypeSettingRepository;
import com.hms.repository.IcuSeverityScoreRepository;
import com.hms.repository.IcuStayRepository;
import com.hms.repository.IpdAdmissionRepository;
import com.hms.repository.PatientRepository;
import com.hms.repository.VitalsRecordRepository;
import com.hms.repository.WardRepository;
import com.hms.security.NurseWriteAccess;
import com.hms.security.PerformingNurseResolver;
import com.hms.security.SecurityContextHelper;
import com.hms.service.hospital.FormAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * ICU Phase 8 - timed severity scores.
 *
 * <p>Two properties carry the phase. First, <b>recording appends</b>, so "what was the SOFA on
 * Monday?" stays answerable — the trend is the whole reason a score is charted at intervals.
 * Second, and the boundary the phase is defined by: <b>the system documents a score, it does not
 * compute one</b>. Summing components a clinician entered is arithmetic; grading a measurement
 * would be interpretation, and nothing here does it.
 */
@SpringBootTest
@ActiveProfiles("test")
class IcuSeverityScoreServiceTest {

    private static final Long RECORDER = 3131L;
    private static final Long SOMEONE_ELSE = 4141L;

    @Autowired IcuSeverityScoreService scoreService;
    @Autowired ScoreTypeSettingService typeSettingService;
    @Autowired IcuIoService icuIoService;
    @Autowired IcuSeverityScoreRepository scoreRepository;
    @Autowired IcuScoreTypeSettingRepository typeRepository;
    @Autowired IcuIoEntryRepository ioRepository;
    @Autowired VitalsRecordRepository vitalsRepository;
    @Autowired IcuStayRepository icuStayRepository;
    @Autowired HospitalRepository hospitalRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired WardRepository wardRepository;
    @Autowired IpdAdmissionRepository ipdAdmissionRepository;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @MockBean SecurityContextHelper securityHelper;
    @MockBean NurseWriteAccess nurseWriteAccess;
    @MockBean PerformingNurseResolver performingNurseResolver;
    @MockBean FormAccessService formAccessService;

    private Long hospitalId;
    private Long admissionId;
    private Long stayId;

    private String uniq() { return Long.toString(System.nanoTime()); }

    @BeforeEach
    void setUp() {
        Hospital h = new Hospital();
        h.setName("H-" + uniq());
        h.setCustomId("HID-" + uniq());
        h.setSubscriptionStatus("ACTIVE");
        h.setIsActive(true);
        h.setModules(List.of("OPD", "IPD", "ICU"));
        h.setIsSingleDoctor(false);
        hospitalId = hospitalRepository.save(h).getId();

        Ward w = new Ward();
        w.setWardName("ICU-" + uniq());
        w.setHospitalId(hospitalId);
        w.setBedPrice(BigDecimal.ZERO);
        w.setTotalBeds(2);
        w.setUnitType(CareUnitRegistry.ICU);
        Long wardId = wardRepository.save(w).getWardId();

        Patient p = new Patient();
        p.setName("Test Patient");
        p.setHospitalId(hospitalId);
        p.setPublicId("ppub-" + uniq());
        p.setGender("MALE");
        p.setPhone("9900000001");
        p.setIsActive(true);
        p.setDateOfBirth(LocalDate.of(1980, 1, 1));
        Long patientId = patientRepository.save(p).getId();

        IpdAdmission a = new IpdAdmission();
        a.setIpdNumber("FIXT-" + uniq());
        a.setHospitalId(hospitalId);
        a.setPatientId(patientId);
        a.setDoctorId(1L);
        a.setWardId(wardId);
        a.setBedId(1L);
        a.setStatus("ADMITTED");
        a.setAdmissionType("ELECTIVE");
        a.setAdmissionDatetime(LocalDateTime.now().minusDays(4));
        a.setAdmissionConfirmed(true);
        admissionId = ipdAdmissionRepository.save(a).getId();

        IcuStay s = new IcuStay();
        s.setHospitalId(hospitalId);
        s.setIpdAdmissionId(admissionId);
        s.setPatientId(patientId);
        s.setWardId(wardId);
        s.setStatus(IcuStay.ACTIVE);
        s.setSource(IcuStay.SRC_WARD);
        s.setAdmittedAt(LocalDateTime.now().minusDays(3));
        s.setActiveMarker(admissionId);
        stayId = icuStayRepository.save(s).getId();

        when(securityHelper.getCurrentHospitalId()).thenReturn(hospitalId);
        when(securityHelper.getCurrentUserId()).thenReturn(RECORDER);
        when(securityHelper.getCurrentUserEmail()).thenReturn("nurse@icu.test");
        when(securityHelper.getCurrentUserRole()).thenReturn("NURSE_INCHARGE");
        when(performingNurseResolver.resolve(any())).thenReturn(null);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private Map<String, Object> comps(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    /** The worked SOFA used throughout: 2+1+0+3+1+2 = 9. */
    private Map<String, Object> sofaNine() {
        return comps("respiratory", 2, "coagulation", 1, "liver", 0,
                "cardiovascular", 3, "cns", 1, "renal", 2);
    }

    private IcuSeverityScoreRequest sofa(Map<String, Object> components) {
        IcuSeverityScoreRequest r = new IcuSeverityScoreRequest();
        r.setIpdAdmissionId(admissionId);
        r.setScoreType(SeverityScoreRegistry.SOFA);
        r.setComponents(components);
        return r;
    }

    private IcuSeverityScoreRequest apache(Integer total) {
        IcuSeverityScoreRequest r = new IcuSeverityScoreRequest();
        r.setIpdAdmissionId(admissionId);
        r.setScoreType(SeverityScoreRegistry.APACHE_II);
        r.setTotalScore(total);
        return r;
    }

    private IcuSeverityScoreRequest at(IcuSeverityScoreRequest r, LocalDateTime when) {
        r.setScoredAt(when);
        return r;
    }

    private void disable(String type) {
        IcuScoreTypeSettingRequest r = new IcuScoreTypeSettingRequest();
        r.setEnabled(false);
        typeSettingService.toggle(type, r);
    }

    private int component(IcuSeverityScore s, String key) {
        return ((Number) scoreService.componentsOf(s).get(key)).intValue();
    }

    // ── recording ────────────────────────────────────────────────────────────

    @Test
    void recordingSofaStoresEveryComponentAndTheSummedTotal() {
        IcuSeverityScore s = scoreService.record(sofa(sofaNine()));

        assertThat(s.getId()).isNotNull();
        assertThat(s.getScoreType()).isEqualTo(SeverityScoreRegistry.SOFA);
        assertThat(component(s, "respiratory")).isEqualTo(2);
        assertThat(component(s, "cardiovascular")).isEqualTo(3);
        assertThat(s.getTotalScore()).as("2+1+0+3+1+2").isEqualTo(9);
        assertThat(scoreService.getByAdmission(admissionId)).hasSize(1);
    }

    @Test
    void theTotalIsTheSumOfWhatWasEnteredAndNothingElse() {
        // The one arithmetic operation the phase allows, and the boundary it is defined by.
        IcuSeverityScore s = scoreService.record(sofa(comps("respiratory", 4, "renal", 4)));
        assertThat(s.getTotalScore()).isEqualTo(8);
    }

    @Test
    void aTypedTotalCannotDisagreeWithTheComponentsItIsMadeOf() {
        IcuSeverityScoreRequest r = sofa(sofaNine());
        r.setTotalScore(3); // a slip, or a stale field
        IcuSeverityScore s = scoreService.record(r);

        assertThat(s.getTotalScore()).as("the components win").isEqualTo(9);
    }

    @Test
    void aPartialSofaSumsOnlyWhatWasGiven() {
        IcuSeverityScore s = scoreService.record(sofa(comps("respiratory", 2, "renal", 1)));
        assertThat(s.getTotalScore()).isEqualTo(3);
        assertThat(scoreService.componentsOf(s)).hasSize(2);
    }

    @Test
    void apacheIsRecordedAsATotalWithNoComponents() {
        // D-4: its twelve variables are largely labs this system does not hold.
        IcuSeverityScore s = scoreService.record(apache(22));

        assertThat(s.getTotalScore()).isEqualTo(22);
        assertThat(s.getComponentsJson()).isNull();
        assertThat(scoreService.componentsOf(s)).isEmpty();
    }

    @Test
    void apacheRequiresATotalAndSofaRequiresAtLeastOneComponent() {
        assertThatThrownBy(() -> scoreService.record(apache(null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("total score is required");
        assertThatThrownBy(() -> scoreService.record(sofa(comps())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one");
    }

    @Test
    void theStayIsStampedAsProvenance() {
        assertThat(scoreService.record(sofa(sofaNine())).getIcuStayId()).isEqualTo(stayId);
    }

    // ── appending and the timeline ───────────────────────────────────────────

    @Test
    void aSecondScoringAppendsAndLeavesTheFirstUntouched() {
        IcuSeverityScore monday = scoreService.record(sofa(sofaNine()));
        scoreService.record(sofa(comps("respiratory", 1, "cardiovascular", 1)));

        assertThat(scoreRepository.findById(monday.getId()).orElseThrow().getTotalScore())
                .as("Monday's 9 is not rewritten").isEqualTo(9);
        assertThat(scoreService.getByAdmission(admissionId)).hasSize(2);
    }

    @Test
    void historyIsOrderedNewestFirst() {
        LocalDateTime t0 = LocalDateTime.now().minusDays(2);
        scoreService.record(at(sofa(comps("renal", 4)), t0));
        scoreService.record(at(sofa(comps("renal", 3)), t0.plusDays(1)));
        scoreService.record(at(sofa(comps("renal", 1)), t0.plusDays(2)));

        assertThat(scoreService.getByAdmission(admissionId))
                .extracting(IcuSeverityScore::getTotalScore).containsExactly(1, 3, 4);
    }

    @Test
    void scoreAtReturnsWhatWasScoredAtThatMoment() {
        LocalDateTime t0 = LocalDateTime.now().minusDays(3);
        scoreService.record(at(sofa(comps("renal", 4)), t0));
        scoreService.record(at(sofa(comps("renal", 1)), t0.plusDays(2)));

        assertThat(scoreService.scoreAt(admissionId, SeverityScoreRegistry.SOFA, t0.plusDays(1))
                .getTotalScore()).isEqualTo(4);
        assertThat(scoreService.scoreAt(admissionId, SeverityScoreRegistry.SOFA, LocalDateTime.now())
                .getTotalScore()).isEqualTo(1);
        assertThat(scoreService.scoreAt(admissionId, SeverityScoreRegistry.SOFA, t0.minusDays(1)))
                .as("before the first scoring").isNull();
    }

    @Test
    void latestIsReportedPerTypeIndependently() {
        scoreService.record(sofa(sofaNine()));
        scoreService.record(apache(22));
        scoreService.record(sofa(comps("renal", 1)));

        Map<String, Map<String, Object>> latest = scoreService.latestByType(admissionId);

        assertThat(latest).containsKeys(SeverityScoreRegistry.SOFA, SeverityScoreRegistry.APACHE_II);
        assertThat(latest.get(SeverityScoreRegistry.SOFA).get("totalScore")).isEqualTo(1);
        assertThat(latest.get(SeverityScoreRegistry.APACHE_II).get("totalScore")).isEqualTo(22);
    }

    // ── validation, structural only ──────────────────────────────────────────

    @Test
    void aComponentOutsideItsRangeIsRejected() {
        assertThatThrownBy(() -> scoreService.record(sofa(comps("renal", 7))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 0 and 4");
        assertThatThrownBy(() -> scoreService.record(sofa(comps("renal", -1))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anApacheTotalOutsideItsRangeIsRejected() {
        assertThatThrownBy(() -> scoreService.record(apache(90)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 0 and 71");
    }

    @Test
    void aComponentThatIsNotPartOfThisScoreIsDropped() {
        IcuSeverityScore s = scoreService.record(
                sofa(comps("renal", 2, "not_a_component", 3)));

        assertThat(scoreService.componentsOf(s)).containsOnlyKeys("renal");
        assertThat(s.getTotalScore()).as("the stray value contributes nothing").isEqualTo(2);
    }

    @Test
    void aNonNumericComponentIsRejected() {
        assertThatThrownBy(() -> scoreService.record(sofa(comps("renal", "severe"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whole number");
    }

    @Test
    void anUnknownScoreTypeIsRejected() {
        IcuSeverityScoreRequest r = sofa(comps("renal", 1));
        r.setScoreType("QSOFA");
        assertThatThrownBy(() -> scoreService.record(r))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown severity score");
    }

    @Test
    void aFutureScoringTimeIsRejected() {
        assertThatThrownBy(() -> scoreService.record(
                at(sofa(comps("renal", 1)), LocalDateTime.now().plusHours(2))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("future");
    }

    // ── configuration governs what may be recorded ───────────────────────────

    @Test
    void aDisabledScoreTypeCannotBeRecorded() {
        disable(SeverityScoreRegistry.APACHE_II);

        assertThatThrownBy(() -> scoreService.record(apache(22)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not enabled");
        assertThat(scoreService.record(sofa(sofaNine())).getTotalScore())
                .as("the other score is unaffected").isEqualTo(9);
    }

    @Test
    void disablingAScoreLeavesItsHistoryReadableAndWritesNoClinicalRow() {
        scoreService.record(apache(22));
        long rowsBefore = scoreRepository.count();

        disable(SeverityScoreRegistry.APACHE_II);

        assertThat(scoreRepository.count())
                .as("disabling writes zero clinical rows").isEqualTo(rowsBefore);
        assertThat(scoreService.getByAdmission(admissionId))
                .extracting(IcuSeverityScore::getTotalScore).containsExactly(22);
        assertThat(scoreService.latestByType(admissionId))
                .as("still reported").containsKey(SeverityScoreRegistry.APACHE_II);
    }

    // ── append-only correction ───────────────────────────────────────────────

    @Test
    void correctingAppendsAndPreservesTheOriginal() {
        IcuSeverityScore original = scoreService.record(sofa(sofaNine()));

        IcuSeverityScore correction = scoreService.correct(original.getPublicId(),
                sofa(comps("respiratory", 2, "coagulation", 1, "liver", 0,
                        "cardiovascular", 1, "cns", 1, "renal", 2)));

        assertThat(correction.getSupersedesScoreId()).isEqualTo(original.getId());
        assertThat(correction.getTotalScore()).isEqualTo(7);
        IcuSeverityScore reloaded = scoreRepository.findById(original.getId()).orElseThrow();
        assertThat(reloaded.getTotalScore()).as("original untouched").isEqualTo(9);
        assertThat(reloaded.getIsActive()).as("original NOT hidden").isTrue();
        assertThat(scoreService.getByAdmission(admissionId)).as("both readable").hasSize(2);
        assertThat(scoreService.supersededIds(admissionId)).containsExactly(original.getId());
        assertThat(scoreService.latestByType(admissionId)
                .get(SeverityScoreRegistry.SOFA).get("totalScore")).isEqualTo(7);
    }

    @Test
    void aSupersededRowIsRemovedFromTheTimelineEntirely() {
        // The isolating case: a scoring whose *time* was wrong. Recorded as Monday, actually
        // Tuesday. Unless the superseded row is excluded, scoreAt(Monday evening) still answers
        // with a score that, as far as the corrected record is concerned, was never in force.
        LocalDateTime t0 = LocalDateTime.now().minusDays(4);
        scoreService.record(at(sofa(comps("renal", 4)), t0));
        IcuSeverityScore mistimed = scoreService.record(at(sofa(comps("renal", 1)), t0.plusDays(1)));

        scoreService.correct(mistimed.getPublicId(),
                at(sofa(comps("renal", 1)), t0.plusDays(2)));

        assertThat(scoreService.scoreAt(admissionId, SeverityScoreRegistry.SOFA,
                t0.plusDays(1).plusHours(6)).getTotalScore())
                .as("the mistimed 1 is gone from the timeline; 4 was still standing")
                .isEqualTo(4);
        assertThat(scoreService.scoreAt(admissionId, SeverityScoreRegistry.SOFA,
                t0.plusDays(2).plusHours(6)).getTotalScore()).isEqualTo(1);
        assertThat(scoreService.getByAdmission(admissionId))
                .as("all three rows stay readable").hasSize(3);
    }

    @Test
    void aCorrectionKeepsTheOriginalScoreType() {
        // A correction restates one scoring; turning SOFA into APACHE II would be a different
        // observation, not a correction of this one.
        IcuSeverityScore original = scoreService.record(sofa(sofaNine()));

        // A rogue scoreType on the correction request is ignored; the components are still
        // validated and summed as SOFA.
        IcuSeverityScoreRequest r = sofa(comps("renal", 2));
        r.setScoreType(SeverityScoreRegistry.APACHE_II);
        IcuSeverityScore correction = scoreService.correct(original.getPublicId(), r);

        assertThat(correction.getScoreType()).isEqualTo(SeverityScoreRegistry.SOFA);
        assertThat(correction.getTotalScore()).isEqualTo(2);
    }

    @Test
    void someoneElseCannotCorrectAScoreTheyDidNotRecord() {
        IcuSeverityScore original = scoreService.record(sofa(sofaNine()));
        when(securityHelper.getCurrentUserId()).thenReturn(SOMEONE_ELSE);

        assertThatThrownBy(() -> scoreService.correct(original.getPublicId(),
                sofa(comps("renal", 1))))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void theEditWindowStillApplies() {
        IcuSeverityScore original = scoreService.record(sofa(sofaNine()));
        jdbc.update("UPDATE icu_severity_score SET created_at = ? WHERE id = ?",
                java.sql.Timestamp.valueOf(LocalDateTime.now().minusDays(2)), original.getId());

        assertThatThrownBy(() -> scoreService.correct(original.getPublicId(),
                sofa(comps("renal", 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Edit window");
    }

    // ── authorisation and tenancy ────────────────────────────────────────────

    @Test
    void everyWriteRequiresTheSeverityScorePermission() {
        doThrow(new org.springframework.security.access.AccessDeniedException("denied"))
                .when(formAccessService).assertCanEdit(anyString());

        assertThatThrownBy(() -> scoreService.record(sofa(sofaNine())))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void anotherHospitalsScoreIsIndistinguishableFromAMissingOne() {
        IcuSeverityScore mine = scoreService.record(sofa(sofaNine()));

        Hospital other = new Hospital();
        other.setName("Other-" + uniq());
        other.setCustomId("HID-" + uniq());
        other.setSubscriptionStatus("ACTIVE");
        other.setIsActive(true);
        other.setModules(List.of("IPD"));
        other.setIsSingleDoctor(false);
        Long otherId = hospitalRepository.save(other).getId();
        when(securityHelper.getCurrentHospitalId()).thenReturn(otherId);

        assertThatThrownBy(() -> scoreService.correct(mine.getPublicId(), sofa(comps("renal", 1))))
                .isInstanceOf(com.hms.exception.ResourceNotFoundException.class);
        assertThatThrownBy(() -> scoreService.getByAdmission(admissionId))
                .isInstanceOf(com.hms.exception.ResourceNotFoundException.class);

        when(securityHelper.getCurrentHospitalId()).thenReturn(hospitalId);
        assertThat(scoreRepository.findById(mine.getId()).orElseThrow().getTotalScore())
                .as("nothing written across tenants").isEqualTo(9);
    }

    @Test
    void aMissingAdmissionIsNotFound() {
        IcuSeverityScoreRequest r = sofa(sofaNine());
        r.setIpdAdmissionId(999999L);
        assertThatThrownBy(() -> scoreService.record(r))
                .isInstanceOf(com.hms.exception.ResourceNotFoundException.class);
    }

    // ── isolation from every other module ────────────────────────────────────

    @Test
    void aFailedWriteLeavesNoRow() {
        long before = scoreRepository.count();

        assertThatThrownBy(() -> scoreService.record(sofa(comps("renal", 9))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(scoreRepository.count()).isEqualTo(before);
    }

    @Test
    void aBedOrWardMoveDoesNotBreakTheHistory() {
        scoreService.record(sofa(sofaNine()));

        IpdAdmission a = ipdAdmissionRepository.findById(admissionId).orElseThrow();
        a.setWardId(99999L);
        a.setBedId(88888L);
        ipdAdmissionRepository.save(a);

        assertThat(scoreService.getByAdmission(admissionId)).hasSize(1);
        assertThat(scoreService.latestByType(admissionId)
                .get(SeverityScoreRegistry.SOFA).get("totalScore")).isEqualTo(9);
    }

    @Test
    void theIcuStayLifecycleIsNeverTouched() {
        long staysBefore = icuStayRepository.count();

        IcuSeverityScore s = scoreService.record(sofa(sofaNine()));
        scoreService.correct(s.getPublicId(), sofa(comps("renal", 1)));

        assertThat(icuStayRepository.count()).isEqualTo(staysBefore);
        assertThat(icuStayRepository.findById(stayId).orElseThrow().getStatus())
                .isEqualTo(IcuStay.ACTIVE);
    }

    @Test
    void vitalsIoAndInfusionDataAreUntouchedByAScore() {
        IcuIoRequest io = new IcuIoRequest();
        io.setIpdAdmissionId(admissionId);
        io.setDirection(IcuIoEntry.INTAKE);
        io.setRoute(IcuIoEntry.ROUTE_IV_FLUIDS);
        io.setVolumeMl(500);
        icuIoService.record(io);
        long ioBefore = ioRepository.count();
        long vitalsBefore = vitalsRepository.count();

        scoreService.record(sofa(sofaNine()));

        assertThat(ioRepository.count()).isEqualTo(ioBefore);
        assertThat(vitalsRepository.count()).isEqualTo(vitalsBefore);
        assertThat(icuIoService.balance(admissionId, null, null).getTotalIntakeMl())
                .as("the fluid balance is not a severity input").isEqualTo(500);
    }

    @Test
    void noScoreIsEverInferredFromAnotherModule() {
        // No vitals, no I/O, no infusion and no ventilator row exists for this admission, and a
        // SOFA is recorded anyway. The score comes from the clinician and nowhere else — nothing
        // is read, nothing is derived, and an absent module cannot change the total.
        IcuSeverityScore s = scoreService.record(sofa(sofaNine()));

        assertThat(s.getTotalScore()).isEqualTo(9);
        assertThat(scoreService.componentsOf(s)).hasSize(6);
    }

    @Test
    void gcsStaysInVitalsAndIsNotASeverityScoreComponent() {
        // D-1: ICU-4 owns GCS on the vitals chart. ICU-8 must not offer a second one.
        assertThat(SeverityScoreRegistry.find(SeverityScoreRegistry.SOFA).orElseThrow()
                .components()).extracting(SeverityScoreRegistry.Component::key)
                .containsExactly("respiratory", "coagulation", "liver",
                        "cardiovascular", "cns", "renal")
                .doesNotContain("gcs", "gcs_eye", "gcs_verbal", "gcs_motor");
        assertThat(SeverityScoreRegistry.isValidType("GCS")).isFalse();
    }

    @Test
    void nothingBeyondTheComponentsAndTheirSumIsStored() {
        IcuSeverityScore s = scoreService.record(sofa(sofaNine()));
        Map<String, Object> view = scoreService.latestByType(admissionId)
                .get(SeverityScoreRegistry.SOFA);

        assertThat(scoreService.componentsOf(s).keySet())
                .containsExactlyInAnyOrder("respiratory", "coagulation", "liver",
                        "cardiovascular", "cns", "renal");
        // No mortality figure, no risk band, no severity label anywhere in what a client receives.
        assertThat(view.keySet()).doesNotContain("mortality", "predictedMortality",
                "riskBand", "severity", "interpretation", "trend");
    }

    // ── the chart payload ────────────────────────────────────────────────────

    @Test
    void theChartCarriesEntriesTypesLatestAndSupersededIds() {
        IcuSeverityScore original = scoreService.record(sofa(sofaNine()));
        scoreService.correct(original.getPublicId(), sofa(comps("renal", 2)));

        Map<String, Object> chart = scoreService.chartFor(admissionId);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) chart.get("entries");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> types = (List<Map<String, Object>>) chart.get("types");
        @SuppressWarnings("unchecked")
        List<Long> superseded = (List<Long>) chart.get("supersededIds");

        assertThat(entries).hasSize(2);
        assertThat(types).extracting(t -> t.get("key"))
                .containsExactly(SeverityScoreRegistry.SOFA, SeverityScoreRegistry.APACHE_II);
        assertThat(superseded).containsExactly(original.getId());
        assertThat(chart.get("latest")).isNotNull();
    }
}
