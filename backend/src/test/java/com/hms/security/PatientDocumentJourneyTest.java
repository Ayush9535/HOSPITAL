package com.hms.security;

import com.hms.entity.Hospital;
import com.hms.entity.IpdAdmission;
import com.hms.entity.Patient;
import com.hms.entity.PatientDocument;
import com.hms.entity.Bed;
import com.hms.entity.Doctor;
import com.hms.entity.PatientNurseAssignment;
import com.hms.entity.Ward;
import com.hms.entity.User;
import com.hms.repository.HospitalRepository;
import com.hms.repository.IpdAdmissionRepository;
import com.hms.repository.PatientDocumentRepository;
import com.hms.repository.PatientNurseAssignmentRepository;
import com.hms.repository.BedRepository;
import com.hms.repository.DoctorRepository;
import com.hms.repository.PatientRepository;
import com.hms.repository.WardRepository;
import com.hms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One report, carried the whole way: the desk files it, the doctor reads it, the nurse looking
 * after the patient reads it, the doctor archives it, and the patient's timeline still says all
 * of that happened.
 *
 * <p>Each piece has its own suite. This one exists because the pieces are only worth anything in
 * order — a document reception can file but a doctor cannot open is not a feature, and a document
 * that disappears from history when it is archived is a record that lies. Everything goes through
 * the real endpoints with real tokens, so the guards are the product's own.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PatientDocumentJourneyTest {

    private static final AtomicLong SEQ = new AtomicLong();
    private static final List<String> MODULES = List.of("OPD", "IPD", "BILLING", "NURSING");
    private static long uniq() { return System.nanoTime() + SEQ.incrementAndGet(); }

    @Autowired TestRestTemplate rest;
    @Autowired JwtUtil jwtUtil;
    @Autowired HospitalRepository hospitals;
    @Autowired UserRepository users;
    @Autowired PatientRepository patients;
    @Autowired PatientDocumentRepository documents;
    @Autowired IpdAdmissionRepository admissions;
    @Autowired PatientNurseAssignmentRepository assignments;
    @Autowired WardRepository wards;
    @Autowired BedRepository beds;
    @Autowired DoctorRepository doctors;

    private Hospital hospital;
    private Patient patient;
    private Long nurseUserId;
    private String doctorToken, receptionToken, nurseToken;

    @BeforeEach
    void setUp() {
        hospital = tenant("Journey");
        patient = patientIn(hospital, "Asha Rao");
        doctorToken = tokenFor(hospital, "DOCTOR", null);
        receptionToken = tokenFor(hospital, "RECEPTIONIST", null);

        // The nurse may read this patient because she is looking after them, which is the only
        // reason the nursing rules accept.
        Ward ward = new Ward();
        ward.setWardName("General Ward");
        ward.setHospitalId(hospital.getId());
        ward.setBedPrice(new BigDecimal("1500"));
        ward.setTotalBeds(5);
        ward = wards.save(ward);

        Bed bed = new Bed();
        bed.setHospitalId(hospital.getId());
        bed.setWardId(ward.getWardId());
        bed.setBedCode("BED-" + uniq());
        bed.setStatus("occupied");
        bed = beds.save(bed);

        Doctor doctor = new Doctor();
        doctor.setHospitalId(hospital.getId());
        doctor.setName("Dr Journey");
        doctor.setEmail("doc." + uniq() + "@journey.test");
        doctor.setPublicId("dpub-" + uniq());
        doctor.setPhone("9800000060");
        doctor.setSpecialization("General Medicine");
        doctor.setIsActive(true);
        doctor = doctors.save(doctor);

        IpdAdmission admission = new IpdAdmission();
        admission.setDoctorId(doctor.getId());
        admission.setWardId(ward.getWardId());
        admission.setBedId(bed.getBedId());
        admission.setHospitalId(hospital.getId());
        admission.setPatientId(patient.getId());
        admission.setIpdNumber("IPD-" + uniq());
        admission.setStatus("ADMITTED");
        admission.setAdmissionType("ELECTIVE");
        admission.setAdmissionDatetime(LocalDateTime.now().minusDays(1));
        admission = admissions.save(admission);

        User nurse = userFor(hospital, "NURSE");
        nurseUserId = nurse.getId();
        nurseToken = jwtUtil.generateToken(nurse.getId(), nurse.getEmail(), "NURSE",
                hospital.getId(), MODULES, null, "HOSPITAL", null, 0);

        PatientNurseAssignment assignment = new PatientNurseAssignment();
        assignment.setHospitalId(hospital.getId());
        assignment.setIpdAdmissionId(admission.getId());
        assignment.setPatientId(patient.getId());
        assignment.setNurseUserId(nurse.getId());
        assignment.setAssignedByUserId(nurse.getId());
        assignment.setAssignedAt(LocalDateTime.now().minusHours(2));
        assignment.setIsActive(true);
        assignments.save(assignment);
    }

    // -- the journey -------------------------------------------------------------

    @Test
    void aReportFiledAtTheDeskIsReadableByTheDoctorAndTheNurseLookingAfterThePatient() {
        assertThat(uploadPdf(patient.getId(), receptionToken).getStatusCode().value())
                .as("reception takes the paperwork at the desk").isEqualTo(200);

        String publicId = onlyActiveDocument().getPublicId();

        assertThat(listAs(doctorToken)).contains(publicId);
        assertThat(get("/hospital/patient-documents/" + publicId + "/content", doctorToken)
                .getStatusCode().value()).as("the doctor opens what the desk filed").isEqualTo(200);

        assertThat(listAs(nurseToken)).as("the nurse looking after them sees it too").contains(publicId);
        assertThat(get("/hospital/patient-documents/" + publicId + "/content", nurseToken)
                .getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void onlyTheRolesThatShouldArchiveCanArchive() {
        uploadPdf(patient.getId(), receptionToken);
        String publicId = onlyActiveDocument().getPublicId();

        assertThat(archive(publicId, receptionToken).getStatusCode().value())
                .as("filing paperwork is not the same as deciding what leaves the record")
                .isEqualTo(403);
        assertThat(archive(publicId, nurseToken).getStatusCode().value()).isEqualTo(403);
        assertThat(documents.findByPublicIdAndHospitalId(publicId, hospital.getId()).orElseThrow()
                .getIsActive()).as("a refused archive changes nothing").isTrue();

        assertThat(archive(publicId, doctorToken).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void anArchivedReportLeavesTheWorkingRecordAndCannotBeOpenedAgain() {
        uploadPdf(patient.getId(), receptionToken);
        String publicId = onlyActiveDocument().getPublicId();

        assertThat(archive(publicId, doctorToken).getStatusCode().value()).isEqualTo(200);

        assertThat(listAs(doctorToken)).doesNotContain(publicId);
        assertThat(listAs(nurseToken)).doesNotContain(publicId);
        assertThat(get("/hospital/patient-documents/" + publicId + "/content", doctorToken)
                .getStatusCode().value())
                .as("archived means out of reach by id as well, not merely hidden from a list")
                .isNotEqualTo(200);
    }

    // -- timeline ----------------------------------------------------------------

    @Test
    void theTimelineRecordsTheFilingAndThenTheArchiving() {
        uploadPdf(patient.getId(), receptionToken);
        PatientDocument doc = onlyActiveDocument();

        String timeline = timelineAs(doctorToken);
        assertThat(timeline).contains("DOCUMENT_UPLOADED");
        assertThat(timeline).as("clinical metadata, and the person who filed it")
                .contains("Outside blood report").contains(PatientDocument.PATHOLOGY_REPORT);
        assertThat(timeline).as("the row points back at the document, it does not copy it")
                .contains("\"sourceType\":\"PatientDocument\"")
                .contains("\"sourceId\":" + doc.getId());

        archive(doc.getPublicId(), doctorToken);

        String after = timelineAs(doctorToken);
        assertThat(after).as("archiving is another event, not an erasure")
                .contains("DOCUMENT_UPLOADED").contains("DOCUMENT_ARCHIVED");
        assertThat(after).contains("Filed against the wrong patient");
    }

    @Test
    void theTimelineNeverCarriesAnythingAboutWhereTheFileLives() {
        uploadPdf(patient.getId(), receptionToken);
        PatientDocument doc = onlyActiveDocument();

        String timeline = timelineAs(doctorToken);
        assertThat(timeline).doesNotContain("storageKey");
        assertThat(timeline).as("nor the key itself, however it were spelled")
                .doesNotContain(doc.getStorageKey());
    }

    /** No document, no history: the timeline is derived from the rows, so it cannot get ahead. */
    @Test
    void aFailedUploadLeavesNoDocumentAndNoTimelineEntry() {
        // Refused by the server's own signature check -- the bytes are not a PDF.
        ResponseEntity<String> refused = upload(patient.getId(), receptionToken, "report.pdf",
                "application/pdf", "this is not a pdf".getBytes(), PatientDocument.PATHOLOGY_REPORT);
        assertThat(refused.getStatusCode().value()).isNotEqualTo(200);

        assertThat(documents.findByHospitalIdAndPatientIdOrderByIdAsc(hospital.getId(), patient.getId()))
                .as("nothing was recorded").isEmpty();
        assertThat(timelineAs(doctorToken)).doesNotContain("DOCUMENT_UPLOADED");
    }

    // -- tenant isolation --------------------------------------------------------

    @Test
    void anotherHospitalCanNeitherSeeNorArchiveThisPatientsDocument() {
        uploadPdf(patient.getId(), receptionToken);
        String publicId = onlyActiveDocument().getPublicId();

        Hospital other = tenant("Beta");
        String otherDoctor = tokenFor(other, "DOCTOR", null);

        assertThat(get("/hospital/patients/" + patient.getId() + "/documents", otherDoctor)
                .getStatusCode().value())
                .as("another facility's patient is not their patient").isNotEqualTo(200);
        assertThat(get("/hospital/patient-documents/" + publicId + "/content", otherDoctor)
                .getStatusCode().value())
                .as("knowing the id is never enough").isNotEqualTo(200);
        assertThat(archive(publicId, otherDoctor).getStatusCode().value()).isNotEqualTo(200);

        assertThat(documents.findByPublicIdAndHospitalId(publicId, hospital.getId()).orElseThrow()
                .getIsActive()).isTrue();
        assertThat(timelineResponse(otherDoctor).getStatusCode().value())
                .as("and the timeline is not a way round either").isNotEqualTo(200);
    }

    // -- fixtures ----------------------------------------------------------------

    private Hospital tenant(String label) {
        Hospital h = new Hospital();
        h.setName("Journey " + label);
        h.setCustomId("JRN-" + uniq());
        h.setIsActive(true);
        h.setSubscriptionStatus("ACTIVE");
        h.setModules(MODULES);
        h.setIsSingleDoctor(false);
        h.setType(com.hms.entity.HospitalType.HOSPITAL);
        return hospitals.save(h);
    }

    private User userFor(Hospital h, String role) {
        User u = new User();
        u.setEmail(role.toLowerCase() + "." + uniq() + "@journey.test");
        u.setPassword("{noop}fixture");
        u.setName("User " + role);
        u.setRole(role);
        u.setHospitalId(h.getId());
        u.setIsActive(true);
        u.setTokenVersion(0);
        return users.save(u);
    }

    private String tokenFor(Hospital h, String role, Object unused) {
        User u = userFor(h, role);
        return jwtUtil.generateToken(u.getId(), u.getEmail(), role, h.getId(),
                MODULES, null, "HOSPITAL", null, 0);
    }

    private Patient patientIn(Hospital h, String name) {
        Patient p = new Patient();
        p.setHospitalId(h.getId());
        p.setName(name);
        p.setPublicId("ppub-" + uniq());
        p.setGender("FEMALE");
        p.setPhone("9" + String.format("%09d", Math.floorMod(uniq(), 1000000000L)));
        p.setIsActive(true);
        p.setDateOfBirth(LocalDate.of(1980, 1, 1));
        return patients.save(p);
    }

    private PatientDocument onlyActiveDocument() {
        return documents.findByHospitalIdAndPatientIdAndIsActiveTrueOrderByReportDateDescIdDesc(
                hospital.getId(), patient.getId()).get(0);
    }

    private HttpHeaders auth(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    private ResponseEntity<String> get(String path, String token) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(auth(token)), String.class);
    }

    private String listAs(String token) {
        ResponseEntity<String> res = get("/hospital/patients/" + patient.getId() + "/documents", token);
        assertThat(res.getStatusCode().value()).as("%s", res.getBody()).isEqualTo(200);
        return res.getBody();
    }

    /** The timeline is addressed by the patient's public id, like the rest of that controller. */
    private ResponseEntity<String> timelineResponse(String token) {
        return get("/hospital/patients/" + patient.getPublicId() + "/timeline", token);
    }

    private String timelineAs(String token) {
        ResponseEntity<String> res = timelineResponse(token);
        assertThat(res.getStatusCode().value()).as("%s", res.getBody()).isEqualTo(200);
        return res.getBody();
    }

    private ResponseEntity<String> archive(String publicId, String token) {
        HttpHeaders headers = auth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("/hospital/patient-documents/" + publicId + "/archive", HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "Filed against the wrong patient"), headers),
                String.class);
    }

    private ResponseEntity<String> upload(Long patientId, String token, String fileName,
                                          String contentType, byte[] content, String type) {
        ByteArrayResource file = new ByteArrayResource(content) {
            @Override public String getFilename() { return fileName; }
        };
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType(contentType));

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(file, partHeaders));
        body.add("documentType", type);
        body.add("title", "Outside blood report");
        body.add("source", "City Lab");

        HttpHeaders headers = auth(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return rest.exchange("/hospital/patients/" + patientId + "/documents", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> uploadPdf(Long patientId, String token) {
        byte[] pdf = new byte[64];
        pdf[0] = 0x25; pdf[1] = 0x50; pdf[2] = 0x44; pdf[3] = 0x46; // %PDF
        return upload(patientId, token, "report.pdf", "application/pdf", pdf,
                PatientDocument.PATHOLOGY_REPORT);
    }
}
