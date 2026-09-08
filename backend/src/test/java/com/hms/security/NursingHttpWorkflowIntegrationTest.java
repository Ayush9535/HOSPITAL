package com.hms.security;

import com.hms.entity.*;
import com.hms.repository.*;
import com.hms.support.NursingHttpFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Release-gate HTTP proof for ward-scoped nursing.  Every principal and clinical
 * row is persisted here; none of these assertions can accidentally authenticate
 * as a user left behind by another H2 test context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class NursingHttpWorkflowIntegrationTest {
    @LocalServerPort int port;
    @Autowired JwtUtil jwtUtil;
    @Autowired HospitalRepository hospitals;
    @Autowired HospitalSettingRepository settings;
    @Autowired UserRepository users;
    @Autowired NurseProfileRepository nurses;
    @Autowired WardRepository wards;
    @Autowired BedRepository beds;
    @Autowired PatientRepository patients;
    @Autowired DoctorRepository doctors;
    @Autowired IpdAdmissionRepository admissions;
    @Autowired MedicalRecordRepository records;
    @Autowired PrescriptionRepository prescriptions;
    @Autowired PatientNurseAssignmentRepository assignments;
    @Autowired NurseWardAssignmentRepository wardAssignments;
    @Autowired VitalsRecordRepository vitals;
    @Autowired NursingNoteRepository notes;
    @Autowired MedicationAdministrationRepository medicationAdministrations;
    @Autowired IpdBedHistoryRepository bedHistory;

    private NursingHttpFixture fixture;
    private Hospital hospitalA, hospitalB;
    private Ward wardA, wardB, wardForeign;
    private NurseProfile nurseA, nurseB, inchargeA;
    private Doctor doctorA, doctorB;
    private IpdAdmission admissionA, admissionB, foreignAdmission;
    private Prescription prescriptionA;

    @BeforeEach
    void setUp() {
        fixture = new NursingHttpFixture(jwtUtil, hospitals, settings, users, nurses, wards, beds,
                patients, doctors, admissions, records, prescriptions, bedHistory);
        hospitalA = fixture.tenant("A");
        hospitalB = fixture.tenant("B");
        wardA = fixture.ward(hospitalA, "A");
        wardB = fixture.ward(hospitalA, "B");
        wardForeign = fixture.ward(hospitalB, "foreign");
        nurseA = fixture.staffNurse(hospitalA, wardA, "Nurse A");
        nurseB = fixture.staffNurse(hospitalA, wardB, "Nurse B");
        inchargeA = fixture.incharge(hospitalA, wardA, "Incharge A");
        doctorA = fixture.doctor(hospitalA, "A");
        doctorB = fixture.doctor(hospitalB, "B");
        admissionA = fixture.admit(hospitalA, wardA, fixture.bed(hospitalA, wardA, "A"), doctorA, "A");
        admissionB = fixture.admit(hospitalA, wardB, fixture.bed(hospitalA, wardB, "B"), doctorA, "B");
        foreignAdmission = fixture.admit(hospitalB, wardForeign, fixture.bed(hospitalB, wardForeign, "F"), doctorB, "F");
        prescriptionA = fixture.activePrescription(hospitalA, admissionA, doctorA);
    }

    @Test
    void staffNurseHasWardScopedHttpVisibilityAndWrites() {
        String token = fixture.tokenFor(nurseA);

        ResponseEntity<String> mine = call(HttpMethod.GET, "/hospital/nurse/my-patients", token, null);
        assertThat(mine.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mine.getBody()).contains("\"ipdAdmissionId\":" + admissionA.getId())
                .doesNotContain("\"ipdAdmissionId\":" + admissionB.getId());

        // Scoped to this admission, not a global table count: @SpringBootTest shares one H2
        // instance across every test class in the run, so a bare vitals.count()==0 precondition
        // is order-dependent on whatever else has already persisted a VitalsRecord this JVM run.
        assertThat(call(HttpMethod.POST, "/hospital/nurse/vitals", token, vitalsBody(admissionA.getId())).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(vitals.findByIpdAdmissionIdAndIsActiveTrueOrderByRecordedAtDesc(admissionA.getId())).hasSize(1);
        assertDenied(call(HttpMethod.POST, "/hospital/nurse/vitals", token, vitalsBody(admissionB.getId())));

        assertThat(call(HttpMethod.POST, "/hospital/nurse/notes", token, noteBody(admissionA.getId())).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(notes.findByIpdAdmissionIdAndIsActiveTrueOrderByRecordedAtDesc(admissionA.getId())).hasSize(1);
        assertDenied(call(HttpMethod.POST, "/hospital/nurse/notes", token, noteBody(admissionB.getId())));

        assertThat(call(HttpMethod.POST, "/hospital/nurse/medication", token, medicationBody(admissionA.getId(), prescriptionA.getId())).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(medicationAdministrations.findByIpdAdmissionIdAndIsActiveTrueOrderByCreatedAtDesc(admissionA.getId()))
                .hasSize(1);
        assertDenied(call(HttpMethod.POST, "/hospital/nurse/medication", token,
                medicationBody(admissionB.getId(), prescriptionA.getId())));
    }

    @Test
    void inchargeCanUseOwnWardAndQueueButNotAnotherWard() {
        String token = fixture.tokenFor(inchargeA);
        ResponseEntity<String> queue = call(HttpMethod.GET, "/hospital/nurse-incharge/unassigned-patients", token, null);
        assertThat(queue.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(queue.getBody()).contains("\"ipdAdmissionId\":" + admissionA.getId())
                .doesNotContain("\"ipdAdmissionId\":" + admissionB.getId());
        assertThat(call(HttpMethod.POST, "/hospital/nurse/medication", token,
                medicationBody(admissionA.getId(), prescriptionA.getId())).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertDenied(call(HttpMethod.POST, "/hospital/nurse/vitals", token, vitalsBody(admissionB.getId())));
    }

    @Test
    void foreignTenantCannotReadOrWriteClinicalNursingResources() {
        String token = fixture.tokenFor(nurseA);
        assertDenied(call(HttpMethod.GET, "/hospital/nurse/patients/" + foreignAdmission.getId(), token, null));
        assertDenied(call(HttpMethod.POST, "/hospital/nurse/vitals", token, vitalsBody(foreignAdmission.getId())));
        assertDenied(call(HttpMethod.POST, "/hospital/nurse/notes", token, noteBody(foreignAdmission.getId())));
        assertDenied(call(HttpMethod.POST, "/hospital/nurse/medication", token,
                medicationBody(foreignAdmission.getId(), prescriptionA.getId())));
        assertDenied(call(HttpMethod.GET, "/hospital/nurse-incharge/unassigned-patients", token, null));
    }

    @Test
    void primaryAndTemporaryWardCoverageAreSubstitutive() {
        String token = fixture.tokenFor(nurseA);
        NurseWardAssignment coverage = new NurseWardAssignment();
        coverage.setHospitalId(hospitalA.getId());
        coverage.setNurseProfileId(nurseA.getId());
        coverage.setTempWardId(wardB.getWardId());
        coverage.setFromDate(LocalDate.now());
        coverage.setToDate(LocalDate.now());
        coverage.setReason("coverage");
        wardAssignments.save(coverage);

        ResponseEntity<String> covered = call(HttpMethod.GET, "/hospital/nurse/my-patients", token, null);
        assertThat(covered.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(covered.getBody()).contains("\"ipdAdmissionId\":" + admissionB.getId())
                .doesNotContain("\"ipdAdmissionId\":" + admissionA.getId());

        coverage.setToDate(LocalDate.now().minusDays(1));
        wardAssignments.save(coverage);
        ResponseEntity<String> restored = call(HttpMethod.GET, "/hospital/nurse/my-patients", token, null);
        assertThat(restored.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(restored.getBody()).contains("\"ipdAdmissionId\":" + admissionA.getId())
                .doesNotContain("\"ipdAdmissionId\":" + admissionB.getId());
    }

    @Test
    void persistedWardWorkflowTransfersOwnershipAndClosesItOnDischarge() {
        User admin = fixture.user(hospitalA, "HOSPITAL_ADMIN", "Admin A");
        String inchargeToken = fixture.tokenFor(inchargeA);
        String adminToken = fixture.tokenFor(admin);

        assertThat(call(HttpMethod.GET, "/hospital/nurse-incharge/unassigned-patients", inchargeToken, null).getBody())
                .contains("\"ipdAdmissionId\":" + admissionA.getId());
        assertThat(call(HttpMethod.POST, "/hospital/nurse-incharge/assign", inchargeToken,
                "{\"ipdAdmissionId\":" + admissionA.getId() + ",\"nurseProfileId\":" + nurseA.getId() + "}").getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(assignments.findByIpdAdmissionIdAndIsActiveTrue(admissionA.getId())).isPresent();

        Bed destination = fixture.availableBed(hospitalA, wardB, "workflow-destination");
        assertThat(call(HttpMethod.PUT, "/hospital/ipd/" + admissionA.getId() + "/change-bed?newBedId=" + destination.getBedId(), adminToken, null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        IpdAdmission transferred = admissions.findById(admissionA.getId()).orElseThrow();
        assertThat(transferred.getWardId()).isEqualTo(wardB.getWardId());
        assertThat(transferred.getBedId()).isEqualTo(destination.getBedId());
        assertThat(beds.findById(destination.getBedId()).orElseThrow().getStatus()).isEqualTo(BedStatus.OCCUPIED);
        assertThat(assignments.findByIpdAdmissionIdAndIsActiveTrue(admissionA.getId())).isEmpty();
        assertThat(call(HttpMethod.GET, "/hospital/nurse/my-patients", fixture.tokenFor(nurseA), null).getBody())
                .doesNotContain("\"ipdAdmissionId\":" + admissionA.getId());
        assertThat(call(HttpMethod.GET, "/hospital/nurse/my-patients", fixture.tokenFor(nurseB), null).getBody())
                .contains("\"ipdAdmissionId\":" + admissionA.getId());

        assertThat(call(HttpMethod.POST, "/hospital/nurse-incharge/assign", adminToken,
                "{\"ipdAdmissionId\":" + admissionA.getId() + ",\"nurseProfileId\":" + nurseB.getId() + "}").getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(assignments.findByIpdAdmissionIdAndIsActiveTrue(admissionA.getId()).orElseThrow().getNurseUserId())
                .isEqualTo(nurseB.getUserId());

        assertThat(call(HttpMethod.POST, "/hospital/ipd/" + admissionA.getId() + "/plan-discharge", adminToken,
                "{\"dischargeNotes\":\"release gate\"}").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.POST, "/hospital/ipd/" + admissionA.getId() + "/confirm-discharge", adminToken, null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(admissions.findById(admissionA.getId()).orElseThrow().getStatus()).isEqualTo("DISCHARGED");
        assertThat(assignments.findByIpdAdmissionIdAndIsActiveTrue(admissionA.getId())).isEmpty();
        assertThat(beds.findById(destination.getBedId()).orElseThrow().getStatus()).isEqualTo(BedStatus.CLEANING);
    }

    private ResponseEntity<String> call(HttpMethod method, String path, String token, String body) {
        try {
            java.net.http.HttpRequest.BodyPublisher publisher = body == null
                    ? java.net.http.HttpRequest.BodyPublishers.noBody()
                    : java.net.http.HttpRequest.BodyPublishers.ofString(body);
            java.net.http.HttpRequest.Builder request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://localhost:" + port + path))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .method(method.name(), publisher);
            if (body != null) request.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
            java.net.http.HttpResponse<String> response = java.net.http.HttpClient.newHttpClient().send(
                    request.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            return ResponseEntity.status(response.statusCode()).body(response.body());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("HTTP call failed: " + method + " " + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted HTTP call: " + method + " " + path, e);
        }
    }

    private static void assertDenied(ResponseEntity<String> response) {
        assertThat(response.getStatusCode().value()).isIn(401, 403, 404);
    }

    private static String vitalsBody(Long admissionId) {
        return "{\"ipdAdmissionId\":" + admissionId + ",\"pulse\":72}";
    }

    private static String noteBody(Long admissionId) {
        return "{\"ipdAdmissionId\":" + admissionId + ",\"noteText\":\"stable\"}";
    }

    private static String medicationBody(Long admissionId, Long prescriptionId) {
        return "{\"ipdAdmissionId\":" + admissionId + ",\"prescriptionId\":" + prescriptionId
                + ",\"status\":\"GIVEN\"}";
    }
}
