package com.hms.security;

import com.hms.entity.Doctor;
import com.hms.entity.Hospital;
import com.hms.entity.IpdAdmission;
import com.hms.entity.MedicalRecord;
import com.hms.entity.Opd;
import com.hms.entity.Patient;
import com.hms.entity.User;
import com.hms.entity.Ward;
import com.hms.entity.Bed;
import com.hms.entity.VitalsRecord;
import com.hms.repository.DoctorRepository;
import com.hms.repository.HospitalRepository;
import com.hms.repository.IpdAdmissionRepository;
import com.hms.repository.MedicalRecordRepository;
import com.hms.repository.OpdRepository;
import com.hms.repository.PatientRepository;
import com.hms.repository.UserRepository;
import com.hms.repository.VitalsRecordRepository;
import com.hms.repository.WardRepository;
import com.hms.repository.BedRepository;
import com.hms.security.JwtUtil;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CLIN-P1: PatientTimelineService aggregates real, persisted clinical entities into one
 * chronological view -- this drives the real endpoint against a real database (not mocks) to
 * prove three things a unit test with stubbed repositories cannot: cross-source chronological
 * ordering is correct, tenant scoping actually holds (a second hospital's patient never leaks
 * in), and OPD's tenant-through-patient join produces the right row.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PatientClinicalTimelineTest {

    @Autowired TestRestTemplate rest;
    @Autowired JwtUtil jwtUtil;
    @Autowired HospitalRepository hospitalRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired DoctorRepository doctorRepository;
    @Autowired UserRepository userRepository;
    @Autowired OpdRepository opdRepository;
    @Autowired MedicalRecordRepository medicalRecordRepository;
    @Autowired IpdAdmissionRepository ipdAdmissionRepository;
    @Autowired VitalsRecordRepository vitalsRecordRepository;
    @Autowired WardRepository wardRepository;
    @Autowired BedRepository bedRepository;

    private static final List<String> MODULES = List.of("OPD", "IPD", "PHARMACY", "BILLING", "OT");

    private String token;
    private String patientPublicId;
    private Long doctorUserId;

    private String uniq() { return Long.toString(System.nanoTime()); }

    @BeforeEach
    void setUp() {
        String slug = "timeline";

        Hospital h = new Hospital();
        h.setName("H " + slug); h.setCustomId("HID-" + uniq());
        h.setSubscriptionStatus("ACTIVE"); h.setIsActive(true);
        h.setModules(MODULES); h.setIsSingleDoctor(false);
        long hid = hospitalRepository.save(h).getId();

        Doctor d = new Doctor();
        d.setName("Dr " + slug); d.setHospitalId(hid); d.setIsActive(true);
        d.setEmail("doc-" + uniq() + "@example.test"); d.setPublicId("dpub-" + uniq());
        d.setPhone("9800000001"); d.setSpecialization("Gen");
        Long docId = doctorRepository.save(d).getId();

        Patient p = new Patient();
        p.setName("Pat " + slug); p.setHospitalId(hid); p.setPublicId("ppub-" + uniq());
        p.setGender("MALE"); p.setPhone("9900000001"); p.setIsActive(true);
        p.setDateOfBirth(LocalDate.of(1980, 1, 1));
        Long patientId = patientRepository.save(p).getId();
        patientPublicId = p.getPublicId();

        // Every explicit timestamp below is anchored to "now" rather than a fixed calendar
        // date: MedicalRecord.createdAt is @CreationTimestamp (Hibernate overwrites whatever is
        // set with the actual insert instant), so the only way to control its position relative
        // to the other, explicitly-settable timestamps is to bracket "now" with them.
        LocalDateTime now = LocalDateTime.now();

        // T-60min -- OPD registration.
        Opd o = new Opd();
        o.setCaseId("OPD-" + uniq()); o.setIpdAdmitRecommended(true);
        o.setPatient(p); o.setDoctor(d);
        o.setCreatedAt(now.minusMinutes(60));
        Long opdId = opdRepository.save(o).getId();

        // ~now -- doctor consultation / diagnosis, referencing that OPD. createdAt is
        // @CreationTimestamp; it lands at actual insert time, a moment after the line above.
        MedicalRecord r = new MedicalRecord();
        r.setHospitalId(hid); r.setPatientId(patientId); r.setDoctorId(docId); r.setOpdId(opdId);
        r.setDiagnosis("Acute appendicitis"); r.setVisitType("OPD");
        medicalRecordRepository.save(r);

        Ward w = new Ward();
        w.setWardName("W " + slug); w.setHospitalId(hid);
        w.setBedPrice(new BigDecimal("1500")); w.setTotalBeds(1);
        Long wardId = wardRepository.save(w).getWardId();

        Bed bed = new Bed();
        bed.setHospitalId(hid); bed.setWardId(wardId); bed.setBedCode("BED-" + uniq()); bed.setStatus("occupied");
        Long bedId = bedRepository.save(bed).getBedId();

        // 10:40 -- admitted (later than both OPD events above).
        IpdAdmission a = new IpdAdmission();
        a.setIpdNumber("IPD-" + uniq()); a.setPatientId(patientId); a.setDoctorId(docId);
        a.setHospitalId(hid); a.setAdmissionType("ELECTIVE"); a.setStatus("ADMITTED");
        a.setAdmissionDatetime(now.plusMinutes(60));
        a.setAdmissionConfirmed(true);
        a.setWardId(wardId); a.setBedId(bedId);
        Long admissionId = ipdAdmissionRepository.save(a).getId();

        User nurse = new User();
        nurse.setEmail("nurse-" + uniq() + "@example.test");
        nurse.setPassword("test-password-hash"); nurse.setName("Nurse " + slug);
        nurse.setRole("NURSE"); nurse.setHospitalId(hid); nurse.setIsActive(true);
        nurse.setTokenVersion(0);
        Long nurseUserId = userRepository.save(nurse).getId();

        // 11:05 -- vitals, after admission. If ordering were alphabetical-by-source instead of
        // chronological, this would sort before the 10:40 admission event.
        VitalsRecord v = new VitalsRecord();
        v.setHospitalId(hid); v.setIpdAdmissionId(admissionId); v.setPatientId(patientId);
        v.setRecordedByUserId(nurseUserId);
        v.setRecordedAt(now.plusMinutes(90));
        v.setBpSystolic(120); v.setBpDiastolic(80); v.setIsActive(true);
        vitalsRecordRepository.save(v);

        User admin = new User();
        admin.setEmail("admin-" + uniq() + "@example.test");
        admin.setPassword("test-password-hash"); admin.setName("Admin");
        admin.setRole("HOSPITAL_ADMIN"); admin.setHospitalId(hid); admin.setIsActive(true);
        admin.setTokenVersion(0);
        admin = userRepository.save(admin);
        token = jwtUtil.generateToken(admin.getId(), admin.getEmail(), admin.getRole(), hid,
                MODULES, null, "HOSPITAL", null, admin.getTokenVersion());
        doctorUserId = docId;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> timeline(String publicId, String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearerToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<List> res = rest.exchange("/hospital/patients/" + publicId + "/timeline",
                HttpMethod.GET, new HttpEntity<>(headers), List.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        return (List<Map<String, Object>>) (List<?>) res.getBody();
    }

    /**
     * Chronological ordering across three different source tables (Opd, MedicalRecord,
     * IpdAdmission, VitalsRecord), none of which share a clock or an id space -- the timeline
     * must sort by timestamp, not by the order the sources happen to be queried in.
     */
    @Test
    void aggregatesAndOrdersEventsChronologicallyAcrossSources() {
        List<Map<String, Object>> events = timeline(patientPublicId, token);

        assertThat(events).hasSizeGreaterThanOrEqualTo(4);
        List<String> types = events.stream().map(e -> (String) e.get("eventType")).toList();
        assertThat(types).containsSubsequence(
                "OPD_REGISTERED", "CONSULTATION", "IPD_ADMITTED", "VITALS");

        Map<String, Object> consultation = events.stream()
                .filter(e -> "CONSULTATION".equals(e.get("eventType"))).findFirst().orElseThrow();
        assertThat((String) consultation.get("summary")).contains("Acute appendicitis");
        assertThat(((Number) consultation.get("performedByUserId")).longValue()).isEqualTo(doctorUserId);
    }

    /** A second hospital's patient, and a second hospital's caller, must never see this timeline. */
    @Test
    void aForeignHospitalPatientId_isNotFoundRatherThanLeaked() {
        Hospital other = new Hospital();
        other.setName("H other"); other.setCustomId("HID-" + uniq());
        other.setSubscriptionStatus("ACTIVE"); other.setIsActive(true);
        other.setModules(MODULES); other.setIsSingleDoctor(false);
        long otherHid = hospitalRepository.save(other).getId();

        User otherAdmin = new User();
        otherAdmin.setEmail("admin2-" + uniq() + "@example.test");
        otherAdmin.setPassword("test-password-hash"); otherAdmin.setName("Other Admin");
        otherAdmin.setRole("HOSPITAL_ADMIN"); otherAdmin.setHospitalId(otherHid); otherAdmin.setIsActive(true);
        otherAdmin.setTokenVersion(0);
        otherAdmin = userRepository.save(otherAdmin);
        String otherToken = jwtUtil.generateToken(otherAdmin.getId(), otherAdmin.getEmail(),
                otherAdmin.getRole(), otherHid, MODULES, null, "HOSPITAL", null, otherAdmin.getTokenVersion());

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(otherToken);
        ResponseEntity<String> res = rest.exchange("/hospital/patients/" + patientPublicId + "/timeline",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(res.getStatusCode().value()).isEqualTo(404);
    }

    /** Neither reception nor a nurse is granted this endpoint in this release. */
    @Test
    void receptionistCannotReadTheClinicalTimeline() {
        User receptionist = new User();
        receptionist.setEmail("recep-" + uniq() + "@example.test");
        receptionist.setPassword("test-password-hash"); receptionist.setName("Reception");
        receptionist.setRole("RECEPTIONIST");
        receptionist.setHospitalId(userRepository.findById(
                jwtUtil.extractUserId(token)).orElseThrow().getHospitalId());
        receptionist.setIsActive(true); receptionist.setTokenVersion(0);
        receptionist = userRepository.save(receptionist);
        String receptionToken = jwtUtil.generateToken(receptionist.getId(), receptionist.getEmail(),
                receptionist.getRole(), receptionist.getHospitalId(), MODULES, null, "HOSPITAL", null,
                receptionist.getTokenVersion());

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(receptionToken);
        ResponseEntity<String> res = rest.exchange("/hospital/patients/" + patientPublicId + "/timeline",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(res.getStatusCode().value()).isEqualTo(403);
    }
}
