package com.hms.security;

import com.hms.entity.Bed;
import com.hms.entity.BedStatus;
import com.hms.entity.Doctor;
import com.hms.entity.Hospital;
import com.hms.entity.IpdAdmission;
import com.hms.entity.Patient;
import com.hms.entity.User;
import com.hms.entity.Ward;
import com.hms.repository.BedRepository;
import com.hms.repository.DoctorRepository;
import com.hms.repository.HospitalRepository;
import com.hms.repository.IpdAdmissionRepository;
import com.hms.repository.PatientRepository;
import com.hms.repository.UserRepository;
import com.hms.repository.WardRepository;
import com.hms.service.hospital.icu.CareUnitRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ICU Phase 2 — the acceptance gate that a cross-tenant ICU bed read is refused.
 *
 * <p>Ward and bed ids are sequential and trivially enumerable, and the ICU board returns patient
 * identity, so a leak here is worse than a leak from a bed-status endpoint. This drives the real
 * endpoint against a real database with two seeded tenants, the way
 * {@code AdmissionBedWardIsolationTest} does — the defect this guards against is invisible to a
 * mocked repository.
 *
 * <p>The board takes no id from the client, so the shape of the attack is different from an IDOR:
 * the token itself decides the tenant. What must hold is that hospital B's token, whatever it
 * does, sees ONLY hospital B — never a bed, patient, or count belonging to A.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class IcuBoardTenancyTest {

    @Autowired TestRestTemplate rest;
    @Autowired JwtUtil jwtUtil;
    @Autowired HospitalRepository hospitalRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired DoctorRepository doctorRepository;
    @Autowired WardRepository wardRepository;
    @Autowired BedRepository bedRepository;
    @Autowired IpdAdmissionRepository ipdAdmissionRepository;
    @Autowired UserRepository userRepository;
    @Autowired com.hms.repository.IcuStayRepository icuStayRepository;

    private static final List<String> MODULES = List.of("OPD", "IPD", "BILLING", "ICU");
    private static final List<String> NO_ICU = List.of("OPD", "IPD", "BILLING");

    private String tokenA;
    private String tokenB;
    private String tokenBNoIcu;
    private Long aBedId;
    private Long hospitalIdB;
    private String aPatientName;
    private String bPatientName;

    private String uniq() { return Long.toString(System.nanoTime()); }

    /**
     * A real, active User row. JwtAuthenticationFilter revalidates every request against
     * userRepository.findActiveTokenVersion(userId), so a token minted for an id that has no
     * user row is rejected with 401 before it ever reaches the controller.
     */
    private User seedUser(long hospitalId, String slug) {
        User u = new User();
        u.setEmail("admin-" + slug + "-" + uniq() + "@x.test");
        u.setPassword("{noop}x");
        u.setName("Admin " + slug);
        u.setRole("HOSPITAL_ADMIN");
        u.setHospitalId(hospitalId);
        u.setIsActive(true);
        return userRepository.save(u);
    }

    private String tokenFor(User u, long hospitalId, List<String> modules) {
        return jwtUtil.generateToken(u.getId(), u.getEmail(), u.getRole(), hospitalId,
                modules, null, "HOSPITAL", null, u.getTokenVersion());
    }

    private long seedHospital(String slug) {
        return seedHospital(slug, MODULES);
    }

    /**
     * ModuleAccessAspect resolves the enabled modules from the HOSPITAL ROW, not from the
     * token's claim, so a plan without ICU has to be modelled on the tenant — a token that
     * simply omits the claim would still be let through.
     */
    private long seedHospital(String slug, List<String> modules) {
        Hospital h = new Hospital();
        h.setName("H-" + slug);
        h.setCustomId("HID-" + uniq());
        h.setSubscriptionStatus("ACTIVE");
        h.setIsActive(true);
        h.setModules(modules);
        h.setIsSingleDoctor(false);
        return hospitalRepository.save(h).getId();
    }

    /** One tenant with an ICU ward, an occupied ICU bed and the patient in it. */
    private long[] seedIcu(long hid, String slug, String patientName) {
        Doctor d = new Doctor();
        d.setName("Dr " + slug);
        d.setHospitalId(hid);
        d.setIsActive(true);
        d.setEmail("doc-" + uniq() + "@" + slug + ".test");
        d.setPublicId("dpub-" + uniq());
        d.setPhone("9800000001");
        d.setSpecialization("Critical Care");
        long did = doctorRepository.save(d).getId();

        Patient p = new Patient();
        p.setName(patientName);
        p.setHospitalId(hid);
        p.setPublicId("ppub-" + uniq());
        p.setGender("MALE");
        p.setPhone("9900000001");
        p.setIsActive(true);
        p.setDateOfBirth(LocalDate.of(1980, 1, 1));
        long pid = patientRepository.save(p).getId();

        Ward w = new Ward();
        w.setWardName("ICU-" + slug);
        w.setHospitalId(hid);
        w.setBedPrice(new BigDecimal("5000"));
        w.setTotalBeds(1);
        w.setUnitType(CareUnitRegistry.ICU);
        long wid = wardRepository.save(w).getWardId();

        Bed b = new Bed();
        b.setHospitalId(hid);
        b.setWardId(wid);
        b.setBedCode("ICUBED-" + uniq());
        b.setStatus(BedStatus.OCCUPIED);
        long bid = bedRepository.save(b).getBedId();

        IpdAdmission a = new IpdAdmission();
        // NOT "IPD-<nanotime>": findMaxIpdSequence() reads MAX(substring) over every row
        // matching 'IPD-%' and returns an Integer, so a 19-digit fixture number overflows
        // and poisons the next real admission for the whole database with a 500.
        a.setIpdNumber("FIXT-" + uniq());
        a.setHospitalId(hid);
        a.setPatientId(pid);
        a.setDoctorId(did);
        a.setWardId(wid);
        a.setBedId(bid);
        a.setStatus("ADMITTED");
        a.setAdmissionType("EMERGENCY");
        a.setAdmissionDatetime(LocalDateTime.now());
        a.setPrimaryDiagnosis("Septic shock - " + slug);
        a.setAdmissionConfirmed(true);
        ipdAdmissionRepository.save(a);

        b.setCurrentIpdAdmissionId(a.getId());
        bedRepository.save(b);

        // ICU Phase 3: the stay that the board must now surface -- and must never surface to
        // the other tenant.
        com.hms.entity.IcuStay stay = new com.hms.entity.IcuStay();
        stay.setHospitalId(hid);
        stay.setIpdAdmissionId(a.getId());
        stay.setPatientId(pid);
        stay.setWardId(wid);
        stay.setStatus(com.hms.entity.IcuStay.ACTIVE);
        stay.setSource(com.hms.entity.IcuStay.SRC_WARD);
        stay.setAdmittedAt(LocalDateTime.now());
        stay.setAdmissionReason("Reason-" + slug);
        stay.setActiveMarker(a.getId());
        icuStayRepository.save(stay);

        return new long[] { wid, bid };
    }

    @BeforeEach
    void setUp() {
        long hidA = seedHospital("alpha");
        long hidB = seedHospital("bravo");

        aPatientName = "AlphaPatient" + uniq();
        bPatientName = "BravoPatient" + uniq();

        long[] a = seedIcu(hidA, "alpha", aPatientName);
        aBedId = a[1];
        seedIcu(hidB, "bravo", bPatientName);

        hospitalIdB = hidB;
        tokenA = tokenFor(seedUser(hidA, "alpha"), hidA, MODULES);
        tokenB = tokenFor(seedUser(hidB, "bravo"), hidB, MODULES);

        // A third tenant whose PLAN has no ICU.
        long hidNoIcu = seedHospital("charlie", NO_ICU);
        tokenBNoIcu = tokenFor(seedUser(hidNoIcu, "charlie"), hidNoIcu, NO_ICU);
    }

    private ResponseEntity<String> get(String path, String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(h), String.class);
    }

    @Test
    void tenantSeesOnlyItsOwnIcuBedsAndPatients() {
        ResponseEntity<String> b = get("/hospital/icu/board", tokenB);

        assertThat(b.getStatusCode().value()).isEqualTo(200);
        assertThat(b.getBody()).contains(bPatientName);
        // The core assertion: nothing belonging to hospital A crosses over.
        assertThat(b.getBody()).doesNotContain(aPatientName);
        assertThat(b.getBody()).doesNotContain("Septic shock - alpha");
        assertThat(b.getBody()).doesNotContain("ICU-alpha");
        assertThat(b.getBody()).doesNotContain("\"bedId\":" + aBedId + ",");
    }

    @Test
    void countsAreTenantScoped_neitherTenantSeesTheOthersOccupancy() {
        // Both tenants have exactly one ICU bed, occupied. If the read leaked, either side
        // would report two.
        assertThat(get("/hospital/icu/board", tokenA).getBody())
                .contains("\"totalBeds\":1").contains("\"occupied\":1");
        assertThat(get("/hospital/icu/board", tokenB).getBody())
                .contains("\"totalBeds\":1").contains("\"occupied\":1");
    }

    @Test
    void theBoardCarriesTheIcuStay_andNeverTheOtherTenantsStay() {
        String body = get("/hospital/icu/board", tokenB).getBody();

        assertThat(body).as("the stay slot is populated, not null").contains("\"icuStay\":{");
        assertThat(body).contains("Reason-bravo");
        assertThat(body).doesNotContain("Reason-alpha");
    }

    @Test
    void summaryEndpointIsTenantScopedToo() {
        ResponseEntity<String> r = get("/hospital/icu/board/units", tokenB);

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).doesNotContain("ICU-alpha");
        assertThat(r.getBody()).contains("ICU-bravo");
    }

    @Test
    void withoutTheIcuModuleOnThePlan_theBoardIsRefused() {
        assertThat(get("/hospital/icu/board", tokenBNoIcu).getStatusCode().value())
                .isIn(401, 403);
    }

    @Test
    void withoutAToken_theBoardIsRefused() {
        assertThat(rest.getForEntity("/hospital/icu/board", String.class).getStatusCode().value())
                .isIn(401, 403);
    }

    @Test
    void icuIsNotReachableUnderTheClinicOrPharmacyAliases() {
        // ICU is hospital-only. These paths must not exist at all.
        assertThat(get("/clinic/icu/board", tokenB).getStatusCode().value()).isIn(401, 403, 404);
        assertThat(get("/pharmacy/icu/board", tokenB).getStatusCode().value()).isIn(401, 403, 404);
    }

    @Test
    void aGeneralWardNeverAppearsOnTheBoard() {
        Ward general = new Ward();
        general.setWardName("GeneralWardBravo");
        general.setHospitalId(hospitalIdB);
        general.setBedPrice(new BigDecimal("500"));
        general.setTotalBeds(1);
        general.setUnitType(CareUnitRegistry.GENERAL);
        long wid = wardRepository.save(general).getWardId();

        Bed b = new Bed();
        b.setHospitalId(general.getHospitalId());
        b.setWardId(wid);
        b.setBedCode("GENBED-" + uniq());
        b.setStatus(BedStatus.AVAILABLE);
        bedRepository.save(b);

        String body = get("/hospital/icu/board", tokenB).getBody();

        assertThat(body).doesNotContain("GeneralWardBravo");
        assertThat(body).contains("\"totalBeds\":1"); // still just the one ICU bed
    }
}
