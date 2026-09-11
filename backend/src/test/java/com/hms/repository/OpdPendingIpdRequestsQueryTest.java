package com.hms.repository;

import com.hms.entity.Opd;
import com.hms.entity.Patient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0-3: the pending-IPD-request query.
 *
 * <p>Two properties matter and neither held when this was a client-side filter over one 1000-row
 * page: the count must be complete however many OPDs the tenant has, and it must never include
 * another tenant's rows. Opd has no hospital_id, so tenancy is proven by joining the owning
 * patient — that join is what these tests exercise.
 */
@SpringBootTest
@ActiveProfiles("test")
class OpdPendingIpdRequestsQueryTest {

    private static final Long HOSPITAL = 4001L;
    private static final Long OTHER_HOSPITAL = 4002L;

    @Autowired OpdRepository opdRepository;
    @Autowired PatientRepository patientRepository;

    private Patient hospitalPatient;
    private Patient otherHospitalPatient;

    @BeforeEach
    void setUp() {
        opdRepository.deleteAll();
        patientRepository.deleteAll();
        hospitalPatient = savePatient(HOSPITAL, "Ours");
        otherHospitalPatient = savePatient(OTHER_HOSPITAL, "Theirs");
    }

    private Patient savePatient(Long hospitalId, String name) {
        Patient p = new Patient();
        p.setHospitalId(hospitalId);
        p.setName(name);
        p.setGender("MALE");
        p.setPhone("9000000000");
        return patientRepository.save(p);
    }

    private void saveOpd(Patient patient, boolean recommended, Opd.Status status) {
        Opd o = new Opd();
        o.setPatient(patient);
        o.setIpdAdmitRecommended(recommended);
        o.setStatus(status);
        o.setCreatedAt(LocalDateTime.now());
        opdRepository.save(o);
    }

    @Test
    void countsOnlyRecommendedAdmissionsNotYetConverted() {
        saveOpd(hospitalPatient, true, Opd.Status.CONSULTED);   // pending
        saveOpd(hospitalPatient, true, Opd.Status.COMPLETED);   // pending
        saveOpd(hospitalPatient, true, Opd.Status.IN_IPD);      // already admitted
        saveOpd(hospitalPatient, false, Opd.Status.CONSULTED);  // never recommended

        assertThat(opdRepository.countPendingIpdRequests(HOSPITAL, Opd.Status.IN_IPD)).isEqualTo(2);
    }

    @Test
    void excludesOtherTenants() {
        saveOpd(hospitalPatient, true, Opd.Status.CONSULTED);
        saveOpd(otherHospitalPatient, true, Opd.Status.CONSULTED);
        saveOpd(otherHospitalPatient, true, Opd.Status.QUEUED);

        assertThat(opdRepository.countPendingIpdRequests(HOSPITAL, Opd.Status.IN_IPD)).isEqualTo(1);
        assertThat(opdRepository.countPendingIpdRequests(OTHER_HOSPITAL, Opd.Status.IN_IPD)).isEqualTo(2);
    }

    /**
     * The regression this endpoint exists for: the dashboard fetched page 0 of size 1000 and
     * filtered it in the browser, so recommendations past that page were invisible.
     */
    @Test
    void countsRecommendationsPastTheOldThousandRowClientPage() {
        for (int i = 0; i < 1005; i++) {
            saveOpd(hospitalPatient, false, Opd.Status.COMPLETED);
        }
        saveOpd(hospitalPatient, true, Opd.Status.CONSULTED);
        saveOpd(hospitalPatient, true, Opd.Status.CONSULTED);
        saveOpd(hospitalPatient, true, Opd.Status.CONSULTED);

        assertThat(opdRepository.countPendingIpdRequests(HOSPITAL, Opd.Status.IN_IPD)).isEqualTo(3);
        assertThat(opdRepository.findPendingIpdRequests(HOSPITAL, Opd.Status.IN_IPD, PageRequest.of(0, 10))
                .getTotalElements()).isEqualTo(3);
    }

    @Test
    void listReturnsOnlyThisTenantsPendingRequests() {
        saveOpd(hospitalPatient, true, Opd.Status.CONSULTED);
        saveOpd(otherHospitalPatient, true, Opd.Status.CONSULTED);

        var page = opdRepository.findPendingIpdRequests(HOSPITAL, Opd.Status.IN_IPD, PageRequest.of(0, 10));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getPatient().getHospitalId()).isEqualTo(HOSPITAL);
    }
}
