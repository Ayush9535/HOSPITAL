package com.hms.security;

import com.hms.entity.Bed;
import com.hms.entity.Doctor;
import com.hms.entity.Hospital;
import com.hms.entity.IpdAdmission;
import com.hms.entity.IpdBedHistory;
import com.hms.entity.NurseProfile;
import com.hms.entity.Patient;
import com.hms.entity.PatientNurseAssignment;
import com.hms.entity.ShiftTemplate;
import com.hms.entity.User;
import com.hms.entity.Ward;
import com.hms.repository.BedRepository;
import com.hms.repository.DoctorRepository;
import com.hms.repository.HospitalRepository;
import com.hms.repository.HospitalSettingRepository;
import com.hms.repository.IpdAdmissionRepository;
import com.hms.repository.IpdBedHistoryRepository;
import com.hms.repository.NurseProfileRepository;
import com.hms.repository.NurseShiftScheduleRepository;
import com.hms.repository.PatientNurseAssignmentRepository;
import com.hms.repository.PatientRepository;
import com.hms.repository.PrescriptionRepository;
import com.hms.repository.ShiftTemplateRepository;
import com.hms.repository.UserRepository;
import com.hms.repository.VitalsRecordRepository;
import com.hms.repository.WardRepository;
import com.hms.entity.NurseShiftSchedule;
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
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three nursing fields this checkpoint touches, proven against a real database:
 * the nurse's effective shift, pain on the vitals record, and food timing on a medication order.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class NursingClinicalFieldsTest {

    private static final AtomicLong SEQ = new AtomicLong();
    private static final List<String> MODULES = List.of("OPD", "IPD", "NURSING");

    @Autowired TestRestTemplate rest;
    @Autowired JwtUtil jwtUtil;
    @Autowired HospitalRepository hospitals;
    @Autowired HospitalSettingRepository settings;
    @Autowired UserRepository users;
    @Autowired WardRepository wards;
    @Autowired BedRepository beds;
    @Autowired PatientRepository patients;
    @Autowired DoctorRepository doctors;
    @Autowired NurseProfileRepository nurses;
    @Autowired IpdAdmissionRepository admissions;
    @Autowired IpdBedHistoryRepository bedHistory;
    @Autowired PatientNurseAssignmentRepository nurseAssignments;
    @Autowired PrescriptionRepository prescriptions;
    @Autowired VitalsRecordRepository vitals;
    @Autowired ShiftTemplateRepository shiftTemplates;
    @Autowired NurseShiftScheduleRepository shiftSchedules;

    private Hospital hospital;
    private Ward ward;
    private NurseProfile nurse;
    private IpdAdmission admission;
    private String adminToken, nurseToken, inchargeToken, doctorToken;

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
        h.setName("Nursing Hospital");
        h.setCustomId("NCF-" + uniq());
        h.setIsActive(true);
        h.setSubscriptionStatus("ACTIVE");
        h.setModules(MODULES);
        h.setIsSingleDoctor(false);
        h.setType(com.hms.entity.HospitalType.HOSPITAL);
        hospital = hospitals.save(h);

        com.hms.entity.HospitalSetting setting = new com.hms.entity.HospitalSetting();
        setting.setHospital(hospital);
        setting.setSeparateNurseLogin(true);
        settings.save(setting);

        Ward w = new Ward();
        w.setWardName("Nursing Ward");
        w.setHospitalId(hospital.getId());
        w.setBedPrice(new BigDecimal("1000"));
        w.setTotalBeds(1);
        ward = wards.save(w);

        User nurseUser = new User();
        nurseUser.setEmail("nurse." + uniq() + "@ncf.test");
        nurseUser.setPassword("{noop}fixture");
        nurseUser.setName("Nurse A");
        nurseUser.setRole("NURSE");
        nurseUser.setHospitalId(hospital.getId());
        nurseUser.setIsActive(true);
        nurseUser.setTokenVersion(0);
        nurseUser = users.save(nurseUser);
        nurseToken = jwtUtil.generateToken(nurseUser.getId(), nurseUser.getEmail(), "NURSE",
                hospital.getId(), MODULES, null, "HOSPITAL", null, 0);

        NurseProfile p = new NurseProfile();
        p.setHospitalId(hospital.getId());
        p.setUserId(nurseUser.getId());
        p.setName("Nurse A");
        p.setEmail(nurseUser.getEmail());
        p.setWardId(ward.getWardId());
        p.setIsIncharge(false);
        p.setIsActive(true);
        p.setOnShift(false);
        nurse = nurses.save(p);

        User inchargeUser = new User();
        inchargeUser.setEmail("incharge." + uniq() + "@ncf.test");
        inchargeUser.setPassword("{noop}fixture");
        inchargeUser.setName("Incharge");
        inchargeUser.setRole("NURSE_INCHARGE");
        inchargeUser.setHospitalId(hospital.getId());
        inchargeUser.setIsActive(true);
        inchargeUser.setTokenVersion(0);
        inchargeUser = users.save(inchargeUser);
        inchargeToken = jwtUtil.generateToken(inchargeUser.getId(), inchargeUser.getEmail(),
                "NURSE_INCHARGE", hospital.getId(), MODULES, null, "HOSPITAL", null, 0);

        NurseProfile inchargeProfile = new NurseProfile();
        inchargeProfile.setHospitalId(hospital.getId());
        inchargeProfile.setUserId(inchargeUser.getId());
        inchargeProfile.setName("Incharge");
        inchargeProfile.setEmail(inchargeUser.getEmail());
        inchargeProfile.setWardId(ward.getWardId());
        inchargeProfile.setIsIncharge(true);
        inchargeProfile.setIsActive(true);
        inchargeProfile = nurses.save(inchargeProfile);
        ward.setInchargeNurseId(inchargeProfile.getId());
        wards.save(ward);

        String docEmail = "doc." + uniq() + "@ncf.test";
        Doctor d = new Doctor();
        d.setHospitalId(hospital.getId());
        d.setName("Dr N");
        d.setEmail(docEmail);
        d.setPublicId("dpub-" + uniq());
        d.setPhone("9800000030");
        d.setSpecialization("Gen");
        d.setIsActive(true);
        d = doctors.save(d);
        doctorToken = tokenFor("DOCTOR", docEmail);
        adminToken = tokenFor("HOSPITAL_ADMIN", "admin." + uniq() + "@ncf.test");

        Patient pat = new Patient();
        pat.setHospitalId(hospital.getId());
        pat.setName("Pat N");
        pat.setPublicId("ppub-" + uniq());
        pat.setGender("MALE");
        pat.setPhone("9900000030");
        pat.setIsActive(true);
        pat.setDateOfBirth(LocalDate.of(1980, 1, 1));
        pat = patients.save(pat);

        Bed bed = new Bed();
        bed.setHospitalId(hospital.getId());
        bed.setWardId(ward.getWardId());
        bed.setBedCode("BED-" + uniq());
        bed.setStatus("occupied");
        bed = beds.save(bed);

        IpdAdmission adm = new IpdAdmission();
        adm.setHospitalId(hospital.getId());
        adm.setPatientId(pat.getId());
        adm.setDoctorId(d.getId());
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

        PatientNurseAssignment link = new PatientNurseAssignment();
        link.setHospitalId(hospital.getId());
        link.setIpdAdmissionId(admission.getId());
        link.setPatientId(pat.getId());
        link.setNurseUserId(nurseUser.getId());
        link.setAssignedByUserId(nurseUser.getId());
        link.setIsActive(true);
        link.setAssignedAt(LocalDateTime.now());
        nurseAssignments.save(link);
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> adminNurseRow() {
        ResponseEntity<Map> res = rest.exchange("/hospital/nurses?page=0&size=50", HttpMethod.GET,
                new HttpEntity<>(headers(adminToken)), Map.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        List<Map<String, Object>> content = (List<Map<String, Object>>) res.getBody().get("content");
        return content.stream()
                .filter(r -> nurse.getId().equals(((Number) r.getOrDefault("nurseProfileId", -1)).longValue()))
                .findFirst().orElseThrow(() -> new AssertionError("nurse missing from the admin list: " + content));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> inchargeNurseRow() {
        ResponseEntity<List> res = rest.exchange("/hospital/nurse-incharge/nurses", HttpMethod.GET,
                new HttpEntity<>(headers(inchargeToken)), List.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        List<Map<String, Object>> rows = res.getBody();
        return rows.stream()
                .filter(r -> nurse.getId().equals(((Number) r.get("nurseProfileId")).longValue()))
                .findFirst().orElseThrow(() -> new AssertionError("nurse missing from the ward roster: " + rows));
    }

    private void rosterShiftForToday(String name, LocalTime start, LocalTime end) {
        ShiftTemplate t = new ShiftTemplate();
        t.setHospitalId(hospital.getId());
        t.setName(name);
        t.setStartTime(start);
        t.setEndTime(end);
        t.setIsActive(true);
        t = shiftTemplates.save(t);

        NurseShiftSchedule s = new NurseShiftSchedule();
        s.setHospitalId(hospital.getId());
        s.setNurseProfileId(nurse.getId());
        s.setWardId(ward.getWardId());
        s.setShiftDate(LocalDate.now());
        s.setShiftTemplateId(t.getId());
        s.setStartTime(start);
        s.setEndTime(end);
        shiftSchedules.save(s);
    }

    // ------------------------------------------------------------------ shift

    /** Nothing rostered: both screens must say the same nothing. */
    @Test
    void withNoShiftRostered_adminAndInchargeBothReportNone() {
        Map<String, Object> admin = adminNurseRow();
        Map<String, Object> incharge = inchargeNurseRow();

        assertThat(admin.get("shiftName")).isNull();
        assertThat(incharge.get("shiftName")).isNull();
        assertThat(admin.get("onShiftNow")).isEqualTo(false);
        assertThat(incharge.get("onShiftNow")).isEqualTo(false);
    }

    /**
     * The reported bug: the incharge saw a live shift and the admin saw nothing. Both now read the
     * same resolver, so they cannot disagree about one nurse.
     */
    @Test
    void withAShiftRostered_adminAndInchargeAgree() {
        rosterShiftForToday("Morning", LocalTime.of(0, 0), LocalTime.of(23, 59));

        Map<String, Object> admin = adminNurseRow();
        Map<String, Object> incharge = inchargeNurseRow();

        assertThat(admin.get("shiftName")).isEqualTo("Morning");
        assertThat(incharge.get("shiftName")).isEqualTo("Morning");
        assertThat(admin.get("shiftStartTime")).isEqualTo(incharge.get("shiftStartTime"));
        assertThat(admin.get("shiftEndTime")).isEqualTo(incharge.get("shiftEndTime"));
        assertThat(admin.get("onShiftNow"))
                .as("a window covering the whole day is on now")
                .isEqualTo(true)
                .isEqualTo(incharge.get("onShiftNow"));
    }

    /**
     * A substitution is written as today's schedule, so reading today reads the substitution --
     * there is no second source of truth to consult, and both screens follow it.
     */
    @Test
    void aSubstitutedShiftIsWhatBothScreensShow() {
        rosterShiftForToday("Night (cover)", LocalTime.of(22, 0), LocalTime.of(6, 0));

        assertThat(adminNurseRow().get("shiftName")).isEqualTo("Night (cover)");
        assertThat(inchargeNurseRow().get("shiftName")).isEqualTo("Night (cover)");
        assertThat(adminNurseRow().get("shiftStartTime")).asString().startsWith("22:00");
    }

    /** Re-reading gives the same answer -- this is the "refresh and it changes" complaint. */
    @Test
    void theShiftIsStableAcrossReads() {
        rosterShiftForToday("Evening", LocalTime.of(0, 0), LocalTime.of(23, 59));

        assertThat(adminNurseRow().get("shiftName")).isEqualTo("Evening");
        assertThat(adminNurseRow().get("shiftName")).isEqualTo("Evening");
        assertThat(inchargeNurseRow().get("shiftName")).isEqualTo("Evening");
    }

    // ------------------------------------------------------------------ pain

    /**
     * Pain already had a home -- vitals_records.pain_score, 0-10 -- so nothing new is stored. What
     * matters is that the two clinically different answers survive a round trip: "no pain" is a
     * finding, "not assessed" is the absence of one.
     */
    @Test
    void painIsPersistedAndNotAssessedStaysDistinctFromNoPain() {
        String body = "{\"ipdAdmissionId\":" + admission.getId() + ",\"painScore\":0,\"pulse\":72}";
        assertThat(post("/hospital/nurse/vitals", nurseToken, body).getStatusCode().value()).isEqualTo(200);

        String unassessed = "{\"ipdAdmissionId\":" + admission.getId() + ",\"pulse\":80}";
        assertThat(post("/hospital/nurse/vitals", nurseToken, unassessed).getStatusCode().value()).isEqualTo(200);

        String inPain = "{\"ipdAdmissionId\":" + admission.getId() + ",\"painScore\":7,\"pulse\":90}";
        assertThat(post("/hospital/nurse/vitals", nurseToken, inPain).getStatusCode().value()).isEqualTo(200);

        List<com.hms.entity.VitalsRecord> stored =
                vitals.findByIpdAdmissionIdAndIsActiveTrueOrderByRecordedAtDesc(admission.getId());
        assertThat(stored).extracting(com.hms.entity.VitalsRecord::getPainScore)
                .containsExactlyInAnyOrder(0, null, 7);
        assertThat(stored).allSatisfy(v ->
                assertThat(v.getHospitalId()).as("tenant scoped").isEqualTo(hospital.getId()));
    }

    /** The score is the existing 0-10 scale, and out-of-range values are still refused. */
    @Test
    void anOutOfRangePainScoreIsRejected() {
        String body = "{\"ipdAdmissionId\":" + admission.getId() + ",\"painScore\":11}";
        assertThat(post("/hospital/nurse/vitals", nurseToken, body).getStatusCode().value()).isEqualTo(400);
    }

    /** Pain reaches the longitudinal record, so it is findable outside the form that captured it. */
    @Test
    void painAppearsOnThePatientTimeline() {
        assertThat(post("/hospital/nurse/vitals", nurseToken,
                "{\"ipdAdmissionId\":" + admission.getId() + ",\"painScore\":7}")
                .getStatusCode().value()).isEqualTo(200);

        ResponseEntity<String> timeline = rest.exchange(
                "/hospital/patients/" + patients.findById(admission.getPatientId()).orElseThrow().getPublicId()
                        + "/timeline",
                HttpMethod.GET, new HttpEntity<>(headers(doctorToken)), String.class);

        assertThat(timeline.getStatusCode().value()).isEqualTo(200);
        assertThat(timeline.getBody()).contains("pain 7/10");
    }

    // ----------------------------------------------------------- food timing

    /** A controlled value round-trips onto the order and back out on the nurse's chart. */
    @Test
    void foodTimingIsPersistedAndShownOnTheChart() {
        String order = "{\"medicineName\":\"Amoxicillin\",\"dose\":\"500mg\",\"frequency\":\"1-0-1\","
                + "\"durationDays\":3,\"startDate\":\"" + LocalDate.now() + "\","
                + "\"type\":\"TABLET\",\"route\":\"ORAL\",\"foodTiming\":\"AFTER_FOOD\"}";
        assertThat(post("/hospital/ipd/" + admission.getId() + "/prescriptions", doctorToken, order)
                .getStatusCode().value()).isEqualTo(200);

        assertThat(prescriptions.findByIpdAdmissionIdAndStatus(admission.getId(), "ACTIVE"))
                .singleElement()
                .satisfies(p -> assertThat(p.getFoodTiming()).isEqualTo("AFTER_FOOD"));

        ResponseEntity<String> chart = rest.exchange(
                "/hospital/nurse/medication/admission/" + admission.getId() + "/chart",
                HttpMethod.GET, new HttpEntity<>(headers(nurseToken)), String.class);
        assertThat(chart.getBody()).contains("\"foodTiming\":\"AFTER_FOOD\"");
    }

    /** An order that says nothing about food stores nothing -- silence is not "not specified". */
    @Test
    void anOrderWithoutFoodTimingStoresNull() {
        String order = "{\"medicineName\":\"Paracetamol\",\"dose\":\"500mg\",\"frequency\":\"1-0-1\","
                + "\"durationDays\":2,\"startDate\":\"" + LocalDate.now() + "\","
                + "\"type\":\"TABLET\",\"route\":\"ORAL\"}";
        assertThat(post("/hospital/ipd/" + admission.getId() + "/prescriptions", doctorToken, order)
                .getStatusCode().value()).isEqualTo(200);

        assertThat(prescriptions.findByIpdAdmissionIdAndStatus(admission.getId(), "ACTIVE"))
                .singleElement()
                .satisfies(p -> assertThat(p.getFoodTiming()).isNull());
    }

    /** A value outside the vocabulary is refused rather than stored as free text. */
    @Test
    void anUnknownFoodTimingIsRefused() {
        String order = "{\"medicineName\":\"Ibuprofen\",\"dose\":\"400mg\",\"frequency\":\"1-0-1\","
                + "\"durationDays\":2,\"startDate\":\"" + LocalDate.now() + "\","
                + "\"type\":\"TABLET\",\"route\":\"ORAL\",\"foodTiming\":\"WHENEVER\"}";
        ResponseEntity<String> res = post("/hospital/ipd/" + admission.getId() + "/prescriptions",
                doctorToken, order);

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(prescriptions.findByIpdAdmissionIdAndStatus(admission.getId(), "ACTIVE")).isEmpty();
    }

    /**
     * Historical orders predate the field and keep their free-text instructions. Food timing lives
     * in its own column precisely so adopting a vocabulary did not overwrite what was already
     * written there.
     */
    @Test
    void aHistoricalOrderKeepsItsFreeTextInstructionsAndHasNoFoodTiming() {
        com.hms.entity.MedicalRecord record = new com.hms.entity.MedicalRecord();
        record.setHospitalId(hospital.getId());
        record.setPatientId(admission.getPatientId());
        record.setDoctorId(admission.getDoctorId());
        record.setIpdAdmissionId(admission.getId());
        record.setVisitType("IPD");
        record = medicalRecords.save(record);

        com.hms.entity.Prescription legacy = new com.hms.entity.Prescription();
        legacy.setHospitalId(hospital.getId());
        legacy.setMedicalRecordId(record.getId());
        legacy.setMedicineName("Legacy medicine");
        legacy.setInstructions("After food, with plenty of water");
        legacy.setStatus("ACTIVE");
        legacy.setType("TABLET");
        legacy.setRoute("ORAL");
        legacy.setStartDate(LocalDate.now());
        legacy.setDurationDays(3);
        legacy = prescriptions.save(legacy);

        assertThat(prescriptions.findById(legacy.getId()).orElseThrow().getInstructions())
                .isEqualTo("After food, with plenty of water");
        assertThat(prescriptions.findById(legacy.getId()).orElseThrow().getFoodTiming()).isNull();

        ResponseEntity<String> chart = rest.exchange(
                "/hospital/nurse/medication/admission/" + admission.getId() + "/chart",
                HttpMethod.GET, new HttpEntity<>(headers(nurseToken)), String.class);
        assertThat(chart.getBody())
                .as("the legacy instruction still renders")
                .contains("After food, with plenty of water");
    }

    @Autowired com.hms.repository.MedicalRecordRepository medicalRecords;
}
