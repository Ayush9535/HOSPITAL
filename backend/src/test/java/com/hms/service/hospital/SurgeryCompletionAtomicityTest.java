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
import com.hms.entity.SurgeryStatus;
import com.hms.entity.Ward;
import com.hms.repository.BedRepository;
import com.hms.repository.HospitalRepository;
import com.hms.repository.DoctorRepository;
import com.hms.repository.IpdAdmissionRepository;
import com.hms.repository.OtRoomOccupancyRepository;
import com.hms.repository.OtRoomRepository;
import com.hms.repository.PatientRepository;
import com.hms.repository.SurgeryRepository;
import com.hms.repository.WardRepository;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import com.hms.service.RealtimeNotifier;
import com.hms.service.hospital.ot.OtSchedulingService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class SurgeryCompletionAtomicityTest {
    private static final AtomicInteger IDS = new AtomicInteger(950_000);

    @Autowired SurgeryService surgeryService;
    @Autowired HospitalRepository hospitalRepository;
    @Autowired DoctorRepository doctorRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired WardRepository wardRepository;
    @Autowired BedRepository bedRepository;
    @Autowired IpdAdmissionRepository admissionRepository;
    @Autowired SurgeryRepository surgeryRepository;
    @Autowired OtRoomRepository roomRepository;
    @Autowired OtRoomOccupancyRepository occupancyRepository;
    @MockBean SecurityContextHelper securityHelper;
    @MockBean AuditLogService auditLogService;
    @MockBean RealtimeNotifier notifier;
    @SpyBean BedStatusService bedStatusService;
    @SpyBean OtSchedulingService otSchedulingService;
    @SpyBean OtRoomOccupancyRepository occupancySpy;

    private Fixture fixture;

    @BeforeEach
    void setUp() {
        fixture = fixture();
        when(securityHelper.getCurrentHospitalId()).thenReturn(fixture.hospitalId);
        when(securityHelper.getCurrentUserId()).thenReturn(44L);
        when(securityHelper.getCurrentUserEmail()).thenReturn("ot@test.local");
    }

    @Test
    void completeReleasesOnlyOtResourcesAndClosesOccupancy() {
        surgeryService.complete(fixture.surgeryPublicId);

        Surgery surgery = surgeryRepository.findById(fixture.surgeryId).orElseThrow();
        Bed otBed = bedRepository.findById(fixture.otBedId).orElseThrow();
        OtRoom room = roomRepository.findById(fixture.roomId).orElseThrow();
        IpdAdmission admission = admissionRepository.findById(fixture.admissionId).orElseThrow();
        OtRoomOccupancy occupancy = occupancyRepository.findById(fixture.occupancyId).orElseThrow();

        assertThat(surgery.getStatus()).isEqualTo(SurgeryStatus.COMPLETED.name());
        assertThat(otBed.getStatus()).isEqualTo(BedStatus.CLEANING);
        assertThat(room.getStatus()).isEqualTo(OtRoom.CLEANING);
        assertThat(room.getCurrentSurgeryId()).isNull();
        assertThat(occupancy.getOccupiedTo()).isNotNull();
        assertThat(admission.getBedId()).isEqualTo(fixture.ipdBedId);
        assertThat(bedRepository.findById(fixture.ipdBedId).orElseThrow().getStatus()).isEqualTo(BedStatus.OCCUPIED);
        assertThat(surgeryRepository.findById(fixture.surgeryId).orElseThrow().getStatus()).isEqualTo(SurgeryStatus.COMPLETED.name());
    }

    @Test
    void roomReleaseFailureRollsBackBedAndSurgery() {
        doThrow(new IllegalStateException("room release failed"))
                .when(otSchedulingService).lockRoom(fixture.roomId);

        assertThatThrownBy(() -> surgeryService.complete(fixture.surgeryPublicId))
                .hasMessageContaining("room release failed");

        assertUnreleased();
    }

    @Test
    void bedReleaseFailureLeavesRoomAndSurgeryUntouched() {
        doThrow(new IllegalStateException("bed release failed"))
                .when(bedStatusService).changeLocked(any(Bed.class), any(), any());

        assertThatThrownBy(() -> surgeryService.complete(fixture.surgeryPublicId))
                .hasMessageContaining("bed release failed");

        assertUnreleased();
    }

    @Test
    void occupancyClosureFailureRollsBackBedRoomAndSurgery() {
        doThrow(new IllegalStateException("occupancy close failed"))
                .when(occupancySpy).save(any(OtRoomOccupancy.class));

        assertThatThrownBy(() -> surgeryService.complete(fixture.surgeryPublicId))
                .hasMessageContaining("occupancy close failed");

        assertUnreleased();
    }

    private void assertUnreleased() {
        assertThat(surgeryRepository.findById(fixture.surgeryId).orElseThrow().getStatus()).isEqualTo(SurgeryStatus.IN_PROGRESS.name());
        assertThat(bedRepository.findById(fixture.otBedId).orElseThrow().getStatus()).isEqualTo(BedStatus.OCCUPIED);
        OtRoom room = roomRepository.findById(fixture.roomId).orElseThrow();
        assertThat(room.getStatus()).isEqualTo(OtRoom.OCCUPIED);
        assertThat(room.getCurrentSurgeryId()).isEqualTo(fixture.surgeryId);
        assertThat(occupancyRepository.findById(fixture.occupancyId).orElseThrow().getOccupiedTo()).isNull();
    }

    private Fixture fixture() {
        long suffix = IDS.incrementAndGet();
        Hospital hospital = new Hospital();
        hospital.setName("OT completion " + suffix);
        hospital.setCustomId("OTC-" + suffix);
        hospital.setSubscriptionStatus("ACTIVE");
        hospital.setIsActive(true);
        hospital.setModules(List.of("OPD", "IPD", "OT"));
        long hospitalId = hospitalRepository.save(hospital).getId();

        Patient patient = new Patient();
        patient.setHospitalId(hospitalId);
        patient.setName("Patient " + suffix);
        patient.setGender("MALE");
        patient.setPhone(String.format("9%09d", suffix));
        patient = patientRepository.save(patient);

        Ward ipdWard = wardRepository.save(ward(hospitalId, "IPD-" + suffix));
        Bed ipdBed = bedRepository.save(bed(hospitalId, ipdWard.getWardId(), "IPD-BED-" + suffix, BedStatus.OCCUPIED));
        Doctor doctor = new Doctor();
        doctor.setName("Doctor Test");
        doctor.setHospitalId(hospitalId);
        doctor.setIsActive(true);
        doctor.setEmail("doctor-" + suffix + "@test.local");
        doctor.setPublicId("doctor-" + suffix);
        doctor.setPhone(String.format("8%09d", suffix));
        doctor.setSpecialization("General");
        doctor = doctorRepository.save(doctor);
        IpdAdmission admission = new IpdAdmission();
        admission.setIpdNumber("IPD-" + suffix);
        admission.setHospitalId(hospitalId);
        admission.setPatientId(patient.getId());
        admission.setDoctorId(doctor.getId());
        admission.setAdmissionType("ELECTIVE");
        admission.setStatus("ADMITTED");
        admission.setAdmissionDatetime(LocalDateTime.now());
        admission.setWardId(ipdWard.getWardId());
        admission.setBedId(ipdBed.getBedId());
        admission.setAdmissionConfirmed(true);
        admission = admissionRepository.save(admission);

        Ward otWard = wardRepository.save(ward(hospitalId, "OT-" + suffix));
        Bed otBed = bedRepository.save(bed(hospitalId, otWard.getWardId(), "OT-BED-" + suffix, BedStatus.OCCUPIED));
        otBed.setCurrentIpdAdmissionId(admission.getId());
        bedRepository.save(otBed);
        OtRoom room = new OtRoom();
        room.setHospitalId(hospitalId);
        room.setName("THEATRE-" + suffix);
        room.setStatus(OtRoom.OCCUPIED);
        room.setTurnoverMinutes(15);
        room.setIsActive(true);
        room = roomRepository.save(room);

        Surgery surgery = new Surgery();
        surgery.setPublicId("completion-" + suffix);
        surgery.setHospitalId(hospitalId);
        surgery.setIpdAdmissionId(admission.getId());
        surgery.setPatientId(patient.getId());
        surgery.setProcedureName("Procedure");
        surgery.setPriority("ELECTIVE");
        surgery.setRequestedAt(LocalDateTime.now());
        surgery.setStatus(SurgeryStatus.IN_PROGRESS.name());
        surgery.setOtWardId(otWard.getWardId());
        surgery.setOtBedId(otBed.getBedId());
        surgery.setOtRoomId(room.getId());
        surgery = surgeryRepository.save(surgery);
        room.setCurrentSurgeryId(surgery.getId());
        roomRepository.save(room);

        OtRoomOccupancy occupancy = new OtRoomOccupancy();
        occupancy.setHospitalId(hospitalId);
        occupancy.setOtRoomId(room.getId());
        occupancy.setSurgeryId(surgery.getId());
        occupancy.setOccupiedFrom(LocalDateTime.now().minusMinutes(30));
        occupancy = occupancyRepository.save(occupancy);
        return new Fixture(hospitalId, surgery.getId(), surgery.getPublicId(), admission.getId(),
                ipdBed.getBedId(), otBed.getBedId(), room.getId(), occupancy.getId());
    }

    private Ward ward(long hospitalId, String name) {
        Ward ward = new Ward();
        ward.setHospitalId(hospitalId);
        ward.setWardName(name);
        ward.setBedPrice(BigDecimal.ZERO);
        ward.setTotalBeds(2);
        return ward;
    }

    private Bed bed(long hospitalId, long wardId, String code, String status) {
        Bed bed = new Bed();
        bed.setHospitalId(hospitalId);
        bed.setWardId(wardId);
        bed.setBedCode(code);
        bed.setStatus(status);
        return bed;
    }

    private record Fixture(long hospitalId, long surgeryId, String surgeryPublicId, long admissionId,
            long ipdBedId, long otBedId, long roomId, long occupancyId) {}
}
