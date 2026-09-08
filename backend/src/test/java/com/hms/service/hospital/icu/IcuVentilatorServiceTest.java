package com.hms.service.hospital.icu;

import com.hms.dto.icu.IcuIoRequest;
import com.hms.dto.icu.IcuVentilatorParameterRequest;
import com.hms.dto.icu.IcuVentilatorRequest;
import com.hms.entity.Hospital;
import com.hms.entity.IcuIoEntry;
import com.hms.entity.IcuStay;
import com.hms.entity.IcuVentilatorParameter;
import com.hms.entity.IcuVentilatorSetting;
import com.hms.entity.IpdAdmission;
import com.hms.entity.Patient;
import com.hms.entity.Ward;
import com.hms.repository.HospitalRepository;
import com.hms.repository.IcuIoEntryRepository;
import com.hms.repository.IcuStayRepository;
import com.hms.repository.IcuVentilatorSettingRepository;
import com.hms.repository.IpdAdmissionRepository;
import com.hms.repository.PatientRepository;
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
 * ICU Phase 7 - timed ventilator snapshots against a configurable catalogue.
 *
 * <p>Two properties carry the phase. First, <b>recording appends</b>, so the setting in force at
 * any past instant stays answerable. Second, and the reason D-5 exists: <b>a configuration change
 * never touches clinical history</b> — disable or rename a parameter and every value ever charted
 * is still there, still readable, still correctly labelled.
 */
@SpringBootTest
@ActiveProfiles("test")
class IcuVentilatorServiceTest {

    private static final Long RECORDER = 5151L;
    private static final Long SOMEONE_ELSE = 6262L;

    @Autowired IcuVentilatorService ventilatorService;
    @Autowired VentilatorParameterService parameterService;
    @Autowired IcuIoService icuIoService;
    @Autowired IcuVentilatorSettingRepository settingRepository;
    @Autowired IcuIoEntryRepository ioRepository;
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
    private Long wardId;
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
        wardId = wardRepository.save(w).getWardId();

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
        a.setAdmissionDatetime(LocalDateTime.now().minusDays(3));
        a.setAdmissionConfirmed(true);
        admissionId = ipdAdmissionRepository.save(a).getId();

        IcuStay s = new IcuStay();
        s.setHospitalId(hospitalId);
        s.setIpdAdmissionId(admissionId);
        s.setPatientId(patientId);
        s.setWardId(wardId);
        s.setStatus(IcuStay.ACTIVE);
        s.setSource(IcuStay.SRC_WARD);
        s.setAdmittedAt(LocalDateTime.now().minusDays(2));
        s.setActiveMarker(admissionId);
        stayId = icuStayRepository.save(s).getId();

        when(securityHelper.getCurrentHospitalId()).thenReturn(hospitalId);
        when(securityHelper.getCurrentUserId()).thenReturn(RECORDER);
        when(securityHelper.getCurrentUserEmail()).thenReturn("nurse@icu.test");
        when(securityHelper.getCurrentUserRole()).thenReturn("NURSE_INCHARGE");
        when(performingNurseResolver.resolve(any())).thenReturn(null);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private Map<String, Object> values(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private IcuVentilatorRequest req(String status, Map<String, Object> vals) {
        IcuVentilatorRequest r = new IcuVentilatorRequest();
        r.setIpdAdmissionId(admissionId);
        r.setVentilationStatus(status);
        r.setValues(vals);
        return r;
    }

    private IcuVentilatorRequest at(String status, Map<String, Object> vals, LocalDateTime when) {
        IcuVentilatorRequest r = req(status, vals);
        r.setObservedAt(when);
        return r;
    }

    private void disable(String key) {
        IcuVentilatorParameterRequest r = new IcuVentilatorParameterRequest();
        r.setEnabled(false);
        parameterService.update(key, r);
    }

    private Object valueOf(IcuVentilatorSetting s, String key) {
        return ventilatorService.valuesOf(s).get(key);
    }

    private double num(Object v) {
        return new BigDecimal(String.valueOf(v)).doubleValue();
    }

    // ── recording ────────────────────────────────────────────────────────────

    @Test
    void recordingStoresTheStatusAndEveryEnabledParameter() {
        IcuVentilatorSetting s = ventilatorService.record(req(IcuVentilatorSetting.INVASIVE,
                values("mode", "VC", "fio2", 60, "peep", 8, "tidal_volume", 450)));

        assertThat(s.getId()).isNotNull();
        assertThat(s.getVentilationStatus()).isEqualTo(IcuVentilatorSetting.INVASIVE);
        assertThat(valueOf(s, "mode")).isEqualTo("VC");
        assertThat(num(valueOf(s, "fio2"))).isEqualTo(60);
        assertThat(num(valueOf(s, "peep"))).isEqualTo(8);
        assertThat(ventilatorService.getByAdmission(admissionId)).hasSize(1);
    }

    @Test
    void aSecondRecordingAppendsAndLeavesTheFirstUntouched() {
        IcuVentilatorSetting first = ventilatorService.record(
                req(IcuVentilatorSetting.INVASIVE, values("fio2", 60)));
        ventilatorService.record(req(IcuVentilatorSetting.INVASIVE, values("fio2", 40)));

        assertThat(num(valueOf(settingRepository.findById(first.getId()).orElseThrow(), "fio2")))
                .as("the earlier snapshot is not rewritten").isEqualTo(60);
        assertThat(ventilatorService.getByAdmission(admissionId)).hasSize(2);
        assertThat(num(valueOf(ventilatorService.current(admissionId), "fio2"))).isEqualTo(40);
    }

    @Test
    void historyIsOrderedNewestFirst() {
        LocalDateTime t0 = LocalDateTime.now().minusHours(3);
        ventilatorService.record(at(IcuVentilatorSetting.INVASIVE, values("fio2", 60), t0));
        ventilatorService.record(
                at(IcuVentilatorSetting.INVASIVE, values("fio2", 50), t0.plusHours(1)));
        ventilatorService.record(
                at(IcuVentilatorSetting.INVASIVE, values("fio2", 40), t0.plusHours(2)));

        assertThat(ventilatorService.getByAdmission(admissionId))
                .extracting(s -> num(valueOf(s, "fio2")))
                .containsExactly(40.0, 50.0, 60.0);
    }

    @Test
    void settingAtReturnsWhatWasRunningAtThatMoment() {
        LocalDateTime t0 = LocalDateTime.now().minusHours(3);
        ventilatorService.record(at(IcuVentilatorSetting.INVASIVE, values("fio2", 60), t0));
        ventilatorService.record(
                at(IcuVentilatorSetting.INVASIVE, values("fio2", 40), t0.plusHours(2)));

        assertThat(num(valueOf(ventilatorService.settingAt(admissionId, t0.plusHours(1)), "fio2")))
                .isEqualTo(60);
        assertThat(num(valueOf(ventilatorService.settingAt(admissionId, t0.plusHours(5)), "fio2")))
                .isEqualTo(40);
        assertThat(ventilatorService.settingAt(admissionId, t0.minusHours(1)))
                .as("before the first recording").isNull();
    }

    @Test
    void extubationIsRecordedNotInferred() {
        ventilatorService.record(req(IcuVentilatorSetting.INVASIVE, values("fio2", 60)));
        IcuVentilatorSetting off =
                ventilatorService.record(req(IcuVentilatorSetting.OFF, values()));

        assertThat(off.getVentilationStatus()).isEqualTo(IcuVentilatorSetting.OFF);
        assertThat(off.isVentilated()).isFalse();
        assertThat(off.getValuesJson()).isNull();
        assertThat(ventilatorService.getByAdmission(admissionId))
                .as("the ventilated history stays readable").hasSize(2);
    }

    @Test
    void theStayIsStampedAsProvenance() {
        IcuVentilatorSetting s = ventilatorService.record(
                req(IcuVentilatorSetting.INVASIVE, values("fio2", 60)));
        assertThat(s.getIcuStayId()).isEqualTo(stayId);
    }

    // ── mandatory status ─────────────────────────────────────────────────────

    @Test
    void ventilationStatusIsMandatoryAndNeverInferred() {
        assertThatThrownBy(() -> ventilatorService.record(req(null, values("fio2", 60))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("status is required");
        assertThatThrownBy(() -> ventilatorService.record(req("  ", values("fio2", 60))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ventilatorService.record(req("MAYBE", values("fio2", 60))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown ventilation status");
    }

    @Test
    void anEmptyValueMapDoesNotMeanOff() {
        // A status with no settings is legitimate; it is not the same as being extubated.
        IcuVentilatorSetting s =
                ventilatorService.record(req(IcuVentilatorSetting.NIV, values()));
        assertThat(s.getVentilationStatus()).isEqualTo(IcuVentilatorSetting.NIV);
        assertThat(s.isVentilated()).isTrue();
    }

    // ── the catalogue governs what may be charted ────────────────────────────

    @Test
    void aDisabledParameterCannotBeCharted() {
        disable(VentilatorParameterRegistry.FIO2);

        IcuVentilatorSetting s = ventilatorService.record(
                req(IcuVentilatorSetting.INVASIVE, values("fio2", 60, "peep", 8)));

        assertThat(valueOf(s, "fio2")).as("dropped, not stored").isNull();
        assertThat(num(valueOf(s, "peep"))).as("the rest still saves").isEqualTo(8);
    }

    @Test
    void anUnknownParameterKeyIsDropped() {
        IcuVentilatorSetting s = ventilatorService.record(req(IcuVentilatorSetting.INVASIVE,
                values("not_a_parameter", 99, "peep", 8)));

        assertThat(valueOf(s, "not_a_parameter")).isNull();
        assertThat(num(valueOf(s, "peep"))).isEqualTo(8);
    }

    @Test
    void aCustomParameterCanBeChartedOnceAdded() {
        IcuVentilatorParameterRequest add = new IcuVentilatorParameterRequest();
        add.setDisplayName("Minute Ventilation");
        add.setUnit("L/min");
        add.setCategory(IcuVentilatorParameter.OBSERVATION);
        parameterService.addCustom(add);

        IcuVentilatorSetting s = ventilatorService.record(
                req(IcuVentilatorSetting.INVASIVE, values("minute_ventilation", 7.5)));

        assertThat(num(valueOf(s, "minute_ventilation"))).isEqualTo(7.5);
    }

    @Test
    void aModeMustBeARegistryValueAndANumberMustBeANumber() {
        assertThatThrownBy(() -> ventilatorService.record(
                req(IcuVentilatorSetting.INVASIVE, values("mode", "whatever the nurse typed"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown ventilator mode");
        assertThatThrownBy(() -> ventilatorService.record(
                req(IcuVentilatorSetting.INVASIVE, values("fio2", "sixty"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a number");
    }

    @Test
    void aTextParameterTakesFreeTextAndIsNeverParsed() {
        IcuVentilatorSetting s = ventilatorService.record(
                req(IcuVentilatorSetting.INVASIVE, values("ie_ratio", "1:2")));
        assertThat(valueOf(s, "ie_ratio")).isEqualTo("1:2");
    }

    @Test
    void aFutureObservationTimeIsRejected() {
        assertThatThrownBy(() -> ventilatorService.record(at(IcuVentilatorSetting.INVASIVE,
                values("fio2", 60), LocalDateTime.now().plusHours(2))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("future");
    }

    // ── the D-5 guarantee: configuration never touches history ───────────────

    @Test
    void disablingAParameterLeavesEveryRecordedValueReadable() {
        ventilatorService.record(req(IcuVentilatorSetting.INVASIVE, values("fio2", 60)));
        long rowsBefore = settingRepository.count();

        disable(VentilatorParameterRegistry.FIO2);

        assertThat(settingRepository.count())
                .as("disabling writes zero clinical rows").isEqualTo(rowsBefore);
        IcuVentilatorSetting historical = ventilatorService.getByAdmission(admissionId).get(0);
        assertThat(num(valueOf(historical, "fio2")))
                .as("the value charted while it was on survives").isEqualTo(60);

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> params = (Map<String, Map<String, Object>>)
                ventilatorService.chartFor(admissionId).get("parameters");
        assertThat(params.get("fio2").get("displayName")).isEqualTo("FiO₂");
        assertThat(params.get("fio2").get("enabled"))
                .as("marked no longer charted, not hidden").isEqualTo(false);
    }

    @Test
    void renamingAParameterLeavesTheRecordedValueAndItsKeyAlone() {
        IcuVentilatorSetting before = ventilatorService.record(
                req(IcuVentilatorSetting.INVASIVE, values("fio2", 60)));
        long rowsBefore = settingRepository.count();

        IcuVentilatorParameterRequest rename = new IcuVentilatorParameterRequest();
        rename.setDisplayName("Inspired O₂");
        parameterService.update(VentilatorParameterRegistry.FIO2, rename);

        assertThat(settingRepository.count())
                .as("renaming writes zero clinical rows").isEqualTo(rowsBefore);
        IcuVentilatorSetting after = settingRepository.findById(before.getId()).orElseThrow();
        assertThat(after.getValuesJson()).isEqualTo(before.getValuesJson());
        assertThat(num(valueOf(after, "fio2"))).isEqualTo(60);

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> params = (Map<String, Map<String, Object>>)
                ventilatorService.chartFor(admissionId).get("parameters");
        assertThat(params.get("fio2").get("displayName"))
                .as("only the caption changed").isEqualTo("Inspired O₂");
    }

    @Test
    void changingAUnitOrCategoryLeavesHistoryAlone() {
        IcuVentilatorSetting before = ventilatorService.record(
                req(IcuVentilatorSetting.INVASIVE, values("peep", 8)));
        long rowsBefore = settingRepository.count();

        IcuVentilatorParameterRequest r = new IcuVentilatorParameterRequest();
        r.setUnit("kPa");
        r.setCategory(IcuVentilatorParameter.OBSERVATION);
        parameterService.update(VentilatorParameterRegistry.PEEP, r);

        assertThat(settingRepository.count()).isEqualTo(rowsBefore);
        IcuVentilatorSetting after = settingRepository.findById(before.getId()).orElseThrow();
        assertThat(after.getValuesJson())
                .as("the stored value is byte-identical").isEqualTo(before.getValuesJson());
    }

    @Test
    void theChartNeverStoresADisplayNameOnAClinicalRow() {
        IcuVentilatorSetting s = ventilatorService.record(
                req(IcuVentilatorSetting.INVASIVE, values("fio2", 60)));
        // Configuration must not become part of the observation, or a rename leaves two
        // contradicting names for one value.
        assertThat(s.getValuesJson()).contains("fio2").doesNotContain("FiO");
    }

    // ── append-only correction ───────────────────────────────────────────────

    @Test
    void correctingAppendsAndPreservesTheOriginal() {
        IcuVentilatorSetting original = ventilatorService.record(
                req(IcuVentilatorSetting.INVASIVE, values("fio2", 60)));

        IcuVentilatorSetting correction = ventilatorService.correct(original.getPublicId(),
                req(IcuVentilatorSetting.INVASIVE, values("fio2", 45)));

        assertThat(correction.getSupersedesSettingId()).isEqualTo(original.getId());
        IcuVentilatorSetting reloaded = settingRepository.findById(original.getId()).orElseThrow();
        assertThat(num(valueOf(reloaded, "fio2"))).as("original untouched").isEqualTo(60);
        assertThat(reloaded.getIsActive()).as("original NOT hidden").isTrue();
        assertThat(ventilatorService.getByAdmission(admissionId))
                .as("both rows readable").hasSize(2);
        assertThat(num(valueOf(ventilatorService.current(admissionId), "fio2"))).isEqualTo(45);
        assertThat(ventilatorService.supersededIds(admissionId))
                .containsExactly(original.getId());
    }

    @Test
    void aSupersededRowIsRemovedFromTheTimelineEntirely() {
        // The isolating case: a row whose *time* was wrong. Recorded as 08:00, actually 09:00.
        // Unless the superseded row is excluded, settingAt(08:30) still answers with a snapshot
        // that, as far as the corrected record is concerned, was never in force.
        LocalDateTime t0 = LocalDateTime.now().minusHours(4);
        ventilatorService.record(at(IcuVentilatorSetting.INVASIVE, values("fio2", 60), t0));
        IcuVentilatorSetting mistimed = ventilatorService.record(
                at(IcuVentilatorSetting.INVASIVE, values("fio2", 40), t0.plusHours(2)));

        ventilatorService.correct(mistimed.getPublicId(),
                at(IcuVentilatorSetting.INVASIVE, values("fio2", 40), t0.plusHours(3)));

        assertThat(num(valueOf(
                ventilatorService.settingAt(admissionId, t0.plusHours(2).plusMinutes(30)), "fio2")))
                .as("the mistimed 40 is gone from the timeline; 60 was still running")
                .isEqualTo(60);
        assertThat(num(valueOf(
                ventilatorService.settingAt(admissionId, t0.plusHours(3).plusMinutes(30)), "fio2")))
                .isEqualTo(40);
        assertThat(ventilatorService.getByAdmission(admissionId))
                .as("all three rows stay readable").hasSize(3);
    }

    @Test
    void aCorrectionMayChangeTheStatusItself() {
        IcuVentilatorSetting original = ventilatorService.record(
                req(IcuVentilatorSetting.INVASIVE, values("fio2", 60)));

        IcuVentilatorSetting correction = ventilatorService.correct(original.getPublicId(),
                req(IcuVentilatorSetting.NIV, values("fio2", 60)));

        assertThat(correction.getVentilationStatus()).isEqualTo(IcuVentilatorSetting.NIV);
        assertThat(settingRepository.findById(original.getId()).orElseThrow()
                .getVentilationStatus()).isEqualTo(IcuVentilatorSetting.INVASIVE);
    }

    @Test
    void aDisabledParametersHistoryStaysReadableAfterACorrection() {
        IcuVentilatorSetting original = ventilatorService.record(
                req(IcuVentilatorSetting.INVASIVE, values("fio2", 60, "peep", 8)));
        ventilatorService.correct(original.getPublicId(),
                req(IcuVentilatorSetting.INVASIVE, values("fio2", 45, "peep", 8)));

        disable(VentilatorParameterRegistry.FIO2);

        List<IcuVentilatorSetting> history = ventilatorService.getByAdmission(admissionId);
        assertThat(history).hasSize(2);
        assertThat(num(valueOf(history.get(1), "fio2"))).isEqualTo(60);
        assertThat(num(valueOf(history.get(0), "fio2"))).isEqualTo(45);
    }

    @Test
    void someoneElseCannotCorrectAnEntryTheyDidNotRecord() {
        IcuVentilatorSetting original = ventilatorService.record(
                req(IcuVentilatorSetting.INVASIVE, values("fio2", 60)));
        when(securityHelper.getCurrentUserId()).thenReturn(SOMEONE_ELSE);

        assertThatThrownBy(() -> ventilatorService.correct(original.getPublicId(),
                req(IcuVentilatorSetting.INVASIVE, values("fio2", 45))))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void theEditWindowStillApplies() {
        IcuVentilatorSetting original = ventilatorService.record(
                req(IcuVentilatorSetting.INVASIVE, values("fio2", 60)));
        jdbc.update("UPDATE icu_ventilator_setting SET created_at = ? WHERE id = ?",
                java.sql.Timestamp.valueOf(LocalDateTime.now().minusDays(2)), original.getId());

        assertThatThrownBy(() -> ventilatorService.correct(original.getPublicId(),
                req(IcuVentilatorSetting.INVASIVE, values("fio2", 45))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Edit window");
    }

    // ── authorisation and tenancy ────────────────────────────────────────────

    @Test
    void everyWriteRequiresTheVentilatorPermission() {
        doThrow(new org.springframework.security.access.AccessDeniedException("denied"))
                .when(formAccessService).assertCanEdit(anyString());

        assertThatThrownBy(() -> ventilatorService.record(
                req(IcuVentilatorSetting.INVASIVE, values("fio2", 60))))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void anotherHospitalsEntryIsIndistinguishableFromAMissingOne() {
        IcuVentilatorSetting mine = ventilatorService.record(
                req(IcuVentilatorSetting.INVASIVE, values("fio2", 60)));

        Hospital other = new Hospital();
        other.setName("Other-" + uniq());
        other.setCustomId("HID-" + uniq());
        other.setSubscriptionStatus("ACTIVE");
        other.setIsActive(true);
        other.setModules(List.of("IPD"));
        other.setIsSingleDoctor(false);
        Long otherId = hospitalRepository.save(other).getId();
        when(securityHelper.getCurrentHospitalId()).thenReturn(otherId);

        assertThatThrownBy(() -> ventilatorService.correct(mine.getPublicId(),
                req(IcuVentilatorSetting.INVASIVE, values("fio2", 45))))
                .isInstanceOf(com.hms.exception.ResourceNotFoundException.class);
        assertThatThrownBy(() -> ventilatorService.getByAdmission(admissionId))
                .isInstanceOf(com.hms.exception.ResourceNotFoundException.class);

        when(securityHelper.getCurrentHospitalId()).thenReturn(hospitalId);
        assertThat(num(valueOf(settingRepository.findById(mine.getId()).orElseThrow(), "fio2")))
                .as("nothing written across tenants").isEqualTo(60);
    }

    @Test
    void aMissingAdmissionIsNotFound() {
        IcuVentilatorRequest r = req(IcuVentilatorSetting.INVASIVE, values("fio2", 60));
        r.setIpdAdmissionId(999999L);
        assertThatThrownBy(() -> ventilatorService.record(r))
                .isInstanceOf(com.hms.exception.ResourceNotFoundException.class);
    }

    // ── isolation from everything else ───────────────────────────────────────

    @Test
    void aFailedWriteLeavesNoRow() {
        long before = settingRepository.count();

        assertThatThrownBy(() -> ventilatorService.record(
                req(IcuVentilatorSetting.INVASIVE, values("mode", "NOT_A_MODE"))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(settingRepository.count()).isEqualTo(before);
    }

    @Test
    void aBedOrWardMoveDoesNotBreakTheHistory() {
        ventilatorService.record(req(IcuVentilatorSetting.INVASIVE, values("fio2", 60)));

        IpdAdmission a = ipdAdmissionRepository.findById(admissionId).orElseThrow();
        a.setWardId(99999L);
        a.setBedId(88888L);
        ipdAdmissionRepository.save(a);

        assertThat(ventilatorService.getByAdmission(admissionId)).hasSize(1);
        assertThat(num(valueOf(ventilatorService.current(admissionId), "fio2"))).isEqualTo(60);
    }

    @Test
    void theIcuStayLifecycleIsNeverTouched() {
        long staysBefore = icuStayRepository.count();

        IcuVentilatorSetting s = ventilatorService.record(
                req(IcuVentilatorSetting.INVASIVE, values("fio2", 60)));
        ventilatorService.correct(s.getPublicId(),
                req(IcuVentilatorSetting.INVASIVE, values("fio2", 45)));
        ventilatorService.record(req(IcuVentilatorSetting.OFF, values()));

        assertThat(icuStayRepository.count()).isEqualTo(staysBefore);
        assertThat(icuStayRepository.findById(stayId).orElseThrow().getStatus())
                .isEqualTo(IcuStay.ACTIVE);
    }

    @Test
    void fluidBalanceIsUnaffectedByVentilatorRecords() {
        IcuIoRequest io = new IcuIoRequest();
        io.setIpdAdmissionId(admissionId);
        io.setDirection(IcuIoEntry.INTAKE);
        io.setRoute(IcuIoEntry.ROUTE_IV_FLUIDS);
        io.setVolumeMl(500);
        icuIoService.record(io);
        long ioBefore = ioRepository.count();

        ventilatorService.record(req(IcuVentilatorSetting.INVASIVE, values("fio2", 60)));

        assertThat(ioRepository.count()).as("no I/O entry derived").isEqualTo(ioBefore);
        assertThat(icuIoService.balance(admissionId, null, null).getTotalIntakeMl())
                .as("only the nurse's own I/O entry counts").isEqualTo(500);
    }

    @Test
    void noDerivedClinicalValueIsEverStored() {
        // FiO₂ and a pressure are both present, which is what a P/F ratio or a compliance figure
        // would be computed from. Neither appears: ICU records values and does not interpret them.
        IcuVentilatorSetting s = ventilatorService.record(req(IcuVentilatorSetting.INVASIVE,
                values("fio2", 60, "peak_pressure", 28, "plateau_pressure", 22,
                        "tidal_volume", 450)));

        assertThat(ventilatorService.valuesOf(s).keySet())
                .containsExactlyInAnyOrder("fio2", "peak_pressure", "plateau_pressure",
                        "tidal_volume");
    }

    // ── the chart payload ────────────────────────────────────────────────────

    @Test
    void theChartCarriesEntriesLabelsAndSupersededIds() {
        IcuVentilatorSetting original = ventilatorService.record(
                req(IcuVentilatorSetting.INVASIVE, values("fio2", 60, "mode", "VC")));
        ventilatorService.correct(original.getPublicId(),
                req(IcuVentilatorSetting.INVASIVE, values("fio2", 45, "mode", "VC")));

        Map<String, Object> chart = ventilatorService.chartFor(admissionId);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) chart.get("entries");
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> params =
                (Map<String, Map<String, Object>>) chart.get("parameters");
        @SuppressWarnings("unchecked")
        List<Long> superseded = (List<Long>) chart.get("supersededIds");

        assertThat(entries).hasSize(2);
        assertThat(params).containsKeys("fio2", "mode");
        assertThat(params.get("mode").get("category")).isEqualTo(IcuVentilatorParameter.SETTING);
        assertThat(superseded).containsExactly(original.getId());
    }
}
