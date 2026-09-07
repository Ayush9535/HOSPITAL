package com.hms.security;

import com.hms.entity.Doctor;
import com.hms.entity.Hospital;
import com.hms.entity.MedicalRecord;
import com.hms.entity.Patient;
import com.hms.entity.User;
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
 * A follow-up falling due is not a patient arriving.
 *
 * <p>It used to be treated as one. Opening the OPD queue ran the due follow-ups and created an
 * OPD, a queue entry and an audit row for each — so a date passing booked the patient in, two
 * people opening the screen at once booked them in twice, and a follow-up nobody opened the
 * screen for on the day was lost for good. These tests hold the new line: reading is reading.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class FollowUpReadModelTest {

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

    private static long uniq() { return System.nanoTime() + SEQ.incrementAndGet(); }

    private Hospital hospital;
    private Doctor doctor;
    private Patient patient;
    private String receptionToken, doctorToken;

    private Hospital tenant(String label) {
        Hospital h = new Hospital();
        h.setName("FU " + label);
        h.setCustomId("FU-" + uniq());
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
        d.setName("Dr FollowUp");
        d.setEmail(email);
        d.setPublicId("dpub-" + uniq());
        d.setPhone("9800000090");
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

    /** A consultation with a follow-up on the given date, in the given lifecycle state. */
    private MedicalRecord record(Hospital h, Patient p, Doctor d, LocalDate followUp,
                                 String status, String instructions) {
        MedicalRecord m = new MedicalRecord();
        m.setHospitalId(h.getId());
        m.setPatientId(p.getId());
        m.setDoctorId(d.getId());
        m.setPublicId("mr-" + uniq());
        m.setVisitType("OPD");
        m.setDiagnosis("Hypertension");
        m.setFollowUpDate(followUp);
        m.setFollowUpStatus(status);
        m.setFollowUpInstructions(instructions);
        return records.save(m);
    }

    @BeforeEach
    void setUp() {
        hospital = tenant("Alpha");
        String docEmail = "doc." + uniq() + "@fu.test";
        doctor = doctorIn(hospital, docEmail);
        patient = patientIn(hospital, "FollowUp Patient");
        receptionToken = tokenFor(hospital, "RECEPTIONIST", "rec." + uniq() + "@fu.test");
        doctorToken = tokenFor(hospital, "DOCTOR", docEmail);
    }

    private HttpHeaders headers(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private ResponseEntity<String> get(String path, String token) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(token)), String.class);
    }

    private ResponseEntity<String> post(String path, String token, String body) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers(token)), String.class);
    }

    private String followUps(String timing) {
        ResponseEntity<String> res = get("/hospital/follow-ups"
                + (timing == null ? "" : "?timing=" + timing), receptionToken);
        assertThat(res.getStatusCode().value()).as("%s", res.getBody()).isEqualTo(200);
        return res.getBody();
    }

    // ── the buckets ──────────────────────────────────────────────────────────

    @Test
    void aFollowUpDueTodayIsListedAsDueToday() {
        record(hospital, patient, doctor, clock.today(), null, "Review blood pressure");
        assertThat(followUps("DUE_TODAY"))
                .contains("FollowUp Patient")
                .contains("DUE_TODAY")
                .contains("Review blood pressure");
    }

    @Test
    void aPastFollowUpIsOverdueRatherThanForgotten() {
        record(hospital, patient, doctor, clock.today().minusDays(3), null, null);
        String body = followUps("OVERDUE");
        assertThat(body).contains("FollowUp Patient").contains("OVERDUE");
        assertThat(body).as("and it is reported as three days late").contains("\"daysOverdue\":3");
    }

    /** The old code matched today exactly, so a follow-up nobody looked at that day vanished. */
    @Test
    void anOverdueFollowUpIsStillThereManyDaysLater() {
        record(hospital, patient, doctor, clock.today().minusDays(45), null, null);
        assertThat(followUps("OVERDUE")).contains("FollowUp Patient");
    }

    @Test
    void aFutureFollowUpIsUpcomingAndNotDue() {
        record(hospital, patient, doctor, clock.today().plusDays(5), null, null);
        assertThat(followUps("UPCOMING")).contains("FollowUp Patient");
        assertThat(followUps("DUE_TODAY")).doesNotContain("FollowUp Patient");
        assertThat(followUps("OVERDUE")).doesNotContain("FollowUp Patient");
    }

    @Test
    void aConsultationWithNoFollowUpDateIsNeverListed() {
        record(hospital, patient, doctor, null, null, null);
        assertThat(followUps(null)).doesNotContain("FollowUp Patient");
    }

    @Test
    void closedFollowUpsDropOutOfTheList() {
        Patient actioned = patientIn(hospital, "Actioned Patient");
        Patient completed = patientIn(hospital, "Completed Patient");
        Patient cancelled = patientIn(hospital, "Cancelled Patient");
        record(hospital, actioned, doctor, clock.today(), MedicalRecord.FOLLOW_UP_ACTIONED, null);
        record(hospital, completed, doctor, clock.today(), MedicalRecord.FOLLOW_UP_COMPLETED, null);
        record(hospital, cancelled, doctor, clock.today(), MedicalRecord.FOLLOW_UP_CANCELLED, null);

        String body = followUps(null);
        assertThat(body)
                .doesNotContain("Actioned Patient")
                .doesNotContain("Completed Patient")
                .doesNotContain("Cancelled Patient");
    }

    /** Consultations written before the status column existed have NULL and are still open. */
    @Test
    void historicalRowsWithNoStatusRemainActionable() {
        record(hospital, patient, doctor, clock.today().minusDays(2), null, null);
        assertThat(followUps("OVERDUE"))
                .as("a NULL status must read as open, not as dealt with")
                .contains("FollowUp Patient");
    }

    // ── reading does not write ───────────────────────────────────────────────

    private long opdCount() {
        return opds.findAll().stream()
                .filter(o -> o.getPatient() != null && patient.getId().equals(o.getPatient().getId()))
                .count();
    }

    @Test
    void readingTheFollowUpListCreatesNothing() {
        record(hospital, patient, doctor, clock.today(), null, null);
        long opdsBefore = opdCount();
        long queuedBefore = queueEntries.count();

        for (int i = 0; i < 5; i++) {
            followUps(null);
        }

        assertThat(opdCount()).as("no encounter is created by reading").isEqualTo(opdsBefore);
        assertThat(queueEntries.count()).as("nobody is queued by reading").isEqualTo(queuedBefore);
    }

    /**
     * The exact regression. Opening the OPD queue created an encounter for every due follow-up;
     * this asserts it no longer does, however many times it is opened.
     */
    @Test
    void openingTheOpdQueueNoLongerCreatesFollowUpEncounters() {
        record(hospital, patient, doctor, clock.today(), null, null);
        long opdsBefore = opdCount();
        long queuedBefore = queueEntries.count();

        for (int i = 0; i < 3; i++) {
            assertThat(get("/hospital/opd/queue", receptionToken).getStatusCode().value()).isEqualTo(200);
            assertThat(get("/hospital/opd/queue/doctor/" + doctor.getId(), receptionToken)
                    .getStatusCode().value()).isEqualTo(200);
        }

        assertThat(opdCount())
                .as("a due follow-up is not a patient who has arrived").isEqualTo(opdsBefore);
        assertThat(queueEntries.count()).isEqualTo(queuedBefore);
    }

    /** Two dashboards open at once used to produce two encounters for one patient. */
    @Test
    void concurrentQueueAndFollowUpReadsCreateNothing() throws Exception {
        record(hospital, patient, doctor, clock.today(), null, null);
        long opdsBefore = opdCount();
        long queuedBefore = queueEntries.count();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<Integer>> jobs = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final boolean queue = i % 2 == 0;
            jobs.add(() -> (queue
                    ? get("/hospital/opd/queue", receptionToken)
                    : get("/hospital/follow-ups", receptionToken)).getStatusCode().value());
        }
        List<Future<Integer>> results = pool.invokeAll(jobs);
        pool.shutdown();
        pool.awaitTermination(60, TimeUnit.SECONDS);
        for (Future<Integer> f : results) {
            assertThat(f.get()).isEqualTo(200);
        }

        assertThat(opdCount()).as("no encounter under concurrency either").isEqualTo(opdsBefore);
        assertThat(queueEntries.count()).isEqualTo(queuedBefore);
    }

    // ── tenancy ──────────────────────────────────────────────────────────────

    @Test
    void oneFacilityCannotSeeAnothersFollowUps() {
        Hospital other = tenant("Bravo");
        Doctor otherDoctor = doctorIn(other, "doc." + uniq() + "@bravo.test");
        Patient otherPatient = patientIn(other, "Bravo Patient");
        record(other, otherPatient, otherDoctor, clock.today(), null, null);
        record(other, otherPatient, otherDoctor, clock.today().minusDays(2), null, null);
        record(other, otherPatient, otherDoctor, clock.today().plusDays(2), null, null);

        for (String bucket : new String[]{"DUE_TODAY", "OVERDUE", "UPCOMING"}) {
            assertThat(followUps(bucket))
                    .as("%s must not leak across facilities", bucket)
                    .doesNotContain("Bravo Patient");
        }
        assertThat(followUps(null)).doesNotContain("Bravo Patient");
    }

    // ── the doctor's own list ────────────────────────────────────────────────

    @Test
    void aDoctorAskingForTheirOwnSeesOnlyTheirOwn() {
        Doctor colleague = doctorIn(hospital, "other." + uniq() + "@fu.test");
        Patient theirs = patientIn(hospital, "Colleague Patient");
        record(hospital, patient, doctor, clock.today(), null, null);
        record(hospital, theirs, colleague, clock.today(), null, null);

        ResponseEntity<String> mine = get("/hospital/follow-ups?mine=true", doctorToken);
        assertThat(mine.getStatusCode().value()).isEqualTo(200);
        assertThat(mine.getBody()).contains("FollowUp Patient").doesNotContain("Colleague Patient");

        // Without mine=true the whole facility's list comes back.
        assertThat(get("/hospital/follow-ups", doctorToken).getBody()).contains("Colleague Patient");
    }

    @Test
    void anUnknownBucketIsRefusedRatherThanIgnored() {
        assertThat(get("/hospital/follow-ups?timing=SOMEDAY", receptionToken)
                .getStatusCode().value()).isEqualTo(400);
    }

    // ── the doctor writes the follow-up ──────────────────────────────────────

    @Test
    void aConsultationPersistsItsFollowUpDateAndInstructions() {
        com.hms.entity.Opd opd = new com.hms.entity.Opd();
        opd.setPatient(patient);
        opd.setDoctor(doctor);
        opd.setCaseId("OPD-" + uniq());
        Long opdId = opds.save(opd).getId();

        ResponseEntity<String> res = post("/hospital/doctors/consultation", doctorToken,
                "{\"opdId\":" + opdId + ",\"patientId\":" + patient.getId()
                        + ",\"diagnosis\":\"Hypertension\""
                        + ",\"followUpDate\":\"" + clock.today().plusDays(7) + "\""
                        + ",\"followUpInstructions\":\"Bring the BP diary\"}");
        assertThat(res.getStatusCode().value()).as("%s", res.getBody()).isEqualTo(200);

        MedicalRecord saved = records.findAll().stream()
                .filter(m -> hospital.getId().equals(m.getHospitalId()))
                .filter(m -> m.getFollowUpDate() != null)
                .findFirst().orElseThrow();
        assertThat(saved.getFollowUpDate()).isEqualTo(clock.today().plusDays(7));
        assertThat(saved.getFollowUpInstructions()).isEqualTo("Bring the BP diary");
        assertThat(saved.getFollowUpStatus())
                .as("a new follow-up starts open").isEqualTo(MedicalRecord.FOLLOW_UP_OPEN);

        assertThat(followUps("UPCOMING")).contains("Bring the BP diary");
    }

    /** A consultation with a date and no instructions still works, as older clients send. */
    @Test
    void aConsultationWithoutInstructionsStillRecordsTheFollowUp() {
        com.hms.entity.Opd opd = new com.hms.entity.Opd();
        opd.setPatient(patient);
        opd.setDoctor(doctor);
        opd.setCaseId("OPD-" + uniq());
        Long opdId = opds.save(opd).getId();

        ResponseEntity<String> res = post("/hospital/doctors/consultation", doctorToken,
                "{\"opdId\":" + opdId + ",\"patientId\":" + patient.getId()
                        + ",\"diagnosis\":\"Hypertension\""
                        + ",\"followUpDate\":\"" + clock.today() + "\"}");
        assertThat(res.getStatusCode().value()).as("%s", res.getBody()).isEqualTo(200);

        assertThat(followUps("DUE_TODAY")).contains("FollowUp Patient");
    }
}
