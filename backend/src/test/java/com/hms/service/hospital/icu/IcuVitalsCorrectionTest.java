package com.hms.service.hospital.icu;

import com.hms.dto.VitalsRequest;
import com.hms.entity.Hospital;
import com.hms.entity.IcuStay;
import com.hms.entity.IpdAdmission;
import com.hms.entity.Patient;
import com.hms.entity.VitalsRecord;
import com.hms.entity.Ward;
import com.hms.repository.HospitalRepository;
import com.hms.repository.IcuStayRepository;
import com.hms.repository.IpdAdmissionRepository;
import com.hms.repository.PatientRepository;
import com.hms.repository.VitalsRecordRepository;
import com.hms.repository.WardRepository;
import com.hms.security.NurseWriteAccess;
import com.hms.security.PerformingNurseResolver;
import com.hms.security.SecurityContextHelper;
import com.hms.service.hospital.FormAccessService;
import com.hms.service.hospital.VitalsService;
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
 * ICU Phase 4 — ICU observations and the append-only correction path.
 *
 * <p>The single property everything here defends: <b>an observation recorded during an ICU stay
 * is never destroyed.</b> In critical care the earlier value is itself evidence — a falling SpO2
 * across three readings IS the finding — so an in-place edit erases the thing the chart exists to
 * show. Correcting writes a new row; the original stays readable.
 *
 * <p>Equally load-bearing: a ward reading must behave exactly as it did before ICU-4.
 */
@SpringBootTest
@ActiveProfiles("test")
class IcuVitalsCorrectionTest {

    private static final Long NURSE_USER = 4242L;
    private static final Long OTHER_NURSE = 5353L;

    @Autowired VitalsService vitalsService;
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
    private Long icuAdmissionId;
    private Long wardAdmissionId;

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

        icuAdmissionId = admission(ward("ICU-A", CareUnitRegistry.ICU));
        wardAdmissionId = admission(ward("General-B", CareUnitRegistry.GENERAL));
        openStay(icuAdmissionId, LocalDateTime.now().minusDays(1), null);

        when(securityHelper.getCurrentHospitalId()).thenReturn(hospitalId);
        when(securityHelper.getCurrentUserId()).thenReturn(NURSE_USER);
        when(securityHelper.getCurrentUserEmail()).thenReturn("nurse@icu.test");
        when(securityHelper.getCurrentUserRole()).thenReturn("NURSE_INCHARGE");
        when(performingNurseResolver.resolve(any())).thenReturn(null);
    }

    private Long ward(String name, String unitType) {
        Ward w = new Ward();
        w.setWardName(name + "-" + uniq());
        w.setHospitalId(hospitalId);
        w.setBedPrice(BigDecimal.ZERO);
        w.setTotalBeds(2);
        w.setUnitType(unitType);
        return wardRepository.save(w).getWardId();
    }

    private Long admission(Long wardId) {
        Patient p = new Patient();
        p.setName("Test Patient");
        p.setHospitalId(hospitalId);
        p.setPublicId("ppub-" + uniq());
        p.setGender("MALE");
        p.setPhone("9900000001");
        p.setIsActive(true);
        p.setDateOfBirth(LocalDate.of(1980, 1, 1));
        Long pid = patientRepository.save(p).getId();

        IpdAdmission a = new IpdAdmission();
        a.setIpdNumber("FIXT-" + uniq());
        a.setHospitalId(hospitalId);
        a.setPatientId(pid);
        a.setDoctorId(1L);
        a.setWardId(wardId);
        a.setBedId(1L);
        a.setStatus("ADMITTED");
        a.setAdmissionType("ELECTIVE");
        a.setAdmissionDatetime(LocalDateTime.now().minusDays(2));
        a.setAdmissionConfirmed(true);
        return ipdAdmissionRepository.save(a).getId();
    }

    private IcuStay openStay(Long admissionId, LocalDateTime from, LocalDateTime to) {
        IcuStay s = new IcuStay();
        s.setHospitalId(hospitalId);
        s.setIpdAdmissionId(admissionId);
        s.setPatientId(1L);
        s.setWardId(1L);
        s.setStatus(to == null ? IcuStay.ACTIVE : IcuStay.CLOSED);
        s.setSource(IcuStay.SRC_WARD);
        s.setAdmittedAt(from);
        s.setDischargedAt(to);
        s.setDisposition(to == null ? null : IcuStay.DISP_WARD);
        s.setActiveMarker(to == null ? admissionId : null);
        return icuStayRepository.save(s);
    }

    private VitalsRequest req(Integer spo2) {
        VitalsRequest r = new VitalsRequest();
        r.setSpo2(spo2);
        return r;
    }

    private VitalsRecord record(Long admissionId, Integer spo2) {
        VitalsRequest r = req(spo2);
        r.setIpdAdmissionId(admissionId);
        return vitalsService.create(r);
    }

    // ── ICU fields ───────────────────────────────────────────────────────────

    @Test
    void icuObservationsAreStoredAndReadBack_withGcsTotalAsArithmetic() {
        VitalsRequest r = req(93);
        r.setIpdAdmissionId(icuAdmissionId);
        r.setMapMmhg(72);
        r.setCvpCmh2o(8);
        r.setUrineOutputMl(45);
        r.setGcsEye(3);
        r.setGcsVerbal(4);
        r.setGcsMotor(5);

        VitalsRecord saved = vitalsService.create(r);

        assertThat(saved.getMapMmhg()).isEqualTo(72);
        assertThat(saved.getCvpCmh2o()).isEqualTo(8);
        assertThat(saved.getUrineOutputMl()).isEqualTo(45);
        assertThat(saved.getGcsTotal()).as("E+V+M, not a judgement").isEqualTo(12);
    }

    @Test
    void aWardReadingLeavesEveryIcuFieldNull() {
        VitalsRecord saved = record(wardAdmissionId, 98);

        assertThat(saved.getMapMmhg()).isNull();
        assertThat(saved.getCvpCmh2o()).isNull();
        assertThat(saved.getUrineOutputMl()).isNull();
        assertThat(saved.getGcsTotal()).isNull();
        assertThat(saved.getSupersedesVitalsId()).isNull();
    }

    @Test
    void anIcuObservationOfOnlyIcuValuesIsAccepted() {
        // Regression: "at least one measurement" originally counted only the eight ward vitals,
        // so a reading of MAP, CVP, urine output or GCS alone -- exactly what ICU-4 exists to
        // capture -- was rejected as empty with a 400.
        VitalsRequest onlyMap = req(null);
        onlyMap.setIpdAdmissionId(icuAdmissionId);
        onlyMap.setMapMmhg(72);
        assertThat(vitalsService.create(onlyMap).getMapMmhg()).isEqualTo(72);

        VitalsRequest onlyGcs = req(null);
        onlyGcs.setIpdAdmissionId(icuAdmissionId);
        onlyGcs.setGcsEye(3);
        onlyGcs.setGcsVerbal(4);
        onlyGcs.setGcsMotor(5);
        assertThat(vitalsService.create(onlyGcs).getGcsTotal()).isEqualTo(12);

        VitalsRequest onlyUrine = req(null);
        onlyUrine.setIpdAdmissionId(icuAdmissionId);
        onlyUrine.setUrineOutputMl(50);
        assertThat(vitalsService.create(onlyUrine).getUrineOutputMl()).isEqualTo(50);
    }

    @Test
    void anEmptyObservationIsStillRejected() {
        VitalsRequest empty = req(null);
        empty.setIpdAdmissionId(icuAdmissionId);

        assertThatThrownBy(() -> vitalsService.create(empty))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("At least one vital measurement");
    }

    // ── ward behaviour unchanged ─────────────────────────────────────────────

    @Test
    void aWardObservationIsStillEditedInPlace_exactlyAsBefore() {
        VitalsRecord original = record(wardAdmissionId, 98);
        long countBefore = vitalsRepository.count();

        VitalsRecord updated = vitalsService.update(original.getPublicId(), req(95));

        assertThat(updated.getPublicId()).as("same row, edited in place").isEqualTo(original.getPublicId());
        assertThat(updated.getSpo2()).isEqualTo(95);
        assertThat(vitalsRepository.count()).as("no extra row").isEqualTo(countBefore);
    }

    // ── the guard ────────────────────────────────────────────────────────────

    @Test
    void anIcuPeriodObservationCannotBeOverwritten() {
        VitalsRecord original = record(icuAdmissionId, 92);

        assertThatThrownBy(() -> vitalsService.update(original.getPublicId(), req(88)))
                .isInstanceOf(com.hms.exception.ConflictException.class)
                .hasMessageContaining("correction");

        assertThat(vitalsRepository.findByPublicId(original.getPublicId()).orElseThrow().getSpo2())
                .as("the original value survived the attempt").isEqualTo(92);
    }

    @Test
    void anObservationRecordedBeforeTheStayOpenedIsStillEditable() {
        // The guard is window-based, not admission-based: only readings taken DURING critical
        // care are protected.
        VitalsRequest r = req(97);
        r.setIpdAdmissionId(icuAdmissionId);
        r.setRecordedAt(LocalDateTime.now().minusDays(2)); // before the stay began
        VitalsRecord before = vitalsService.create(r);

        VitalsRecord updated = vitalsService.update(before.getPublicId(), req(96));
        assertThat(updated.getPublicId()).isEqualTo(before.getPublicId());
        assertThat(updated.getSpo2()).isEqualTo(96);
    }

    // ── append-only correction ───────────────────────────────────────────────

    @Test
    void correctionCreatesANewRowAndLeavesTheOriginalIntact() {
        VitalsRecord original = record(icuAdmissionId, 92);

        VitalsRecord correction = vitalsService.correct(original.getPublicId(), req(89));

        assertThat(correction.getPublicId()).isNotEqualTo(original.getPublicId());
        assertThat(correction.getSupersedesVitalsId()).isEqualTo(original.getId());
        assertThat(correction.getSpo2()).isEqualTo(89);

        VitalsRecord reloaded = vitalsRepository.findByPublicId(original.getPublicId()).orElseThrow();
        assertThat(reloaded.getSpo2()).as("original untouched").isEqualTo(92);
        assertThat(reloaded.getIsActive()).as("original NOT hidden").isTrue();
        assertThat(reloaded.getSupersedesVitalsId()).isNull();
    }

    @Test
    void theCorrectionKeepsTheOriginalObservationTime() {
        VitalsRequest r = req(92);
        r.setIpdAdmissionId(icuAdmissionId);
        LocalDateTime observedAt = LocalDateTime.now().minusHours(3);
        r.setRecordedAt(observedAt);
        VitalsRecord original = vitalsService.create(r);

        VitalsRecord correction = vitalsService.correct(original.getPublicId(), req(90));

        // Compare against the persisted original: the in-memory object created above still
        // carries nanosecond precision the datetime(6) column does not keep.
        VitalsRecord persisted = vitalsRepository.findByPublicId(original.getPublicId()).orElseThrow();
        assertThat(correction.getRecordedAt()).isEqualTo(persisted.getRecordedAt());
    }

    @Test
    void bothTheOriginalAndTheCorrectionRemainReadableInHistory() {
        VitalsRecord original = record(icuAdmissionId, 92);
        VitalsRecord correction = vitalsService.correct(original.getPublicId(), req(88));

        List<VitalsRecord> history = vitalsService.getByAdmission(icuAdmissionId);

        assertThat(history).extracting(VitalsRecord::getPublicId)
                .contains(original.getPublicId(), correction.getPublicId());
    }

    @Test
    void aChainOfCorrectionsIsPreservedEndToEnd() {
        VitalsRecord first = record(icuAdmissionId, 92);
        VitalsRecord second = vitalsService.correct(first.getPublicId(), req(90));
        VitalsRecord third = vitalsService.correct(second.getPublicId(), req(88));

        assertThat(second.getSupersedesVitalsId()).isEqualTo(first.getId());
        assertThat(third.getSupersedesVitalsId()).isEqualTo(second.getId());
        assertThat(vitalsService.getByAdmission(icuAdmissionId)).hasSize(3);
    }

    @Test
    void aClosedStaysObservationsRemainReadableAndCorrectable() {
        VitalsRecord original = record(icuAdmissionId, 92);
        icuStayRepository.findByIpdAdmissionIdAndHospitalIdAndStatus(
                icuAdmissionId, hospitalId, IcuStay.ACTIVE).ifPresent(s -> {
            s.setStatus(IcuStay.CLOSED);
            s.setDischargedAt(LocalDateTime.now().plusHours(1)); // window still covers the reading
            s.setDisposition(IcuStay.DISP_WARD);
            s.setActiveMarker(null);
            icuStayRepository.save(s);
        });

        assertThat(vitalsService.getByAdmission(icuAdmissionId)).isNotEmpty();
        assertThat(vitalsService.correct(original.getPublicId(), req(87)).getSupersedesVitalsId())
                .isEqualTo(original.getId());
    }

    // ── correction is refused outside an ICU window ──────────────────────────

    @Test
    void correctingAWardObservationIsRefused() {
        VitalsRecord wardRow = record(wardAdmissionId, 98);

        assertThatThrownBy(() -> vitalsService.correct(wardRow.getPublicId(), req(97)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not recorded during an ICU stay");
    }

    // ── authorisation: unchanged, NOT widened (D-6) ──────────────────────────

    @Test
    void correctionRequiresTheVitalsFormPermission() {
        VitalsRecord original = record(icuAdmissionId, 92);
        doThrow(new org.springframework.security.access.AccessDeniedException("denied"))
                .when(formAccessService).assertCanEdit(anyString());

        assertThatThrownBy(() -> vitalsService.correct(original.getPublicId(), req(88)))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void aNurseWhoDidNotRecordTheObservationCannotCorrectIt() {
        VitalsRecord original = record(icuAdmissionId, 92);
        when(securityHelper.getCurrentUserId()).thenReturn(OTHER_NURSE);

        assertThatThrownBy(() -> vitalsService.correct(original.getPublicId(), req(88)))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(vitalsRepository.findByPublicId(original.getPublicId()).orElseThrow().getSpo2())
                .isEqualTo(92);
    }

    @Test
    void theEditWindowStillAppliesToACorrection() {
        VitalsRecord original = record(icuAdmissionId, 92);
        // created_at is @CreationTimestamp/updatable=false, so age it in the database directly.
        jdbc.update("UPDATE vitals_records SET created_at = ? WHERE id = ?",
                java.sql.Timestamp.valueOf(LocalDateTime.now().minusDays(2)), original.getId());

        assertThatThrownBy(() -> vitalsService.correct(original.getPublicId(), req(88)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Edit window");
    }

    // ── tenancy ──────────────────────────────────────────────────────────────

    @Test
    void anotherHospitalsObservationIsIndistinguishableFromAMissingOne() {
        VitalsRecord mine = record(icuAdmissionId, 92);

        Hospital other = new Hospital();
        other.setName("Other-" + uniq());
        other.setCustomId("HID-" + uniq());
        other.setSubscriptionStatus("ACTIVE");
        other.setIsActive(true);
        other.setModules(List.of("IPD"));
        other.setIsSingleDoctor(false);
        Long otherHospitalId = hospitalRepository.save(other).getId();
        when(securityHelper.getCurrentHospitalId()).thenReturn(otherHospitalId);

        assertThatThrownBy(() -> vitalsService.correct(mine.getPublicId(), req(88)))
                .isInstanceOf(com.hms.exception.ResourceNotFoundException.class);

        when(securityHelper.getCurrentHospitalId()).thenReturn(hospitalId);
        assertThat(vitalsRepository.findByPublicId(mine.getPublicId()).orElseThrow().getSpo2())
                .as("nothing written across tenants").isEqualTo(92);
    }

    // ── rollback ─────────────────────────────────────────────────────────────

    @Test
    void aFailedCorrectionLeavesTheOriginalAndWritesNothing() {
        VitalsRecord original = record(icuAdmissionId, 92);
        long before = vitalsRepository.count();

        // An empty request fails validation after the guards have passed.
        assertThatThrownBy(() -> vitalsService.correct(original.getPublicId(), new VitalsRequest()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(vitalsRepository.count()).isEqualTo(before);
        assertThat(vitalsRepository.findByPublicId(original.getPublicId()).orElseThrow().getSpo2())
                .isEqualTo(92);
    }
}
