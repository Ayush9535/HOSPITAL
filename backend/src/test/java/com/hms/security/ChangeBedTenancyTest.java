package com.hms.security;

import com.hms.entity.Bed;
import com.hms.entity.BedStatus;
import com.hms.entity.Doctor;
import com.hms.entity.Hospital;
import com.hms.entity.IpdAdmission;
import com.hms.entity.Patient;
import com.hms.entity.User;
import com.hms.entity.Ward;
import com.hms.repository.BedRepository;
import com.hms.repository.BedStatusAuditRepository;
import com.hms.repository.DoctorRepository;
import com.hms.repository.HospitalRepository;
import com.hms.repository.IpdAdmissionRepository;
import com.hms.repository.PatientRepository;
import com.hms.repository.UserRepository;
import com.hms.repository.WardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E1 (C4) — the target bed of a transfer is client-supplied, and must be tenant-scoped.
 *
 * <p>{@code changeBed} used to resolve it with a bare {@code findById}, so another hospital's bed
 * was loaded and inspected before anything refused it. That leaked two things over enumerable
 * ids: which bed ids exist elsewhere, and what state they are in — a foreign OCCUPIED bed
 * answered 400 "already occupied" while a foreign AVAILABLE one fell through to a 404 from
 * {@code BedStatusService}. It also meant the ward, and therefore the price used by the upgrade
 * billing, could be read from another tenant.
 *
 * <p>The invariant: <b>a foreign bed must be indistinguishable from a bed that does not exist</b>
 * — same status, same body — and nothing may be written on either side.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ChangeBedTenancyTest {

    @LocalServerPort int port;
    @Autowired JwtUtil jwtUtil;
    @Autowired HospitalRepository hospitalRepository;
    @Autowired UserRepository userRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired DoctorRepository doctorRepository;
    @Autowired WardRepository wardRepository;
    @Autowired BedRepository bedRepository;
    @Autowired com.hms.repository.IpdBedHistoryRepository ipdBedHistoryRepository;
    @Autowired IpdAdmissionRepository ipdAdmissionRepository;
    @Autowired BedStatusAuditRepository bedStatusAuditRepository;

    private static final List<String> MODULES = List.of("OPD", "IPD", "BILLING");

    private String tokenB;
    private Long bAdmissionId;
    private Long bBedId;
    private Long aOccupiedBedId;
    private Long aAvailableBedId;

    private String uniq() { return Long.toString(System.nanoTime()); }

    private long seedHospital(String slug) {
        Hospital h = new Hospital();
        h.setName("H-" + slug);
        h.setCustomId("HID-" + uniq());
        h.setSubscriptionStatus("ACTIVE");
        h.setIsActive(true);
        h.setModules(MODULES);
        h.setIsSingleDoctor(false);
        return hospitalRepository.save(h).getId();
    }

    private Long seedBed(long hid, long wardId, String status) {
        Bed b = new Bed();
        b.setHospitalId(hid);
        b.setWardId(wardId);
        b.setBedCode("BED-" + uniq());
        b.setStatus(status);
        return bedRepository.save(b).getBedId();
    }

    private long seedWard(long hid, String slug) {
        Ward w = new Ward();
        w.setWardName("W-" + slug);
        w.setHospitalId(hid);
        w.setBedPrice(new BigDecimal("1000"));
        w.setTotalBeds(3);
        return wardRepository.save(w).getWardId();
    }

    @BeforeEach
    void setUp() {
        long hidA = seedHospital("alpha");
        long hidB = seedHospital("bravo");

        long aWard = seedWard(hidA, "alpha");
        aOccupiedBedId = seedBed(hidA, aWard, BedStatus.OCCUPIED);
        aAvailableBedId = seedBed(hidA, aWard, BedStatus.AVAILABLE);

        long bWard = seedWard(hidB, "bravo");
        bBedId = seedBed(hidB, bWard, BedStatus.OCCUPIED);

        Doctor d = new Doctor();
        d.setName("Doctor Bravo");
        d.setHospitalId(hidB);
        d.setIsActive(true);
        d.setEmail("doc-" + uniq() + "@bravo.test");
        d.setPublicId("dpub-" + uniq());
        d.setPhone("9800000002");
        d.setSpecialization("Gen");
        long did = doctorRepository.save(d).getId();

        Patient p = new Patient();
        p.setName("Bravo Patient");
        p.setHospitalId(hidB);
        p.setPublicId("ppub-" + uniq());
        p.setGender("MALE");
        p.setPhone("9900000002");
        p.setIsActive(true);
        p.setDateOfBirth(LocalDate.of(1985, 1, 1));
        long pid = patientRepository.save(p).getId();

        IpdAdmission a = new IpdAdmission();
        // NOT "IPD-<nanotime>": findMaxIpdSequence() reads MAX(substring) over every row
        // matching 'IPD-%' and returns an Integer, so a 19-digit fixture number overflows
        // and poisons the next real admission for the whole database with a 500.
        a.setIpdNumber("FIXT-" + uniq());
        a.setHospitalId(hidB);
        a.setPatientId(pid);
        a.setDoctorId(did);
        a.setWardId(bWard);
        a.setBedId(bBedId);
        a.setStatus("ADMITTED");
        a.setAdmissionType("ELECTIVE");
        a.setAdmissionDatetime(LocalDateTime.now());
        a.setAdmissionConfirmed(true);
        bAdmissionId = ipdAdmissionRepository.save(a).getId();

        // The release candidate's changeBed also checks that the bed it is moving OFF actually
        // records this admission, not merely that it is occupied. Link them, or every transfer
        // here fails the precondition before the tenancy rule under test is ever reached.
        bedRepository.findById(bBedId).ifPresent(bed -> {
            bed.setCurrentIpdAdmissionId(bAdmissionId);
            bedRepository.save(bed);
        });

        // Same reason: changeBed moves the OPEN bed-history span rather than inventing one, so a
        // fixture that inserts the admission directly still has to record where the patient is.
        com.hms.entity.IpdBedHistory hist = new com.hms.entity.IpdBedHistory();
        hist.setIpdAdmissionId(bAdmissionId);
        hist.setWardId(bWard);
        hist.setBedId(bBedId);
        hist.setAssignedAt(LocalDateTime.now());
        ipdBedHistoryRepository.save(hist);

        User admin = new User();
        admin.setEmail("admin-bravo-" + uniq() + "@x.test");
        admin.setPassword("{noop}x");
        admin.setName("Admin bravo");
        admin.setRole("HOSPITAL_ADMIN");
        admin.setHospitalId(hidB);
        admin.setIsActive(true);
        admin = userRepository.save(admin);

        tokenB = jwtUtil.generateToken(admin.getId(), admin.getEmail(), admin.getRole(), hidB,
                MODULES, null, "HOSPITAL", null, admin.getTokenVersion());
    }

    /** java.net.http, not TestRestTemplate: the latter cannot observe a 401 on a streamed body. */
    private ResponseEntity<String> changeBed(Long admissionId, Long newBedId) {
        try {
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://localhost:" + port + "/hospital/ipd/"
                            + admissionId + "/change-bed?newBedId=" + newBedId))
                    .header("Authorization", "Bearer " + tokenB)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .method("PUT", java.net.http.HttpRequest.BodyPublishers.noBody())
                    .build();
            java.net.http.HttpResponse<String> res = java.net.http.HttpClient.newHttpClient()
                    .send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            return ResponseEntity.status(res.statusCode()).body(res.body());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("HTTP call failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", e);
        }
    }

    private String strip(String body) {
        return body == null ? null : body.replaceAll("\"requestId\":\"[^\"]*\"", "\"requestId\":\"X\"");
    }

    private void assertNothingMutated(Long foreignBedId, String expectedForeignStatus) {
        assertThat(bedRepository.findById(foreignBedId).orElseThrow().getStatus())
                .as("the foreign bed is untouched").isEqualToIgnoringCase(expectedForeignStatus);
        assertThat(bedStatusAuditRepository.findByBedIdOrderByChangedAtDesc(foreignBedId))
                .as("no bed_status_audit row is written against another tenant's bed").isEmpty();
        IpdAdmission after = ipdAdmissionRepository.findById(bAdmissionId).orElseThrow();
        assertThat(after.getBedId()).as("the caller's patient has not moved").isEqualTo(bBedId);
    }

    @Test
    void transferOntoAForeignOccupiedBed_is404_andMutatesNothing() {
        // Previously 400 "Requested bed is already occupied" -- which confirmed the id existed
        // AND disclosed its state.
        ResponseEntity<String> res = changeBed(bAdmissionId, aOccupiedBedId);

        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(res.getBody()).contains("\"code\":\"RESOURCE_NOT_FOUND\"");
        assertNothingMutated(aOccupiedBedId, BedStatus.OCCUPIED);
    }

    @Test
    void transferOntoAForeignAvailableBed_is404_andMutatesNothing() {
        ResponseEntity<String> res = changeBed(bAdmissionId, aAvailableBedId);

        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(res.getBody()).contains("\"code\":\"RESOURCE_NOT_FOUND\"");
        assertNothingMutated(aAvailableBedId, BedStatus.AVAILABLE);
    }

    @Test
    void aForeignBedAnswersExactlyLikeANonExistentOne() {
        // The whole point of C4: an attacker enumerating ids must learn nothing. Occupied and
        // available foreign beds must be indistinguishable from an id that was never issued.
        String foreignOccupied = strip(changeBed(bAdmissionId, aOccupiedBedId).getBody());
        String foreignAvailable = strip(changeBed(bAdmissionId, aAvailableBedId).getBody());
        String missing = strip(changeBed(bAdmissionId, 99999999L).getBody());

        assertThat(foreignOccupied).isEqualTo(missing);
        assertThat(foreignAvailable).isEqualTo(missing);
    }

    @Test
    void transferWithinTheCallersOwnHospitalStillWorks() {
        // The positive control: C4 must refuse foreign beds without breaking legitimate transfers.
        Long ownTarget = seedBed(
                ipdAdmissionRepository.findById(bAdmissionId).orElseThrow().getHospitalId(),
                ipdAdmissionRepository.findById(bAdmissionId).orElseThrow().getWardId(),
                BedStatus.AVAILABLE);

        ResponseEntity<String> res = changeBed(bAdmissionId, ownTarget);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(ipdAdmissionRepository.findById(bAdmissionId).orElseThrow().getBedId())
                .isEqualTo(ownTarget);
    }
}
