package com.hms.security;

import com.hms.entity.Bed;
import com.hms.entity.Doctor;
import com.hms.entity.Hospital;
import com.hms.entity.IpdAdmission;
import com.hms.entity.Opd;
import com.hms.entity.Patient;
import com.hms.entity.User;
import com.hms.entity.Ward;
import com.hms.repository.BedRepository;
import com.hms.repository.DoctorRepository;
import com.hms.repository.HospitalRepository;
import com.hms.repository.IcuStayRepository;
import com.hms.repository.IpdAdmissionRepository;
import com.hms.repository.OpdRepository;
import com.hms.repository.PatientRepository;
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
 * One critically ill patient carried across roles, through the endpoints the ICU screens call.
 *
 * <p>ICU is an overlay on an IPD admission rather than a second admission system, so the thing
 * worth proving is that the overlay and the authoritative record stay in agreement: moving a
 * patient into a critical-care ward opens a stay, the IPD admission and its bed remain coherent
 * throughout, clinical data written by one role is readable afterwards, and moving the patient
 * back out closes the stay without disturbing the admission underneath.
 *
 * <p>Also fenced here because they are policy, not detail: a receptionist may not write ICU
 * clinical records, another facility can neither see nor mutate this stay, and a hospital without
 * the ICU module cannot reach any of it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class IcuCrossRoleJourneyTest {

    private static final AtomicLong SEQ = new AtomicLong();
    private static final List<String> ICU_MODULES =
            List.of("OPD", "IPD", "NURSING", "BILLING", "ICU");
    private static final List<String> NO_ICU_MODULES =
            List.of("OPD", "IPD", "NURSING", "BILLING");

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
    @Autowired IcuStayRepository icuStays;
    @Autowired com.hms.repository.NurseProfileRepository nurseProfiles;
    @Autowired com.hms.repository.PatientNurseAssignmentRepository nurseAssignments;
    @Autowired com.hms.repository.HospitalSettingRepository hospitalSettings;
    @Autowired com.hms.repository.BillingRepository billings;
    @Autowired com.hms.repository.BillingPaymentRepository billingPayments;

    private static long uniq() { return System.nanoTime() + SEQ.incrementAndGet(); }

    /** Id of the user minted by the most recent {@link #tokenFor} call. */
    private Long lastUserId;

    private Hospital hospital;
    private Doctor doctor;
    private Ward generalWard, icuWard;
    private Long generalBedId, icuBedId;
    private Long admissionId;
    private String receptionToken, nurseToken, inchargeToken, doctorToken;
    private Long nurseUserId, nurseProfileId, inchargeUserId, inchargeProfileId;

    // ── fixtures ─────────────────────────────────────────────────────────────

    private Hospital tenant(String label, List<String> modules) {
        Hospital h = new Hospital();
        h.setName("ICU " + label);
        h.setCustomId("ICUJ-" + uniq());
        h.setIsActive(true);
        h.setSubscriptionStatus("ACTIVE");
        h.setModules(modules);
        h.setIsSingleDoctor(false);
        h.setType(com.hms.entity.HospitalType.HOSPITAL);
        return hospitals.save(h);
    }

    private String tokenFor(Hospital h, String role, List<String> modules) {
        User u = new User();
        u.setEmail(role.toLowerCase() + "." + uniq() + "@icu.test");
        u.setPassword("{noop}fixture");
        u.setName("User " + role);
        u.setRole(role);
        u.setHospitalId(h.getId());
        u.setIsActive(true);
        u.setTokenVersion(0);
        u = users.save(u);
        lastUserId = u.getId();
        return jwtUtil.generateToken(u.getId(), u.getEmail(), role, h.getId(),
                modules, null, "HOSPITAL", null, 0);
    }

    private Ward ward(Hospital h, String name, String unitType) {
        Ward w = new Ward();
        w.setWardName(name);
        w.setHospitalId(h.getId());
        w.setBedPrice(new BigDecimal("2500"));
        w.setTotalBeds(4);
        w.setUnitType(unitType);
        return wards.save(w);
    }

    private Long bed(Hospital h, Ward w) {
        Bed b = new Bed();
        b.setHospitalId(h.getId());
        b.setWardId(w.getWardId());
        b.setBedCode("BED-" + uniq());
        b.setStatus("available");
        return beds.save(b).getBedId();
    }

    @BeforeEach
    void setUp() {
        hospital = tenant("Alpha", ICU_MODULES);

        Doctor d = new Doctor();
        d.setHospitalId(hospital.getId());
        d.setName("Dr Intensivist");
        d.setEmail("doc." + uniq() + "@icu.test");
        d.setPublicId("dpub-" + uniq());
        d.setPhone("9800000070");
        d.setSpecialization("Critical Care");
        d.setIsActive(true);
        doctor = doctors.save(d);

        generalWard = ward(hospital, "General Ward", "GENERAL");
        icuWard = ward(hospital, "MICU", "MICU");
        generalBedId = bed(hospital, generalWard);
        icuBedId = bed(hospital, icuWard);

        receptionToken = tokenFor(hospital, "RECEPTIONIST", ICU_MODULES);
        // Both nursing roles are built the way production builds them: a NurseProfile, a ward
        // relationship, and — for the staff nurse — an actual patient assignment. Nothing here
        // bypasses NurseAccessGuard or NurseInchargeGuard; the fixtures satisfy them honestly.
        // Staff nurses here have their own logins, so the hospital is configured that way and
        // the logged-in nurse is the recorder. With this off, production instead requires the
        // incharge to name a "performed by" nurse — a different workflow, not this one.
        com.hms.entity.HospitalSetting settings = new com.hms.entity.HospitalSetting();
        settings.setHospital(hospital);
        settings.setSeparateNurseLogin(true);
        hospitalSettings.save(settings);

        inchargeToken = tokenFor(hospital, "NURSE_INCHARGE", ICU_MODULES);
        inchargeUserId = lastUserId;
        inchargeProfileId = nurseProfile(inchargeUserId, "Sister Incharge", null, true);
        // One incharge, many wards — they supervise both the general ward and the unit.
        for (Ward w : List.of(generalWard, icuWard)) {
            w.setInchargeNurseId(inchargeProfileId);
            wards.save(w);
        }

        nurseToken = tokenFor(hospital, "NURSE", ICU_MODULES);
        nurseUserId = lastUserId;
        nurseProfileId = nurseProfile(nurseUserId, "Staff Nurse", icuWard.getWardId(), false);
        doctorToken = tokenFor(hospital, "DOCTOR", ICU_MODULES);

        admissionId = admitToGeneralWard();
    }

    private Long nurseProfile(Long userId, String name, Long wardId, boolean incharge) {
        com.hms.entity.NurseProfile np = new com.hms.entity.NurseProfile();
        np.setUserId(userId);
        np.setHospitalId(hospital.getId());
        np.setName(name);
        np.setPublicId("npub-" + uniq());
        np.setEmail("nurse." + uniq() + "@icu.test");
        np.setPhone("97" + String.format("%08d", Math.floorMod(uniq(), 100_000_000L)));
        np.setIsActive(true);
        np.setWardId(wardId);
        np.setIsIncharge(incharge);
        return nurseProfiles.save(np).getId();
    }

    /** The staff nurse is given this patient, exactly as the incharge would assign them. */
    private void assignNurseToPatient() {
        com.hms.entity.PatientNurseAssignment a = new com.hms.entity.PatientNurseAssignment();
        a.setHospitalId(hospital.getId());
        a.setIpdAdmissionId(admissionId);
        a.setPatientId(admissions.findById(admissionId).orElseThrow().getPatientId());
        a.setNurseUserId(nurseUserId);
        a.setPublicId("pna-" + uniq());
        a.setAssignedByUserId(inchargeUserId); // the incharge assigns, as in production
        a.setAssignedAt(java.time.LocalDateTime.now());
        a.setIsActive(true);
        nurseAssignments.save(a);
    }

    /** Gets a real patient onto a real bed through the real admission endpoint. */
    private Long admitToGeneralWard() {
        Patient p = new Patient();
        p.setHospitalId(hospital.getId());
        p.setName("ICU Journey Patient");
        p.setPublicId("ppub-" + uniq());
        p.setGender("MALE");
        p.setPhone("9" + String.format("%09d", Math.floorMod(uniq(), 1_000_000_000L)));
        p.setIsActive(true);
        p.setDateOfBirth(LocalDate.of(1975, 3, 4));
        p = patients.save(p);

        Opd opd = new Opd();
        opd.setPatient(p);
        opd.setDoctor(doctor);
        opd.setCaseId("OPD-" + uniq());
        opd = opds.save(opd);

        ResponseEntity<String> admitted = post("/hospital/ipd/admit", receptionToken,
                "{\"opdId\":" + opd.getId() + ",\"wardId\":" + generalWard.getWardId()
                        + ",\"bedId\":" + generalBedId
                        + ",\"admissionType\":\"ELECTIVE\",\"primaryDiagnosis\":\"Sepsis\"}");
        ok(admitted, "reception admits to the general ward");

        return admissions.findAll().stream()
                .filter(a -> hospital.getId().equals(a.getHospitalId()))
                .findFirst().orElseThrow().getId();
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

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

    private void ok(ResponseEntity<String> res, String step) {
        assertThat(res.getStatusCode().value()).as("%s -> %s", step, res.getBody()).isEqualTo(200);
    }

    /** Moves the patient between beds through the real transfer endpoint. */
    private ResponseEntity<String> transfer(Long bedId, String token) {
        return rest.exchange("/hospital/ipd/" + admissionId + "/change-bed?newBedId=" + bedId,
                HttpMethod.PUT, new HttpEntity<>(headers(token)), String.class);
    }

    private String ioBody() {
        return "{\"ipdAdmissionId\":" + admissionId
                + ",\"direction\":\"INTAKE\",\"route\":\"IV_FLUIDS\",\"volumeMl\":250}";
    }

    // ── the journey ──────────────────────────────────────────────────────────

    @Test
    void wardToIcuAndBackKeepsTheAdmissionAndTheStayInAgreement() {
        // ---------------------------------------------------- into critical care
        ok(transfer(icuBedId, receptionToken), "reception transfers the patient into the ICU");

        var stay = icuStays.findAll().stream()
                .filter(s -> hospital.getId().equals(s.getHospitalId()))
                .findFirst().orElseThrow();
        assertThat(stay.getStatus()).as("the transfer opens an ICU stay").isEqualTo("ACTIVE");
        assertThat(stay.getIpdAdmissionId()).isEqualTo(admissionId);
        assertThat(stay.getWardId()).isEqualTo(icuWard.getWardId());

        // The authoritative record is untouched underneath the overlay.
        IpdAdmission ipd = admissions.findById(admissionId).orElseThrow();
        assertThat(ipd.getStatus()).as("the admission survives the move").isEqualTo("ADMITTED");
        assertThat(ipd.getBedId()).isEqualTo(icuBedId);
        assertThat(beds.findById(icuBedId).orElseThrow().getStatus()).isEqualToIgnoringCase("occupied");
        assertThat(beds.findById(generalBedId).orElseThrow().getStatus())
                .as("the bed the patient left must not stay occupied").isNotEqualToIgnoringCase("occupied");

        // ---------------------------------------------------- the board shows this patient
        ResponseEntity<String> board = get("/hospital/icu/board", inchargeToken);
        ok(board, "the ICU board loads");
        assertThat(board.getBody())
                .as("the board must show the patient who was just moved in")
                .contains("ICU Journey Patient");

        // ---------------------------------------------------- clinical records
        assignNurseToPatient();
        ok(post("/hospital/nurse/io", nurseToken, ioBody()), "the assigned staff nurse records intake");

        ResponseEntity<String> infusion = post("/hospital/nurse/infusions", nurseToken,
                "{\"ipdAdmissionId\":" + admissionId + ",\"medicineName\":\"Noradrenaline\""
                        + ",\"rateValue\":5,\"rateUnit\":\"ML_HR\"}");
        ok(infusion, "the doctor starts an infusion");
        String infusionPublicId = infusion.getBody().replaceAll("(?s).*\"publicId\"\\s*:\\s*\"([^\"]+)\".*", "$1");
        assertThat(infusionPublicId).as("the infusion must come back with an id").isNotEmpty();

        ok(post("/hospital/nurse/infusions/" + infusionPublicId + "/rate", inchargeToken,
                "{\"ipdAdmissionId\":" + admissionId + ",\"rateValue\":8,\"rateUnit\":\"ML_HR\"}"),
                "the ward incharge titrates the infusion");

        ok(post("/hospital/nurse/ventilator", nurseToken,
                "{\"ipdAdmissionId\":" + admissionId + ",\"ventilationStatus\":\"INVASIVE\"}"),
                "the staff nurse records ventilator support");

        // ---------------------------------------------------- persisted, read back cold
        ResponseEntity<String> io = get("/hospital/nurse/io/admission/" + admissionId, doctorToken);
        ok(io, "the doctor reads the fluid chart");
        assertThat(io.getBody()).as("the intake the nurse recorded must still be there").contains("250");

        ResponseEntity<String> balance = get("/hospital/nurse/io/admission/" + admissionId + "/balance", doctorToken);
        ok(balance, "the doctor reads the fluid balance");

        ResponseEntity<String> rates = get("/hospital/nurse/infusions/" + infusionPublicId + "/rates", doctorToken);
        ok(rates, "the doctor reads the infusion history");
        assertThat(rates.getBody())
                .as("both the starting rate and the titration must be in the history")
                .contains("5").contains("8");

        ok(post("/hospital/nurse/severity-scores", nurseToken,
                "{\"ipdAdmissionId\":" + admissionId + ",\"scoreType\":\"SOFA\""
                        + ",\"components\":{\"respiratory\":2,\"renal\":1}}"),
                "the staff nurse records a severity score");

        ok(post("/hospital/nurse/infusions/" + infusionPublicId + "/stop", nurseToken, "{}"),
                "the staff nurse stops the infusion");

        // ---------------------------------------------------- back out to the ward
        // The bed the patient left is in CLEANING, not free: vacating a bed does not make it
        // available, the incharge does. Following that rather than working around it.
        assertThat(beds.findById(generalBedId).orElseThrow().getStatus())
                .as("the vacated bed waits to be cleaned").isEqualToIgnoringCase("cleaning");
        ok(post("/hospital/beds/" + generalBedId + "/cleaned", inchargeToken, "{}"),
                "the incharge marks the vacated bed cleaned");

        ok(transfer(generalBedId, receptionToken), "reception transfers the patient back to the ward");

        var closed = icuStays.findById(stay.getId()).orElseThrow();
        assertThat(closed.getStatus()).as("leaving critical care closes the stay").isEqualTo("CLOSED");
        assertThat(closed.getDisposition()).isEqualTo("WARD");
        assertThat(closed.getDischargedAt()).isNotNull();

        IpdAdmission after = admissions.findById(admissionId).orElseThrow();
        assertThat(after.getStatus()).as("the admission is still the same admission").isEqualTo("ADMITTED");
        assertThat(after.getBedId()).isEqualTo(generalBedId);
        assertThat(beds.findById(generalBedId).orElseThrow().getStatus()).isEqualToIgnoringCase("occupied");

        // The closed stay stays readable — an ICU episode is part of the record forever.
        ok(get("/hospital/icu/admissions/" + admissionId + "/stays", doctorToken),
                "the ICU history remains readable after discharge from the unit");
    }

    // ── policy: who may write ────────────────────────────────────────────────

    @Test
    void aReceptionistCannotWriteIcuClinicalRecords() {
        ok(transfer(icuBedId, receptionToken), "patient is in the ICU");

        assertThat(post("/hospital/nurse/io", receptionToken, ioBody()).getStatusCode().value())
                .as("a receptionist must not chart fluids").isEqualTo(403);

        assertThat(post("/hospital/nurse/infusions", receptionToken,
                "{\"ipdAdmissionId\":" + admissionId + ",\"medicineName\":\"Noradrenaline\""
                        + ",\"rateValue\":5,\"rateUnit\":\"ML_HR\"}").getStatusCode().value())
                .as("a receptionist must not start an infusion").isEqualTo(403);

        assertThat(post("/hospital/nurse/ventilator", receptionToken,
                "{\"ipdAdmissionId\":" + admissionId + ",\"ventilationStatus\":\"INVASIVE\"}")
                .getStatusCode().value())
                .as("a receptionist must not set ventilator support").isEqualTo(403);

        assertThat(post("/hospital/nurse/severity-scores", receptionToken,
                "{\"ipdAdmissionId\":" + admissionId + ",\"scoreType\":\"SOFA\""
                        + ",\"components\":{\"respiratory\":2}}")
                .getStatusCode().value())
                .as("a receptionist must not record a severity score").isEqualTo(403);
    }

    /** The other half of the rule: taking reception out must not take the clinicians out. */
    @Test
    void everyClinicalRoleStillCan() {
        ok(transfer(icuBedId, receptionToken), "patient is in the ICU");
        assignNurseToPatient();

        ok(post("/hospital/nurse/io", nurseToken, ioBody()), "the assigned staff nurse may chart");
        ok(post("/hospital/nurse/io", inchargeToken, ioBody()), "the ward incharge may chart");
        ok(post("/hospital/nurse/io", doctorToken, ioBody()), "the doctor may chart");
    }

    /** Ward scope is not a formality: a nurse outside the ward is refused like anyone else. */
    @Test
    void aNurseWithNeitherAssignmentNorWardIsRefused() {
        ok(transfer(icuBedId, receptionToken), "patient is in the ICU");

        String strangerToken = tokenFor(hospital, "NURSE", ICU_MODULES);
        nurseProfile(lastUserId, "Unrelated Nurse", generalWard.getWardId(), false);

        assertThat(post("/hospital/nurse/io", strangerToken, ioBody()).getStatusCode().value())
                .as("a nurse with no claim on this patient must be refused").isEqualTo(403);
    }

    // ── the three IPD hooks, as invariants ───────────────────────────────────

    private long activeStays() {
        return icuStays.findAll().stream()
                .filter(s -> hospital.getId().equals(s.getHospitalId()))
                .filter(s -> "ACTIVE".equals(s.getStatus()))
                .count();
    }

    /** Moving bed inside the same unit is not a new episode. */
    @Test
    void aSecondBedInTheSameUnitDoesNotOpenASecondStay() {
        ok(transfer(icuBedId, receptionToken), "into the ICU");
        assertThat(activeStays()).isEqualTo(1);
        Long stayId = icuStays.findAll().stream()
                .filter(s -> hospital.getId().equals(s.getHospitalId())).findFirst().orElseThrow().getId();

        Long secondIcuBed = bed(hospital, icuWard);
        ok(transfer(secondIcuBed, receptionToken), "a different bed in the same unit");

        assertThat(activeStays()).as("one episode, not two").isEqualTo(1);
        assertThat(icuStays.findById(stayId).orElseThrow().getStatus())
                .as("and it is the same episode, still open").isEqualTo("ACTIVE");
    }

    /** Unit to unit IS a new episode: the old one closes, a new one opens. */
    @Test
    void movingToADifferentUnitClosesOneStayAndOpensAnother() {
        ok(transfer(icuBedId, receptionToken), "into the MICU");
        Long firstStayId = icuStays.findAll().stream()
                .filter(s -> hospital.getId().equals(s.getHospitalId())).findFirst().orElseThrow().getId();

        Ward sicu = ward(hospital, "SICU", "SICU");
        ok(transfer(bed(hospital, sicu), receptionToken), "into a different unit");

        assertThat(icuStays.findById(firstStayId).orElseThrow().getStatus())
                .as("the first episode ended").isEqualTo("CLOSED");
        assertThat(icuStays.findById(firstStayId).orElseThrow().getDisposition()).isEqualTo("ANOTHER_ICU");
        assertThat(activeStays()).as("exactly one episode is open").isEqualTo(1);
    }

    /** Discharge must not leave an episode open behind it. */
    @Test
    void dischargeClosesTheStay() {
        ok(transfer(icuBedId, receptionToken), "into the ICU");
        assertThat(activeStays()).isEqualTo(1);

        // Test data, not a workflow shortcut: discharge is correctly gated on an outstanding
        // balance, and settling it through the billing endpoints is a different checkpoint's
        // subject. What is under test here is that the stay closes with the admission.
        billings.findByIpdAdmissionId(admissionId).forEach(b -> {
            com.hms.entity.BillingPayment pay = new com.hms.entity.BillingPayment();
            pay.setBillingId(b.getId());
            pay.setHospitalId(hospital.getId());
            pay.setAmount(b.getAmount());
            pay.setMode("CASH");
            billingPayments.save(pay);
            b.setPaymentStatus("PAID");
            billings.save(b);
        });

        // Discharge is two steps in production: the doctor plans it, reception confirms it.
        ok(post("/hospital/ipd/" + admissionId + "/plan-discharge", doctorToken,
                "{\"dischargeType\":\"NORMAL\",\"summary\":\"Recovered\"}"),
                "the doctor plans the discharge");

        ResponseEntity<String> discharged = rest.exchange(
                "/hospital/ipd/" + admissionId + "/confirm-discharge", HttpMethod.POST,
                new HttpEntity<>(headers(receptionToken)), String.class);
        assertThat(discharged.getStatusCode().is2xxSuccessful())
                .as("discharge -> %s", discharged.getBody()).isTrue();

        assertThat(activeStays()).as("no episode may survive the discharge").isZero();
        assertThat(admissions.findById(admissionId).orElseThrow().getStatus()).isEqualTo("DISCHARGED");
    }

    /** A patient admitted straight into a unit gets an episode without any transfer. */
    @Test
    void aDirectAdmissionIntoAUnitOpensAStay() {
        assertThat(activeStays()).as("the general-ward admission opened nothing").isZero();
    }

    /** Reception still coordinates beds — removing clinical writes must not break that. */
    @Test
    void aReceptionistCanStillSeeTheBoardAndMovePatients() {
        ok(transfer(icuBedId, receptionToken), "reception can still move a patient into the ICU");
        ok(get("/hospital/icu/board", receptionToken), "reception can still see the ICU board");
    }

    // ── policy: tenancy ──────────────────────────────────────────────────────

    @Test
    void anotherFacilityCanNeitherSeeNorMutateThisStay() {
        ok(transfer(icuBedId, receptionToken), "patient is in the ICU");
        var stay = icuStays.findAll().stream()
                .filter(s -> hospital.getId().equals(s.getHospitalId())).findFirst().orElseThrow();

        Hospital other = tenant("Bravo", ICU_MODULES);
        String otherDoctor = tokenFor(other, "DOCTOR", ICU_MODULES);

        ResponseEntity<String> board = get("/hospital/icu/board", otherDoctor);
        ok(board, "the other facility's own board loads");
        assertThat(board.getBody())
                .as("one facility must never see another's ICU patients")
                .doesNotContain("ICU Journey Patient");

        assertThat(get("/hospital/icu/stays/" + stay.getPublicId(), otherDoctor).getStatusCode().value())
                .as("another facility's stay must read as missing").isEqualTo(404);

        assertThat(get("/hospital/nurse/io/admission/" + admissionId, otherDoctor).getBody())
                .as("another facility must not read this admission's fluid chart")
                .doesNotContain("250");

        ResponseEntity<String> write = post("/hospital/nurse/io", otherDoctor, ioBody());
        assertThat(write.getStatusCode().is2xxSuccessful())
                .as("another facility must not write to this admission -> %s", write.getBody())
                .isFalse();
    }

    // ── policy: entitlement ──────────────────────────────────────────────────

    @Test
    void aHospitalWithoutTheIcuModuleCannotReachIcuAtAll() {
        Hospital plain = tenant("NoIcu", NO_ICU_MODULES);
        String nurse = tokenFor(plain, "NURSE_INCHARGE", NO_ICU_MODULES);

        assertThat(get("/hospital/icu/board", nurse).getStatusCode().is2xxSuccessful())
                .as("the ICU board needs the ICU module").isFalse();

        assertThat(post("/hospital/nurse/io", nurse,
                "{\"ipdAdmissionId\":1,\"direction\":\"INTAKE\",\"route\":\"IV_FLUIDS\",\"volumeMl\":100}")
                .getStatusCode().is2xxSuccessful())
                .as("ICU fluid charting needs the ICU module, not merely IPD").isFalse();

        assertThat(post("/hospital/nurse/ventilator", nurse,
                "{\"ipdAdmissionId\":1,\"ventilationStatus\":\"INVASIVE\"}")
                .getStatusCode().is2xxSuccessful())
                .as("ventilator records need the ICU module").isFalse();
    }

    /** The point of the previous test: IPD alone must not be a back door into ICU. */
    @Test
    void ipdWithoutIcuIsNotEnough() {
        Hospital plain = tenant("IpdOnly", NO_ICU_MODULES);
        String nurse = tokenFor(plain, "NURSE_INCHARGE", NO_ICU_MODULES);

        assertThat(NO_ICU_MODULES).contains("IPD").doesNotContain("ICU");
        assertThat(get("/hospital/icu/board", nurse).getStatusCode().value())
                .as("a fully IPD-enabled hospital still cannot open the ICU board").isNotEqualTo(200);
    }
}
