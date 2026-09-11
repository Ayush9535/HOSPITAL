package com.hms.security;

import com.hms.entity.Bed;
import com.hms.entity.Doctor;
import com.hms.entity.Hospital;
import com.hms.entity.IpdAdmission;
import com.hms.entity.IpdBedHistory;
import com.hms.entity.Patient;
import com.hms.entity.User;
import com.hms.entity.Ward;
import com.hms.repository.BedRepository;
import com.hms.repository.DoctorRepository;
import com.hms.repository.HospitalRepository;
import com.hms.repository.IpdAdmissionRepository;
import com.hms.repository.IpdBedHistoryRepository;
import com.hms.repository.PatientRepository;
import com.hms.repository.SurgeryRepository;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One surgery carried the length of the theatre workflow by the roles that really do each step.
 *
 * <p>This is the check the reported bug needed and did not have. Signing the WHO checklist
 * returned Access Denied on staging because the screen offering it belonged to a role without
 * OT_TIME_OUT. Reading a permission map cannot catch that; only walking the case as each role and
 * watching for a 403 can.
 *
 * <p>Roles are the shipped defaults, unmodified. Nothing here grants a permission to make a step
 * pass — where a role is refused, that refusal is asserted as the correct answer.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OtRoleJourneyTest {

    private static final AtomicLong SEQ = new AtomicLong();
    private static final List<String> MODULES = List.of("OPD", "IPD", "OT", "NURSING");

    @Autowired TestRestTemplate rest;
    @Autowired JwtUtil jwtUtil;
    @Autowired HospitalRepository hospitals;
    @Autowired UserRepository users;
    @Autowired DoctorRepository doctors;
    @Autowired PatientRepository patients;
    @Autowired WardRepository wards;
    @Autowired BedRepository beds;
    @Autowired IpdAdmissionRepository admissions;
    @Autowired IpdBedHistoryRepository bedHistory;
    @Autowired SurgeryRepository surgeries;

    private Hospital hospital;
    private Doctor surgeon;
    private IpdAdmission admission;

    private String doctorToken, receptionToken, nurseToken, otInchargeToken, adminToken;

    private static long uniq() { return System.nanoTime() + SEQ.incrementAndGet(); }

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
                MODULES, null, "HOSPITAL", null, u.getTokenVersion());
    }

    @BeforeEach
    void setUp() {
        Hospital h = new Hospital();
        h.setName("Theatre Hospital");
        h.setCustomId("OTJ-" + uniq());
        h.setIsActive(true);
        h.setSubscriptionStatus("ACTIVE");
        h.setModules(MODULES);
        h.setIsSingleDoctor(false);
        h.setType(com.hms.entity.HospitalType.HOSPITAL);
        hospital = hospitals.save(h);

        String surgeonEmail = "surgeon." + uniq() + "@ot.test";
        Doctor d = new Doctor();
        d.setHospitalId(hospital.getId());
        d.setName("Dr Surgeon");
        d.setEmail(surgeonEmail);
        d.setPublicId("dpub-" + uniq());
        d.setPhone("9800000020");
        d.setSpecialization("Surgery");
        d.setIsActive(true);
        surgeon = doctors.save(d);

        Patient p = new Patient();
        p.setHospitalId(hospital.getId());
        p.setName("Theatre Patient");
        p.setPublicId("ppub-" + uniq());
        p.setGender("MALE");
        p.setPhone("9900000020");
        p.setIsActive(true);
        p.setDateOfBirth(LocalDate.of(1980, 1, 1));
        p = patients.save(p);

        Ward ward = new Ward();
        ward.setWardName("Surgical Ward");
        ward.setHospitalId(hospital.getId());
        ward.setBedPrice(new BigDecimal("1000"));
        ward.setTotalBeds(1);
        ward = wards.save(ward);

        Bed bed = new Bed();
        bed.setHospitalId(hospital.getId());
        bed.setWardId(ward.getWardId());
        bed.setBedCode("BED-" + uniq());
        bed.setStatus("occupied");
        bed = beds.save(bed);

        IpdAdmission adm = new IpdAdmission();
        adm.setHospitalId(hospital.getId());
        adm.setPatientId(p.getId());
        adm.setDoctorId(surgeon.getId());
        adm.setWardId(ward.getWardId());
        adm.setBedId(bed.getBedId());
        adm.setIpdNumber("IPD-" + uniq());
        adm.setAdmissionType("ELECTIVE");
        adm.setStatus("ADMITTED");
        adm.setAdmissionDatetime(LocalDateTime.now());
        admission = admissions.save(adm);

        bed.setCurrentIpdAdmissionId(admission.getId());
        beds.save(bed);

        IpdBedHistory hist = new IpdBedHistory();
        hist.setIpdAdmissionId(admission.getId());
        hist.setBedId(bed.getBedId());
        hist.setWardId(ward.getWardId());
        hist.setAssignedAt(LocalDateTime.now());
        bedHistory.save(hist);

        doctorToken = tokenFor("DOCTOR", surgeonEmail);
        receptionToken = tokenFor("RECEPTIONIST", "rec." + uniq() + "@ot.test");
        nurseToken = tokenFor("NURSE", "nurse." + uniq() + "@ot.test");
        otInchargeToken = tokenFor("OT_INCHARGE", "otic." + uniq() + "@ot.test");
        adminToken = tokenFor("HOSPITAL_ADMIN", "admin." + uniq() + "@ot.test");
    }

    private HttpHeaders headers(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private ResponseEntity<String> post(String path, String token, String body) {
        return rest.exchange(path, HttpMethod.POST,
                new HttpEntity<>(body == null ? "{}" : body, headers(token)), String.class);
    }

    private ResponseEntity<String> get(String path, String token) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(token)), String.class);
    }

    private void allowed(ResponseEntity<String> res, String step, String role) {
        assertThat(res.getStatusCode().value())
                .as("%s as %s must not be refused for lack of permission: %s", step, role, res.getBody())
                .isNotEqualTo(403);
    }

    private void refused(ResponseEntity<String> res, String step, String role) {
        assertThat(res.getStatusCode().value())
                .as("%s must be refused for %s", step, role).isEqualTo(403);
    }

    /**
     * The theatre incharge holds the full clinical set, so every step of the case is open to it.
     * Nothing in this walk may answer 403.
     */
    @Test
    void theTheatreInchargeCanWalkTheWholeCaseWithoutA403() {
        String create = "{\"ipdAdmissionId\":" + admission.getId()
                + ",\"procedureName\":\"Appendicectomy\",\"surgeonDoctorId\":" + surgeon.getId() + "}";
        ResponseEntity<String> requested = post("/hospital/surgeries", otInchargeToken, create);
        allowed(requested, "request a surgery", "OT_INCHARGE");
        assertThat(requested.getStatusCode().value()).as("%s", requested.getBody()).isEqualTo(200);

        Long surgeryId = surgeries.findAll().stream()
                .filter(s -> hospital.getId().equals(s.getHospitalId()))
                .findFirst().orElseThrow().getId();
        String publicId = surgeries.findById(surgeryId).orElseThrow().getPublicId();

        allowed(get("/hospital/surgeries/board", otInchargeToken), "read the board", "OT_INCHARGE");
        allowed(get("/hospital/surgeries/requests", otInchargeToken), "read requests", "OT_INCHARGE");
        allowed(post("/hospital/surgeries/" + publicId + "/approve", otInchargeToken, null),
                "approve", "OT_INCHARGE");
        allowed(get("/hospital/ot/surgeries/" + surgeryId + "/team", otInchargeToken),
                "read the team", "OT_INCHARGE");
        allowed(post("/hospital/surgeries/" + publicId + "/pre-op", otInchargeToken, null),
                "pre-op", "OT_INCHARGE");
        allowed(post("/hospital/surgeries/" + publicId + "/anaesthesia-clearance", otInchargeToken, null),
                "anaesthesia clearance", "OT_INCHARGE");
        allowed(get("/hospital/ot/surgeries/" + surgeryId + "/who-checklist", otInchargeToken),
                "read the WHO checklist", "OT_INCHARGE");
        allowed(post("/hospital/ot/surgeries/" + surgeryId + "/who-checklist/SIGN_IN/sign",
                        otInchargeToken, "{\"siteMarked\":true}"),
                "sign WHO SIGN_IN", "OT_INCHARGE");
        allowed(post("/hospital/ot/surgeries/" + surgeryId + "/who-checklist/TIME_OUT/sign",
                        otInchargeToken, null),
                "sign WHO TIME_OUT", "OT_INCHARGE");
        allowed(post("/hospital/ot/surgeries/" + surgeryId + "/milestones", otInchargeToken,
                        "{\"milestone\":\"INCISION\"}"),
                "record a milestone", "OT_INCHARGE");
        allowed(post("/hospital/ot/surgeries/" + surgeryId + "/who-checklist/SIGN_OUT/sign",
                        otInchargeToken, "{\"countsCorrect\":true}"),
                "sign WHO SIGN_OUT", "OT_INCHARGE");
        allowed(post("/hospital/ot/surgeries/" + surgeryId + "/operative-note", otInchargeToken,
                        "{\"note\":\"Uneventful.\"}"),
                "write the operative note", "OT_INCHARGE");
        allowed(get("/hospital/ot/recovery/board", otInchargeToken), "read the recovery board", "OT_INCHARGE");
        allowed(post("/hospital/ot/recovery/" + surgeryId + "/observe", otInchargeToken, "{}"),
                "record recovery observations", "OT_INCHARGE");
        allowed(post("/hospital/ot/recovery/" + surgeryId + "/discharge", otInchargeToken, "{}"),
                "transfer out of recovery", "OT_INCHARGE");
        allowed(post("/hospital/surgeries/" + publicId + "/close", otInchargeToken, null),
                "close the case", "OT_INCHARGE");
    }

    /**
     * The reported failure, and the division of labour that produced it: reception runs the board
     * and may start and complete a case, but the WHO checklist belongs to the theatre team. The
     * refusal is correct — what was wrong was offering reception the button.
     */
    @Test
    void whoChecklistBelongsToTheTheatreTeamNotToReception() {
        String create = "{\"ipdAdmissionId\":" + admission.getId()
                + ",\"procedureName\":\"Appendicectomy\",\"surgeonDoctorId\":" + surgeon.getId() + "}";
        assertThat(post("/hospital/surgeries", doctorToken, create).getStatusCode().value()).isEqualTo(200);
        Long surgeryId = surgeries.findAll().stream()
                .filter(s -> hospital.getId().equals(s.getHospitalId()))
                .findFirst().orElseThrow().getId();

        // Reception can see it, and can act where it is meant to.
        allowed(get("/hospital/ot/surgeries/" + surgeryId + "/who-checklist", receptionToken),
                "read the WHO checklist", "RECEPTIONIST");
        allowed(get("/hospital/surgeries/board", receptionToken), "read the board", "RECEPTIONIST");

        // ...but not sign it. This is the exact call that failed on staging.
        refused(post("/hospital/ot/surgeries/" + surgeryId + "/who-checklist/SIGN_IN/sign",
                receptionToken, "{\"siteMarked\":true}"), "signing WHO SIGN_IN", "RECEPTIONIST");

        // The nurse, who does hold OT_TIME_OUT, can.
        allowed(post("/hospital/ot/surgeries/" + surgeryId + "/who-checklist/SIGN_IN/sign",
                nurseToken, "{\"siteMarked\":true}"), "sign WHO SIGN_IN", "NURSE");
    }

    /** Each role reaches the steps its own screens offer, and is refused nothing it is shown. */
    @Test
    void eachRoleReachesTheStepsItsOwnScreensOffer() {
        String create = "{\"ipdAdmissionId\":" + admission.getId()
                + ",\"procedureName\":\"Hernia repair\",\"surgeonDoctorId\":" + surgeon.getId() + "}";
        assertThat(post("/hospital/surgeries", doctorToken, create).getStatusCode().value()).isEqualTo(200);
        String publicId = surgeries.findAll().stream()
                .filter(s -> hospital.getId().equals(s.getHospitalId()))
                .findFirst().orElseThrow().getPublicId();
        Long surgeryId = surgeries.findAll().stream()
                .filter(s -> hospital.getId().equals(s.getHospitalId()))
                .findFirst().orElseThrow().getId();

        // Doctor: requests, reads its own board, assigns the team, gives anaesthesia clearance.
        allowed(get("/hospital/surgeries/my-board", doctorToken), "read my board", "DOCTOR");
        allowed(get("/hospital/ot/surgeries/" + surgeryId + "/team", doctorToken), "read the team", "DOCTOR");

        // Reception: approves, schedules, starts, completes, closes.
        allowed(post("/hospital/surgeries/" + publicId + "/approve", receptionToken, null),
                "approve", "RECEPTIONIST");
        allowed(get("/hospital/surgeries/requests", receptionToken), "read requests", "RECEPTIONIST");

        // Nurse: pre-op and the checklist, not scheduling.
        allowed(post("/hospital/surgeries/" + publicId + "/pre-op", nurseToken, null), "pre-op", "NURSE");
        refused(get("/hospital/surgeries/requests", nurseToken), "the scheduling worklist", "NURSE");

        // Admin: configures, and deliberately does not run cases.
        allowed(get("/hospital/ot/permissions", adminToken), "read the permission matrix", "HOSPITAL_ADMIN");
        // Asserting the status, not merely "not 403": this URL was previously wrong in the
        // frontend service, and a 404 would satisfy a not-403 check for entirely the wrong reason.
        assertThat(get("/hospital/surgeries/waiting-list", adminToken).getStatusCode().value())
                .as("the admin's read-only view of the theatre must actually serve").isEqualTo(200);
        // /surgeries/board is the SCHEDULING worklist and asks for OT_SCHEDULE, which the admin
        // default deliberately withholds; the read-only views above are its way in.
        refused(get("/hospital/surgeries/board", adminToken), "the scheduling worklist", "HOSPITAL_ADMIN");
        refused(post("/hospital/surgeries/" + publicId + "/start", adminToken, null),
                "starting a surgery", "HOSPITAL_ADMIN");
        refused(post("/hospital/ot/surgeries/" + surgeryId + "/who-checklist/SIGN_IN/sign",
                adminToken, "{\"siteMarked\":true}"), "signing WHO SIGN_IN", "HOSPITAL_ADMIN");
    }

    /** Another facility's surgery is never reachable, whatever OT permissions the caller holds. */
    @Test
    void otActionsDoNotCrossTenants() {
        String create = "{\"ipdAdmissionId\":" + admission.getId()
                + ",\"procedureName\":\"Appendicectomy\",\"surgeonDoctorId\":" + surgeon.getId() + "}";
        assertThat(post("/hospital/surgeries", doctorToken, create).getStatusCode().value()).isEqualTo(200);
        Long surgeryId = surgeries.findAll().stream()
                .filter(s -> hospital.getId().equals(s.getHospitalId()))
                .findFirst().orElseThrow().getId();

        Hospital other = new Hospital();
        other.setName("Other Hospital");
        other.setCustomId("OTJ-" + uniq());
        other.setIsActive(true);
        other.setSubscriptionStatus("ACTIVE");
        other.setModules(MODULES);
        other.setIsSingleDoctor(false);
        other.setType(com.hms.entity.HospitalType.HOSPITAL);
        other = hospitals.save(other);

        User foreign = new User();
        foreign.setEmail("foreign." + uniq() + "@ot.test");
        foreign.setPassword("{noop}fixture");
        foreign.setName("Foreign incharge");
        foreign.setRole("OT_INCHARGE");
        foreign.setHospitalId(other.getId());
        foreign.setIsActive(true);
        foreign.setTokenVersion(0);
        foreign = users.save(foreign);
        String foreignToken = jwtUtil.generateToken(foreign.getId(), foreign.getEmail(), "OT_INCHARGE",
                other.getId(), MODULES, null, "HOSPITAL", null, 0);

        // The write is refused. The server answers a cross-tenant OT call with 401 and an
        // authentication challenge, and the JDK's HTTP client will not re-send a streamed body on
        // a challenge -- so the refusal surfaces here as a transport error rather than a status.
        // Either way it is not success, and the assertion below on the stored surgery is the one
        // that actually matters: nothing was signed.
        boolean writeSucceeded;
        try {
            writeSucceeded = rest.exchange(
                    "/hospital/ot/surgeries/" + surgeryId + "/who-checklist/SIGN_IN/sign",
                    HttpMethod.POST, new HttpEntity<>("{}", headers(foreignToken)), String.class)
                    .getStatusCode().is2xxSuccessful();
        } catch (org.springframework.web.client.ResourceAccessException refusedAtTransport) {
            writeSucceeded = false;
        }
        assertThat(writeSucceeded)
                .as("a full OT permission set is not a licence to another hospital's theatre")
                .isFalse();

        assertThat(get("/hospital/ot/surgeries/" + surgeryId + "/who-checklist", foreignToken)
                .getStatusCode().is2xxSuccessful())
                .as("nor to read it").isFalse();

        // And the case itself is untouched: read back by its owner, nothing was signed. A null
        // body here is the honest answer too -- no checklist has been started on this surgery.
        String owned = get("/hospital/ot/surgeries/" + surgeryId + "/who-checklist", otInchargeToken).getBody();
        if (owned != null) {
            assertThat(owned).as("no signature was written by the foreign caller")
                    .doesNotContain("\"signInAt\":\"");
        }
    }
}
