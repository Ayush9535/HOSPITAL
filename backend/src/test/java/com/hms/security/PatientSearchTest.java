package com.hms.security;

import com.hms.entity.Hospital;
import com.hms.entity.Patient;
import com.hms.entity.User;
import com.hms.repository.HospitalRepository;
import com.hms.repository.PatientRepository;
import com.hms.repository.UserRepository;
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

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reception's patient search is a read. It must answer, not fail.
 *
 * <p>The search box is the first thing reception touches for every single visit, and it was
 * answering 409 CONFLICT — a write-conflict status on a plain lookup. A read has no conflict
 * semantics to report, so whatever the cause, that status was wrong and the screen had nothing
 * sensible to show.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PatientSearchTest {

    private static final AtomicLong SEQ = new AtomicLong();
    private static final List<String> MODULES = List.of("OPD", "IPD", "BILLING");

    @Autowired TestRestTemplate rest;
    @Autowired JwtUtil jwtUtil;
    @Autowired HospitalRepository hospitals;
    @Autowired UserRepository users;
    @Autowired PatientRepository patients;

    private static long uniq() { return System.nanoTime() + SEQ.incrementAndGet(); }

    private Hospital hospital;
    private String token;
    private String phoneA;

    private Hospital tenant(String label) {
        Hospital h = new Hospital();
        h.setName("Search " + label);
        h.setCustomId("SRCH-" + uniq());
        h.setIsActive(true);
        h.setSubscriptionStatus("ACTIVE");
        h.setModules(MODULES);
        h.setIsSingleDoctor(false);
        h.setType(com.hms.entity.HospitalType.HOSPITAL);
        return hospitals.save(h);
    }

    private String tokenFor(Hospital h) {
        User u = new User();
        u.setEmail("rec." + uniq() + "@search.test");
        u.setPassword("{noop}fixture");
        u.setName("Reception");
        u.setRole("RECEPTIONIST");
        u.setHospitalId(h.getId());
        u.setIsActive(true);
        u.setTokenVersion(0);
        u = users.save(u);
        return jwtUtil.generateToken(u.getId(), u.getEmail(), "RECEPTIONIST", h.getId(),
                MODULES, null, "HOSPITAL", null, 0);
    }

    private Patient patientIn(Hospital h, String name, String phone) {
        Patient p = new Patient();
        p.setHospitalId(h.getId());
        p.setName(name);
        p.setPublicId("ppub-" + uniq());
        p.setGender("MALE");
        p.setPhone(phone);
        p.setIsActive(true);
        p.setDateOfBirth(LocalDate.of(1980, 1, 1));
        return patients.save(p);
    }

    @BeforeEach
    void setUp() {
        hospital = tenant("A");
        token = tokenFor(hospital);
        phoneA = "9" + String.format("%09d", Math.floorMod(uniq(), 1_000_000_000L));
        patientIn(hospital, "Anita Sharma", phoneA);
        patientIn(hospital, "Anil Sharma", "9" + String.format("%09d", Math.floorMod(uniq(), 1_000_000_000L)));
    }

    private ResponseEntity<String> search(String token, String term) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("/hospital/patients?search=" + term, HttpMethod.GET,
                new HttpEntity<>(h), String.class);
    }

    /** The reported defect, stated as the rule it broke. */
    private void answers(ResponseEntity<String> res, String what) {
        assertThat(res.getStatusCode().value())
                .as("%s must be answered, not refused as a conflict -> %s", what, res.getBody())
                .isEqualTo(200);
    }

    @Test
    void exactPhoneIsFound() {
        ResponseEntity<String> res = search(token, phoneA);
        answers(res, "an exact phone search");
        assertThat(res.getBody()).contains("Anita Sharma");
    }

    @Test
    void partialPhoneIsFound() {
        ResponseEntity<String> res = search(token, phoneA.substring(3));
        answers(res, "a partial phone search");
        assertThat(res.getBody()).contains("Anita Sharma");
    }

    @Test
    void fullNameIsFound() {
        ResponseEntity<String> res = search(token, "Anita");
        answers(res, "a name search");
        assertThat(res.getBody()).contains("Anita Sharma");
    }

    @Test
    void partialNameIsFound() {
        ResponseEntity<String> res = search(token, "Ani");
        answers(res, "a partial name search");
        assertThat(res.getBody()).contains("Anita Sharma");
    }

    /** Several hits is the normal case for a surname, not an ambiguity to fail on. */
    @Test
    void severalMatchesAreAllReturned() {
        ResponseEntity<String> res = search(token, "Sharma");
        answers(res, "a search matching several patients");
        assertThat(res.getBody()).contains("Anita Sharma").contains("Anil Sharma");
    }

    /** Nothing found is an empty list, never an error. */
    @Test
    void noMatchesIsAnEmptyAnswer() {
        ResponseEntity<String> res = search(token, "Zzzznobody");
        answers(res, "a search with no matches");
        assertThat(res.getBody()).doesNotContain("Sharma");
    }

    /** Searching must not become a way to read another facility's patients. */
    @Test
    void anotherFacilitysPatientsAreNeverReturned() {
        Hospital other = tenant("B");
        String otherPhone = "9" + String.format("%09d", Math.floorMod(uniq(), 1_000_000_000L));
        patientIn(other, "Foreign Patient", otherPhone);

        ResponseEntity<String> res = search(token, "Foreign");
        answers(res, "a search matching only another facility's patient");
        assertThat(res.getBody())
                .as("a facility must never see another facility's patients")
                .doesNotContain("Foreign Patient");

        assertThat(search(token, otherPhone).getBody()).doesNotContain("Foreign Patient");
    }

    /** The same surname in two facilities must return only the caller's own. */
    @Test
    void identicalNamesInTwoFacilitiesStaySeparate() {
        Hospital other = tenant("B");
        patientIn(other, "Anita Sharma", "9" + String.format("%09d", Math.floorMod(uniq(), 1_000_000_000L)));
        String otherToken = tokenFor(other);

        assertThat(search(token, "Anita").getBody()).contains("Anita Sharma");
        ResponseEntity<String> theirs = search(otherToken, "Anita");
        answers(theirs, "the other facility's own search");
        assertThat(theirs.getBody()).contains("Anita Sharma");
    }
}
