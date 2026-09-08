package com.hms.security;

import com.hms.entity.Bed;
import com.hms.entity.Doctor;
import com.hms.entity.Hospital;
import com.hms.entity.Patient;
import com.hms.entity.User;
import com.hms.entity.Ward;
import com.hms.repository.BedRepository;
import com.hms.repository.DoctorRepository;
import com.hms.repository.HospitalRepository;
import com.hms.repository.IpdAdmissionRepository;
import com.hms.repository.MedicalRecordRepository;
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
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What "Admit to IPD" on the doctor's screen actually does, which is ask.
 *
 * <p>The button is not an admission. It posts the same consultation the Complete button does with
 * one extra flag, and the bed is reception's decision -- they are the ones who know which bed is
 * really free. The failure this guards against is the opposite of the obvious one: a doctor's
 * request quietly admitting somebody, or occupying a bed nobody has agreed to, before reception
 * has seen the request at all.
 *
 * <p>The other half of the journey -- reception picking a ward and bed, the admission, the IPD
 * number, the bed turning occupied, the request leaving the list, the doctor still seeing the
 * patient -- is already carried end to end by CrossRoleGoldenJourneyTest against this same
 * payload shape, and the refusals by AdmissionBedWardIsolationTest, IpdAdmissionServiceTest and
 * IpdConcurrencyIT. This covers only the part none of them touch.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class IpdAdmitRequestPayloadTest {

    private static final AtomicLong SEQ = new AtomicLong();
    private static final List<String> MODULES =
            List.of("APPOINTMENTS", "OPD", "IPD", "BILLING", "MEDICAL_INVENTORY");

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
    @Autowired MedicalRecordRepository medicalRecords;
    @Autowired PrescriptionRepository prescriptions;

    private static long uniq() { return System.nanoTime() + SEQ.incrementAndGet(); }
    private static String phone() { return "9" + String.format("%09d", Math.floorMod(uniq(), 1_000_000_000L)); }

    private Hospital hospital;
    private Doctor doctor;
    private Patient patient;
    private Long bedId;
    private String doctorToken, receptionToken;

    @BeforeEach
    void setUp() {
        hospital = new Hospital();
        hospital.setName("Admit Hospital");
        hospital.setCustomId("ADM-" + uniq());
        hospital.setIsActive(true);
        hospital.setSubscriptionStatus("ACTIVE");
        hospital.setModules(MODULES);
        hospital.setIsSingleDoctor(false);
        hospital.setType(com.hms.entity.HospitalType.HOSPITAL);
        hospital = hospitals.save(hospital);

        String docEmail = "doc." + uniq() + "@admit.test";
        Doctor d = new Doctor();
        d.setHospitalId(hospital.getId());
        d.setName("Dr Admit");
        d.setEmail(docEmail);
        d.setPublicId("dpub-" + uniq());
        d.setPhone(phone());
        d.setSpecialization("General Medicine");
        d.setIsActive(true);
        doctor = doctors.save(d);

        Patient p = new Patient();
        p.setHospitalId(hospital.getId());
        p.setName("Sunita Desai");
        p.setPublicId("ppub-" + uniq());
        p.setGender("FEMALE");
        p.setPhone(phone());
        p.setIsActive(true);
        p.setDateOfBirth(LocalDate.of(1972, 9, 4));
        patient = patients.save(p);

        Ward w = new Ward();
        w.setWardName("General Ward");
        w.setHospitalId(hospital.getId());
        w.setBedPrice(new BigDecimal("1500"));
        w.setTotalBeds(3);
        w = wards.save(w);

        Bed b = new Bed();
        b.setHospitalId(hospital.getId());
        b.setWardId(w.getWardId());
        b.setBedCode("BED-" + uniq());
        b.setStatus("available");
        bedId = beds.save(b).getBedId();

        doctorToken = tokenFor("DOCTOR", docEmail);
        receptionToken = tokenFor("RECEPTIONIST", "rec." + uniq() + "@admit.test");
    }

    @Test
    void theDoctorsRequestReachesReceptionWithoutAdmittingAnyone() {
        Long opdId = startOpdVisit();

        ok(post("/hospital/doctors/consultation", doctorToken, admitPayload(opdId,
                "\"prescription\":[" + prescriptionRow("Paracetamol") + "]")));

        // What the doctor recorded is kept.
        var record = medicalRecords.findByPatientIdOrderByCreatedAtDesc(patient.getId())
                .stream().findFirst().orElseThrow();
        assertThat(record.getDiagnosis()).isEqualTo("Dengue suspected");
        assertThat(prescriptions.findByMedicalRecordIdIn(List.of(record.getId()))
                .stream().map(com.hms.entity.Prescription::getMedicineName))
                .contains("Paracetamol");

        // The request exists, and it is a request.
        assertThat(opds.findById(opdId).orElseThrow().getIpdAdmitRecommended()).isTrue();
        assertThat(get("/hospital/opd/ipd-requests", receptionToken).getBody())
                .as("reception is the one who decides the bed, so reception must see it")
                .contains(patient.getPublicId());

        // And nothing was decided on reception's behalf.
        assertThat(admissions.findByHospitalIdAndPatientId(hospital.getId(), patient.getId()))
                .as("no admission before anyone chose a bed").isEmpty();
        assertThat(beds.findById(bedId).orElseThrow().getStatus())
                .as("and no bed taken").isEqualTo("available");
    }

    /** A double click, or a doctor who presses the button again, must not queue a second bed. */
    @Test
    void askingTwiceIsStillOneRequest() {
        Long opdId = startOpdVisit();
        ok(post("/hospital/doctors/consultation", doctorToken, admitPayload(opdId, "\"prescription\":[]")));

        ResponseEntity<String> again = post("/hospital/doctors/consultation", doctorToken,
                admitPayload(opdId, "\"prescription\":[]"));
        assertThat(again.getStatusCode().value())
                .as("the case is already closed, so the second submission is refused outright")
                .isEqualTo(409);

        String pending = get("/hospital/opd/ipd-requests", receptionToken).getBody();
        assertThat(pending.split("\"caseId\"", -1).length - 1)
                .as("one patient waiting for a bed, not two").isEqualTo(1);
        assertThat(admissions.findByHospitalIdAndPatientId(hospital.getId(), patient.getId())).isEmpty();
    }

    @Test
    void aRefusedRequestLeavesNothingBehind() {
        Long opdId = startOpdVisit();

        // Same button, one bad medicine row: the server refuses the whole thing.
        ResponseEntity<String> res = post("/hospital/doctors/consultation", doctorToken,
                admitPayload(opdId, "\"prescription\":[{\"medicineName\":\"Paracetamol\""
                        + ",\"dosage\":\"\",\"frequency\":\"1-0-1\",\"duration\":\"5 Days\"}]"));
        assertThat(res.getStatusCode().value()).isEqualTo(400);

        assertThat(opds.findById(opdId).orElseThrow().getIpdAdmitRecommended())
                .as("no half-made request for reception to act on").isNotEqualTo(true);
        assertThat(get("/hospital/opd/ipd-requests", receptionToken).getBody())
                .doesNotContain(patient.getPublicId());
        assertThat(medicalRecords.findByPatientIdOrderByCreatedAtDesc(patient.getId())).isEmpty();
        assertThat(beds.findById(bedId).orElseThrow().getStatus()).isEqualTo("available");
    }

    // -- fixtures ----------------------------------------------------------------

    /** The payload ConsultationModal's "Admit to IPD" button builds. */
    private String admitPayload(Long opdId, String prescription) {
        return "{\"appointmentId\":null"
                + ",\"opdId\":" + opdId
                + ",\"patientId\":\"" + patient.getPublicId() + "\""
                + ",\"symptoms\":\"High fever, low platelets\""
                + ",\"diagnosis\":\"Dengue suspected\""
                + ",\"treatmentNotes\":\"Needs inpatient monitoring\""
                + ",\"followUpDate\":\"\""
                + ",\"followUpRequired\":false"
                + ",\"labRequired\":true"
                + ",\"labTests\":[\"CBC\"]"
                + ",\"ipdAdmitRecommended\":true"
                + ",\"administeredItems\":[]"
                + ",\"charges\":[]"
                + ",\"hospitalInventoryItems\":[]"
                + "," + prescription + "}";
    }

    private static String prescriptionRow(String name) {
        return "{\"medicineName\":\"" + name + "\",\"dosage\":\"500mg\""
                + ",\"frequency\":\"Morning, Night\",\"duration\":\"5 Days\""
                + ",\"instructions\":\"After food\"}";
    }

    private Long startOpdVisit() {
        ok(post("/hospital/opd", doctorToken,
                "{\"patientId\":\"" + patient.getPublicId() + "\""
                        + ",\"doctorId\":\"" + doctor.getPublicId() + "\""
                        + ",\"visitType\":\"NEW\",\"problem\":\"Fever\"}"));
        return opds.findAll().stream()
                .filter(o -> o.getPatient() != null && patient.getId().equals(o.getPatient().getId()))
                .findFirst().orElseThrow().getId();
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
}
