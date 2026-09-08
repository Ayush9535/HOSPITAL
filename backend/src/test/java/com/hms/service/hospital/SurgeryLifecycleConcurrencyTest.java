package com.hms.service.hospital;

import com.hms.entity.Bed;
import com.hms.entity.BedStatus;
import com.hms.entity.Doctor;
import com.hms.entity.Hospital;
import com.hms.entity.IpdAdmission;
import com.hms.entity.OtRoom;
import com.hms.entity.OtRoomOccupancy;
import com.hms.entity.Patient;
import com.hms.entity.Surgery;
import com.hms.entity.SurgeryStateTransition;
import com.hms.entity.SurgeryStatus;
import com.hms.entity.User;
import com.hms.entity.Ward;
import com.hms.repository.BedRepository;
import com.hms.repository.DoctorRepository;
import com.hms.repository.HospitalRepository;
import com.hms.repository.IpdAdmissionRepository;
import com.hms.repository.OtRoomOccupancyRepository;
import com.hms.repository.OtRoomRepository;
import com.hms.repository.PatientRepository;
import com.hms.repository.SurgeryRepository;
import com.hms.repository.SurgeryStateTransitionRepository;
import com.hms.repository.UserRepository;
import com.hms.repository.WardRepository;
import com.hms.security.JwtUtil;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SurgeryLifecycleConcurrencyTest {
    private static final List<String> MODULES = List.of("OPD", "IPD", "BILLING", "OT");
    private static final AtomicInteger IDS = new AtomicInteger(970_000);

    @Autowired TestRestTemplate rest;
    @Autowired JwtUtil jwtUtil;
    @Autowired HospitalRepository hospitalRepository;
    @Autowired UserRepository userRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired DoctorRepository doctorRepository;
    @Autowired WardRepository wardRepository;
    @Autowired BedRepository bedRepository;
    @Autowired IpdAdmissionRepository admissionRepository;
    @Autowired SurgeryRepository surgeryRepository;
    @Autowired SurgeryStateTransitionRepository transitionRepository;
    @Autowired OtRoomRepository roomRepository;
    @Autowired OtRoomOccupancyRepository occupancyRepository;
    @MockBean com.hms.service.hospital.ot.OtPolicyService policyService;

    private long hospitalId;
    private long doctorId;
    private String token;

    @BeforeEach
    void setUp() {
        hospitalId = hospitalRepository.save(hospital("lifecycle")).getId();
        User user = userRepository.save(user(hospitalId, "reception"));
        token = tokenFor(user, hospitalId);
        doctorId = doctorRepository.save(doctor(hospitalId)).getId();
    }

    @RepeatedTest(3)
    void concurrentScheduleWithTheSameVersionCommitsExactlyOneThenFreshRescheduleWorks() throws Exception {
        Fixture fixture = fixture(SurgeryStatus.APPROVED, false, false);
        Ward alternate = wardRepository.save(ward(hospitalId, "OT-ALT-" + IDS.incrementAndGet()));
        LocalDateTime firstSlot = LocalDateTime.now().plusDays(1).truncatedTo(ChronoUnit.MICROS);
        LocalDateTime secondSlot = firstSlot.plusHours(2);
        LocalDateTime freshSlot = secondSlot.plusHours(2);

        ResponseEntity<String>[] results = race(
                () -> schedule(fixture.publicId, fixture.otWardId, firstSlot, 0L, token),
                () -> schedule(fixture.publicId, alternate.getWardId(), secondSlot, 0L, token));

        assertOneSuccessAndConflict(results);
        Surgery saved = surgeryRepository.findById(fixture.surgeryId).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(SurgeryStatus.SCHEDULED.name());
        assertThat(saved.getLifecycleVersion()).isGreaterThan(0L);
        assertThat(transitionCount(fixture.surgeryId, SurgeryStatus.SCHEDULED)).isEqualTo(1);
        long winningVersion = saved.getLifecycleVersion();
        Long winningWard = saved.getOtWardId();
        LocalDateTime winningSlot = saved.getScheduledAt();

        ResponseEntity<String> fresh = schedule(fixture.publicId, alternate.getWardId(), freshSlot,
                saved.getLifecycleVersion(), token);

        assertThat(fresh.getStatusCode().value()).isEqualTo(200);
        Surgery rescheduled = surgeryRepository.findById(fixture.surgeryId).orElseThrow();
        assertThat(rescheduled.getLifecycleVersion()).isGreaterThan(winningVersion);
        assertThat(rescheduled.getOtWardId()).isEqualTo(alternate.getWardId());
        assertThat(rescheduled.getScheduledAt()).isEqualTo(freshSlot);
        assertThat(transitionCount(fixture.surgeryId, SurgeryStatus.SCHEDULED)).isEqualTo(2);
        assertThat(winningWard).isNotNull();
        assertThat(winningSlot).isNotNull();
    }

    @RepeatedTest(3)
    void startAndCancelSerializeWithoutCancelledOccupiedResources() throws Exception {
        Fixture fixture = fixture(SurgeryStatus.SCHEDULED, false, true);
        ResponseEntity<String>[] results = race(
                () -> post(fixture.publicId, "start", Map.of(), token),
                () -> post(fixture.publicId, "cancel", Map.of(), token));

        assertOneSuccessAndClientFailure(results);
        assertStartRaceInvariant(fixture);
    }

    @RepeatedTest(3)
    void startAndPostponeSerializeWithoutApprovedOccupiedResources() throws Exception {
        Fixture fixture = fixture(SurgeryStatus.SCHEDULED, false, true);
        ResponseEntity<String>[] results = race(
                () -> post(fixture.publicId, "start", Map.of(), token),
                () -> post(fixture.publicId, "postpone", Map.of(), token));

        assertOneSuccessAndClientFailure(results);
        assertStartRaceInvariant(fixture);
    }

    @RepeatedTest(3)
    void cancelAndPostponeProduceOnlyACommittedLegalHistory() throws Exception {
        Fixture fixture = fixture(SurgeryStatus.SCHEDULED, false, false);
        ResponseEntity<String>[] results = race(
                () -> post(fixture.publicId, "cancel", Map.of(), token),
                () -> post(fixture.publicId, "postpone", Map.of(), token));

        assertThat(results[0].getStatusCode().value()).isIn(200, 400);
        assertThat(results[1].getStatusCode().value()).isIn(200, 400);
        Surgery surgery = surgeryRepository.findById(fixture.surgeryId).orElseThrow();
        List<SurgeryStateTransition> transitions = transitionRepository.findBySurgeryIdOrderByCreatedAtAsc(fixture.surgeryId);
        assertThat(transitions).allSatisfy(t -> assertThat(t.getFromStatus()).isNotEqualTo(SurgeryStatus.CANCELLED.name()));
        assertThat(surgery.getStatus()).isIn(SurgeryStatus.CANCELLED.name(), SurgeryStatus.APPROVED.name());
        assertNoOtResourcesHeld(fixture);
        assertIpdBedUnchanged(fixture);
    }

    @RepeatedTest(3)
    void completeAndCompleteReleaseResourcesOnce() throws Exception {
        Fixture fixture = fixture(SurgeryStatus.IN_PROGRESS, true, true);
        ResponseEntity<String>[] results = race(
                () -> post(fixture.publicId, "complete", Map.of(), token),
                () -> post(fixture.publicId, "complete", Map.of(), token));

        assertOneSuccessAndClientFailure(results);
        assertThat(transitionCount(fixture.surgeryId, SurgeryStatus.COMPLETED)).isEqualTo(1);
        assertCompletionInvariant(fixture);
    }

    @RepeatedTest(3)
    void completeAndCancelLeaveNoPartialCancellationCleanup() throws Exception {
        Fixture fixture = fixture(SurgeryStatus.IN_PROGRESS, true, true);
        ResponseEntity<String>[] results = race(
                () -> post(fixture.publicId, "complete", Map.of(), token),
                () -> post(fixture.publicId, "cancel", Map.of(), token));

        assertOneSuccessAndClientFailure(results);
        assertThat(transitionCount(fixture.surgeryId, SurgeryStatus.CANCELLED)).isZero();
        assertCompletionInvariant(fixture);
    }

    @Test
    void foreignTenantCannotScheduleUsingAnotherSurgeryVersion() {
        Fixture fixture = fixture(SurgeryStatus.APPROVED, false, false);
        long otherHospitalId = hospitalRepository.save(hospital("foreign")).getId();
        User otherUser = userRepository.save(user(otherHospitalId, "foreign"));
        String otherToken = tokenFor(otherUser, otherHospitalId);

        ResponseEntity<String> result = schedule(fixture.publicId, fixture.otWardId, LocalDateTime.now().plusDays(1),
                0L, otherToken);

        assertThat(result.getStatusCode().value()).isEqualTo(404);
        Surgery unchanged = surgeryRepository.findById(fixture.surgeryId).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(SurgeryStatus.APPROVED.name());
        assertThat(unchanged.getLifecycleVersion()).isEqualTo(0L);
    }

    private void assertStartRaceInvariant(Fixture fixture) {
        Surgery surgery = surgeryRepository.findById(fixture.surgeryId).orElseThrow();
        assertThat(surgery.getStatus()).isIn(SurgeryStatus.IN_PROGRESS.name(), SurgeryStatus.CANCELLED.name(), SurgeryStatus.APPROVED.name());
        assertThat(transitionCount(fixture.surgeryId, SurgeryStatus.IN_PROGRESS)).isLessThanOrEqualTo(1);
        if (SurgeryStatus.IN_PROGRESS.name().equals(surgery.getStatus())) {
            assertThat(bedRepository.findById(fixture.otBedId).orElseThrow().getStatus()).isEqualTo(BedStatus.OCCUPIED);
            assertThat(roomRepository.findById(fixture.roomId).orElseThrow().getStatus()).isEqualTo(OtRoom.OCCUPIED);
            assertThat(openOccupancies(fixture.roomId)).isEqualTo(1);
        } else {
            assertNoOtResourcesHeld(fixture);
        }
        assertIpdBedUnchanged(fixture);
    }

    private void assertCompletionInvariant(Fixture fixture) {
        assertThat(surgeryRepository.findById(fixture.surgeryId).orElseThrow().getStatus()).isEqualTo(SurgeryStatus.COMPLETED.name());
        assertThat(bedRepository.findById(fixture.otBedId).orElseThrow().getStatus()).isEqualTo(BedStatus.CLEANING);
        OtRoom room = roomRepository.findById(fixture.roomId).orElseThrow();
        assertThat(room.getStatus()).isEqualTo(OtRoom.CLEANING);
        assertThat(room.getCurrentSurgeryId()).isNull();
        assertThat(openOccupancies(fixture.roomId)).isZero();
        assertIpdBedUnchanged(fixture);
    }

    private void assertNoOtResourcesHeld(Fixture fixture) {
        assertThat(bedRepository.findById(fixture.otBedId).orElseThrow().getStatus()).isEqualTo(BedStatus.AVAILABLE);
        assertThat(roomRepository.findById(fixture.roomId).orElseThrow().getStatus()).isEqualTo(OtRoom.AVAILABLE);
        assertThat(openOccupancies(fixture.roomId)).isZero();
    }

    private void assertIpdBedUnchanged(Fixture fixture) {
        IpdAdmission admission = admissionRepository.findById(fixture.admissionId).orElseThrow();
        assertThat(admission.getBedId()).isEqualTo(fixture.ipdBedId);
        assertThat(bedRepository.findById(fixture.ipdBedId).orElseThrow().getStatus()).isEqualTo(BedStatus.OCCUPIED);
    }

    private long transitionCount(long surgeryId, SurgeryStatus status) {
        return transitionRepository.findBySurgeryIdOrderByCreatedAtAsc(surgeryId).stream()
                .filter(t -> status.name().equals(t.getToStatus())).count();
    }

    private long openOccupancies(long roomId) {
        return occupancyRepository.findAll().stream()
                .filter(o -> roomId == o.getOtRoomId() && o.getOccupiedTo() == null).count();
    }

    private ResponseEntity<String> schedule(String publicId, long otWardId, LocalDateTime scheduledAt,
            long expectedVersion, String authToken) {
        return post(publicId, "schedule", Map.of(
                "surgeonDoctorId", doctorId,
                "scheduledAt", scheduledAt.toString(),
                "otWardId", otWardId,
                "expectedVersion", expectedVersion), authToken);
    }

    private ResponseEntity<String> post(String publicId, String command, Object body, String authToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(authToken);
        return rest.exchange("/hospital/surgeries/" + publicId + "/" + command, HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
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
        assertThat(List.of(results[0].getStatusCode().value(), results[1].getStatusCode().value()))
                .containsExactlyInAnyOrder(200, 409);
        String conflict = results[0].getStatusCode().value() == 409 ? results[0].getBody() : results[1].getBody();
        assertThat(conflict).contains("\"code\":\"CONFLICT\"").contains("Refresh and retry");
    }

    private void assertOneSuccessAndClientFailure(ResponseEntity<String>[] results) {
        List<Integer> codes = List.of(results[0].getStatusCode().value(), results[1].getStatusCode().value());
        assertThat(codes).filteredOn(code -> code == 200).hasSize(1);
        assertThat(codes).filteredOn(code -> code >= 400 && code < 500).hasSize(1);
    }

    private Fixture fixture(SurgeryStatus status, boolean resourcesOccupied, boolean roomAssigned) {
        long suffix = IDS.incrementAndGet();
        Patient patient = patientRepository.save(patient(hospitalId, suffix));
        Ward ipdWard = wardRepository.save(ward(hospitalId, "IPD-" + suffix));
        Bed ipdBed = bedRepository.save(bed(hospitalId, ipdWard.getWardId(), "IPD-BED-" + suffix, BedStatus.OCCUPIED));
        IpdAdmission admission = new IpdAdmission();
        admission.setIpdNumber("IPD-" + suffix);
        admission.setHospitalId(hospitalId);
        admission.setPatientId(patient.getId());
        admission.setDoctorId(doctorId);
        admission.setAdmissionType("ELECTIVE");
        admission.setStatus("ADMITTED");
        admission.setAdmissionDatetime(LocalDateTime.now());
        admission.setWardId(ipdWard.getWardId());
        admission.setBedId(ipdBed.getBedId());
        admission.setAdmissionConfirmed(true);
        admission = admissionRepository.save(admission);

        Ward otWard = wardRepository.save(ward(hospitalId, "OT-" + suffix));
        Bed otBed = bedRepository.save(bed(hospitalId, otWard.getWardId(), "OT-BED-" + suffix,
                resourcesOccupied ? BedStatus.OCCUPIED : BedStatus.AVAILABLE));
        if (resourcesOccupied) {
            otBed.setCurrentIpdAdmissionId(admission.getId());
            otBed = bedRepository.save(otBed);
        }
        OtRoom room = roomRepository.save(room(hospitalId, "THEATRE-" + suffix,
                resourcesOccupied ? OtRoom.OCCUPIED : OtRoom.AVAILABLE));

        Surgery surgery = new Surgery();
        surgery.setPublicId("lifecycle-" + suffix);
        surgery.setHospitalId(hospitalId);
        surgery.setIpdAdmissionId(admission.getId());
        surgery.setPatientId(patient.getId());
        surgery.setProcedureName("Procedure");
        surgery.setPriority("ELECTIVE");
        surgery.setRequestedAt(LocalDateTime.now());
        surgery.setStatus(status.name());
        surgery.setScheduledAt(LocalDateTime.now().plusHours(1));
        surgery.setOtWardId(otWard.getWardId());
        surgery.setOtBedId(resourcesOccupied ? otBed.getBedId() : null);
        surgery.setOtRoomId(roomAssigned ? room.getId() : null);
        surgery = surgeryRepository.save(surgery);
        if (resourcesOccupied) {
            room.setCurrentSurgeryId(surgery.getId());
            roomRepository.save(room);
            OtRoomOccupancy occupancy = new OtRoomOccupancy();
            occupancy.setHospitalId(hospitalId);
            occupancy.setOtRoomId(room.getId());
            occupancy.setSurgeryId(surgery.getId());
            occupancy.setOccupiedFrom(LocalDateTime.now().minusMinutes(10));
            occupancyRepository.save(occupancy);
        }
        return new Fixture(surgery.getId(), surgery.getPublicId(), admission.getId(), ipdBed.getBedId(),
                otBed.getBedId(), otWard.getWardId(), room.getId());
    }

    private Hospital hospital(String suffix) {
        Hospital hospital = new Hospital();
        hospital.setName("Hospital " + suffix + IDS.incrementAndGet());
        hospital.setCustomId("HID-" + IDS.incrementAndGet());
        hospital.setSubscriptionStatus("ACTIVE");
        hospital.setIsActive(true);
        hospital.setModules(MODULES);
        return hospital;
    }

    private User user(long ownerHospitalId, String suffix) {
        User user = new User();
        user.setEmail("ot-life-" + suffix + "-" + IDS.incrementAndGet() + "@test.local");
        user.setPassword("{noop}x");
        user.setName("OT Reception");
        user.setRole("RECEPTIONIST");
        user.setHospitalId(ownerHospitalId);
        user.setIsActive(true);
        return user;
    }

    private String tokenFor(User user, long ownerHospitalId) {
        return jwtUtil.generateToken(user.getId(), user.getEmail(), user.getRole(), ownerHospitalId,
                MODULES, null, "HOSPITAL", null, user.getTokenVersion());
    }

    private Doctor doctor(long ownerHospitalId) {
        long suffix = IDS.incrementAndGet();
        Doctor doctor = new Doctor();
        doctor.setName("Doctor Test");
        doctor.setHospitalId(ownerHospitalId);
        doctor.setIsActive(true);
        doctor.setEmail("doctor-life-" + suffix + "@test.local");
        doctor.setPublicId("doctor-life-" + suffix);
        doctor.setPhone(String.format("8%09d", suffix));
        doctor.setSpecialization("General");
        return doctor;
    }

    private Patient patient(long ownerHospitalId, long suffix) {
        Patient patient = new Patient();
        patient.setHospitalId(ownerHospitalId);
        patient.setName("Patient " + suffix);
        patient.setGender("MALE");
        patient.setPhone(String.format("9%09d", suffix));
        patient.setIsActive(true);
        return patient;
    }

    private Ward ward(long ownerHospitalId, String name) {
        Ward ward = new Ward();
        ward.setHospitalId(ownerHospitalId);
        ward.setWardName(name);
        ward.setBedPrice(BigDecimal.ZERO);
        ward.setTotalBeds(2);
        return ward;
    }

    private Bed bed(long ownerHospitalId, long wardId, String code, String status) {
        Bed bed = new Bed();
        bed.setHospitalId(ownerHospitalId);
        bed.setWardId(wardId);
        bed.setBedCode(code);
        bed.setStatus(status);
        return bed;
    }

    private OtRoom room(long ownerHospitalId, String name, String status) {
        OtRoom room = new OtRoom();
        room.setHospitalId(ownerHospitalId);
        room.setName(name);
        room.setStatus(status);
        room.setTurnoverMinutes(15);
        room.setIsActive(true);
        return room;
    }

    private record Fixture(long surgeryId, String publicId, long admissionId, long ipdBedId,
            long otBedId, long otWardId, long roomId) {}
}
