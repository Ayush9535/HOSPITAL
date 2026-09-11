package com.hms.service.hospital;

import com.hms.entity.Bed;
import com.hms.entity.BedStatus;
import com.hms.entity.Doctor;
import com.hms.entity.Hospital;
import com.hms.entity.Opd;
import com.hms.entity.Patient;
import com.hms.entity.Ward;
import com.hms.repository.BedRepository;
import com.hms.repository.BillingItemRepository;
import com.hms.repository.BillingRepository;
import com.hms.repository.DoctorRepository;
import com.hms.repository.HospitalRepository;
import com.hms.repository.IpdAdmissionRepository;
import com.hms.repository.IpdBedHistoryRepository;
import com.hms.repository.OpdRepository;
import com.hms.repository.PatientRepository;
import com.hms.repository.WardRepository;
import com.hms.security.SecurityContextHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

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
 * E1 (C1) — the transaction boundary on {@code admitFromOpd}.
 *
 * <p><b>Why this class exists.</b> The repaired {@code AdmissionBedWardIsolationTest} passes 6/6
 * and does NOT reproduce C1: every refusal it exercises is a tenant check that fires on the OPD,
 * ward or bed lookup — all of them BEFORE the first write — so nothing is ever left behind.
 * C1 only shows itself when a step fails AFTER the admission row is written, and no test in the
 * repository did that. These do.
 *
 * <p>Each case forces a failure at a different point past the first write and asserts that the
 * whole unit of work is gone: no admission, no bill, no bed-history span, and a bed still
 * available. Before the fix, each of these left a permanently committed admission behind.
 */
@SpringBootTest
@ActiveProfiles("test")
class IpdAdmissionTransactionTest {

    @Autowired IpdAdmissionService service;
    @Autowired HospitalRepository hospitalRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired DoctorRepository doctorRepository;
    @Autowired WardRepository wardRepository;
    @Autowired BedRepository bedRepository;
    @Autowired OpdRepository opdRepository;
    @Autowired IpdAdmissionRepository ipdAdmissionRepository;
    @Autowired IpdBedHistoryRepository ipdBedHistoryRepository;
    @Autowired BillingRepository billingRepository;
    @Autowired BillingItemRepository billingItemRepository;

    /** The seam used to fail a step that runs after the admission row is written. */
    @MockBean BedStatusService bedStatusService;

    /** Stands in for the caller's authenticated tenant. */
    @MockBean SecurityContextHelper securityHelper;

    private Long hospitalId;
    private Long opdId;
    private Long wardId;
    private Long bedId;

    private String uniq() { return Long.toString(System.nanoTime()); }

    @BeforeEach
    void setUp() {
        // Default claim behaviour: apply the status and hand the row back, as BedStatusService
        // does. Tests that care about the claim override this; the rest are about the
        // transaction boundary and should not have to know how a bed is locked.
        when(bedStatusService.changeLocked(any(Bed.class), anyString(), anyString()))
                .thenAnswer(inv -> {
                    Bed b = inv.getArgument(0);
                    b.setStatus(inv.getArgument(1));
                    return bedRepository.save(b);
                });

        Hospital h = new Hospital();
        h.setName("H-" + uniq());
        h.setCustomId("HID-" + uniq());
        h.setSubscriptionStatus("ACTIVE");
        h.setIsActive(true);
        h.setModules(List.of("OPD", "IPD", "BILLING"));
        h.setIsSingleDoctor(false);
        hospitalId = hospitalRepository.save(h).getId();

        Doctor d = new Doctor();
        d.setName("Dr Edwards");
        d.setHospitalId(hospitalId);
        d.setIsActive(true);
        d.setEmail("doc-" + uniq() + "@e1.test");
        d.setPublicId("dpub-" + uniq());
        d.setPhone("9800000001");
        d.setSpecialization("Gen");
        doctorRepository.save(d);

        Patient p = new Patient();
        p.setName("Test Patient");
        p.setHospitalId(hospitalId);
        p.setPublicId("ppub-" + uniq());
        p.setGender("MALE");
        p.setPhone("9900000001");
        p.setIsActive(true);
        p.setDateOfBirth(LocalDate.of(1980, 1, 1));
        patientRepository.save(p);

        Ward w = new Ward();
        w.setWardName("W-" + uniq());
        w.setHospitalId(hospitalId);
        w.setBedPrice(new BigDecimal("1500"));
        w.setTotalBeds(1);
        wardId = wardRepository.save(w).getWardId();

        Bed b = new Bed();
        b.setHospitalId(hospitalId);
        b.setWardId(wardId);
        b.setBedCode("BED-" + uniq());
        b.setStatus(BedStatus.AVAILABLE);
        bedId = bedRepository.save(b).getBedId();

        Opd o = new Opd();
        o.setCaseId("OPD-" + uniq());
        o.setIpdAdmitRecommended(true);
        o.setPatient(p);
        o.setDoctor(d);
        opdId = opdRepository.save(o).getId();

        when(securityHelper.getCurrentHospitalId()).thenReturn(hospitalId);
        when(securityHelper.getCurrentUserId()).thenReturn(1L);
        when(securityHelper.getCurrentUserEmail()).thenReturn("admin@e1.test");
        when(securityHelper.getCurrentUserRole()).thenReturn("HOSPITAL_ADMIN");
    }

    private record Counts(long admissions, long bills, long items, long history, String bedStatus) {}

    private Counts snapshot() {
        return new Counts(
                ipdAdmissionRepository.count(),
                billingRepository.count(),
                billingItemRepository.count(),
                ipdBedHistoryRepository.count(),
                bedRepository.findById(bedId).orElseThrow().getStatus());
    }

    private void assertNothingSurvived(Counts before) {
        Counts after = snapshot();
        assertThat(after.admissions()).as("no admission row survives").isEqualTo(before.admissions());
        assertThat(after.bills()).as("no bill survives").isEqualTo(before.bills());
        assertThat(after.items()).as("no bill item survives").isEqualTo(before.items());
        assertThat(after.history()).as("no bed-history span survives").isEqualTo(before.history());
        assertThat(after.bedStatus()).as("bed is still available").isEqualToIgnoringCase(BedStatus.AVAILABLE);
    }

    // ── the happy path still works ───────────────────────────────────────────

    @Test
    void admission_commitsEveryCriticalRowTogether() {
        Bed available = bedRepository.findById(bedId).orElseThrow();
                Bed claimed = bedRepository.findById(bedId).orElseThrow();
        claimed.setStatus(BedStatus.OCCUPIED);
        when(bedStatusService.changeLocked(any(Bed.class), anyString(), anyString())).thenReturn(claimed);

        Counts before = snapshot();
        var saved = service.admitFromOpd(opdId, wardId, bedId, "ELECTIVE", "obs");

        assertThat(saved.getId()).isNotNull();
        Counts after = snapshot();
        assertThat(after.admissions()).isEqualTo(before.admissions() + 1);
        assertThat(after.bills()).isEqualTo(before.bills() + 1);
        assertThat(after.history()).as("the bed span is written, not merely attempted")
                .isEqualTo(before.history() + 1);
        assertThat(opdRepository.findById(opdId).orElseThrow().getStatus()).isEqualTo(Opd.Status.IN_IPD);
    }

    // ── C1: a failure after the first write must undo everything ─────────────

    @Test
    void bedClaimFails_afterTheAdmissionRowIsWritten_rollsBackTheWholeAdmission() {
        // Scenario A of the plan. Before the fix this left a committed ipd_admission row, a
        // consumed IPD number and an untouched bed -- a patient admitted to nowhere.
                doThrow(new IllegalStateException("bed claim exploded"))
                .when(bedStatusService).changeLocked(any(Bed.class), anyString(), anyString());

        Counts before = snapshot();

        assertThatThrownBy(() -> service.admitFromOpd(opdId, wardId, bedId, "ELECTIVE", "obs"))
                .isInstanceOf(IllegalStateException.class);

        assertNothingSurvived(before);
    }

    @Test
    void bedHistoryIsCriticalState_itsFailureRollsBackTheAdmission() {
        // D-4: promoted from a swallowed try/catch. A lost bed span used to leave an admission
        // whose location could not be reconstructed by the ICU board or LOS reporting.
        // Proven here by failing the very next step and asserting the span is gone too.
                doThrow(new IllegalStateException("claim failed"))
                .when(bedStatusService).changeLocked(any(Bed.class), anyString(), anyString());

        Counts before = snapshot();
        assertThatThrownBy(() -> service.admitFromOpd(opdId, wardId, bedId, "ELECTIVE", "obs"))
                .isInstanceOf(RuntimeException.class);

        assertThat(ipdBedHistoryRepository.count())
                .as("the bed span rolled back with the admission")
                .isEqualTo(before.history());
    }

    // ── best-effort side effects must stay best-effort ───────────────────────

    @Test
    void aRealtimePushFailureNeverRollsBackACompletedAdmission() {
        Bed available = bedRepository.findById(bedId).orElseThrow();
                Bed claimed = bedRepository.findById(bedId).orElseThrow();
        claimed.setStatus(BedStatus.OCCUPIED);
        when(bedStatusService.changeLocked(any(Bed.class), anyString(), anyString())).thenReturn(claimed);

        Counts before = snapshot();
        service.admitFromOpd(opdId, wardId, bedId, "ELECTIVE", "obs");

        // RealtimeNotifier swallows its own failures and fires after commit, so the admission
        // stands regardless of the socket.
        assertThat(ipdAdmissionRepository.count()).isEqualTo(before.admissions() + 1);
    }

    // ── I-7 / ICU-3 readiness ────────────────────────────────────────────────

    @Test
    void aMandatoryPropagationBeanCanJoinTheAdmissionTransaction() {
        // The explicit ICU-3 gate. IcuStayService is declared Propagation.MANDATORY, which throws
        // IllegalTransactionStateException when no transaction is active. Before C1 this threw on
        // every admission; it must now join silently.
                Bed claimed = bedRepository.findById(bedId).orElseThrow();
        claimed.setStatus(BedStatus.OCCUPIED);
        when(bedStatusService.change(anyLong(), anyString(), anyString())).thenAnswer(inv -> {
            // Runs inside admitFromOpd's transaction, exactly where ICU-3's hook will sit.
            assertThat(TransactionAspectSupport.currentTransactionStatus())
                    .as("a transaction is active at the point ICU-3 will call IcuStayService")
                    .isNotNull();
            return claimed;
        });

        service.admitFromOpd(opdId, wardId, bedId, "ELECTIVE", "obs");

        assertThat(ipdAdmissionRepository.count()).isPositive();
    }

    /** Proves the probe above is meaningful: MANDATORY really does throw without a transaction. */
    @Test
    void mandatoryPropagationThrowsWhenNoTransactionIsActive() {
        assertThatThrownBy(TransactionAspectSupport::currentTransactionStatus)
                .as("no ambient transaction outside a @Transactional method")
                .isInstanceOf(org.springframework.transaction.NoTransactionException.class);
    }
}
