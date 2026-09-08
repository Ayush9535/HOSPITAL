package com.hms.service.hospital.icu;

import com.hms.dto.icu.IcuInfusionRequest;
import com.hms.dto.icu.IcuIoRequest;
import com.hms.entity.Hospital;
import com.hms.entity.IcuInfusion;
import com.hms.entity.IcuInfusionRate;
import com.hms.entity.IcuIoEntry;
import com.hms.entity.IcuStay;
import com.hms.entity.IpdAdmission;
import com.hms.entity.Patient;
import com.hms.entity.Ward;
import com.hms.repository.HospitalRepository;
import com.hms.repository.IcuInfusionRateRepository;
import com.hms.repository.IcuInfusionRepository;
import com.hms.repository.IcuIoEntryRepository;
import com.hms.repository.IcuStayRepository;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * ICU Phase 6 - continuous infusions and their rate history.
 *
 * <p>The property the phase exists for: <b>a titration appends, so the rate at any past moment
 * stays answerable</b>. If a titration overwrote the rate, "what was it running at when the BP
 * dropped?" would be unanswerable, and that question is the whole reason a history is kept.
 *
 * <p>D-1 is pinned here too: an infusion is drug delivery, never a fluid-balance event.
 */
@SpringBootTest
@ActiveProfiles("test")
class IcuInfusionServiceTest {

    private static final Long RECORDER = 6161L;
    private static final Long SOMEONE_ELSE = 7272L;

    @Autowired IcuInfusionService infusionService;
    @Autowired IcuIoService icuIoService;
    @Autowired IcuInfusionRepository infusionRepository;
    @Autowired IcuInfusionRateRepository rateRepository;
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
        a.setAdmissionDatetime(LocalDateTime.now().minusDays(2));
        a.setAdmissionConfirmed(true);
        admissionId = ipdAdmissionRepository.save(a).getId();

        IcuStay s = new IcuStay();
        s.setHospitalId(hospitalId);
        s.setIpdAdmissionId(admissionId);
        s.setPatientId(patientId);
        s.setWardId(wardId);
        s.setStatus(IcuStay.ACTIVE);
        s.setSource(IcuStay.SRC_WARD);
        s.setAdmittedAt(LocalDateTime.now().minusDays(1));
        s.setActiveMarker(admissionId);
        icuStayRepository.save(s);

        when(securityHelper.getCurrentHospitalId()).thenReturn(hospitalId);
        when(securityHelper.getCurrentUserId()).thenReturn(RECORDER);
        when(securityHelper.getCurrentUserEmail()).thenReturn("nurse@icu.test");
        when(securityHelper.getCurrentUserRole()).thenReturn("NURSE_INCHARGE");
        when(performingNurseResolver.resolve(any())).thenReturn(null);
    }

    private IcuInfusionRequest req(String rate, String unit) {
        IcuInfusionRequest r = new IcuInfusionRequest();
        r.setIpdAdmissionId(admissionId);
        r.setMedicineName("Noradrenaline");
        r.setRateValue(rate == null ? null : new BigDecimal(rate));
        r.setRateUnit(unit);
        return r;
    }

    private IcuInfusionRequest at(String rate, LocalDateTime when) {
        IcuInfusionRequest r = req(rate, InfusionRateUnitRegistry.ML_HR);
        r.setEffectiveFrom(when);
        return r;
    }

    private IcuInfusion start(String rate) {
        return infusionService.start(req(rate, InfusionRateUnitRegistry.ML_HR));
    }

    // ── start & read ─────────────────────────────────────────────────────────

    @Test
    void startingAnInfusionStoresItsFirstRate() {
        IcuInfusion inf = start("5");

        assertThat(inf.getId()).isNotNull();
        assertThat(inf.isRunning()).isTrue();
        assertThat(infusionService.currentRate(inf.getPublicId()).getRateValue())
                .isEqualByComparingTo("5");
        assertThat(infusionService.getRunning(admissionId)).hasSize(1);
    }

    @Test
    void aStartedInfusionIsReadableFromTheAdmission() {
        start("5");
        assertThat(infusionService.getByAdmission(admissionId)).hasSize(1);
    }

    @Test
    void anInfusionMayStandWithoutAPrescription() {
        // D-2: an ICU drip is often started on a verbal order before the prescription exists.
        IcuInfusion inf = start("5");
        assertThat(inf.getPrescriptionId()).isNull();
    }

    // ── titration appends ────────────────────────────────────────────────────

    @Test
    void titrationAppendsANewRateAndLeavesTheOldOneUntouched() {
        IcuInfusion inf = start("5");
        Long firstRateId = infusionService.currentRate(inf.getPublicId()).getId();

        infusionService.titrate(inf.getPublicId(), req("8", InfusionRateUnitRegistry.ML_HR));

        assertThat(infusionService.currentRate(inf.getPublicId()).getRateValue())
                .isEqualByComparingTo("8");
        assertThat(rateRepository.findById(firstRateId).orElseThrow().getRateValue())
                .as("the earlier rate is not rewritten").isEqualByComparingTo("5");
        assertThat(infusionService.rateHistory(inf.getPublicId())).hasSize(2);
    }

    @Test
    void rateHistoryIsOrderedNewestFirst() {
        LocalDateTime t0 = LocalDateTime.now().minusHours(3);
        IcuInfusionRequest first = at("5", t0);
        IcuInfusion inf = infusionService.start(first);
        infusionService.titrate(inf.getPublicId(), at("8", t0.plusHours(1)));
        infusionService.titrate(inf.getPublicId(), at("6", t0.plusHours(2)));

        List<IcuInfusionRate> history = infusionService.rateHistory(inf.getPublicId());

        assertThat(history).extracting(r -> r.getRateValue().stripTrailingZeros().toPlainString())
                .containsExactly("6", "8", "5");
    }

    @Test
    void rateAtReturnsTheRateInForceAtThatMoment() {
        // The question the whole phase exists to answer.
        LocalDateTime t0 = LocalDateTime.now().minusHours(3);
        IcuInfusion inf = infusionService.start(at("5", t0));
        infusionService.titrate(inf.getPublicId(), at("8", t0.plusHours(1)));
        infusionService.titrate(inf.getPublicId(), at("6", t0.plusHours(2)));

        assertThat(infusionService.rateAt(inf.getPublicId(), t0.plusMinutes(30)).getRateValue())
                .isEqualByComparingTo("5");
        assertThat(infusionService.rateAt(inf.getPublicId(), t0.plusMinutes(90)).getRateValue())
                .isEqualByComparingTo("8");
        assertThat(infusionService.rateAt(inf.getPublicId(), t0.plusHours(5)).getRateValue())
                .isEqualByComparingTo("6");
        assertThat(infusionService.rateAt(inf.getPublicId(), t0.minusHours(1)))
                .as("before the infusion started").isNull();
    }

    // ── stop ─────────────────────────────────────────────────────────────────

    @Test
    void stoppingClosesTheSpanAndTheHistoryRemains() {
        IcuInfusion inf = start("5");
        infusionService.titrate(inf.getPublicId(), req("8", InfusionRateUnitRegistry.ML_HR));

        IcuInfusionRequest stop = new IcuInfusionRequest();
        stop.setStopReason("weaned");
        IcuInfusion stopped = infusionService.stop(inf.getPublicId(), stop);

        assertThat(stopped.isRunning()).isFalse();
        assertThat(stopped.getStopReason()).isEqualTo("weaned");
        assertThat(infusionService.getRunning(admissionId)).isEmpty();
        assertThat(infusionService.getByAdmission(admissionId)).as("still readable").hasSize(1);
        assertThat(infusionService.rateHistory(inf.getPublicId())).hasSize(2);
    }

    @Test
    void aStoppedInfusionCannotBeTitratedOrStoppedAgain() {
        IcuInfusion inf = start("5");
        infusionService.stop(inf.getPublicId(), new IcuInfusionRequest());

        assertThatThrownBy(() -> infusionService.titrate(inf.getPublicId(),
                req("8", InfusionRateUnitRegistry.ML_HR)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stopped");
        assertThatThrownBy(() -> infusionService.stop(inf.getPublicId(), new IcuInfusionRequest()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── validation, no clinical calculation ──────────────────────────────────

    @Test
    void rateMustBePositiveAndTheUnitMustBeKnown() {
        assertThatThrownBy(() -> infusionService.start(req("0", InfusionRateUnitRegistry.ML_HR)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> infusionService.start(req("-1", InfusionRateUnitRegistry.ML_HR)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> infusionService.start(req("5", "DROPS_PER_FORTNIGHT")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown rate unit");
    }

    @Test
    void theUnitIsStoredExactlyAsEnteredAndNeverConverted() {
        IcuInfusion inf = infusionService.start(req("0.05", InfusionRateUnitRegistry.MCG_KG_MIN));

        IcuInfusionRate rate = infusionService.currentRate(inf.getPublicId());
        assertThat(rate.getRateUnit()).isEqualTo(InfusionRateUnitRegistry.MCG_KG_MIN);
        assertThat(rate.getRateValue()).as("fractional rates survive").isEqualByComparingTo("0.05");
    }

    // ── append-only correction ───────────────────────────────────────────────

    @Test
    void correctingARateAppendsAndPreservesTheOriginal() {
        IcuInfusion inf = start("5");
        IcuInfusionRate original = infusionService.currentRate(inf.getPublicId());

        IcuInfusionRate correction = infusionService.correctRate(original.getPublicId(),
                req("15", InfusionRateUnitRegistry.ML_HR));

        assertThat(correction.getSupersedesRateId()).isEqualTo(original.getId());
        assertThat(rateRepository.findById(original.getId()).orElseThrow().getRateValue())
                .as("original untouched").isEqualByComparingTo("5");
        assertThat(rateRepository.findById(original.getId()).orElseThrow().getIsActive())
                .as("original NOT hidden").isTrue();
        assertThat(infusionService.currentRate(inf.getPublicId()).getRateValue())
                .as("the corrected value is in force").isEqualByComparingTo("15");
        assertThat(infusionService.rateHistory(inf.getPublicId()))
                .as("both rows readable").hasSize(2);
    }

    @Test
    void aSupersededRateIsRemovedFromTheTimelineEntirely() {
        // The isolating case: a rate whose *time* was wrong. Recorded as 08:00, actually 09:00.
        // Unless the superseded row is excluded, rateAt(08:30) still answers with a rate that,
        // as far as the corrected record is concerned, was never running.
        LocalDateTime t0 = LocalDateTime.now().minusHours(4);
        IcuInfusion inf = infusionService.start(at("5", t0));
        infusionService.titrate(inf.getPublicId(), at("8", t0.plusHours(2)));
        IcuInfusionRate mistimed = infusionService.currentRate(inf.getPublicId());
        assertThat(mistimed.getRateValue()).isEqualByComparingTo("8");

        infusionService.correctRate(mistimed.getPublicId(), at("8", t0.plusHours(3)));

        assertThat(infusionService.supersededRateIds(inf.getPublicId()))
                .containsExactly(mistimed.getId());
        assertThat(infusionService.rateAt(inf.getPublicId(), t0.plusHours(2).plusMinutes(30))
                .getRateValue())
                .as("the mistimed 8 is gone from the timeline; 5 was still running")
                .isEqualByComparingTo("5");
        assertThat(infusionService.rateAt(inf.getPublicId(), t0.plusHours(3).plusMinutes(30))
                .getRateValue()).isEqualByComparingTo("8");
        assertThat(infusionService.rateHistory(inf.getPublicId()))
                .as("all three rows stay readable").hasSize(3);
    }

    @Test
    void aCorrectedValueTakesEffectFromTheSameMoment() {
        LocalDateTime t0 = LocalDateTime.now().minusHours(2);
        IcuInfusion inf = infusionService.start(at("5", t0));
        IcuInfusionRate original = infusionService.currentRate(inf.getPublicId());
        infusionService.correctRate(original.getPublicId(), req("15", InfusionRateUnitRegistry.ML_HR));

        assertThat(infusionService.rateAt(inf.getPublicId(), t0.plusMinutes(30)).getRateValue())
                .as("the correction restates the same moment, not a new one")
                .isEqualByComparingTo("15");
    }

    // ── authorisation, unchanged ─────────────────────────────────────────────

    @Test
    void everyWriteRequiresTheMedicationPermission() {
        doThrow(new org.springframework.security.access.AccessDeniedException("denied"))
                .when(formAccessService).assertCanEdit(anyString());

        assertThatThrownBy(() -> start("5"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void someoneElseCannotCorrectARateTheyDidNotRecord() {
        IcuInfusion inf = start("5");
        IcuInfusionRate original = infusionService.currentRate(inf.getPublicId());
        when(securityHelper.getCurrentUserId()).thenReturn(SOMEONE_ELSE);

        assertThatThrownBy(() -> infusionService.correctRate(original.getPublicId(),
                req("15", InfusionRateUnitRegistry.ML_HR)))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void theEditWindowStillAppliesToARateCorrection() {
        IcuInfusion inf = start("5");
        IcuInfusionRate original = infusionService.currentRate(inf.getPublicId());
        jdbc.update("UPDATE icu_infusion_rate SET created_at = ? WHERE id = ?",
                java.sql.Timestamp.valueOf(LocalDateTime.now().minusDays(2)), original.getId());

        assertThatThrownBy(() -> infusionService.correctRate(original.getPublicId(),
                req("15", InfusionRateUnitRegistry.ML_HR)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Edit window");
    }

    // ── D-1: infusions are not fluid balance ─────────────────────────────────

    @Test
    void anInfusionCreatesNoIoEntryAndNeverAffectsTheFluidBalance() {
        // D-1. icu_infusion is drug delivery; icu_io_entry is the authoritative fluid balance.
        // Nothing is synchronised between them and nothing is counted twice.
        long ioBefore = ioRepository.count();

        IcuInfusion inf = start("5");
        infusionService.titrate(inf.getPublicId(), req("8", InfusionRateUnitRegistry.ML_HR));
        infusionService.stop(inf.getPublicId(), new IcuInfusionRequest());

        assertThat(ioRepository.count()).as("no IV_FLUIDS entry derived").isEqualTo(ioBefore);
        assertThat(icuIoService.balance(admissionId, null, null).getTotalIntakeMl())
                .as("infusions contribute nothing to the balance").isZero();
        assertThat(icuIoService.getByAdmission(admissionId)).isEmpty();
    }

    @Test
    void anIoEntryAndAnInfusionCoexistWithoutInfluencingEachOther() {
        IcuIoRequest io = new IcuIoRequest();
        io.setIpdAdmissionId(admissionId);
        io.setDirection(IcuIoEntry.INTAKE);
        io.setRoute(IcuIoEntry.ROUTE_IV_FLUIDS);
        io.setVolumeMl(500);
        icuIoService.record(io);

        start("5");

        assertThat(icuIoService.balance(admissionId, null, null).getTotalIntakeMl())
                .as("only the nurse's own I/O entry counts").isEqualTo(500);
        assertThat(infusionService.getRunning(admissionId)).hasSize(1);
    }

    // ── independence & tenancy ───────────────────────────────────────────────

    @Test
    void severalInfusionsRunIndependently() {
        IcuInfusion a = start("5");
        IcuInfusionRequest second = req("2", InfusionRateUnitRegistry.MCG_MIN);
        second.setMedicineName("Fentanyl");
        IcuInfusion b = infusionService.start(second);

        infusionService.titrate(a.getPublicId(), req("8", InfusionRateUnitRegistry.ML_HR));

        assertThat(infusionService.currentRate(a.getPublicId()).getRateValue()).isEqualByComparingTo("8");
        assertThat(infusionService.currentRate(b.getPublicId()).getRateValue()).isEqualByComparingTo("2");
        assertThat(infusionService.getRunning(admissionId)).hasSize(2);

        infusionService.stop(a.getPublicId(), new IcuInfusionRequest());
        assertThat(infusionService.getRunning(admissionId)).hasSize(1);
    }

    @Test
    void anotherHospitalsInfusionIsIndistinguishableFromAMissingOne() {
        IcuInfusion mine = start("5");

        Hospital other = new Hospital();
        other.setName("Other-" + uniq());
        other.setCustomId("HID-" + uniq());
        other.setSubscriptionStatus("ACTIVE");
        other.setIsActive(true);
        other.setModules(List.of("IPD"));
        other.setIsSingleDoctor(false);
        Long otherId = hospitalRepository.save(other).getId();
        when(securityHelper.getCurrentHospitalId()).thenReturn(otherId);

        assertThatThrownBy(() -> infusionService.titrate(mine.getPublicId(),
                req("8", InfusionRateUnitRegistry.ML_HR)))
                .isInstanceOf(com.hms.exception.ResourceNotFoundException.class);
        assertThatThrownBy(() -> infusionService.getByAdmission(admissionId))
                .isInstanceOf(com.hms.exception.ResourceNotFoundException.class);

        when(securityHelper.getCurrentHospitalId()).thenReturn(hospitalId);
        assertThat(infusionService.currentRate(mine.getPublicId()).getRateValue())
                .as("nothing written across tenants").isEqualByComparingTo("5");
    }

    @Test
    void aForeignPrescriptionIsRejected() {
        IcuInfusionRequest r = req("5", InfusionRateUnitRegistry.ML_HR);
        r.setPrescriptionId(999999L);

        assertThatThrownBy(() -> infusionService.start(r))
                .isInstanceOf(com.hms.exception.ResourceNotFoundException.class);
    }

    // ── rollback & movement independence ─────────────────────────────────────

    @Test
    void aFailedStartWritesNeitherInfusionNorRate() {
        long infBefore = infusionRepository.count();
        long rateBefore = rateRepository.count();

        assertThatThrownBy(() -> infusionService.start(req("5", "NOT_A_UNIT")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(infusionRepository.count()).isEqualTo(infBefore);
        assertThat(rateRepository.count()).isEqualTo(rateBefore);
    }

    @Test
    void infusionsSurviveABedOrWardMoveBecauseTheyAreAdmissionKeyed() {
        IcuInfusion inf = start("5");

        IpdAdmission a = ipdAdmissionRepository.findById(admissionId).orElseThrow();
        a.setWardId(99999L);
        a.setBedId(88888L);
        ipdAdmissionRepository.save(a);

        assertThat(infusionService.getRunning(admissionId)).hasSize(1);
        assertThat(infusionService.currentRate(inf.getPublicId()).getRateValue())
                .isEqualByComparingTo("5");
    }

    @Test
    void theIcuStayLifecycleIsNeverTouched() {
        long staysBefore = icuStayRepository.count();

        IcuInfusion inf = start("5");
        infusionService.titrate(inf.getPublicId(), req("8", InfusionRateUnitRegistry.ML_HR));
        infusionService.stop(inf.getPublicId(), new IcuInfusionRequest());

        assertThat(icuStayRepository.count()).isEqualTo(staysBefore);
    }
}
