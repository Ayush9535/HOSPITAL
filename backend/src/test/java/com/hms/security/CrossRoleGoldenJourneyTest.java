package com.hms.security;

import com.hms.entity.Bed;
import com.hms.entity.Doctor;
import com.hms.entity.Hospital;
import com.hms.entity.User;
import com.hms.entity.Ward;
import com.hms.repository.BedRepository;
import com.hms.repository.DoctorRepository;
import com.hms.repository.HospitalRepository;
import com.hms.repository.IpdAdmissionRepository;
import com.hms.repository.OpdRepository;
import com.hms.repository.PatientRepository;
import com.hms.repository.PrescriptionRepository;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One patient carried across three roles, through the endpoints the product's own screens call.
 *
 * <p>The point is not that each screen works in isolation — separate suites already cover that.
 * It is that a thing one role creates is actually visible to the next: reception books, the
 * DOCTOR sees that exact appointment, the doctor consults and asks for admission, RECEPTION sees
 * that request and admits, and the doctor still has the patient afterwards. Every boundary is
 * crossed with a fresh authenticated request rather than a return value, because the defect this
 * guards against is data that exists but nobody can reach.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CrossRoleGoldenJourneyTest {

    private static final AtomicLong SEQ = new AtomicLong();
    private static final List<String> MODULES =
            List.of("APPOINTMENTS", "OPD", "IPD", "NURSING", "BILLING", "MEDICAL_INVENTORY");

    @Autowired TestRestTemplate rest;
    @Autowired JwtUtil jwtUtil;
    @Autowired HospitalRepository hospitals;
    @Autowired UserRepository users;
    @Autowired DoctorRepository doctors;
    @Autowired PatientRepository patients;
    @Autowired WardRepository wards;
    @Autowired BedRepository beds;
    @Autowired OpdRepository opds;
    @Autowired IpdAdmissionRepository admissions;
    @Autowired PrescriptionRepository prescriptions;

    private static long uniq() { return System.nanoTime() + SEQ.incrementAndGet(); }
    private static String phone() { return "9" + String.format("%09d", Math.floorMod(uniq(), 1_000_000_000L)); }

    private Hospital hospital;
    private Doctor doctor;
    private Ward ward;
    private Long bedId;
    private String receptionToken, doctorToken;

    @BeforeEach
    void setUp() {
        Hospital h = new Hospital();
        h.setName("Journey Hospital");
        h.setCustomId("JRN-" + uniq());
        h.setIsActive(true);
        h.setSubscriptionStatus("ACTIVE");
        h.setModules(MODULES);
        h.setIsSingleDoctor(false);
        h.setType(com.hms.entity.HospitalType.HOSPITAL);
        hospital = hospitals.save(h);

        String docEmail = "doc." + uniq() + "@journey.test";
        Doctor d = new Doctor();
        d.setHospitalId(hospital.getId());
        d.setName("Dr Journey");
        d.setEmail(docEmail);
        d.setPublicId("dpub-" + uniq());
        d.setPhone("9800000060");
        d.setSpecialization("General Medicine");
        d.setIsActive(true);
        doctor = doctors.save(d);

        Ward w = new Ward();
        w.setWardName("General Ward");
        w.setHospitalId(hospital.getId());
        w.setBedPrice(new BigDecimal("1500"));
        w.setTotalBeds(5);
        ward = wards.save(w);

        Bed b = new Bed();
        b.setHospitalId(hospital.getId());
        b.setWardId(ward.getWardId());
        b.setBedCode("BED-" + uniq());
        b.setStatus("available");
        bedId = beds.save(b).getBedId();

        receptionToken = tokenFor("RECEPTIONIST", "rec." + uniq() + "@journey.test");
        doctorToken = tokenFor("DOCTOR", docEmail);
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

    /** Fail with the response body attached — a bare status tells nobody what broke. */
    private void ok(ResponseEntity<String> res, String step) {
        assertThat(res.getStatusCode().value()).as("%s -> %s", step, res.getBody()).isEqualTo(200);
    }

    @Test
    void receptionBooksDoctorConsultsAndReceptionAdmits() {
        // ---------------------------------------------------------- RECEPTION: patient
        String patientPhone = phone();
        ResponseEntity<String> created = post("/hospital/patients", receptionToken,
                "{\"name\":\"Journey Patient\",\"phone\":\"" + patientPhone + "\",\"gender\":\"MALE\""
                        + ",\"dateOfBirth\":\"1980-01-01\",\"address\":\"Demo\"}");
        ok(created, "reception creates a patient");

        var patient = patients.findAll().stream()
                .filter(p -> hospital.getId().equals(p.getHospitalId()))
                .findFirst().orElseThrow();
        String patientPublicId = patient.getPublicId();

        // Re-fetched, not trusted from the create response: this is what the search screen does.
        ResponseEntity<String> found = get("/hospital/patients?search=" + patientPhone, receptionToken);
        ok(found, "reception searches for the patient by phone");
        assertThat(found.getBody())
                .as("the search box must find the patient reception just registered")
                .contains("Journey Patient");

        // ---------------------------------------------------------- RECEPTION: appointment
        LocalDate today = LocalDate.now();
        ResponseEntity<String> booked = post("/hospital/appointments", receptionToken,
                "{\"doctorId\":" + doctor.getId()
                        + ",\"patientId\":" + patient.getId()
                        + ",\"appointmentDate\":\"" + today + "\""
                        + ",\"appointmentTime\":\"23:30\"}");
        ok(booked, "reception books an appointment");

        // ---------------------------------------------------------- DOCTOR: sees THAT appointment
        ResponseEntity<String> myAppointments =
                get("/hospital/appointments/my-appointments?view=today&page=0&size=50", doctorToken);
        ok(myAppointments, "doctor opens today's appointments");
        assertThat(myAppointments.getBody())
                .as("the doctor must see the exact appointment reception just booked")
                .contains("23:30");

        // ---------------------------------------------------------- DOCTOR: OPD + consultation
        ResponseEntity<String> opd = post("/hospital/opd", doctorToken,
                "{\"patientId\":\"" + patientPublicId + "\""
                        + ",\"doctorId\":\"" + doctor.getPublicId() + "\""
                        + ",\"visitType\":\"NEW\",\"problem\":\"Fever\"}");
        ok(opd, "doctor starts an OPD visit");

        Long opdId = opds.findAll().stream()
                .filter(o -> o.getPatient() != null && patient.getId().equals(o.getPatient().getId()))
                .findFirst().orElseThrow().getId();

        ResponseEntity<String> consultation = post("/hospital/doctors/consultation", doctorToken,
                "{\"opdId\":" + opdId
                        + ",\"patientId\":" + patient.getId()
                        + ",\"diagnosis\":\"Viral fever\""
                        + ",\"treatmentNotes\":\"Rest and fluids\""
                        // The doctor asks for admission on the consultation itself; there is no
                        // separate endpoint. This flag is what puts the case on reception's list.
                        + ",\"ipdAdmitRecommended\":true"
                        + ",\"followUpDate\":\"" + today.plusDays(7) + "\""
                        + ",\"prescription\":[{\"medicineName\":\"Paracetamol\",\"dosage\":\"500mg\""
                        + ",\"frequency\":\"1-0-1\",\"duration\":\"5 Days\",\"instructions\":\"After food\"}]}");
        ok(consultation, "doctor records the consultation");

        // Persisted, read back cold rather than believed from the response.
        assertThat(prescriptions.findAll().stream()
                .filter(p -> hospital.getId().equals(p.getHospitalId()))
                .map(com.hms.entity.Prescription::getMedicineName))
                .as("the prescription is persisted").contains("Paracetamol");

        assertThat(opds.findById(opdId).orElseThrow().getIpdAdmitRecommended())
                .as("the consultation must record the doctor's request for admission").isTrue();

        // ---------------------------------------------------------- RECEPTION: sees the request
        ResponseEntity<String> pending = get("/hospital/opd/ipd-requests", receptionToken);
        ok(pending, "reception opens pending admissions");
        assertThat(pending.getBody())
                .as("the admission the doctor asked for must reach reception")
                .contains(String.valueOf(opdId));

        // Ward and bed are visible to the person doing the admitting.
        ok(get("/hospital/wards/for-admission", receptionToken), "reception sees wards");
        ok(get("/hospital/beds/available?wardId=" + ward.getWardId(), receptionToken), "reception sees free beds");

        // ---------------------------------------------------------- RECEPTION: admit
        ResponseEntity<String> admitted = post("/hospital/ipd/admit", receptionToken,
                "{\"opdId\":" + opdId + ",\"wardId\":" + ward.getWardId() + ",\"bedId\":" + bedId
                        + ",\"admissionType\":\"ELECTIVE\",\"primaryDiagnosis\":\"Viral fever\"}");
        ok(admitted, "reception admits the patient");

        // ---------------------------------------------------------- persistence, read cold
        var admission = admissions.findAll().stream()
                .filter(a -> hospital.getId().equals(a.getHospitalId()))
                .findFirst().orElseThrow();
        assertThat(admission.getStatus()).isEqualTo("ADMITTED");
        assertThat(admission.getIpdNumber()).matches("IPD-\\d+");
        assertThat(beds.findById(bedId).orElseThrow().getStatus())
                .as("the bed the patient was put in must read occupied").isEqualToIgnoringCase("occupied");

        // The request must leave reception's pending list once it has been actioned.
        assertThat(get("/hospital/opd/ipd-requests", receptionToken).getBody())
                .as("an admitted patient must not still look like a pending request")
                .doesNotContain("\"opdId\":" + opdId);

        // ---------------------------------------------------------- DOCTOR: still has the patient
        ResponseEntity<String> doctorIpd = get("/hospital/ipd/my", doctorToken);
        ok(doctorIpd, "doctor opens their admitted patients");
        assertThat(doctorIpd.getBody())
                .as("the doctor must still see the patient after admission")
                .contains(admission.getIpdNumber());

        // ---------------------------------------------------------- the longitudinal record
        ResponseEntity<String> timeline = get("/hospital/patients/" + patientPublicId + "/timeline", doctorToken);
        ok(timeline, "doctor opens the patient timeline");
        assertThat(timeline.getBody())
                .as("the timeline must carry the story that was just created")
                .contains("IPD");
    }
}
