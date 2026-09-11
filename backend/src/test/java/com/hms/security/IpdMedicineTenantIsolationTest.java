package com.hms.security;

import com.hms.entity.Bed;
import com.hms.entity.Doctor;
import com.hms.entity.Hospital;
import com.hms.entity.IpdAdmission;
import com.hms.entity.Medicine;
import com.hms.entity.Patient;
import com.hms.entity.User;
import com.hms.entity.Ward;
import com.hms.repository.BedRepository;
import com.hms.repository.DoctorRepository;
import com.hms.repository.HospitalRepository;
import com.hms.repository.IpdAdmissionRepository;
import com.hms.repository.MedicineRepository;
import com.hms.repository.PatientRepository;
import com.hms.repository.UserRepository;
import com.hms.repository.WardRepository;
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
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INV-P0-1: a request-supplied medicineId must never reach another facility's stock.
 *
 * <p>IpdAdmissionService carried this medicine lookup three times (consultation, IPD follow-up,
 * IPD administer). DoctorService's copy always compared the medicine's hospitalId to the caller's;
 * the two IpdAdmissionService copies loaded it by raw id and decremented whatever they found. Both
 * were additionally covered by a TenantScopingArchTest allowlist entry marked reviewed-and-safe,
 * so no test objected.
 *
 * <p>Driven through the real endpoint against a real database: a mocked repository cannot show
 * that the wrong row was loaded, which is precisely how this survived.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class IpdMedicineTenantIsolationTest {

    @Autowired TestRestTemplate rest;
    @Autowired JwtUtil jwtUtil;
    @Autowired HospitalRepository hospitalRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired DoctorRepository doctorRepository;
    @Autowired UserRepository userRepository;
    @Autowired WardRepository wardRepository;
    @Autowired BedRepository bedRepository;
    @Autowired IpdAdmissionRepository ipdAdmissionRepository;
    @Autowired MedicineRepository medicineRepository;

    private static final List<String> MODULES = List.of("OPD", "IPD", "PHARMACY", "BILLING");

    private String tokenA;
    private Long admissionA;
    private Long victimMedicineB;
    private static final int VICTIM_START_STOCK = 100;

    private String uniq() { return Long.toString(System.nanoTime()); }

    private long seedHospital(String slug) {
        Hospital h = new Hospital();
        h.setName("H " + slug); h.setCustomId("HID-" + uniq());
        h.setSubscriptionStatus("ACTIVE"); h.setIsActive(true);
        h.setModules(MODULES); h.setIsSingleDoctor(false);
        return hospitalRepository.save(h).getId();
    }

    private Long seedMedicine(long hid, String name, int stock) {
        Medicine m = new Medicine();
        m.setName(name);
        m.setHospitalId(hid);
        m.setStockQuantity(stock);
        m.setUnitPrice(10.0);
        m.setMinStockLevel(1);
        m.setIsActive(true);
        return medicineRepository.save(m).getId();
    }

    @BeforeEach
    void setUp() {
        long hidA = seedHospital("alpha");
        long hidB = seedHospital("bravo");

        Doctor d = new Doctor();
        d.setName("Dr alpha"); d.setHospitalId(hidA); d.setIsActive(true);
        d.setEmail("doc-" + uniq() + "@example.test"); d.setPublicId("dpub-" + uniq());
        d.setPhone("9800000001"); d.setSpecialization("Gen");
        Long docId = doctorRepository.save(d).getId();

        Patient p = new Patient();
        p.setName("Pat alpha"); p.setHospitalId(hidA); p.setPublicId("ppub-" + uniq());
        p.setGender("MALE"); p.setPhone("9900000001"); p.setIsActive(true);
        p.setDateOfBirth(LocalDate.of(1980, 1, 1));
        Long patId = patientRepository.save(p).getId();

        Ward w = new Ward();
        w.setWardName("W alpha"); w.setHospitalId(hidA);
        w.setBedPrice(new BigDecimal("1500")); w.setTotalBeds(1);
        Long wardId = wardRepository.save(w).getWardId();

        Bed b = new Bed();
        b.setHospitalId(hidA); b.setWardId(wardId); b.setBedCode("BED-" + uniq()); b.setStatus("occupied");
        Long bedId = bedRepository.save(b).getBedId();

        IpdAdmission a = new IpdAdmission();
        a.setIpdNumber("IPD-" + uniq()); a.setPatientId(patId); a.setDoctorId(docId);
        a.setHospitalId(hidA); a.setAdmissionType("ELECTIVE"); a.setStatus("ADMITTED");
        a.setAdmissionDatetime(LocalDateTime.now()); a.setAdmissionConfirmed(true);
        a.setWardId(wardId); a.setBedId(bedId);
        admissionA = ipdAdmissionRepository.save(a).getId();

        // The victim: stock that belongs to hospital B and must be untouchable from A.
        victimMedicineB = seedMedicine(hidB, "Ceftriaxone", VICTIM_START_STOCK);

        User adminA = new User();
        adminA.setEmail("admin-" + uniq() + "@example.test");
        adminA.setPassword("test-password-hash"); adminA.setName("Admin alpha");
        adminA.setRole("HOSPITAL_ADMIN"); adminA.setHospitalId(hidA);
        adminA.setIsActive(true); adminA.setTokenVersion(0);
        adminA = userRepository.save(adminA);
        tokenA = jwtUtil.generateToken(adminA.getId(), adminA.getEmail(), adminA.getRole(), hidA,
                MODULES, null, "HOSPITAL", null, adminA.getTokenVersion());
    }

    private ResponseEntity<String> administer(Long medicineId, int qty) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(tokenA);
        h.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"administeredItems\":[{\"medicineId\":" + medicineId
                + ",\"medicineName\":\"Ceftriaxone\",\"quantity\":" + qty + "}]}";
        return rest.exchange("/hospital/ipd/" + admissionA + "/administer", HttpMethod.POST,
                new HttpEntity<>(body, h), String.class);
    }

    /** The headline breach: hospital A administering against hospital B's medicine id. */
    @Test
    void administeringAnotherHospitalsMedicine_isRefused_andLeavesItsStockUntouched() {
        ResponseEntity<String> res = administer(victimMedicineB, 5);

        assertThat(res.getStatusCode().value())
                .as("a foreign medicine must not be usable; status=%s body=%s",
                        res.getStatusCode(), res.getBody())
                .isNotEqualTo(200);

        Medicine victim = medicineRepository.findById(victimMedicineB).orElseThrow();
        assertThat(victim.getStockQuantity())
                .as("hospital B stock must be byte-for-byte untouched")
                .isEqualTo(VICTIM_START_STOCK);
    }

    /** A foreign id must be indistinguishable from one that does not exist. */
    @Test
    void aForeignMedicineAnswersLikeAMissingOne() {
        int foreign = administer(victimMedicineB, 1).getStatusCode().value();
        int missing = administer(999999999L, 1).getStatusCode().value();

        assertThat(foreign).isEqualTo(missing);
    }
}
