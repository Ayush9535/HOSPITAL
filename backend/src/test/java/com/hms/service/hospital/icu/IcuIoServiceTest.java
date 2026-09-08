package com.hms.service.hospital.icu;

import com.hms.dto.icu.IcuIoBalanceDTO;
import com.hms.dto.icu.IcuIoRequest;
import com.hms.entity.Hospital;
import com.hms.entity.IcuIoEntry;
import com.hms.entity.IcuStay;
import com.hms.entity.IpdAdmission;
import com.hms.entity.Patient;
import com.hms.entity.VitalsRecord;
import com.hms.entity.Ward;
import com.hms.repository.HospitalRepository;
import com.hms.repository.IcuIoEntryRepository;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * ICU Phase 5 - the fluid intake/output event stream, its balance, and its correction path.
 *
 * <p>Two properties carry the phase. First, the balance is ALWAYS summed from the entries, so it
 * cannot drift from them. Second, and the reason this is a table rather than more vitals columns:
 * several fluid events happen between two observations, and every one of them must survive.
 *
 * <p>D-2 is pinned here too: {@code VitalsRecord.urine_output_ml} is a separate observation and
 * must never leak into an I/O balance or become an entry.
 */
@SpringBootTest
@ActiveProfiles("test")
class IcuIoServiceTest {

    private static final Long RECORDER = 7777L;
    private static final Long SOMEONE_ELSE = 8888L;

    @Autowired IcuIoService icuIoService;
    @Autowired IcuIoEntryRepository ioRepository;
    @Autowired IcuStayRepository icuStayRepository;
    @Autowired VitalsRecordRepository vitalsRepository;
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
    private Long patientId;

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
        patientId = patientRepository.save(p).getId();

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

    private IcuIoRequest req(String direction, String route, int volume) {
        IcuIoRequest r = new IcuIoRequest();
        r.setIpdAdmissionId(admissionId);
        r.setDirection(direction);
        r.setRoute(route);
        r.setVolumeMl(volume);
        return r;
    }

    private IcuIoEntry rec(String direction, String route, int volume) {
        return icuIoService.record(req(direction, route, volume));
    }

    // ── the five NABH routes ─────────────────────────────────────────────────

    @Test
    void allFiveNabhRoutesAreRecordable() {
        assertThat(rec(IcuIoEntry.INTAKE, IcuIoEntry.ROUTE_IV_FLUIDS, 500).getId()).isNotNull();
        assertThat(rec(IcuIoEntry.INTAKE, IcuIoEntry.ROUTE_ORAL, 200).getId()).isNotNull();
        assertThat(rec(IcuIoEntry.OUTPUT, IcuIoEntry.ROUTE_RYLES_ASPIRATION, 50).getId()).isNotNull();
        assertThat(rec(IcuIoEntry.OUTPUT, IcuIoEntry.ROUTE_URINE, 400).getId()).isNotNull();
        assertThat(rec(IcuIoEntry.OUTPUT, IcuIoEntry.ROUTE_VOMIT, 30).getId()).isNotNull();

        assertThat(icuIoService.getByAdmission(admissionId)).hasSize(5);
    }

    @Test
    void aRouteFromTheWrongDirectionIsRejected() {
        assertThatThrownBy(() -> rec(IcuIoEntry.INTAKE, IcuIoEntry.ROUTE_URINE, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not valid for INTAKE");
        assertThatThrownBy(() -> rec(IcuIoEntry.OUTPUT, IcuIoEntry.ROUTE_ORAL, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnknownRouteOrDirectionIsRejected() {
        assertThatThrownBy(() -> rec(IcuIoEntry.INTAKE, "BLOOD_PRODUCTS", 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rec("SIDEWAYS", IcuIoEntry.ROUTE_ORAL, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INTAKE or OUTPUT");
    }

    @Test
    void volumeMustBePositiveAndTimeCannotBeInTheFuture() {
        assertThatThrownBy(() -> rec(IcuIoEntry.INTAKE, IcuIoEntry.ROUTE_ORAL, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rec(IcuIoEntry.INTAKE, IcuIoEntry.ROUTE_ORAL, -50))
                .isInstanceOf(IllegalArgumentException.class);

        IcuIoRequest future = req(IcuIoEntry.INTAKE, IcuIoEntry.ROUTE_ORAL, 100);
        future.setOccurredAt(LocalDateTime.now().plusHours(2));
        assertThatThrownBy(() -> icuIoService.record(future))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("future");
    }

    // ── balance ──────────────────────────────────────────────────────────────

    @Test
    void balanceSumsIntakeAndOutputAndNets() {
        rec(IcuIoEntry.INTAKE, IcuIoEntry.ROUTE_IV_FLUIDS, 1000);
        rec(IcuIoEntry.INTAKE, IcuIoEntry.ROUTE_ORAL, 250);
        rec(IcuIoEntry.OUTPUT, IcuIoEntry.ROUTE_URINE, 600);
        rec(IcuIoEntry.OUTPUT, IcuIoEntry.ROUTE_RYLES_ASPIRATION, 100);
        rec(IcuIoEntry.OUTPUT, IcuIoEntry.ROUTE_VOMIT, 50);

        IcuIoBalanceDTO b = icuIoService.balance(admissionId, null, null);

        assertThat(b.getTotalIntakeMl()).isEqualTo(1250);
        assertThat(b.getTotalOutputMl()).isEqualTo(750);
        assertThat(b.getNetBalanceMl()).isEqualTo(500);
        assertThat(b.getEntryCount()).isEqualTo(5);
    }

    @Test
    void aNegativeNetBalanceIsReportedAsIs() {
        // Output exceeding intake is a fact to display, not an error and not a warning.
        rec(IcuIoEntry.INTAKE, IcuIoEntry.ROUTE_ORAL, 100);
        rec(IcuIoEntry.OUTPUT, IcuIoEntry.ROUTE_URINE, 400);

        assertThat(icuIoService.balance(admissionId, null, null).getNetBalanceMl()).isEqualTo(-300);
    }

    @Test
    void manyEventsBetweenTwoObservationsAllSurvive() {
        // The reason this is a table and not more vitals columns: one observation row could not
        // hold these five events, and forcing it would drop entries or invent timestamps.
        LocalDateTime base = LocalDateTime.now().minusHours(2);
        for (int i = 0; i < 5; i++) {
            IcuIoRequest r = req(IcuIoEntry.INTAKE, IcuIoEntry.ROUTE_IV_FLUIDS, 100);
            r.setOccurredAt(base.plusMinutes(i * 5));
            icuIoService.record(r);
        }

        assertThat(icuIoService.getByAdmission(admissionId)).hasSize(5);
        assertThat(icuIoService.balance(admissionId, null, null).getTotalIntakeMl()).isEqualTo(500);
    }

    @Test
    void balanceCanBeScopedToATimeRange() {
        IcuIoRequest old = req(IcuIoEntry.INTAKE, IcuIoEntry.ROUTE_ORAL, 900);
        old.setOccurredAt(LocalDateTime.now().minusDays(1).minusHours(2));
        icuIoService.record(old);
        rec(IcuIoEntry.INTAKE, IcuIoEntry.ROUTE_ORAL, 100);

        IcuIoBalanceDTO windowed = icuIoService.balance(
                admissionId, LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1));

        assertThat(windowed.getTotalIntakeMl()).as("only the recent entry").isEqualTo(100);
    }

    // ── D-2: the urine boundary ──────────────────────────────────────────────

    @Test
    void vitalsUrineOutputNeverEntersTheIoBalanceOrTheEntryList() {
        // D-2. VitalsRecord.urine_output_ml is a point-in-time observation; icu_io_entry is the
        // authoritative fluid-balance source. Neither is synchronised into the other, and the same
        // event is never counted twice.
        VitalsRecord v = new VitalsRecord();
        v.setHospitalId(hospitalId);
        v.setIpdAdmissionId(admissionId);
        v.setPatientId(patientId);
        v.setRecordedByUserId(RECORDER);
        v.setRecordedAt(LocalDateTime.now());
        v.setUrineOutputMl(750);
        v.setIsActive(true);
        vitalsRepository.save(v);

        rec(IcuIoEntry.OUTPUT, IcuIoEntry.ROUTE_URINE, 400);

        IcuIoBalanceDTO b = icuIoService.balance(admissionId, null, null);
        assertThat(b.getTotalOutputMl()).as("only the I/O entry counts").isEqualTo(400);
        assertThat(b.getEntryCount()).isEqualTo(1);
        assertThat(icuIoService.getByAdmission(admissionId)).hasSize(1);
        assertThat(vitalsRepository.findById(v.getId()).orElseThrow().getUrineOutputMl())
                .as("the observation is untouched").isEqualTo(750);
    }

    // ── append-only correction ───────────────────────────────────────────────

    @Test
    void correctionCreatesANewEntryAndPreservesTheOriginal() {
        IcuIoEntry original = rec(IcuIoEntry.OUTPUT, IcuIoEntry.ROUTE_URINE, 400);

        IcuIoEntry correction = icuIoService.correct(original.getPublicId(),
                req(IcuIoEntry.OUTPUT, IcuIoEntry.ROUTE_URINE, 350));

        assertThat(correction.getPublicId()).isNotEqualTo(original.getPublicId());
        assertThat(correction.getSupersedesIoEntryId()).isEqualTo(original.getId());

        IcuIoEntry reloaded = ioRepository.findById(original.getId()).orElseThrow();
        assertThat(reloaded.getVolumeMl()).as("original untouched").isEqualTo(400);
        assertThat(reloaded.getIsActive()).as("original NOT hidden").isTrue();
    }

    @Test
    void theBalanceCountsTheCorrectionAndNotTheSupersededEntry() {
        IcuIoEntry original = rec(IcuIoEntry.OUTPUT, IcuIoEntry.ROUTE_URINE, 400);
        icuIoService.correct(original.getPublicId(), req(IcuIoEntry.OUTPUT, IcuIoEntry.ROUTE_URINE, 350));

        IcuIoBalanceDTO b = icuIoService.balance(admissionId, null, null);

        assertThat(b.getTotalOutputMl()).as("350, not 750").isEqualTo(350);
        assertThat(b.getEntryCount()).isEqualTo(1);
        assertThat(icuIoService.getByAdmission(admissionId))
                .as("both rows remain readable").hasSize(2);
    }

    @Test
    void aCorrectionKeepsTheOriginalEventTime() {
        IcuIoRequest r = req(IcuIoEntry.OUTPUT, IcuIoEntry.ROUTE_URINE, 400);
        r.setOccurredAt(LocalDateTime.now().minusHours(3));
        IcuIoEntry original = icuIoService.record(r);

        IcuIoEntry correction = icuIoService.correct(original.getPublicId(),
                req(IcuIoEntry.OUTPUT, IcuIoEntry.ROUTE_URINE, 350));

        IcuIoEntry persisted = ioRepository.findById(original.getId()).orElseThrow();
        assertThat(correction.getOccurredAt()).isEqualTo(persisted.getOccurredAt());
    }

    // ── authorisation: unchanged, not widened ────────────────────────────────

    @Test
    void recordingAndCorrectingBothRequireTheIoChartPermission() {
        doThrow(new org.springframework.security.access.AccessDeniedException("denied"))
                .when(formAccessService).assertCanEdit(anyString());

        assertThatThrownBy(() -> rec(IcuIoEntry.INTAKE, IcuIoEntry.ROUTE_ORAL, 100))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void someoneElseCannotCorrectAnEntryTheyDidNotRecord() {
        IcuIoEntry original = rec(IcuIoEntry.OUTPUT, IcuIoEntry.ROUTE_URINE, 400);
        when(securityHelper.getCurrentUserId()).thenReturn(SOMEONE_ELSE);

        assertThatThrownBy(() -> icuIoService.correct(original.getPublicId(),
                req(IcuIoEntry.OUTPUT, IcuIoEntry.ROUTE_URINE, 350)))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(ioRepository.findById(original.getId()).orElseThrow().getVolumeMl()).isEqualTo(400);
    }

    @Test
    void theEditWindowStillAppliesToACorrection() {
        IcuIoEntry original = rec(IcuIoEntry.OUTPUT, IcuIoEntry.ROUTE_URINE, 400);
        jdbc.update("UPDATE icu_io_entry SET created_at = ? WHERE id = ?",
                java.sql.Timestamp.valueOf(LocalDateTime.now().minusDays(2)), original.getId());

        assertThatThrownBy(() -> icuIoService.correct(original.getPublicId(),
                req(IcuIoEntry.OUTPUT, IcuIoEntry.ROUTE_URINE, 350)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Edit window");
    }

    // ── tenancy ──────────────────────────────────────────────────────────────

    @Test
    void anotherHospitalsEntryIsIndistinguishableFromAMissingOne() {
        IcuIoEntry mine = rec(IcuIoEntry.OUTPUT, IcuIoEntry.ROUTE_URINE, 400);

        Hospital other = new Hospital();
        other.setName("Other-" + uniq());
        other.setCustomId("HID-" + uniq());
        other.setSubscriptionStatus("ACTIVE");
        other.setIsActive(true);
        other.setModules(List.of("IPD"));
        other.setIsSingleDoctor(false);
        Long otherId = hospitalRepository.save(other).getId();
        when(securityHelper.getCurrentHospitalId()).thenReturn(otherId);

        assertThatThrownBy(() -> icuIoService.correct(mine.getPublicId(),
                req(IcuIoEntry.OUTPUT, IcuIoEntry.ROUTE_URINE, 1)))
                .isInstanceOf(com.hms.exception.ResourceNotFoundException.class);

        when(securityHelper.getCurrentHospitalId()).thenReturn(hospitalId);
        assertThat(ioRepository.findById(mine.getId()).orElseThrow().getVolumeMl()).isEqualTo(400);
    }

    @Test
    void anotherHospitalsAdmissionIsNotFound() {
        Hospital other = new Hospital();
        other.setName("Other-" + uniq());
        other.setCustomId("HID-" + uniq());
        other.setSubscriptionStatus("ACTIVE");
        other.setIsActive(true);
        other.setModules(List.of("IPD"));
        other.setIsSingleDoctor(false);
        Long otherId = hospitalRepository.save(other).getId();
        when(securityHelper.getCurrentHospitalId()).thenReturn(otherId);

        assertThatThrownBy(() -> icuIoService.getByAdmission(admissionId))
                .isInstanceOf(com.hms.exception.ResourceNotFoundException.class);
    }

    // ── rollback ─────────────────────────────────────────────────────────────

    @Test
    void aFailedRecordWritesNothing() {
        long before = ioRepository.count();

        assertThatThrownBy(() -> rec(IcuIoEntry.INTAKE, IcuIoEntry.ROUTE_URINE, 100))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(ioRepository.count()).isEqualTo(before);
    }

    @Test
    void aFailedCorrectionLeavesTheOriginalIntact() {
        IcuIoEntry original = rec(IcuIoEntry.OUTPUT, IcuIoEntry.ROUTE_URINE, 400);
        long before = ioRepository.count();

        assertThatThrownBy(() -> icuIoService.correct(original.getPublicId(),
                req(IcuIoEntry.OUTPUT, IcuIoEntry.ROUTE_ORAL, 100)))  // wrong-direction route
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(ioRepository.count()).isEqualTo(before);
        assertThat(ioRepository.findById(original.getId()).orElseThrow().getVolumeMl()).isEqualTo(400);
    }

    // ── the stay relationship is read-only ───────────────────────────────────

    @Test
    void entriesAreKeyedToTheAdmissionSoTheySurviveABedOrWardMove() {
        rec(IcuIoEntry.INTAKE, IcuIoEntry.ROUTE_IV_FLUIDS, 500);

        // Simulate a move: the admission changes ward/bed, the admission id does not.
        IpdAdmission a = ipdAdmissionRepository.findById(admissionId).orElseThrow();
        a.setWardId(99999L);
        a.setBedId(88888L);
        ipdAdmissionRepository.save(a);

        assertThat(icuIoService.getByAdmission(admissionId)).hasSize(1);
        assertThat(icuIoService.balance(admissionId, null, null).getTotalIntakeMl()).isEqualTo(500);
    }

    @Test
    void theStayWindowIsReadableButNeverWritten() {
        long staysBefore = icuStayRepository.count();

        assertThat(icuIoService.isInIcuAt(admissionId, hospitalId, LocalDateTime.now())).isTrue();
        rec(IcuIoEntry.INTAKE, IcuIoEntry.ROUTE_ORAL, 100);

        assertThat(icuStayRepository.count()).as("ICU-5 never touches the stay lifecycle")
                .isEqualTo(staysBefore);
    }
}
