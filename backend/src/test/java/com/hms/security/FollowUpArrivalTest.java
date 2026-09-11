package com.hms.security;

import com.hms.entity.Doctor;
import com.hms.entity.Hospital;
import com.hms.entity.MedicalRecord;
import com.hms.entity.Opd;
import com.hms.entity.Patient;
import com.hms.entity.User;
import com.hms.repository.BillingRepository;
import com.hms.repository.DoctorRepository;
import com.hms.repository.HospitalRepository;
import com.hms.repository.MedicalRecordRepository;
import com.hms.repository.OpdRepository;
import com.hms.repository.PatientRepository;
import com.hms.repository.QueueEntryRepository;
import com.hms.repository.UserRepository;
import com.hms.service.hospital.BusinessClock;
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
 * A patient returning becomes exactly one visit, however many people press the button.
 *
 * <p>The due list is a worklist: several people watch it, and the same patient can be marked
 * arrived twice by a double-click, by two receptionists, or by a retry after a timeout. Each of
 * those used to be capable of producing a second encounter, a second queue entry and a second
 * bill for one returning patient. The claim is now a conditional UPDATE inside the same
 * transaction as the visit, so exactly one caller wins and the rest roll back whole.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class FollowUpArrivalTest {

    private static final AtomicLong SEQ = new AtomicLong();
    private static final List<String> MODULES = List.of("OPD", "IPD", "BILLING");

    @Autowired TestRestTemplate rest;
    @Autowired JwtUtil jwtUtil;
    /**
     * The same "today" the service uses.
     *
     * <p>A follow-up is due, overdue or upcoming relative to the business date, which
     * BusinessClock pins to the deployment's timezone. Asking the JVM for the date instead
     * answers in whatever zone the runner happens to use, so on a UTC machine after 18:30 these
     * tests were asserting against yesterday: due-today lists came back empty and daysOverdue
     * read one too high. Asking the application what day it is keeps them honest at any hour,
     * in any zone.
     */
    @Autowired BusinessClock clock;
    @Autowired HospitalRepository hospitals;
    @Autowired UserRepository users;
    @Autowired DoctorRepository doctors;
    @Autowired PatientRepository patients;
    @Autowired MedicalRecordRepository records;
    @Autowired OpdRepository opds;
    @Autowired QueueEntryRepository queueEntries;
    @Autowired BillingRepository billings;

    private static long uniq() { return System.nanoTime() + SEQ.incrementAndGet(); }

    private Hospital hospital;
    private Doctor doctor;
    private Patient patient;
    private String receptionToken, doctorToken, adminToken, nurseToken;

    private Hospital tenant(String label) {
        Hospital h = new Hospital();
        h.setName("Arrive " + label);
        h.setCustomId("ARR-" + uniq());
        h.setIsActive(true);
        h.setSubscriptionStatus("ACTIVE");
        h.setModules(MODULES);
        h.setIsSingleDoctor(false);
        h.setType(com.hms.entity.HospitalType.HOSPITAL);
        return hospitals.save(h);
    }

    private String tokenFor(Hospital h, String role, String email) {
        User u = new User();
        u.setEmail(email);
        u.setPassword("{noop}fixture");
        u.setName("User " + role);
        u.setRole(role);
        u.setHospitalId(h.getId());
        u.setIsActive(true);
        u.setTokenVersion(0);
        u = users.save(u);
        return jwtUtil.generateToken(u.getId(), u.getEmail(), role, h.getId(),
                MODULES, null, "HOSPITAL", null, 0);
    }

    private Doctor doctorIn(Hospital h, String email) {
        Doctor d = new Doctor();
        d.setHospitalId(h.getId());
        d.setName("Dr Arrival");
        d.setEmail(email);
        d.setPublicId("dpub-" + uniq());
        d.setPhone("9800000091");
        d.setSpecialization("General");
        d.setIsActive(true);
        return doctors.save(d);
    }

    private Patient patientIn(Hospital h, String name) {
        Patient p = new Patient();
        p.setHospitalId(h.getId());
        p.setName(name);
        p.setPublicId("ppub-" + uniq());
        p.setGender("MALE");
        p.setPhone("9" + String.format("%09d", Math.floorMod(uniq(), 1_000_000_000L)));
        p.setIsActive(true);
        p.setDateOfBirth(LocalDate.of(1980, 1, 1));
        return patients.save(p);
    }

    private MedicalRecord followUp(Hospital h, Patient p, Doctor d, LocalDate date, String status) {
        MedicalRecord m = new MedicalRecord();
        m.setHospitalId(h.getId());
        m.setPatientId(p.getId());
        m.setDoctorId(d == null ? null : d.getId());
        m.setPublicId("mr-" + uniq());
        m.setVisitType("OPD");
        m.setDiagnosis("Hypertension");
        m.setFollowUpDate(date);
        m.setFollowUpStatus(status);
        m.setFollowUpInstructions("Bring the BP diary");
        return records.save(m);
    }

    @BeforeEach
    void setUp() {
        hospital = tenant("Alpha");
        String docEmail = "doc." + uniq() + "@arr.test";
        doctor = doctorIn(hospital, docEmail);
        patient = patientIn(hospital, "Returning Patient");
        receptionToken = tokenFor(hospital, "RECEPTIONIST", "rec." + uniq() + "@arr.test");
        doctorToken = tokenFor(hospital, "DOCTOR", docEmail);
        adminToken = tokenFor(hospital, "HOSPITAL_ADMIN", "adm." + uniq() + "@arr.test");
        nurseToken = tokenFor(hospital, "NURSE", "nur." + uniq() + "@arr.test");
    }

    private HttpHeaders headers(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private ResponseEntity<String> arrive(Long recordId, String token) {
        return rest.exchange("/hospital/follow-ups/" + recordId + "/arrive", HttpMethod.POST,
                new HttpEntity<>("{}", headers(token)), String.class);
    }

    private ResponseEntity<String> get(String path, String token) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(token)), String.class);
    }

    private List<Opd> opdsForPatient(Patient p) {
        return opds.findAll().stream()
                .filter(o -> o.getPatient() != null && p.getId().equals(o.getPatient().getId()))
                .toList();
    }

    /** Queue entries for this patient's visits only — the table is shared across the whole run. */
    private long queuedFor(Patient p) {
        return opdsForPatient(p).stream().mapToLong(o -> queueEntries.countByOpdId(o.getId())).sum();
    }

    private long billsFor(Hospital h) {
        return billings.findAll().stream().filter(b -> h.getId().equals(b.getHospitalId())).count();
    }

    // ── the happy paths ──────────────────────────────────────────────────────

    @Test
    void arrivingOnTheDueDateCreatesTheFollowUpVisit() {
        MedicalRecord m = followUp(hospital, patient, doctor, clock.today(), null);

        ResponseEntity<String> res = arrive(m.getId(), receptionToken);
        assertThat(res.getStatusCode().value()).as("%s", res.getBody()).isEqualTo(200);

        List<Opd> created = opdsForPatient(patient);
        assertThat(created).hasSize(1);
        assertThat(created.get(0).getVisitType()).isEqualTo(Opd.VisitType.FOLLOWUP);

        MedicalRecord after = records.findById(m.getId()).orElseThrow();
        assertThat(after.getFollowUpStatus()).isEqualTo(MedicalRecord.FOLLOW_UP_ACTIONED);
        assertThat(after.getActionedOpdId()).isEqualTo(created.get(0).getId());
        assertThat(after.getActionedAt()).isNotNull();
        assertThat(after.getActionedByUserId()).isNotNull();

        // The clinical history of the original consultation is untouched.
        assertThat(after.getFollowUpDate()).isEqualTo(clock.today());
        assertThat(after.getFollowUpInstructions()).isEqualTo("Bring the BP diary");
        assertThat(after.getDiagnosis()).isEqualTo("Hypertension");
    }

    @Test
    void aLatePatientCanStillBeSeen() {
        MedicalRecord m = followUp(hospital, patient, doctor, clock.today().minusDays(10), null);
        assertThat(arrive(m.getId(), receptionToken).getStatusCode().value()).isEqualTo(200);
        assertThat(opdsForPatient(patient)).hasSize(1);
    }

    /** The visit must be reachable from the queues reception and the doctor actually read. */
    @Test
    void theNewVisitAppearsInTheQueues() {
        MedicalRecord m = followUp(hospital, patient, doctor, clock.today(), null);
        assertThat(arrive(m.getId(), receptionToken).getStatusCode().value()).isEqualTo(200);

        assertThat(get("/hospital/opd/queue", receptionToken).getBody())
                .as("reception must see the returning patient").contains("Returning Patient");
        assertThat(get("/hospital/opd/queue/doctor/" + doctor.getId(), receptionToken).getBody())
                .as("and so must the doctor who asked them back").contains("Returning Patient");
    }

    @Test
    void anActionedFollowUpLeavesTheDueList() {
        MedicalRecord m = followUp(hospital, patient, doctor, clock.today(), null);
        assertThat(get("/hospital/follow-ups", receptionToken).getBody()).contains("Returning Patient");

        arrive(m.getId(), receptionToken);

        assertThat(get("/hospital/follow-ups", receptionToken).getBody())
                .as("a patient who has been seen is no longer outstanding")
                .doesNotContain("Returning Patient");
    }

    // ── eligibility ──────────────────────────────────────────────────────────

    @Test
    void anEarlyArrivalIsRefused() {
        MedicalRecord m = followUp(hospital, patient, doctor, clock.today().plusDays(3), null);
        ResponseEntity<String> res = arrive(m.getId(), receptionToken);
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(opdsForPatient(patient)).isEmpty();
    }

    @Test
    void aConsultationWithNoFollowUpCannotBeArrived() {
        MedicalRecord m = followUp(hospital, patient, doctor, null, null);
        assertThat(arrive(m.getId(), receptionToken).getStatusCode().value()).isEqualTo(400);
        assertThat(opdsForPatient(patient)).isEmpty();
    }

    @Test
    void terminalFollowUpsAreNotRevived() {
        for (String status : new String[]{MedicalRecord.FOLLOW_UP_COMPLETED,
                                          MedicalRecord.FOLLOW_UP_CANCELLED}) {
            Patient p = patientIn(hospital, "Closed " + status);
            MedicalRecord m = followUp(hospital, p, doctor, clock.today(), status);
            assertThat(arrive(m.getId(), receptionToken).getStatusCode().value())
                    .as("%s must not be revived", status).isEqualTo(409);
            assertThat(opdsForPatient(p)).isEmpty();
        }
    }

    @Test
    void anAlreadyActionedFollowUpCannotProduceASecondVisit() {
        MedicalRecord m = followUp(hospital, patient, doctor, clock.today(), null);
        assertThat(arrive(m.getId(), receptionToken).getStatusCode().value()).isEqualTo(200);

        assertThat(arrive(m.getId(), receptionToken).getStatusCode().value()).isEqualTo(409);
        assertThat(opdsForPatient(patient)).as("still exactly one visit").hasSize(1);
    }

    // ── concurrency ──────────────────────────────────────────────────────────

    private void assertExactlyOneVisit(long billsBefore) {
        assertThat(opdsForPatient(patient)).as("exactly one encounter").hasSize(1);
        assertThat(queuedFor(patient)).as("exactly one queue entry").isEqualTo(1);
        assertThat(billsFor(hospital) - billsBefore)
                .as("at most one bill for one returning patient").isLessThanOrEqualTo(1);
    }

    @Test
    void aDoubleClickProducesOneVisit() {
        MedicalRecord m = followUp(hospital, patient, doctor, clock.today(), null);
        long billsBefore = billsFor(hospital);

        int first = arrive(m.getId(), receptionToken).getStatusCode().value();
        int second = arrive(m.getId(), receptionToken).getStatusCode().value();

        assertThat(first).isEqualTo(200);
        assertThat(second).as("the second click is refused, not silently duplicated").isEqualTo(409);
        assertExactlyOneVisit(billsBefore);
    }

    private void concurrentArrivals(int threads, String... tokens) throws Exception {
        MedicalRecord m = followUp(hospital, patient, doctor, clock.today(), null);
        long billsBefore = billsFor(hospital);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<Integer>> jobs = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final String token = tokens[i % tokens.length];
            jobs.add(() -> arrive(m.getId(), token).getStatusCode().value());
        }
        List<Future<Integer>> results = pool.invokeAll(jobs);
        pool.shutdown();
        pool.awaitTermination(60, TimeUnit.SECONDS);

        int created = 0, refused = 0;
        for (Future<Integer> f : results) {
            int code = f.get();
            if (code == 200) created++;
            else if (code == 409) refused++;
        }

        assertThat(created).as("exactly one caller may win").isEqualTo(1);
        assertThat(created + refused)
                .as("and every other gets a definite answer, not an error").isEqualTo(threads);
        assertExactlyOneVisit(billsBefore);
    }

    @Test
    void twoReceptionistsAtOnceProduceOneVisit() throws Exception {
        concurrentArrivals(2, receptionToken);
    }

    @Test
    void aReceptionistAndADoctorAtOnceProduceOneVisit() throws Exception {
        concurrentArrivals(2, receptionToken, doctorToken);
    }

    @Test
    void eightConcurrentArrivalsProduceOneVisit() throws Exception {
        concurrentArrivals(8, receptionToken, doctorToken, adminToken);
    }

    /** A client that retries after a timeout must not book the patient in twice. */
    @Test
    void aRetryAfterTheFirstSucceededIsRefused() {
        MedicalRecord m = followUp(hospital, patient, doctor, clock.today(), null);
        long billsBefore = billsFor(hospital);

        assertThat(arrive(m.getId(), receptionToken).getStatusCode().value()).isEqualTo(200);
        for (int i = 0; i < 3; i++) {
            assertThat(arrive(m.getId(), receptionToken).getStatusCode().value()).isEqualTo(409);
        }
        assertExactlyOneVisit(billsBefore);
    }

    // ── authorisation and tenancy ────────────────────────────────────────────

    @Test
    void theRolesThatCanRegisterAVisitCanRecordAnArrival() {
        for (String token : new String[]{receptionToken, doctorToken, adminToken}) {
            Patient p = patientIn(hospital, "Allowed " + uniq());
            MedicalRecord m = followUp(hospital, p, doctor, clock.today(), null);
            assertThat(arrive(m.getId(), token).getStatusCode().value()).isEqualTo(200);
        }
    }

    @Test
    void aNurseCannotRecordAnArrival() {
        MedicalRecord m = followUp(hospital, patient, doctor, clock.today(), null);
        assertThat(arrive(m.getId(), nurseToken).getStatusCode().value()).isEqualTo(403);
        assertThat(opdsForPatient(patient)).isEmpty();
    }

    @Test
    void anotherFacilitysFollowUpReadsAsMissing() {
        Hospital other = tenant("Bravo");
        Doctor otherDoctor = doctorIn(other, "doc." + uniq() + "@bravo.test");
        Patient otherPatient = patientIn(other, "Bravo Patient");
        MedicalRecord theirs = followUp(other, otherPatient, otherDoctor, clock.today(), null);

        assertThat(arrive(theirs.getId(), receptionToken).getStatusCode().value())
                .as("another facility's follow-up must be indistinguishable from a missing one")
                .isEqualTo(404);

        MedicalRecord after = records.findById(theirs.getId()).orElseThrow();
        assertThat(after.getFollowUpStatus()).as("and must remain untouched").isNull();
        assertThat(after.getActionedOpdId()).isNull();
        assertThat(opdsForPatient(otherPatient)).isEmpty();
    }

    // ── atomicity ────────────────────────────────────────────────────────────

    /**
     * Rollback proof, through a real seam rather than a mock: a follow-up whose patient has been
     * deactivated fails inside recordArrival after the record has been read and validated. The
     * transaction must leave nothing behind — no visit, no queue entry, no bill, and a follow-up
     * still open for whoever sorts the problem out.
     */
    @Test
    void aFailedArrivalLeavesNothingBehind() {
        MedicalRecord m = followUp(hospital, patient, doctor, clock.today(), null);
        patient.setIsActive(false);
        patients.save(patient);

        long opdsBefore = opds.count();
        long queuedBefore = queuedFor(patient);
        long billsBefore = billsFor(hospital);

        ResponseEntity<String> res = arrive(m.getId(), receptionToken);
        assertThat(res.getStatusCode().is2xxSuccessful()).as("%s", res.getBody()).isFalse();

        assertThat(opds.count()).as("no stray encounter").isEqualTo(opdsBefore);
        assertThat(queuedFor(patient)).as("no stray queue entry").isEqualTo(queuedBefore);
        assertThat(billsFor(hospital)).as("no stray bill").isEqualTo(billsBefore);

        MedicalRecord after = records.findById(m.getId()).orElseThrow();
        assertThat(after.getFollowUpStatus()).as("the follow-up is still open").isNull();
        assertThat(after.getActionedOpdId()).isNull();
    }
}
