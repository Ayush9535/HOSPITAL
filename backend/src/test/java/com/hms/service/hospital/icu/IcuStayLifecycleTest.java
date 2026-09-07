package com.hms.service.hospital.icu;

import com.hms.entity.Bed;
import com.hms.entity.BedStatus;
import com.hms.entity.Doctor;
import com.hms.entity.Hospital;
import com.hms.entity.IcuStay;
import com.hms.entity.IpdAdmission;
import com.hms.entity.Opd;
import com.hms.entity.Patient;
import com.hms.entity.Ward;
import com.hms.repository.BedRepository;
import com.hms.repository.DoctorRepository;
import com.hms.repository.HospitalRepository;
import com.hms.repository.IcuStayRepository;
import com.hms.repository.IpdAdmissionRepository;
import com.hms.repository.OpdRepository;
import com.hms.repository.PatientRepository;
import com.hms.repository.WardRepository;
import com.hms.security.SecurityContextHelper;
import com.hms.service.hospital.BedStatusService;
import com.hms.service.hospital.IpdAdmissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * ICU Phase 3 — the stay lifecycle, driven through the REAL IPD movement paths.
 *
 * <p>Deliberately not a unit test of {@code IcuStayService} in isolation: the whole point of the
 * design is that a stay is a consequence of an existing movement and shares its transaction, so
 * exercising it any other way would prove something other than what ships.
 */
@SpringBootTest
@ActiveProfiles("test")
class IcuStayLifecycleTest {

    @Autowired IpdAdmissionService ipdService;
    @Autowired IcuStayService icuStayService;
    @Autowired IcuStayRepository icuStayRepository;
    @Autowired HospitalRepository hospitalRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired DoctorRepository doctorRepository;
    @Autowired WardRepository wardRepository;
    @Autowired BedRepository bedRepository;
    @Autowired OpdRepository opdRepository;
    @Autowired IpdAdmissionRepository ipdAdmissionRepository;

    @MockBean BedStatusService bedStatusService;
    @MockBean SecurityContextHelper securityHelper;

    private Long hospitalId;
    private Long icuWardId;
    private Long icu2WardId;
    private Long generalWardId;
    private Long doctorId;

    private String uniq() { return Long.toString(System.nanoTime()); }

    @BeforeEach
    void setUp() {
        Hospital h = new Hospital();
        h.setName("H-" + uniq());
        h.setCustomId("HID-" + uniq());
        h.setSubscriptionStatus("ACTIVE");
        h.setIsActive(true);
        h.setModules(List.of("OPD", "IPD", "BILLING", "ICU"));
        h.setIsSingleDoctor(false);
        hospitalId = hospitalRepository.save(h).getId();

        icuWardId = ward("ICU-A", CareUnitRegistry.ICU);
        icu2WardId = ward("MICU-B", CareUnitRegistry.MICU);
        generalWardId = ward("General-C", CareUnitRegistry.GENERAL);

        Doctor d = new Doctor();
        d.setName("Doctor Who");
        d.setHospitalId(hospitalId);
        d.setIsActive(true);
        d.setEmail("doc-" + uniq() + "@icu.test");
        d.setPublicId("dpub-" + uniq());
        d.setPhone("9800000001");
        d.setSpecialization("Critical Care");
        doctorId = doctorRepository.save(d).getId();

        when(securityHelper.getCurrentHospitalId()).thenReturn(hospitalId);
        when(securityHelper.getCurrentUserId()).thenReturn(1L);
        when(securityHelper.getCurrentUserEmail()).thenReturn("admin@icu.test");
        when(securityHelper.getCurrentUserRole()).thenReturn("HOSPITAL_ADMIN");
        when(securityHelper.getCurrentUserDetails()).thenReturn(null);

        // The bed claim is mocked so these tests stay about the STAY, not about E1's locking.
        when(bedStatusService.lockForClaim(anyLong())).thenAnswer(inv ->
                bedRepository.findById((Long) inv.getArgument(0)).orElseThrow());
        when(bedStatusService.change(anyLong(), anyString(), anyString())).thenAnswer(inv ->
                bedRepository.findById((Long) inv.getArgument(0)).orElseThrow());
        // The release candidate claims the bed through changeLocked(bed, status, reason) rather
        // than ICU's earlier lockForClaim seam. Same intent as the two stubs above: keep these
        // tests about the STAY, not about how the bed is locked.
        when(bedStatusService.changeLocked(any(com.hms.entity.Bed.class), anyString(), anyString()))
                .thenAnswer(inv -> {
                    com.hms.entity.Bed b = inv.getArgument(0);
                    b.setStatus(inv.getArgument(1));
                    return bedRepository.save(b);
                });
    }

    private Long ward(String name, String unitType) {
        return ward(name, unitType, new BigDecimal("2000"));
    }

    private Long ward(String name, String unitType, BigDecimal bedPrice) {
        Ward w = new Ward();
        w.setWardName(name + "-" + uniq());
        w.setHospitalId(hospitalId);
        w.setBedPrice(bedPrice);
        w.setTotalBeds(4);
        w.setUnitType(unitType);
        return wardRepository.save(w).getWardId();
    }

    private Long bed(Long wardId) {
        Bed b = new Bed();
        b.setHospitalId(hospitalId);
        b.setWardId(wardId);
        b.setBedCode("BED-" + uniq());
        b.setStatus(BedStatus.AVAILABLE);
        return bedRepository.save(b).getBedId();
    }

    private Long opdCase() {
        Patient p = new Patient();
        p.setName("Test Patient");
        p.setHospitalId(hospitalId);
        p.setPublicId("ppub-" + uniq());
        p.setGender("MALE");
        p.setPhone("9900000001");
        p.setIsActive(true);
        p.setDateOfBirth(LocalDate.of(1980, 1, 1));
        patientRepository.save(p);

        Opd o = new Opd();
        o.setCaseId("OPD-" + uniq());
        o.setIpdAdmitRecommended(true);
        o.setPatient(p);
        o.setDoctor(doctorRepository.findById(doctorId).orElseThrow());
        return opdRepository.save(o).getId();
    }

    private IpdAdmission admitTo(Long wardId, String type) {
        return ipdService.admitFromOpd(opdCase(), wardId, bed(wardId), type, "obs");
    }

    private List<IcuStay> staysOf(Long admissionId) {
        return icuStayRepository.findByIpdAdmissionIdAndHospitalIdOrderByAdmittedAtDesc(
                admissionId, hospitalId);
    }

    private IcuStay activeOf(Long admissionId) {
        return icuStayRepository
                .findByIpdAdmissionIdAndHospitalIdAndStatus(admissionId, hospitalId, IcuStay.ACTIVE)
                .orElse(null);
    }

    // ── open ─────────────────────────────────────────────────────────────────

    @Test
    void admittingStraightIntoIcu_opensAStay_withSourceDerivedFromAdmissionType() {
        IpdAdmission a = admitTo(icuWardId, "EMERGENCY");

        IcuStay stay = activeOf(a.getId());
        assertThat(stay).isNotNull();
        assertThat(stay.getSource()).isEqualTo(IcuStay.SRC_EMERGENCY);
        assertThat(stay.getWardId()).isEqualTo(icuWardId);
        assertThat(stay.getPatientId()).isEqualTo(a.getPatientId());
        assertThat(stay.getActiveMarker()).isEqualTo(a.getId());
    }

    @Test
    void anElectiveDirectAdmissionRecordsOpdAsTheSource() {
        assertThat(activeOf(admitTo(icuWardId, "ELECTIVE").getId()).getSource())
                .isEqualTo(IcuStay.SRC_OPD);
    }

    @Test
    void admittingIntoAGeneralWard_createsNoStayAtAll() {
        // The backward-compatibility guarantee: a hospital with no ICU is untouched.
        IpdAdmission a = admitTo(generalWardId, "ELECTIVE");
        assertThat(staysOf(a.getId())).isEmpty();
    }

    @Test
    void generalToIcu_opensAStayWithTheWardItSteppedUpFrom() {
        IpdAdmission a = admitTo(generalWardId, "ELECTIVE");
        assertThat(staysOf(a.getId())).isEmpty();

        ipdService.changeBed(a.getId(), bed(icuWardId));

        IcuStay stay = activeOf(a.getId());
        assertThat(stay).isNotNull();
        assertThat(stay.getSource()).isEqualTo(IcuStay.SRC_WARD);
        assertThat(stay.getSourceRefId()).isEqualTo(generalWardId);
    }

    // ── no-op ────────────────────────────────────────────────────────────────

    @Test
    void movingBedWithinTheSameIcuWard_doesNotStartANewStay() {
        IpdAdmission a = admitTo(icuWardId, "ELECTIVE");
        String firstPublicId = activeOf(a.getId()).getPublicId();

        ipdService.changeBed(a.getId(), bed(icuWardId));

        assertThat(staysOf(a.getId())).hasSize(1);
        assertThat(activeOf(a.getId()).getPublicId()).isEqualTo(firstPublicId);
    }

    // ── close ────────────────────────────────────────────────────────────────

    @Test
    void icuToGeneral_closesTheStayWithAWardDisposition() {
        IpdAdmission a = admitTo(icuWardId, "ELECTIVE");

        ipdService.changeBed(a.getId(), bed(generalWardId));

        assertThat(activeOf(a.getId())).isNull();
        IcuStay closed = staysOf(a.getId()).get(0);
        assertThat(closed.getStatus()).isEqualTo(IcuStay.CLOSED);
        assertThat(closed.getDisposition()).isEqualTo(IcuStay.DISP_WARD);
        assertThat(closed.getDischargedAt()).isNotNull();
        assertThat(closed.getActiveMarker()).as("freed for a later stay").isNull();
    }

    @Test
    void icuToAnotherIcu_closesTheOldStayAndOpensANewOne() {
        IpdAdmission a = admitTo(icuWardId, "ELECTIVE");
        Long firstId = activeOf(a.getId()).getId();

        ipdService.changeBed(a.getId(), bed(icu2WardId));

        List<IcuStay> all = staysOf(a.getId());
        assertThat(all).hasSize(2);
        IcuStay current = activeOf(a.getId());
        assertThat(current.getWardId()).isEqualTo(icu2WardId);
        assertThat(current.getSource()).isEqualTo(IcuStay.SRC_ICU_TRANSFER);
        assertThat(current.getSourceRefId()).as("points at the stay it continues").isEqualTo(firstId);

        IcuStay previous = icuStayRepository.findById(firstId).orElseThrow();
        assertThat(previous.getStatus()).isEqualTo(IcuStay.CLOSED);
        assertThat(previous.getDisposition()).isEqualTo(IcuStay.DISP_ANOTHER_ICU);
    }

    @Test
    void readmissionToIcuAfterStepDown_producesTwoStays() {
        IpdAdmission a = admitTo(icuWardId, "ELECTIVE");
        ipdService.changeBed(a.getId(), bed(generalWardId));
        ipdService.changeBed(a.getId(), bed(icuWardId));

        assertThat(staysOf(a.getId())).hasSize(2);
        assertThat(activeOf(a.getId())).isNotNull();
    }

    // ── at most one ACTIVE ───────────────────────────────────────────────────

    @Test
    void aSecondActiveStayIsRejected_byTheDatabaseNotJustTheService() {
        IpdAdmission a = admitTo(icuWardId, "ELECTIVE");

        // Bypass the service guard entirely and go straight at the unique index, so this proves
        // the DB enforces it rather than the service check happening to run first.
        IcuStay duplicate = new IcuStay();
        duplicate.setHospitalId(hospitalId);
        duplicate.setIpdAdmissionId(a.getId());
        duplicate.setPatientId(a.getPatientId());
        duplicate.setWardId(icuWardId);
        duplicate.setStatus(IcuStay.ACTIVE);
        duplicate.setSource(IcuStay.SRC_WARD);
        duplicate.setAdmittedAt(java.time.LocalDateTime.now());
        duplicate.setActiveMarker(a.getId()); // collides on uk_icu_stay_active

        assertThatThrownBy(() -> icuStayRepository.saveAndFlush(duplicate))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    // ── discharge ────────────────────────────────────────────────────────────

    @Test
    void dischargeClosesTheActiveStay() {
        // A free ICU ward, so confirmDischarge's outstanding-balance guard is not what this
        // test ends up measuring. Billing behaviour is IPD's, and unchanged by ICU-3.
        Long freeIcuWard = ward("ICU-Free", CareUnitRegistry.ICU, BigDecimal.ZERO);
        IpdAdmission a = admitTo(freeIcuWard, "ELECTIVE");
        ipdService.planDischarge(a.getId(), new com.hms.dto.PlanDischargeRequest());
        ipdService.confirmDischarge(a.getId());

        assertThat(activeOf(a.getId())).isNull();
        assertThat(staysOf(a.getId()).get(0).getStatus()).isEqualTo(IcuStay.CLOSED);
    }

    // ── immutability + narrow mutations ──────────────────────────────────────

    @Test
    void closedStaysRemainReadableButCannotBeMutated() {
        IpdAdmission a = admitTo(icuWardId, "ELECTIVE");
        String publicId = activeOf(a.getId()).getPublicId();
        ipdService.changeBed(a.getId(), bed(generalWardId));

        assertThat(icuStayService.getByPublicId(publicId)).isNotNull();     // still readable
        assertThat(icuStayService.historyFor(a.getId())).hasSize(1);

        assertThatThrownBy(() -> icuStayService.setIntensivist(publicId, doctorId))
                .isInstanceOf(com.hms.exception.ConflictException.class);
        assertThatThrownBy(() -> icuStayService.setAdmissionReason(publicId, "x"))
                .isInstanceOf(com.hms.exception.ConflictException.class);
    }

    @Test
    void intensivistAndReasonCanBeSetOnAnActiveStay() {
        IpdAdmission a = admitTo(icuWardId, "ELECTIVE");
        String publicId = activeOf(a.getId()).getPublicId();

        assertThat(icuStayService.setIntensivist(publicId, doctorId).getIntensivistDoctorId())
                .isEqualTo(doctorId);
        assertThat(icuStayService.setAdmissionReason(publicId, "Septic shock").getAdmissionReason())
                .isEqualTo("Septic shock");
        assertThat(icuStayService.setIntensivist(publicId, null).getIntensivistDoctorId()).isNull();
    }

    @Test
    void settingAnIntensivistNeverMovesTheCaseOffTheAdmittingDoctor() {
        // D4/I21: IpdAdmission.doctorId feeds billing and the doctor's IPD list, so repointing it
        // at the intensivist would silently take the case off the admitting doctor's dashboard.
        IpdAdmission a = admitTo(icuWardId, "ELECTIVE");
        Long admittingDoctorId = a.getDoctorId();
        String publicId = activeOf(a.getId()).getPublicId();

        icuStayService.setIntensivist(publicId, doctorId);

        assertThat(ipdAdmissionRepository.findById(a.getId()).orElseThrow().getDoctorId())
                .as("the admitting doctor keeps the case").isEqualTo(admittingDoctorId);
    }

    @Test
    void aForeignIntensivistIsRefusedAsIfMissing() {
        Hospital other = new Hospital();
        other.setName("Other-" + uniq());
        other.setCustomId("HID-" + uniq());
        other.setSubscriptionStatus("ACTIVE");
        other.setIsActive(true);
        other.setModules(List.of("OPD", "IPD"));
        other.setIsSingleDoctor(false);
        Long otherHospitalId = hospitalRepository.save(other).getId();

        Doctor foreign = new Doctor();
        foreign.setName("Foreign Doctor");
        foreign.setHospitalId(otherHospitalId);
        foreign.setIsActive(true);
        foreign.setEmail("f-" + uniq() + "@other.test");
        foreign.setPublicId("dpub-" + uniq());
        foreign.setPhone("9800000009");
        foreign.setSpecialization("Gen");
        Long foreignDoctorId = doctorRepository.save(foreign).getId();

        IpdAdmission a = admitTo(icuWardId, "ELECTIVE");
        String publicId = activeOf(a.getId()).getPublicId();

        assertThatThrownBy(() -> icuStayService.setIntensivist(publicId, foreignDoctorId))
                .isInstanceOf(com.hms.exception.ResourceNotFoundException.class);
        assertThat(activeOf(a.getId()).getIntensivistDoctorId()).isNull();
    }

    // ── the stay is critical state, not a side effect ────────────────────────

    @Test
    void aFailureAfterTheStayOpens_rollsBackTheStayWithTheAdmission() {
        // Injected at the seam the release candidate actually claims the bed through
        // (changeLocked, on an already-locked row) rather than ICU's earlier change(id, ...).
        // The assertion below is unchanged: the stay must not survive a failed admission.
        doThrow(new IllegalStateException("bed claim exploded"))
                .when(bedStatusService)
                .changeLocked(any(com.hms.entity.Bed.class), anyString(), anyString());

        long staysBefore = icuStayRepository.count();
        long admissionsBefore = ipdAdmissionRepository.count();

        assertThatThrownBy(() -> admitTo(icuWardId, "ELECTIVE"))
                .isInstanceOf(RuntimeException.class);

        assertThat(icuStayRepository.count()).as("no orphan stay survives").isEqualTo(staysBefore);
        assertThat(ipdAdmissionRepository.count()).isEqualTo(admissionsBefore);
    }

    @Test
    void icuStayServiceRefusesToRunOutsideAMovementTransaction() {
        // MANDATORY is the guardrail: a stay must never commit apart from the movement.
        IpdAdmission detached = new IpdAdmission();
        detached.setId(-1L);
        detached.setHospitalId(hospitalId);
        detached.setPatientId(-1L);
        detached.setWardId(icuWardId);

        assertThatThrownBy(() -> icuStayService.onWardSettled(detached, null, IcuStay.SRC_WARD, null, null))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }
}
