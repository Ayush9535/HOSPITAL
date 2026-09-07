package com.hms.service.hospital;

import com.hms.entity.Bed;
import com.hms.entity.Doctor;
import com.hms.entity.Hospital;
import com.hms.entity.IpdAdmission;
import com.hms.entity.Patient;
import com.hms.entity.Surgery;
import com.hms.entity.SurgeryStatus;
import com.hms.entity.User;
import com.hms.entity.Ward;
import com.hms.repository.BedRepository;
import com.hms.repository.DoctorRepository;
import com.hms.repository.HospitalRepository;
import com.hms.repository.IpdAdmissionRepository;
import com.hms.repository.PatientRepository;
import com.hms.repository.SurgeryRepository;
import com.hms.repository.UserRepository;
import com.hms.repository.WardRepository;
import com.hms.security.JwtUtil;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OtBedConcurrencyTest {
    private static final List<String> MODULES = List.of("OPD", "IPD", "BILLING", "OT");
    private static final AtomicInteger IPD_SEQUENCE = new AtomicInteger(800_000);

    @Autowired TestRestTemplate rest;
    @Autowired JwtUtil jwtUtil;
    @Autowired HospitalRepository hospitalRepository;
    @Autowired UserRepository userRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired DoctorRepository doctorRepository;
    @Autowired WardRepository wardRepository;
    @Autowired BedRepository bedRepository;
    @Autowired IpdAdmissionRepository ipdAdmissionRepository;
    @Autowired SurgeryRepository surgeryRepository;
    @MockBean com.hms.service.hospital.ot.OtPolicyService otPolicyService;

    private String token;
    private Long otBedId;
    private String firstSurgery;
    private String secondSurgery;

    @BeforeEach
    void setUp() {
        long hospitalId = hospitalRepository.save(hospital("ot-bed")).getId();
        User user = userRepository.save(user(hospitalId));
        token = jwtUtil.generateToken(user.getId(), user.getEmail(), user.getRole(), hospitalId,
                MODULES, null, "HOSPITAL", null, user.getTokenVersion());

        Doctor doctor = doctorRepository.save(doctor(hospitalId));
        Ward otWard = wardRepository.save(ward(hospitalId, "OT-" + unique()));
        otBedId = bedRepository.save(bed(hospitalId, otWard.getWardId(), "available")).getBedId();
        Ward admissionWard = wardRepository.save(ward(hospitalId, "WARD-" + unique()));

        firstSurgery = surgeryFor(hospitalId, doctor.getId(), admissionWard.getWardId(), otWard.getWardId());
        secondSurgery = surgeryFor(hospitalId, doctor.getId(), admissionWard.getWardId(), otWard.getWardId());
    }

    @RepeatedTest(3)
    void twoSurgeriesStartingInOneTheatre_onlyOneMayTakeTheBed() throws Exception {
        ResponseEntity<String>[] results = race(() -> start(firstSurgery), () -> start(secondSurgery));

        assertOneSuccessAndConflict(results);
        assertThat(inProgressSurgeriesHoldingOtBed()).isEqualTo(1);
        assertThat(bedRepository.findById(otBedId).orElseThrow().getStatus()).isEqualToIgnoringCase("occupied");
    }

    @Test
    void startingASecondSurgeryAfterTheBedIsTaken_returnsConflict() {
        assertThat(start(firstSurgery).getStatusCode().value()).isEqualTo(200);

        ResponseEntity<String> second = start(secondSurgery);

        assertThat(second.getStatusCode().value()).isEqualTo(409);
        assertThat(second.getBody()).contains("\"code\":\"CONFLICT\"").containsIgnoringCase("bed");
        assertThat(inProgressSurgeriesHoldingOtBed()).isEqualTo(1);
    }

    private ResponseEntity<String> start(String surgeryPublicId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange("/hospital/surgeries/" + surgeryPublicId + "/start", HttpMethod.POST,
                new HttpEntity<>(null, headers), String.class);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<String>[] race(Callable<ResponseEntity<String>> first,
                                          Callable<ResponseEntity<String>> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            Future<ResponseEntity<String>> one = pool.submit(() -> { barrier.await(); return first.call(); });
            Future<ResponseEntity<String>> two = pool.submit(() -> { barrier.await(); return second.call(); });
            return new ResponseEntity[] { one.get(60, TimeUnit.SECONDS), two.get(60, TimeUnit.SECONDS) };
        } finally {
            pool.shutdownNow();
        }
    }

    private void assertOneSuccessAndConflict(ResponseEntity<String>[] results) {
        List<Integer> statuses = List.of(results[0].getStatusCode().value(), results[1].getStatusCode().value());
        assertThat(statuses).filteredOn(status -> status == 200).hasSize(1);
        assertThat(statuses).filteredOn(status -> status == 409).hasSize(1);
        String loser = results[0].getStatusCode().value() == 200 ? results[1].getBody() : results[0].getBody();
        assertThat(loser).contains("\"code\":\"CONFLICT\"").containsIgnoringCase("bed");
    }

    private long inProgressSurgeriesHoldingOtBed() {
        return surgeryRepository.findAll().stream()
                .filter(surgery -> otBedId.equals(surgery.getOtBedId()))
                .filter(surgery -> SurgeryStatus.IN_PROGRESS.name().equals(surgery.getStatus()))
                .count();
    }

    private String surgeryFor(long hospitalId, long doctorId, long admissionWardId, long otWardId) {
        Patient patient = patientRepository.save(patient(hospitalId));
        Bed admissionBed = bedRepository.save(bed(hospitalId, admissionWardId, "occupied"));
        IpdAdmission admission = new IpdAdmission();
        admission.setIpdNumber("IPD-" + IPD_SEQUENCE.incrementAndGet());
        admission.setPatientId(patient.getId());
        admission.setDoctorId(doctorId);
        admission.setHospitalId(hospitalId);
        admission.setAdmissionType("ELECTIVE");
        admission.setStatus("ADMITTED");
        admission.setAdmissionDatetime(LocalDateTime.now());
        admission.setWardId(admissionWardId);
        admission.setBedId(admissionBed.getBedId());
        admission.setAdmissionConfirmed(true);
        admission = ipdAdmissionRepository.save(admission);

        Surgery surgery = new Surgery();
        surgery.setPublicId("surgery-" + unique());
        surgery.setHospitalId(hospitalId);
        surgery.setIpdAdmissionId(admission.getId());
        surgery.setPatientId(patient.getId());
        surgery.setProcedureName("Appendectomy");
        surgery.setPriority("ELECTIVE");
        surgery.setRequestedAt(LocalDateTime.now());
        surgery.setStatus(SurgeryStatus.SCHEDULED.name());
        surgery.setScheduledAt(LocalDateTime.now().plusMinutes(10));
        surgery.setOtWardId(otWardId);
        return surgeryRepository.save(surgery).getPublicId();
    }

    private Hospital hospital(String suffix) {
        Hospital hospital = new Hospital();
        hospital.setName("Hospital " + suffix + unique());
        hospital.setCustomId("HID-" + unique());
        hospital.setSubscriptionStatus("ACTIVE");
        hospital.setIsActive(true);
        hospital.setModules(MODULES);
        return hospital;
    }

    private User user(long hospitalId) {
        User user = new User();
        user.setEmail("ot-bed-" + unique() + "@test.local");
        user.setPassword("{noop}x");
        user.setName("OT Reception");
        user.setRole("RECEPTIONIST");
        user.setHospitalId(hospitalId);
        user.setIsActive(true);
        return user;
    }

    private Doctor doctor(long hospitalId) {
        Doctor doctor = new Doctor();
        doctor.setName("Dr Test");
        doctor.setHospitalId(hospitalId);
        doctor.setIsActive(true);
        doctor.setEmail("doctor-" + unique() + "@test.local");
        doctor.setPublicId("doctor-" + unique());
        doctor.setPhone("9000000000");
        doctor.setSpecialization("General");
        return doctor;
    }

    private Ward ward(long hospitalId, String name) {
        Ward ward = new Ward();
        ward.setHospitalId(hospitalId);
        ward.setWardName(name);
        ward.setBedPrice(BigDecimal.ZERO);
        ward.setTotalBeds(5);
        return ward;
    }

    private Bed bed(long hospitalId, long wardId, String status) {
        Bed bed = new Bed();
        bed.setHospitalId(hospitalId);
        bed.setWardId(wardId);
        bed.setBedCode("BED-" + unique());
        bed.setStatus(status);
        return bed;
    }

    private Patient patient(long hospitalId) {
        Patient patient = new Patient();
        patient.setHospitalId(hospitalId);
        patient.setName("Patient " + unique());
        patient.setPublicId("patient-" + unique());
        patient.setGender("MALE");
        patient.setPhone("9100000000");
        patient.setDateOfBirth(LocalDate.of(1980, 1, 1));
        patient.setIsActive(true);
        return patient;
    }

    private String unique() {
        return Long.toString(System.nanoTime());
    }
}
