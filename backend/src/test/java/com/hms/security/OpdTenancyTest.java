package com.hms.security;

import com.hms.entity.*;
import com.hms.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R3-2 — the OPD tenancy chain, asserted directly for the first time.
 *
 * <p>Opd has no hospital_id. Its tenant is only knowable by joining the owning patient, which means
 * the usual "load by id, then compare entity.getHospitalId()" pattern is unavailable — there is
 * nothing on the row to compare. Everything therefore rests on one query keeping its predicate, and
 * until now nothing asserted that it does.
 *
 * <p>These tests exercise the repository directly rather than through HTTP, because the property
 * under test is the query itself: the three production paths converted in R3-2 all reduce to this
 * one call, and {@code OpdRepositoryScopingArchTest} proves there is no other way in.
 */
@SpringBootTest
@ActiveProfiles("test")
class OpdTenancyTest {

    @Autowired OpdRepository opdRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired HospitalRepository hospitalRepository;

    private Hospital hospitalA;
    private Hospital hospitalB;
    private Opd opdOfA;

    private String uniq() { return Long.toString(System.nanoTime()); }

    @BeforeEach
    void setUp() {
        hospitalA = newHospital();
        hospitalB = newHospital();
        opdOfA = newOpd(hospitalA);
    }

    private Hospital newHospital() {
        Hospital h = new Hospital();
        h.setName("OpdTen-" + uniq());
        h.setCustomId("OT-" + uniq());
        h.setType(HospitalType.HOSPITAL);
        h.setIsActive(true);
        h.setIsSingleDoctor(false);
        h.setSubscriptionStatus("ACTIVE");
        h.setModules(List.of("OPD"));
        return hospitalRepository.save(h);
    }

    private Opd newOpd(Hospital owner) {
        Patient p = new Patient();
        p.setHospitalId(owner.getId());
        p.setName("Opd Patient");
        p.setPhone(String.format("9%09d", System.nanoTime() % 1_000_000_000L));
        p.setGender("MALE");
        p.setDateOfBirth(LocalDate.of(1990, 1, 1));
        p.setIsActive(true);
        p = patientRepository.save(p);

        Opd o = new Opd();
        o.setPatient(p);
        o.setStatus(Opd.Status.QUEUED);
        o = opdRepository.save(o);
        o.setCaseId("OPD-" + o.getId());
        return opdRepository.save(o);
    }

    @Test
    void theOwningTenantCanLoadItsOwnOpd() {
        Optional<Opd> found = opdRepository
                .findByIdAndHospitalIdWithPatientAndDoctor(opdOfA.getId(), hospitalA.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getCaseId()).isEqualTo(opdOfA.getCaseId());
    }

    @Test
    void aForeignTenantGetsNothing() {
        Optional<Opd> found = opdRepository
                .findByIdAndHospitalIdWithPatientAndDoctor(opdOfA.getId(), hospitalB.getId());

        assertThat(found).as("a cross-tenant id must be indistinguishable from a missing one")
                .isEmpty();
    }

    @Test
    void aNonexistentOpdAndAForeignOpdAreIndistinguishable() {
        Optional<Opd> foreign = opdRepository
                .findByIdAndHospitalIdWithPatientAndDoctor(opdOfA.getId(), hospitalB.getId());
        Optional<Opd> missing = opdRepository
                .findByIdAndHospitalIdWithPatientAndDoctor(99_999_999L, hospitalB.getId());

        assertThat(foreign).isEqualTo(missing).isEmpty();
    }

    @Test
    void aNullTenantMatchesNothing() {
        // Callers that cannot resolve a hospital must not fall through to an unscoped read.
        assertThat(opdRepository.findByIdAndHospitalIdWithPatientAndDoctor(opdOfA.getId(), null))
                .isEmpty();
    }

    /**
     * The predicate has to survive the LEFT JOIN FETCH. Written as its own test because the WHERE
     * on p.hospitalId is the only thing making that join effectively inner — drop it and every OPD
     * in the system becomes readable by every tenant.
     */
    @Test
    void thePatientJoinIsEffectivelyInner() {
        Opd opdOfB = newOpd(hospitalB);

        assertThat(opdRepository.findByIdAndHospitalIdWithPatientAndDoctor(
                opdOfB.getId(), hospitalA.getId())).isEmpty();
        assertThat(opdRepository.findByIdAndHospitalIdWithPatientAndDoctor(
                opdOfB.getId(), hospitalB.getId())).isPresent();
    }

    /** Eager fetch is why this query exists in this shape; losing it breaks PDF rendering. */
    @Test
    void thePatientIsFetchedEagerlyForUseAfterTheTransaction() {
        Opd found = opdRepository
                .findByIdAndHospitalIdWithPatientAndDoctor(opdOfA.getId(), hospitalA.getId())
                .orElseThrow();

        assertThat(found.getPatient()).isNotNull();
        assertThat(found.getPatient().getName()).isEqualTo("Opd Patient");
    }
}
