package com.hms.security;

import com.hms.entity.Doctor;
import com.hms.entity.Hospital;
import com.hms.entity.MedicalRecord;
import com.hms.entity.Medicine;
import com.hms.entity.Opd;
import com.hms.entity.Patient;
import com.hms.entity.User;
import com.hms.repository.AppointmentRepository;
import com.hms.repository.BillingRepository;
import com.hms.repository.DoctorRepository;
import com.hms.repository.HospitalRepository;
import com.hms.repository.MedicalRecordRepository;
import com.hms.repository.MedicineRepository;
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
 * The consultation the product actually sends, rather than the tidy one a test would invent.
 *
 * <p>ConsultationModal.handleSubmit posts far more than a diagnosis and a prescription: lab
 * flags, administered medicines that move real stock and money, ad-hoc charges, inventory items,
 * and a couple of fields the DTO has never had. Everything covered before stopped at diagnosis
 * and free-text prescriptions, so the parts that touch inventory -- the parts a demo shows off --
 * had no test at all. These use the frontend's own payload shapes, field for field.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ConsultationRealPayloadTest {

    private static final AtomicLong SEQ = new AtomicLong();
    private static final List<String> MODULES =
            List.of("APPOINTMENTS", "OPD", "IPD", "BILLING", "PHARMACY", "MEDICAL_INVENTORY");

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
    @Autowired MedicineRepository medicines;
    @Autowired BillingRepository billings;

    private static long uniq() { return System.nanoTime() + SEQ.incrementAndGet(); }
    private static String phone() { return "9" + String.format("%09d", Math.floorMod(uniq(), 1_000_000_000L)); }

    private Hospital hospital;
    private Doctor doctor;
    private Patient patient;
    private String doctorToken, receptionToken;

    @BeforeEach
    void setUp() {
        hospital = hospitalNamed("Payload");
        String docEmail = "doc." + uniq() + "@payload.test";
        doctor = doctorIn(hospital, docEmail);
        patient = patientIn(hospital);
        doctorToken = tokenFor(hospital, "DOCTOR", docEmail);
        receptionToken = tokenFor(hospital, "RECEPTIONIST", "rec." + uniq() + "@payload.test");
    }

    // -- the shapes the UI sends --------------------------------------------------

    @Test
    void aConsultationWithNoPrescriptionAtAll() {
        Long opdId = startOpdVisit();
        ok(consult(uiPayload(opdId, "\"prescription\":[]")));

        assertThat(opds.findById(opdId).orElseThrow().getStatus()).isEqualTo(Opd.Status.COMPLETED);
        assertThat(latestRecord().getDiagnosis()).isEqualTo("Viral fever");
    }

    @Test
    void aConsultationWithOneFreeTextPrescription() {
        Long opdId = startOpdVisit();
        ok(consult(uiPayload(opdId, "\"prescription\":[" + prescriptionRow("Paracetamol", "500mg") + "]")));

        assertThat(opds.findById(opdId).orElseThrow().getStatus()).isEqualTo(Opd.Status.COMPLETED);
        assertThat(prescribedNames()).containsExactly("Paracetamol");
    }

    @Test
    void aConsultationWithSeveralPrescriptions() {
        Long opdId = startOpdVisit();
        ok(consult(uiPayload(opdId, "\"prescription\":["
                + prescriptionRow("Paracetamol", "500mg") + ","
                + prescriptionRow("Azithromycin", "250mg") + ","
                + prescriptionRow("Pantoprazole", "40mg") + "]")));

        assertThat(prescribedNames())
                .containsExactlyInAnyOrder("Paracetamol", "Azithromycin", "Pantoprazole");
        assertThat(opds.findById(opdId).orElseThrow().getStatus()).isEqualTo(Opd.Status.COMPLETED);
    }

    /** The path a demo actually walks: a medicine picked from inventory, given at the clinic. */
    @Test
    void aConsultationThatAdministersAMedicineFromInventory() {
        Medicine med = medicine("Paracetamol 500", 100, 2.50);
        Long opdId = startOpdVisit();

        ok(consult(uiPayload(opdId, "\"prescription\":[" + prescriptionRow("Paracetamol", "500mg") + "]"
                + ",\"administeredItems\":[" + administeredRow(med.getId(), med.getName(), 4) + "]")));

        assertThat(medicines.findById(med.getId()).orElseThrow().getStockQuantity())
                .as("stock moves exactly once, by exactly what was given").isEqualTo(96);
        assertThat(opds.findById(opdId).orElseThrow().getStatus()).isEqualTo(Opd.Status.COMPLETED);
        assertThat(latestRecord().getAdministeredItemsJson())
                .as("what was given is recorded on the case").contains(med.getName());
        assertThat(billings.findByOpdId(opdId)).as("and it reaches a bill").isPresent();
    }

    @Test
    void aConsultationThatAdministersSeveralMedicines() {
        Medicine one = medicine("Paracetamol 500", 50, 2.50);
        Medicine two = medicine("Ondansetron 4", 30, 6.00);
        Long opdId = startOpdVisit();

        ok(consult(uiPayload(opdId, "\"administeredItems\":["
                + administeredRow(one.getId(), one.getName(), 2) + ","
                + administeredRow(two.getId(), two.getName(), 3) + "]")));

        assertThat(medicines.findById(one.getId()).orElseThrow().getStockQuantity()).isEqualTo(48);
        assertThat(medicines.findById(two.getId()).orElseThrow().getStockQuantity()).isEqualTo(27);
    }

    @Test
    void theAppointmentPathCarriesTheSamePayload() {
        Long appointmentId = bookAppointment();
        Medicine med = medicine("Paracetamol 500", 20, 2.50);

        ok(consult("{\"appointmentId\":" + appointmentId
                + ",\"opdId\":null"
                + ",\"patientId\":\"" + patient.getPublicId() + "\""
                + ",\"symptoms\":\"Fever since 2 days\""
                + ",\"diagnosis\":\"Viral fever\""
                + ",\"treatmentNotes\":\"Rest and fluids\""
                + ",\"followUpDate\":\"\""
                + ",\"followUpRequired\":false"
                + ",\"labRequired\":true"
                + ",\"labTests\":[\"CBC\",\"RBS\"]"
                + ",\"prescription\":[" + prescriptionRow("Paracetamol", "500mg") + "]"
                + ",\"administeredItems\":[" + administeredRow(med.getId(), med.getName(), 1) + "]"
                + ",\"charges\":[{\"description\":\"Dressing\",\"amount\":150}]"
                + ",\"hospitalInventoryItems\":[]}"));

        assertThat(appointments.findById(appointmentId).orElseThrow().getStatus()).isEqualTo("COMPLETED");
        assertThat(medicines.findById(med.getId()).orElseThrow().getStockQuantity()).isEqualTo(19);
        assertThat(opds.findAll().stream()
                .filter(o -> o.getPatient() != null && patient.getId().equals(o.getPatient().getId()))
                .findFirst().orElseThrow().getStatus()).isEqualTo(Opd.Status.COMPLETED);
    }

    /**
     * The UI sends followUpRequired, which the DTO has never had, and omits followUpInstructions,
     * which it does. Neither may be fatal: a payload the product sends must not be rejected for
     * carrying a field the server stopped caring about.
     */
    @Test
    void fieldsTheUiSendsButTheServerDoesNotKnowAreIgnoredRatherThanFatal() {
        Long opdId = startOpdVisit();
        ok(consult(uiPayload(opdId, "\"followUpRequired\":true"
                + ",\"someFieldAddedByAFutureUi\":\"whatever\"")));

        assertThat(opds.findById(opdId).orElseThrow().getStatus()).isEqualTo(Opd.Status.COMPLETED);
    }

    // -- refusals ----------------------------------------------------------------

    @Test
    void anInactiveMedicineIsRefusedRatherThanSilentlyNotGiven() {
        Medicine med = medicine("Retired Syrup", 10, 3.00);
        med.setIsActive(false);
        medicines.save(med);
        Long opdId = startOpdVisit();

        ResponseEntity<String> res = consult(uiPayload(opdId,
                "\"administeredItems\":[" + administeredRow(med.getId(), med.getName(), 1) + "]"));

        assertThat(res.getStatusCode().value()).as("%s", res.getBody()).isEqualTo(400);
        assertThat(res.getBody()).contains("inactive");
        assertThat(medicines.findById(med.getId()).orElseThrow().getStockQuantity())
                .as("and nothing moved").isEqualTo(10);
    }

    @Test
    void aMedicineFromAnotherFacilityIsRefused() {
        Hospital other = hospitalNamed("Beta");
        Medicine theirs = new Medicine();
        theirs.setName("Their Paracetamol");
        theirs.setHospitalId(other.getId());
        theirs.setStockQuantity(100);
        theirs.setUnitPrice(2.0);
        theirs.setIsActive(true);
        theirs = medicines.save(theirs);
        Long opdId = startOpdVisit();

        ResponseEntity<String> res = consult(uiPayload(opdId,
                "\"administeredItems\":[" + administeredRow(theirs.getId(), theirs.getName(), 1) + "]"));

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(medicines.findById(theirs.getId()).orElseThrow().getStockQuantity())
                .as("another facility's stock is untouchable").isEqualTo(100);
    }

    @Test
    void aMedicineWithNoUsablePriceIsRefused() {
        Medicine med = medicine("Unpriced Tonic", 10, null);
        Long opdId = startOpdVisit();

        ResponseEntity<String> res = consult(uiPayload(opdId,
                "\"administeredItems\":[" + administeredRow(med.getId(), med.getName(), 1) + "]"));

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getBody()).contains("unit price");
    }

    @Test
    void givingMoreThanIsInStockIsRefused() {
        Medicine med = medicine("Paracetamol 500", 2, 2.50);
        Long opdId = startOpdVisit();

        ResponseEntity<String> res = consult(uiPayload(opdId,
                "\"administeredItems\":[" + administeredRow(med.getId(), med.getName(), 5) + "]"));

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getBody()).contains("Insufficient stock");
        assertThat(medicines.findById(med.getId()).orElseThrow().getStockQuantity()).isEqualTo(2);
    }

    /**
     * A refusal must be a refusal, whatever the exception happened to carry.
     *
     * <p>The classifier used to read e.getMessage() and match text on it. A cause with no message
     * threw a NullPointerException from inside the catch, so a 400 reached the doctor as a 500 --
     * "server error" for what was really "there are only two left".
     */
    @Test
    void aRefusalIsNeverReportedAsAServerError() {
        Medicine med = medicine("Paracetamol 500", 1, 2.50);
        Long opdId = startOpdVisit();

        ResponseEntity<String> res = consult(uiPayload(opdId,
                "\"administeredItems\":[" + administeredRow(med.getId(), med.getName(), 9) + "]"));

        assertThat(res.getStatusCode().value()).as("never a 500").isLessThan(500);
    }

    @Test
    void aPrescriptionRowMissingItsDurationIsRefused() {
        Long opdId = startOpdVisit();

        ResponseEntity<String> res = consult(uiPayload(opdId,
                "\"prescription\":[{\"medicineName\":\"Paracetamol\",\"dosage\":\"500mg\""
                        + ",\"frequency\":\"1-0-1\",\"duration\":\"\",\"instructions\":\"\"}]"));

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getBody()).contains("duration");
        assertThat(opds.findById(opdId).orElseThrow().getStatus())
                .as("and the case stays open for the doctor to correct it")
                .isNotEqualTo(Opd.Status.COMPLETED);
    }

    // -- fixtures ----------------------------------------------------------------

    /** The payload ConsultationModal builds, with the parts under test substituted in. */
    private String uiPayload(Long opdId, String... extras) {
        StringBuilder b = new StringBuilder("{\"appointmentId\":null")
                .append(",\"opdId\":").append(opdId)
                .append(",\"patientId\":\"").append(patient.getPublicId()).append("\"")
                .append(",\"symptoms\":\"Fever since 2 days\"")
                .append(",\"diagnosis\":\"Viral fever\"")
                .append(",\"treatmentNotes\":\"Rest and fluids\"")
                .append(",\"followUpDate\":\"\"")
                .append(",\"labRequired\":false")
                .append(",\"labTests\":[]")
                .append(",\"charges\":[]")
                .append(",\"hospitalInventoryItems\":[]");
        for (String extra : extras) b.append(",").append(extra);
        return b.append("}").toString();
    }

    private static String prescriptionRow(String name, String dosage) {
        return "{\"medicineName\":\"" + name + "\",\"dosage\":\"" + dosage + "\""
                + ",\"frequency\":\"Morning, Night\",\"duration\":\"5 Days\""
                + ",\"instructions\":\"After food\"}";
    }

    private static String administeredRow(Long medicineId, String name, int quantity) {
        return "{\"medicineId\":" + medicineId + ",\"medicineName\":\"" + name + "\""
                + ",\"quantity\":" + quantity + ",\"dosage\":\"\",\"frequency\":\"As Per Required\""
                + ",\"duration\":\"\",\"instructions\":\"\"}";
    }

    private Medicine medicine(String name, int stock, Double price) {
        Medicine m = new Medicine();
        m.setName(name + " " + uniq());
        m.setHospitalId(hospital.getId());
        m.setStockQuantity(stock);
        m.setUnitPrice(price);
        m.setIsActive(true);
        return medicines.save(m);
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

    private Long bookAppointment() {
        ok(post("/hospital/appointments", receptionToken,
                "{\"doctorId\":" + doctor.getId() + ",\"patientId\":" + patient.getId()
                        + ",\"appointmentDate\":\"" + LocalDate.now() + "\""
                        + ",\"appointmentTime\":\"23:45\"}"));
        return appointments.findAll().stream()
                .filter(a -> patient.getId().equals(a.getPatientId()))
                .findFirst().orElseThrow().getId();
    }

    private MedicalRecord latestRecord() {
        return medicalRecords.findByPatientIdOrderByCreatedAtDesc(patient.getId())
                .stream().findFirst().orElseThrow();
    }

    private List<String> prescribedNames() {
        return prescriptions.findByMedicalRecordIdIn(List.of(latestRecord().getId()))
                .stream().map(com.hms.entity.Prescription::getMedicineName).toList();
    }

    private Hospital hospitalNamed(String label) {
        Hospital h = new Hospital();
        h.setName("Payload " + label);
        h.setCustomId("PLD-" + uniq());
        h.setIsActive(true);
        h.setSubscriptionStatus("ACTIVE");
        h.setModules(MODULES);
        h.setIsSingleDoctor(false);
        h.setType(com.hms.entity.HospitalType.HOSPITAL);
        return hospitals.save(h);
    }

    private Doctor doctorIn(Hospital h, String email) {
        Doctor d = new Doctor();
        d.setHospitalId(h.getId());
        d.setName("Dr Payload");
        d.setEmail(email);
        d.setPublicId("dpub-" + uniq());
        d.setPhone(phone());
        d.setSpecialization("General Medicine");
        d.setIsActive(true);
        return doctors.save(d);
    }

    private Patient patientIn(Hospital h) {
        Patient p = new Patient();
        p.setHospitalId(h.getId());
        p.setName("Ravi Kumar");
        p.setPublicId("ppub-" + uniq());
        p.setGender("MALE");
        p.setPhone(phone());
        p.setIsActive(true);
        p.setDateOfBirth(LocalDate.of(1979, 3, 11));
        return patients.save(p);
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

    private HttpHeaders headers(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private ResponseEntity<String> post(String path, String token, String body) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers(token)), String.class);
    }

    private ResponseEntity<String> consult(String body) {
        return post("/hospital/doctors/consultation", doctorToken, body);
    }

    private static void ok(ResponseEntity<String> res) {
        assertThat(res.getStatusCode().value()).as("%s", res.getBody()).isEqualTo(200);
    }
}
