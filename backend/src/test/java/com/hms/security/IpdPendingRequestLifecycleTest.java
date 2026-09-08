package com.hms.security;

import com.hms.entity.Bed;
import com.hms.entity.Doctor;
import com.hms.entity.Hospital;
import com.hms.entity.IpdAdmission;
import com.hms.entity.Opd;
import com.hms.entity.Patient;
import com.hms.entity.User;
import com.hms.entity.Ward;
import com.hms.repository.BedRepository;
import com.hms.repository.DoctorRepository;
import com.hms.repository.HospitalRepository;
import com.hms.repository.IpdAdmissionRepository;
import com.hms.repository.OpdRepository;
import com.hms.repository.PatientRepository;
import com.hms.repository.UserRepository;
import com.hms.repository.WardRepository;
import com.hms.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IPD-P0: the reported staging symptom -- doctor recommends IPD, admission succeeds, the patient
 * is still shown as "Admission Pending".
 *
 * Static analysis found admitFromOpd correctly writes opd.status = IN_IPD (IpdAdmissionService,
 * around line 202), and both the old client-side filter and the new server-side query use the
 * identical predicate (ipdAdmitRecommended AND status != IN_IPD). This test proves that predicate
 * against a REAL persisted admission, driven through the actual /hospital/ipd/admit endpoint and
 * the actual GET /hospital/opd/ipd-requests/{count} endpoints -- not a mock -- so it would catch a
 * regression in either query, a missed status write, or a broken index/cast, none of which a
 * mocked-repository test can see.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class IpdPendingRequestLifecycleTest {

    @Autowired TestRestTemplate rest;
    @Autowired JwtUtil jwtUtil;
    @Autowired HospitalRepository hospitalRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired DoctorRepository doctorRepository;
    @Autowired UserRepository userRepository;
    @Autowired WardRepository wardRepository;
    @Autowired BedRepository bedRepository;
    @Autowired OpdRepository opdRepository;
    @Autowired IpdAdmissionRepository ipdAdmissionRepository;

    private static final List<String> MODULES = List.of("OPD", "IPD", "PHARMACY", "BILLING");

    private String token;
    private Long opdId, wardId, bedId;

    private String uniq() { return Long.toString(System.nanoTime()); }

    @BeforeEach
    void setUp() {
        // Name-bearing fields (Doctor/Patient/Hospital) are letters-only; uniqueness for the run
        // lives in the id/email/code fields instead, exactly as AdmissionBedWardIsolationTest does.
        String slug = "pending";

        Hospital h = new Hospital();
        h.setName("H " + slug); h.setCustomId("HID-" + uniq());
        h.setSubscriptionStatus("ACTIVE"); h.setIsActive(true);
        h.setModules(MODULES); h.setIsSingleDoctor(false);
        long hid = hospitalRepository.save(h).getId();

        Doctor d = new Doctor();
        d.setName("Dr " + slug); d.setHospitalId(hid); d.setIsActive(true);
        d.setEmail("doc-" + uniq() + "@example.test"); d.setPublicId("dpub-" + uniq());
        d.setPhone("9800000001"); d.setSpecialization("Gen");
        doctorRepository.save(d);

        Patient p = new Patient();
        p.setName("Pat " + slug); p.setHospitalId(hid); p.setPublicId("ppub-" + uniq());
        p.setGender("MALE"); p.setPhone("9900000001"); p.setIsActive(true);
        p.setDateOfBirth(LocalDate.of(1980, 1, 1));
        patientRepository.save(p);

        Ward w = new Ward();
        w.setWardName("W " + slug); w.setHospitalId(hid);
        w.setBedPrice(new BigDecimal("1500")); w.setTotalBeds(1);
        wardId = wardRepository.save(w).getWardId();

        Bed b = new Bed();
        b.setHospitalId(hid); b.setWardId(wardId); b.setBedCode("BED-" + uniq()); b.setStatus("available");
        bedId = bedRepository.save(b).getBedId();

        // The doctor's recommendation: an OPD case flagged ipdAdmitRecommended, not yet admitted.
        Opd o = new Opd();
        o.setCaseId("OPD-" + uniq()); o.setIpdAdmitRecommended(true);
        o.setPatient(p); o.setDoctor(d);
        opdId = opdRepository.save(o).getId();

        User admin = new User();
        admin.setEmail("admin-" + uniq() + "@example.test");
        admin.setPassword("test-password-hash");
        admin.setName("Admin");
        admin.setRole("HOSPITAL_ADMIN");
        admin.setHospitalId(hid);
        admin.setIsActive(true);
        admin.setTokenVersion(0);
        admin = userRepository.save(admin);
        token = jwtUtil.generateToken(admin.getId(), admin.getEmail(), admin.getRole(), hid,
                MODULES, null, "HOSPITAL", null, admin.getTokenVersion());
    }

    private HttpHeaders authHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private long pendingCount() {
        ResponseEntity<Map> res = rest.exchange("/hospital/opd/ipd-requests/count", HttpMethod.GET,
                new HttpEntity<>(authHeaders()), Map.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        return ((Number) res.getBody().get("count")).longValue();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> pendingList() {
        ResponseEntity<Map> res = rest.exchange("/hospital/opd/ipd-requests?page=0&size=10", HttpMethod.GET,
                new HttpEntity<>(authHeaders()), Map.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        return (List<Map<String, Object>>) res.getBody().get("content");
    }

    private ResponseEntity<String> admit() {
        String body = "{\"opdId\":" + opdId + ",\"wardId\":" + wardId + ",\"bedId\":" + bedId
                + ",\"admissionType\":\"ELECTIVE\",\"primaryDiagnosis\":\"obs\"}";
        return rest.exchange("/hospital/ipd/admit", HttpMethod.POST, new HttpEntity<>(body, authHeaders()),
                String.class);
    }

    /**
     * The full lifecycle the release test asks for: recommend -> appears pending -> admit ->
     * no longer pending -> appears as an active IPD admission. Every assertion reads real,
     * committed database state through the real endpoints.
     */
    @Test
    void recommendedOpd_isPending_thenAdmitted_thenNoLongerPending() {
        // Doctor recommends: the OPD from setUp is already ipdAdmitRecommended=true, unadmitted.
        assertThat(pendingCount()).as("recommended and not yet admitted -> pending").isEqualTo(1);
        assertThat(pendingList())
                .as("appears in the pending list, keyed by its own OPD id")
                .anyMatch(row -> ((Number) row.get("id")).longValue() == opdId);

        // Reception admits.
        ResponseEntity<String> admitResponse = admit();
        assertThat(admitResponse.getStatusCode().value()).as("admission succeeds").isEqualTo(200);

        // The exact reported symptom: pending count/list must not still contain it.
        assertThat(pendingCount()).as("no longer pending after a successful admission").isEqualTo(0);
        assertThat(pendingList())
                .as("removed from the pending list")
                .noneMatch(row -> ((Number) row.get("id")).longValue() == opdId);

        // And it now exists as an active IPD admission -- ownership moved, it did not vanish.
        assertThat(opdRepository.findById(opdId).orElseThrow().getStatus()).isEqualTo(Opd.Status.IN_IPD);
        IpdAdmission created = ipdAdmissionRepository.findAll().stream()
                .max(java.util.Comparator.comparing(IpdAdmission::getId)).orElseThrow();
        assertThat(created.getStatus()).isEqualTo("ADMITTED");
        assertThat(created.getWardId()).isEqualTo(wardId);
        assertThat(created.getBedId()).isEqualTo(bedId);
    }

    /** A hospital with no recommendation at all sees an honest zero, not an error. */
    @Test
    void aHospitalWithNoRecommendation_seesZero() {
        Opd notRecommended = opdRepository.findById(opdId).orElseThrow();
        notRecommended.setIpdAdmitRecommended(false);
        opdRepository.save(notRecommended);

        assertThat(pendingCount()).isEqualTo(0);
        assertThat(pendingList()).isEmpty();
    }
}
