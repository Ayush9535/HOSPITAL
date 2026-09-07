package com.hms.security;

import com.hms.entity.Doctor;
import com.hms.entity.Hospital;
import com.hms.entity.MedicalRecord;
import com.hms.entity.Opd;
import com.hms.entity.Patient;
import com.hms.entity.User;
import com.hms.repository.AppointmentRepository;
import com.hms.repository.DoctorRepository;
import com.hms.repository.HospitalRepository;
import com.hms.repository.MedicalRecordRepository;
import com.hms.repository.OpdRepository;
import com.hms.repository.PatientRepository;
import com.hms.repository.PrescriptionRepository;
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
 * The end of a consultation, which is the moment the doctor's screen empties.
 *
 * <p>Everything either side of this was already covered -- starting a visit, prescribing, the
 * follow-up that comes out of it -- but nothing asserted that finishing actually finishes: that
 * the case closes, the appointment closes with it, and the patient stops appearing in the queue
 * as though still waiting. A doctor who keeps seeing a patient they have already seen stops
 * trusting the queue, and a queue nobody trusts is worse than no queue.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ConsultationCompletionTest {

    private static final AtomicLong SEQ = new AtomicLong();
    private static final List<String> MODULES =
            List.of("APPOINTMENTS", "OPD", "IPD", "NURSING", "BILLING");

    @Autowired TestRestTemplate rest;
    @Autowired JwtUtil jwtUtil;
    @Autowired HospitalRepository hospitals;
    @Autowired UserRepository users;
    @Autowired DoctorRepository doctors;
    @Autowired PatientRepository patients;
    @Autowired OpdRepository opds;
    @Autowired AppointmentRepository appointments;
    @Autowired PrescriptionRepository prescriptions;
    @Autowired MedicalRecordRepository medicalRecords;

    private static long uniq() { return System.nanoTime() + SEQ.incrementAndGet(); }
    private static String phone() { return "9" + String.format("%09d", Math.floorMod(uniq(), 1_000_000_000L)); }

    private Hospital hospital;
    private Doctor doctor;
    private Patient patient;
    private String doctorToken, receptionToken;

    @BeforeEach
    void setUp() {
        hospital = new Hospital();
        hospital.setName("Completion Hospital");
        hospital.setCustomId("CMP-" + uniq());
        hospital.setIsActive(true);
        hospital.setSubscriptionStatus("ACTIVE");
        hospital.setModules(MODULES);
        hospital.setIsSingleDoctor(false);
        hospital.setType(com.hms.entity.HospitalType.HOSPITAL);
        hospital = hospitals.save(hospital);

        String docEmail = "doc." + uniq() + "@completion.test";
        Doctor d = new Doctor();
        d.setHospitalId(hospital.getId());
        d.setName("Dr Completion");
        d.setEmail(docEmail);
        d.setPublicId("dpub-" + uniq());
        d.setPhone(phone());
        d.setSpecialization("General Medicine");
        d.setIsActive(true);
        doctor = doctors.save(d);

        Patient p = new Patient();
        p.setHospitalId(hospital.getId());
        p.setName("Meera Joshi");
        p.setPublicId("ppub-" + uniq());
        p.setGender("FEMALE");
        p.setPhone(phone());
        p.setIsActive(true);
        p.setDateOfBirth(LocalDate.of(1985, 5, 20));
        patient = patients.save(p);

        doctorToken = tokenFor("DOCTOR", docEmail);
        receptionToken = tokenFor("RECEPTIONIST", "rec." + uniq() + "@completion.test");
    }

    // -- OPD-based consultation --------------------------------------------------

    @Test
    void completingAnOpdConsultationClosesTheCaseAndClearsTheQueue() {
        Long opdId = startOpdVisit();

        assertThat(get("/hospital/opd/queue/my", doctorToken).getBody())
                .as("the patient is waiting to be seen").contains(patient.getPublicId());

        ok(consult("{\"opdId\":" + opdId
                + ",\"patientId\":" + patient.getId()
                + ",\"diagnosis\":\"Viral fever\""
                + ",\"treatmentNotes\":\"Rest and fluids\""
                + ",\"followUpDate\":\"" + LocalDate.now().plusDays(7) + "\""
                + ",\"followUpInstructions\":\"Return if the fever persists\""
                + ",\"prescription\":[{\"medicineName\":\"Paracetamol\",\"dosage\":\"500mg\""
                + ",\"frequency\":\"1-0-1\",\"duration\":\"5 Days\",\"instructions\":\"After food\"}]}"));

        // Read cold: the response is not evidence that anything was written.
        Opd closed = opds.findById(opdId).orElseThrow();
        assertThat(closed.getStatus()).as("the case is closed").isEqualTo(Opd.Status.COMPLETED);

        assertThat(get("/hospital/opd/queue/my", doctorToken).getBody())
                .as("and the doctor's queue no longer offers a patient they have just seen")
                .doesNotContain(patient.getPublicId());
    }

    @Test
    void whatWasRecordedDuringTheConsultationSurvivesTheCompletion() {
        Long opdId = startOpdVisit();
        LocalDate followUp = LocalDate.now().plusDays(7);

        ok(consult("{\"opdId\":" + opdId
                + ",\"patientId\":" + patient.getId()
                + ",\"diagnosis\":\"Viral fever\""
                + ",\"treatmentNotes\":\"Rest and fluids\""
                + ",\"followUpDate\":\"" + followUp + "\""
                + ",\"prescription\":[{\"medicineName\":\"Paracetamol\",\"dosage\":\"500mg\""
                + ",\"frequency\":\"1-0-1\",\"duration\":\"5 Days\"}]}"));

        MedicalRecord record = medicalRecords.findByPatientIdOrderByCreatedAtDesc(patient.getId())
                .stream().findFirst().orElseThrow();
        assertThat(record.getDiagnosis()).isEqualTo("Viral fever");
        assertThat(record.getFollowUpDate()).as("the follow-up the doctor asked for").isEqualTo(followUp);

        assertThat(prescriptions.findByMedicalRecordIdIn(List.of(record.getId()))
                .stream().map(com.hms.entity.Prescription::getMedicineName))
                .contains("Paracetamol");

        // Closing the case must not take the record with it.
        assertThat(opds.findById(opdId).orElseThrow().getStatus()).isEqualTo(Opd.Status.COMPLETED);
    }

    /** Reading it back through the API a doctor would actually use, not just from the table. */
    @Test
    void theClosedCaseStillReadsAsCompletedThroughTheApi() {
        Long opdId = startOpdVisit();
        ok(consult("{\"opdId\":" + opdId + ",\"patientId\":" + patient.getId()
                + ",\"diagnosis\":\"Viral fever\"}"));

        assertThat(get("/hospital/opd/" + opdId, doctorToken).getBody())
                .as("a page refresh must not resurrect the case").contains("COMPLETED");
    }

    // -- appointment-based consultation ------------------------------------------

    @Test
    void completingAnAppointmentBasedConsultationClosesTheAppointmentToo() {
        LocalDate today = LocalDate.now();
        ok(post("/hospital/appointments", receptionToken,
                "{\"doctorId\":" + doctor.getId()
                        + ",\"patientId\":" + patient.getId()
                        + ",\"appointmentDate\":\"" + today + "\""
                        + ",\"appointmentTime\":\"23:15\"}"));

        Long appointmentId = appointments.findAll().stream()
                .filter(a -> patient.getId().equals(a.getPatientId()))
                .findFirst().orElseThrow().getId();

        ok(consult("{\"appointmentId\":" + appointmentId
                + ",\"patientId\":" + patient.getId()
                + ",\"diagnosis\":\"Viral fever\""
                + ",\"prescription\":[{\"medicineName\":\"Paracetamol\",\"dosage\":\"500mg\""
                + ",\"frequency\":\"1-0-1\",\"duration\":\"3 Days\"}]}"));

        assertThat(appointments.findById(appointmentId).orElseThrow().getStatus())
                .as("the booking is done, not still waiting").isEqualTo("COMPLETED");

        // An appointment-based consultation creates the OPD case itself; it must be born closed.
        Opd created = opds.findAll().stream()
                .filter(o -> o.getPatient() != null && patient.getId().equals(o.getPatient().getId()))
                .findFirst().orElseThrow();
        assertThat(created.getStatus()).isEqualTo(Opd.Status.COMPLETED);
    }

    // -- helpers -----------------------------------------------------------------

    private Long startOpdVisit() {
        ok(post("/hospital/opd", doctorToken,
                "{\"patientId\":\"" + patient.getPublicId() + "\""
                        + ",\"doctorId\":\"" + doctor.getPublicId() + "\""
                        + ",\"visitType\":\"NEW\",\"problem\":\"Fever\"}"));
        return opds.findAll().stream()
                .filter(o -> o.getPatient() != null && patient.getId().equals(o.getPatient().getId()))
                .findFirst().orElseThrow().getId();
    }

    private ResponseEntity<String> consult(String body) {
        return post("/hospital/doctors/consultation", doctorToken, body);
    }

    private String tokenFor(String role, String email) {
        User u = new User();
        u.setEmail(email);
        u.setPassword("{noop}fixture");
        u.setName("User " + role);
        u.setRole(role);
        u.setHospitalId(hospital.getId());
        u.setIsActive(true);
        u.setTokenVersion(0);
        u = users.save(u);
        return jwtUtil.generateToken(u.getId(), u.getEmail(), role, hospital.getId(),
                MODULES, null, "HOSPITAL", null, 0);
    }

    private HttpHeaders headers(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private ResponseEntity<String> post(String path, String token, String body) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers(token)), String.class);
    }

    private ResponseEntity<String> get(String path, String token) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(token)), String.class);
    }

    private static void ok(ResponseEntity<String> res) {
        assertThat(res.getStatusCode().value()).as("%s", res.getBody()).isEqualTo(200);
    }

    private static void ok(ResponseEntity<String> res, String what) {
        assertThat(res.getStatusCode().value()).as("%s: %s", what, res.getBody()).isEqualTo(200);
    }
}
