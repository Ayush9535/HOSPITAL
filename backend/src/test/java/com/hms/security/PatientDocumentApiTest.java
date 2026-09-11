package com.hms.security;

import com.hms.entity.Hospital;
import com.hms.entity.Patient;
import com.hms.entity.PatientDocument;
import com.hms.entity.User;
import com.hms.repository.HospitalRepository;
import com.hms.repository.PatientDocumentRepository;
import com.hms.repository.PatientRepository;
import com.hms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Documents a patient brought in: who may file one, who may read it, and the fact that knowing an
 * identifier is never enough.
 *
 * <p>The point of the whole design is that a blood report has no URL. These go through the real
 * endpoints, so a document uploaded here is only retrievable by a caller the server agrees should
 * see it -- and a caller from another facility gets the same answer as one asking for a document
 * that does not exist.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PatientDocumentApiTest {

    private static final AtomicLong SEQ = new AtomicLong();
    private static final List<String> MODULES = List.of("OPD", "IPD", "BILLING");
    private static long uniq() { return System.nanoTime() + SEQ.incrementAndGet(); }

    @Autowired TestRestTemplate rest;
    @Autowired JwtUtil jwtUtil;
    @Autowired HospitalRepository hospitals;
    @Autowired UserRepository users;
    @Autowired PatientRepository patients;
    @Autowired PatientDocumentRepository documents;

    private Hospital hospital;
    private Patient patient;
    private String adminToken, doctorToken, receptionToken, nurseToken, pharmacistToken;

    private Hospital tenant(String label) {
        Hospital h = new Hospital();
        h.setName("Doc " + label);
        h.setCustomId("DOC-" + uniq());
        h.setIsActive(true);
        h.setSubscriptionStatus("ACTIVE");
        h.setModules(MODULES);
        h.setIsSingleDoctor(false);
        h.setType(com.hms.entity.HospitalType.HOSPITAL);
        return hospitals.save(h);
    }

    private String tokenFor(Hospital h, String role) {
        User u = new User();
        u.setEmail(role.toLowerCase() + "." + uniq() + "@doc.test");
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

    private Patient patientIn(Hospital h, String name) {
        Patient p = new Patient();
        p.setHospitalId(h.getId());
        p.setName(name);
        p.setPublicId("ppub-" + uniq());
        p.setGender("MALE");
        p.setPhone("9" + String.format("%09d", Math.floorMod(uniq(), 1000000000L)));
        p.setIsActive(true);
        p.setDateOfBirth(LocalDate.of(1980, 1, 1));
        return patients.save(p);
    }

    @BeforeEach
    void setUp() {
        hospital = tenant("Alpha");
        patient = patientIn(hospital, "Asha Rao");
        adminToken = tokenFor(hospital, "HOSPITAL_ADMIN");
        doctorToken = tokenFor(hospital, "DOCTOR");
        receptionToken = tokenFor(hospital, "RECEPTIONIST");
        nurseToken = tokenFor(hospital, "NURSE");
        pharmacistToken = tokenFor(hospital, "PHARMACIST");
    }

    private HttpHeaders auth(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    /** A real multipart upload, as a browser would send one. */
    private ResponseEntity<String> upload(Long patientId, String token, String fileName,
                                          String contentType, byte[] content, String type,
                                          MultiValueMap<String, String> extra) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        ByteArrayResource file = new ByteArrayResource(content) {
            @Override public String getFilename() { return fileName; }
        };
        body.add("file", file);
        body.add("documentType", type);
        body.add("title", "Outside blood report");
        if (extra != null) extra.forEach((k, v) -> v.forEach(one -> body.add(k, one)));

        HttpHeaders headers = auth(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        // Spring needs the part's own content type to reach the server.
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType(contentType));
        MultiValueMap<String, Object> typed = new LinkedMultiValueMap<>();
        typed.add("file", new HttpEntity<>(file, partHeaders));
        body.remove("file");
        body.forEach((k, v) -> v.forEach(one -> typed.add(k, one)));

        return rest.exchange("/hospital/patients/" + patientId + "/documents",
                HttpMethod.POST, new HttpEntity<>(typed, headers), String.class);
    }

    /** Real leading bytes: the server now checks these, not just the declared type. */
    private static byte[] pdfBytes() {
        byte[] b = new byte[64];
        b[0] = 0x25; b[1] = 0x50; b[2] = 0x44; b[3] = 0x46; // %PDF
        return b;
    }

    private static byte[] jpegBytes() {
        byte[] b = new byte[64];
        b[0] = (byte) 0xFF; b[1] = (byte) 0xD8; b[2] = (byte) 0xFF;
        return b;
    }

    private static byte[] pngBytes() {
        byte[] b = new byte[64];
        byte[] sig = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(sig, 0, b, 0, sig.length);
        return b;
    }

    private static byte[] webpBytes() {
        byte[] b = new byte[64];
        byte[] riff = {0x52, 0x49, 0x46, 0x46};
        byte[] webp = {0x57, 0x45, 0x42, 0x50};
        System.arraycopy(riff, 0, b, 0, 4);
        System.arraycopy(webp, 0, b, 8, 4);
        return b;
    }

    private ResponseEntity<String> uploadPdf(Long patientId, String token) {
        return upload(patientId, token, "report.pdf", "application/pdf",
                pdfBytes(), PatientDocument.PATHOLOGY_REPORT, null);
    }

    private ResponseEntity<String> get(String path, String token) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(auth(token)), String.class);
    }

    /** Scoped to this patient: the table is shared with every other test in the class. */
    private long documentsFor(Patient p) {
        return documents.findByHospitalIdAndPatientIdAndIsActiveTrueOrderByReportDateDescIdDesc(
                hospital.getId(), p.getId()).size();
    }

    private String publicIdOf(Patient p) {
        return documents.findByHospitalIdAndPatientIdAndIsActiveTrueOrderByReportDateDescIdDesc(
                hospital.getId(), p.getId()).get(0).getPublicId();
    }

    // -- uploading ---------------------------------------------------------------

    @Test
    void aPdfCanBeFiledAgainstAPatient() {
        ResponseEntity<String> res = uploadPdf(patient.getId(), receptionToken);
        assertThat(res.getStatusCode().value()).as("%s", res.getBody()).isEqualTo(200);

        var saved = documents.findByHospitalIdAndPatientIdAndIsActiveTrueOrderByReportDateDescIdDesc(
                hospital.getId(), patient.getId());
        assertThat(saved).hasSize(1);
        PatientDocument doc = saved.get(0);
        assertThat(doc.getMimeType()).isEqualTo("application/pdf");
        assertThat(doc.getOriginalFileName()).isEqualTo("report.pdf");
        assertThat(doc.getFileSizeBytes()).isPositive();
        assertThat(doc.getStorageKey()).isNotBlank();
        assertThat(doc.getStorageKey())
                .as("a storage handle, never a URL").doesNotContain("http").doesNotContain("://");
    }

    @Test
    void imagesFromAPhoneAreOrdinaryUploads() {
        Object[][] kinds = {
                {"scan.jpg", "image/jpeg", jpegBytes()},
                {"scan.png", "image/png", pngBytes()},
                {"scan.webp", "image/webp", webpBytes()}};
        for (Object[] kind : kinds) {
            Patient p = patientIn(hospital, "P " + uniq());
            ResponseEntity<String> res = upload(p.getId(), receptionToken, (String) kind[0],
                    (String) kind[1], (byte[]) kind[2], PatientDocument.OTHER, null);
            assertThat(res.getStatusCode().value()).as("%s -> %s", kind[1], res.getBody()).isEqualTo(200);
        }
    }

    @Test
    void anExecutableIsRefused() {
        ResponseEntity<String> res = upload(patient.getId(), receptionToken, "evil.exe",
                "application/octet-stream", "MZ".getBytes(), PatientDocument.OTHER, null);
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(documentsFor(patient)).isZero();
    }

    @Test
    void aRenamedFileWhoseTypeDisagreesWithItsNameIsRefused() {
        ResponseEntity<String> res = upload(patient.getId(), receptionToken, "report.exe",
                "application/pdf", pdfBytes(), PatientDocument.OTHER, null);
        assertThat(res.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void anEmptyFileIsRefused() {
        ResponseEntity<String> res = upload(patient.getId(), receptionToken, "empty.pdf",
                "application/pdf", new byte[0], PatientDocument.OTHER, null);
        assertThat(res.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void anUnknownDocumentTypeIsRefused() {
        ResponseEntity<String> res = upload(patient.getId(), receptionToken, "r.pdf",
                "application/pdf", pdfBytes(), "MADE_UP_TYPE", null);
        assertThat(res.getStatusCode().value()).isEqualTo(400);
    }

    // -- roles -------------------------------------------------------------------

    @Test
    void theRolesThatMeetPatientsCanFileDocuments() {
        for (String token : new String[]{receptionToken, doctorToken, adminToken}) {
            Patient p = patientIn(hospital, "P " + uniq());
            assertThat(uploadPdf(p.getId(), token).getStatusCode().value()).isEqualTo(200);
        }
    }

    /**
     * A nurse never files documents. Whether they may read one is a separate question, decided by
     * the nursing rules -- see aNurseWithNoRelationshipToThePatientIsRefused.
     */
    @Test
    void aNurseCannotFileDocuments() {
        assertThat(uploadPdf(patient.getId(), nurseToken).getStatusCode().value()).isEqualTo(403);
        assertThat(documentsFor(patient)).isZero();
    }

    @Test
    void aPharmacistHasNoAccessAtAll() {
        uploadPdf(patient.getId(), receptionToken);
        assertThat(uploadPdf(patient.getId(), pharmacistToken).getStatusCode().value()).isEqualTo(403);
        assertThat(get("/hospital/patients/" + patient.getId() + "/documents", pharmacistToken)
                .getStatusCode().value()).isEqualTo(403);
        assertThat(get("/hospital/patient-documents/" + publicIdOf(patient) + "/content", pharmacistToken)
                .getStatusCode().value()).isEqualTo(403);
    }

    // -- reading back ------------------------------------------------------------

    @Test
    void theListDescribesTheDocumentWithoutRevealingWhereItLives() {
        uploadPdf(patient.getId(), receptionToken);
        ResponseEntity<String> res = get("/hospital/patients/" + patient.getId() + "/documents", doctorToken);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).contains("Outside blood report").contains("report.pdf");
        assertThat(res.getBody())
                .as("the storage handle is not the client's business")
                .doesNotContain("storageKey").doesNotContain("/var/").doesNotContain("tmp");
    }

    @Test
    void theContentComesBackThroughTheApplication() {
        uploadPdf(patient.getId(), receptionToken);
        ResponseEntity<String> res =
                get("/hospital/patient-documents/" + publicIdOf(patient) + "/content", doctorToken);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).startsWith("%PDF");
        assertThat(res.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("report.pdf");
        assertThat(res.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).contains("no-store");
    }

    // -- tenancy: the whole point ------------------------------------------------

    @Test
    void anotherFacilityCannotFileAgainstThisPatient() {
        Hospital other = tenant("Bravo");
        String theirReception = tokenFor(other, "RECEPTIONIST");

        assertThat(uploadPdf(patient.getId(), theirReception).getStatusCode().value()).isEqualTo(404);
        assertThat(documentsFor(patient)).as("nothing was filed").isZero();
    }

    @Test
    void knowingTheDocumentIdIsNotEnoughFromAnotherFacility() {
        uploadPdf(patient.getId(), receptionToken);
        String id = publicIdOf(patient);

        Hospital other = tenant("Bravo");
        String theirDoctor = tokenFor(other, "DOCTOR");

        assertThat(get("/hospital/patient-documents/" + id + "/content", theirDoctor)
                .getStatusCode().value())
                .as("indistinguishable from a document that does not exist").isEqualTo(404);
        assertThat(get("/hospital/patients/" + patient.getId() + "/documents", theirDoctor)
                .getStatusCode().value()).isEqualTo(404);
    }

    /** The client never names a file, so there is nothing for it to tamper with. */
    @Test
    void aClientSuppliedStorageKeyIsIgnored() {
        MultiValueMap<String, String> extra = new LinkedMultiValueMap<>();
        extra.add("storageKey", "../../etc/passwd");
        ResponseEntity<String> res = upload(patient.getId(), receptionToken, "r.pdf",
                "application/pdf", pdfBytes(), PatientDocument.OTHER, extra);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(documents.findByHospitalIdAndPatientIdAndIsActiveTrueOrderByReportDateDescIdDesc(
                hospital.getId(), patient.getId()).get(0).getStorageKey())
                .as("the server chose the key, not the caller")
                .doesNotContain("..").doesNotContain("etc");
    }

    // -- archiving ---------------------------------------------------------------

    @Test
    void archivingHidesTheDocumentButKeepsIt() {
        uploadPdf(patient.getId(), receptionToken);
        String id = publicIdOf(patient);

        ResponseEntity<String> archived = rest.exchange(
                "/hospital/patient-documents/" + id + "/archive", HttpMethod.POST,
                new HttpEntity<>("{\"reason\":\"Filed against the wrong patient\"}", jsonAuth(doctorToken)),
                String.class);
        assertThat(archived.getStatusCode().value()).as("%s", archived.getBody()).isEqualTo(200);

        assertThat(get("/hospital/patients/" + patient.getId() + "/documents", doctorToken).getBody())
                .doesNotContain("Outside blood report");

        PatientDocument row = documents.findByPublicIdAndHospitalId(id, hospital.getId()).orElseThrow();
        assertThat(row.getIsActive()).isFalse();
        assertThat(row.getArchiveReason()).isEqualTo("Filed against the wrong patient");
        assertThat(row.getStorageKey()).as("the file itself is kept").isNotBlank();
    }

    @Test
    void archivingNeedsAReason() {
        uploadPdf(patient.getId(), receptionToken);
        ResponseEntity<String> res = rest.exchange(
                "/hospital/patient-documents/" + publicIdOf(patient) + "/archive", HttpMethod.POST,
                new HttpEntity<>("{}", jsonAuth(doctorToken)), String.class);
        assertThat(res.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void receptionCannotArchive() {
        uploadPdf(patient.getId(), receptionToken);
        ResponseEntity<String> res = rest.exchange(
                "/hospital/patient-documents/" + publicIdOf(patient) + "/archive", HttpMethod.POST,
                new HttpEntity<>("{\"reason\":\"x\"}", jsonAuth(receptionToken)), String.class);
        assertThat(res.getStatusCode().value()).isEqualTo(403);
    }

    private HttpHeaders jsonAuth(String token) {
        HttpHeaders h = auth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    // -- 4B.1 corrections --------------------------------------------------------

    /** A declared type is the caller's word for it; the bytes are not. */
    @Test
    void aFileWhoseContentsDoNotMatchItsTypeIsRefused() {
        ResponseEntity<String> res = upload(patient.getId(), receptionToken, "report.pdf",
                "application/pdf", "MZ this is actually an executable".getBytes(),
                PatientDocument.OTHER, null);

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(documentsFor(patient)).isZero();
    }

    @Test
    void anImageRenamedAsAPdfIsRefused() {
        ResponseEntity<String> res = upload(patient.getId(), receptionToken, "scan.pdf",
                "application/pdf", pngBytes(), PatientDocument.OTHER, null);
        assertThat(res.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void aTruncatedFileIsRefused() {
        ResponseEntity<String> res = upload(patient.getId(), receptionToken, "tiny.pdf",
                "application/pdf", new byte[]{0x25, 0x50}, PatientDocument.OTHER, null);
        assertThat(res.getStatusCode().value()).isEqualTo(400);
    }

    /** An archived document is out of the working list and out of reach by direct id. */
    @Test
    void anArchivedDocumentCannotBeFetchedByItsId() {
        uploadPdf(patient.getId(), receptionToken);
        String id = publicIdOf(patient);

        rest.exchange("/hospital/patient-documents/" + id + "/archive", HttpMethod.POST,
                new HttpEntity<>("{\"reason\":\"Wrong patient\"}", jsonAuth(doctorToken)), String.class);

        assertThat(get("/hospital/patient-documents/" + id + "/content", doctorToken)
                .getStatusCode().value())
                .as("archived means gone from the working record, not merely hidden from a list")
                .isEqualTo(404);
    }

    /** A pharmacy facility has no patient records to attach reports to. */
    @Test
    void aPharmacyFacilityCannotReachPatientDocuments() {
        Hospital pharmacy = tenant("Chemist");
        pharmacy.setType(com.hms.entity.HospitalType.PHARMACY);
        hospitals.save(pharmacy);
        String pharmacyAdmin = tokenFor(pharmacy, "HOSPITAL_ADMIN");

        assertThat(get("/hospital/patients/" + patient.getId() + "/documents", pharmacyAdmin)
                .getStatusCode().is2xxSuccessful())
                .as("a pharmacy tenant must not reach patient documents").isFalse();
        assertThat(uploadPdf(patient.getId(), pharmacyAdmin).getStatusCode().is2xxSuccessful()).isFalse();
    }

    /** Working in the hospital is not by itself a reason to open any patient's file. */
    @Test
    void aNurseWithNoRelationshipToThePatientIsRefused() {
        uploadPdf(patient.getId(), receptionToken);

        assertThat(get("/hospital/patients/" + patient.getId() + "/documents", nurseToken)
                .getStatusCode().value())
                .as("this patient is not admitted, so no nurse is looking after them").isEqualTo(403);
        assertThat(get("/hospital/patient-documents/" + publicIdOf(patient) + "/content", nurseToken)
                .getStatusCode().value()).isEqualTo(403);
    }

    /** Reception, doctors and admins are unaffected by the nursing rule. */
    @Test
    void theOtherRolesAreNotSubjectToNursingScope() {
        uploadPdf(patient.getId(), receptionToken);
        for (String token : new String[]{doctorToken, adminToken, receptionToken}) {
            assertThat(get("/hospital/patients/" + patient.getId() + "/documents", token)
                    .getStatusCode().value()).isEqualTo(200);
        }
    }
}
