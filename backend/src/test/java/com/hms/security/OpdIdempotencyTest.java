package com.hms.security;

import com.hms.entity.Doctor;
import com.hms.entity.Hospital;
import com.hms.entity.Opd;
import com.hms.entity.Patient;
import com.hms.entity.User;
import com.hms.repository.BillingRepository;
import com.hms.repository.DoctorRepository;
import com.hms.repository.HospitalRepository;
import com.hms.repository.OpdRepository;
import com.hms.repository.PatientRepository;
import com.hms.repository.QueueEntryRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One logical registration produces one OPD.
 *
 * <p>Registering a patient inserts the OPD, a queue entry and — under "bill before OPD" — a PAID
 * bill. None of that is repeatable. A double-clicked button or a retried request therefore
 * charged the patient twice and queued them twice, and nothing afterwards could tell the
 * duplicate from a genuine second visit.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OpdIdempotencyTest {

    private static final AtomicLong SEQ = new AtomicLong();
    private static final List<String> MODULES = List.of("OPD", "IPD", "BILLING");

    @Autowired TestRestTemplate rest;
    @Autowired JwtUtil jwtUtil;
    @Autowired HospitalRepository hospitals;
    @Autowired UserRepository users;
    @Autowired PatientRepository patients;
    @Autowired DoctorRepository doctors;
    @Autowired OpdRepository opds;
    @Autowired QueueEntryRepository queueEntries;
    @Autowired BillingRepository billings;

    private static long uniq() { return System.nanoTime() + SEQ.incrementAndGet(); }

    private Hospital hospital;
    private Patient patient;
    private Doctor doctor;
    private String token;

    private Hospital tenant(String label) {
        Hospital h = new Hospital();
        h.setName("Idem " + label);
        h.setCustomId("IDEM-" + uniq());
        h.setIsActive(true);
        h.setSubscriptionStatus("ACTIVE");
        h.setModules(MODULES);
        h.setIsSingleDoctor(false);
        h.setType(com.hms.entity.HospitalType.HOSPITAL);
        return hospitals.save(h);
    }

    private String tokenFor(Hospital h) {
        User u = new User();
        u.setEmail("rec." + uniq() + "@idem.test");
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

    private Patient patientIn(Hospital h) {
        Patient p = new Patient();
        p.setHospitalId(h.getId());
        p.setName("Pat Idem");
        p.setPublicId("ppub-" + uniq());
        p.setGender("MALE");
        p.setPhone("9900000050");
        p.setIsActive(true);
        p.setDateOfBirth(LocalDate.of(1980, 1, 1));
        return patients.save(p);
    }

    private Doctor doctorIn(Hospital h) {
        Doctor d = new Doctor();
        d.setHospitalId(h.getId());
        d.setName("Dr Idem");
        d.setEmail("doc." + uniq() + "@idem.test");
        d.setPublicId("dpub-" + uniq());
        d.setPhone("9800000050");
        d.setSpecialization("Gen");
        d.setIsActive(true);
        return doctors.save(d);
    }

    @BeforeEach
    void setUp() {
        hospital = tenant("A");
        token = tokenFor(hospital);
        patient = patientIn(hospital);
        doctor = doctorIn(hospital);
    }

    private HttpHeaders headers(String t) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(t);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private String body(String key, Patient p, Doctor d) {
        return "{\"patientId\":\"" + p.getPublicId() + "\""
                + ",\"doctorId\":\"" + d.getPublicId() + "\""
                + ",\"visitType\":\"NEW\""
                + (key == null ? "" : ",\"idempotencyKey\":\"" + key + "\"")
                + "}";
    }

    private ResponseEntity<String> register(String t, String key, Patient p, Doctor d) {
        return rest.exchange("/hospital/opd", HttpMethod.POST,
                new HttpEntity<>(body(key, p, d), headers(t)), String.class);
    }

    private long opdCountFor(Patient p) {
        return opds.findAll().stream()
                .filter(o -> o.getPatient() != null && p.getId().equals(o.getPatient().getId()))
                .count();
    }

    /** The double-click: the same key twice must register once. */
    @Test
    void aReplayedSubmissionRegistersOnce() {
        String key = "opd-" + uniq();

        ResponseEntity<String> first = register(token, key, patient, doctor);
        assertThat(first.getStatusCode().value()).as("%s", first.getBody()).isEqualTo(200);

        ResponseEntity<String> replay = register(token, key, patient, doctor);
        assertThat(replay.getStatusCode().value()).as("a replay is answered, not refused").isEqualTo(200);

        assertThat(opdCountFor(patient)).as("one registration, not two").isEqualTo(1);
    }

    /** And the replay hands back the SAME registration, not a fresh one. */
    @Test
    void aReplayReturnsTheOriginalRegistration() {
        String key = "opd-" + uniq();
        register(token, key, patient, doctor);
        Long originalId = opds.findAll().stream()
                .filter(o -> o.getPatient() != null && patient.getId().equals(o.getPatient().getId()))
                .findFirst().orElseThrow().getId();

        ResponseEntity<String> replay = register(token, key, patient, doctor);
        assertThat(replay.getBody()).contains("\"id\":" + originalId);
    }

    /** The financial consequence: one registration, one queue entry, one bill. */
    @Test
    void aReplayDoesNotDuplicateTheQueueEntryOrTheBill() {
        String key = "opd-" + uniq();
        register(token, key, patient, doctor);
        long queuedAfterFirst = queueEntries.count();
        long billsAfterFirst = billings.findAll().stream()
                .filter(b -> hospital.getId().equals(b.getHospitalId())).count();

        register(token, key, patient, doctor);

        assertThat(queueEntries.count()).as("no second queue entry").isEqualTo(queuedAfterFirst);
        assertThat(billings.findAll().stream()
                .filter(b -> hospital.getId().equals(b.getHospitalId())).count())
                .as("the patient is not charged twice").isEqualTo(billsAfterFirst);
    }

    /**
     * True concurrency: six identical submissions at once. Exactly one may register; the rest are
     * either answered with that registration or told they are a duplicate. None may create a
     * second one.
     */
    @Test
    void concurrentIdenticalSubmissionsRegisterOnce() throws Exception {
        String key = "opd-" + uniq();
        int threads = 6;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<Integer>> jobs = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            jobs.add(() -> register(token, key, patient, doctor).getStatusCode().value());
        }
        List<Future<Integer>> results = pool.invokeAll(jobs);
        pool.shutdown();
        pool.awaitTermination(60, TimeUnit.SECONDS);

        int created = 0;
        int refusedAsDuplicate = 0;
        for (Future<Integer> f : results) {
            int code = f.get();
            if (code == 200) created++;
            else if (code == 409) refusedAsDuplicate++;
        }

        assertThat(created + refusedAsDuplicate)
                .as("every caller gets a definite answer, none an unexpected error").isEqualTo(threads);
        assertThat(opdCountFor(patient)).as("exactly one registration exists").isEqualTo(1);
    }

    /** Without a key nothing changes: two genuine visits are still two registrations. */
    @Test
    void withoutAKeyBehaviourIsUnchanged() {
        assertThat(register(token, null, patient, doctor).getStatusCode().value()).isEqualTo(200);
        assertThat(register(token, null, patient, doctor).getStatusCode().value()).isEqualTo(200);

        assertThat(opdCountFor(patient))
                .as("a caller that sends no key keeps the old behaviour").isEqualTo(2);
    }

    /** A different key is a different visit, not a replay. */
    @Test
    void aDifferentKeyRegistersAgain() {
        register(token, "opd-" + uniq(), patient, doctor);
        register(token, "opd-" + uniq(), patient, doctor);
        assertThat(opdCountFor(patient)).isEqualTo(2);
    }

    /**
     * The key is claimed per facility. Two hospitals using the same key — likely, since clients
     * generate them independently — must not suppress each other's registrations.
     */
    @Test
    void theSameKeyInTwoFacilitiesIsTwoRegistrations() {
        String sharedKey = "shared-" + uniq();

        Hospital other = tenant("B");
        String otherToken = tokenFor(other);
        Patient otherPatient = patientIn(other);
        Doctor otherDoctor = doctorIn(other);

        assertThat(register(token, sharedKey, patient, doctor).getStatusCode().value()).isEqualTo(200);
        assertThat(register(otherToken, sharedKey, otherPatient, otherDoctor).getStatusCode().value())
                .as("one facility's key must not block another's registration").isEqualTo(200);

        assertThat(opdCountFor(patient)).isEqualTo(1);
        assertThat(opdCountFor(otherPatient)).isEqualTo(1);
    }

    /**
     * A failed registration releases its key, so the user can fix the problem and retry with the
     * same key rather than being told forever that they are a duplicate.
     */
    @Test
    void aFailedRegistrationReleasesItsKeyForRetry() {
        String key = "opd-" + uniq();

        Patient foreign = patientIn(tenant("C"));
        ResponseEntity<String> failed = register(token, key, foreign, doctor);
        assertThat(failed.getStatusCode().is2xxSuccessful())
                .as("another facility's patient cannot be registered here").isFalse();

        ResponseEntity<String> retry = register(token, key, patient, doctor);
        assertThat(retry.getStatusCode().value())
                .as("the same key must work once the mistake is corrected").isEqualTo(200);
        assertThat(opdCountFor(patient)).isEqualTo(1);
    }
}
