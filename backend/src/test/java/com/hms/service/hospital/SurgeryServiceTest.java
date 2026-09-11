package com.hms.service.hospital;

import com.hms.dto.CreateSurgeryRequest;
import com.hms.dto.ScheduleSurgeryRequest;
import com.hms.entity.*;
import com.hms.repository.*;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SurgeryServiceTest {

    @Mock SurgeryRepository surgeryRepository;
    @Mock IpdAdmissionRepository ipdAdmissionRepository;
    @Mock DoctorRepository doctorRepository;
    @Mock UserRepository userRepository;
    @Mock WardRepository wardRepository;
    @Mock BedRepository bedRepository;
    @Mock PatientRepository patientRepository;
    @Mock PatientNurseAssignmentRepository assignmentRepository;
    @Mock NotificationService notificationService;
    @Mock SecurityContextHelper securityHelper;
    @Mock AuditLogService auditLogService;
    @Mock BedStatusService bedStatusService;
    @Mock com.hms.service.hospital.ot.SurgeryStateMachine stateMachine;
    @Mock com.hms.service.hospital.ot.OtRoomService otRoomService;
    @Mock com.hms.service.hospital.ot.OtSchedulingService otSchedulingService;
    @Mock com.hms.repository.OtRoomRepository otRoomRepository;
    @Mock com.hms.service.hospital.ot.OtPolicyService otPolicyService;
    @Mock com.hms.service.hospital.ot.SurgeryExecutionService surgeryExecutionService;
    @Mock com.hms.service.hospital.ot.PreOpSafetyService preOpSafetyService;
    @Mock com.hms.repository.OtRoomOccupancyRepository occupancyRepository;

    @Mock com.hms.service.RealtimeNotifier notifier;

    @InjectMocks SurgeryService service;

    @org.junit.jupiter.api.BeforeEach
    void stubPolicies() {
        org.mockito.Mockito.lenient().when(otPolicyService.resolve(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("APPROVAL_MODE"),
                org.mockito.ArgumentMatchers.any())).thenReturn("NONE");
        org.mockito.Mockito.lenient().when(otPolicyService.resolve(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("CANCELLATION_REASON"),
                org.mockito.ArgumentMatchers.any())).thenReturn("OPTIONAL");
        stubStateMachine();
    }

    void stubStateMachine() {
        org.mockito.Mockito.lenient()
                .when(stateMachine.transition(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(i -> applyStatus(i.getArgument(0), i.getArgument(1)));
        org.mockito.Mockito.lenient()
                .when(stateMachine.autoTransition(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(i -> applyStatus(i.getArgument(0), i.getArgument(1)));
    }

    private static Surgery applyStatus(Surgery s, com.hms.entity.SurgeryStatus to) {
        s.setStatus(to.name());
        return s;
    }

    private IpdAdmission admission() {
        IpdAdmission a = new IpdAdmission();
        a.setId(1L); a.setHospitalId(7L); a.setPatientId(500L); a.setIpdNumber("IPD-1");
        return a;
    }

    private CreateSurgeryRequest createReq() {
        CreateSurgeryRequest r = new CreateSurgeryRequest();
        r.setIpdAdmissionId(1L);
        r.setProcedureName("Appendectomy");
        r.setPriority("ELECTIVE");
        return r;
    }

    @Test
    void createRequest_savesRequestedSurgery() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(securityHelper.getCurrentUserId()).thenReturn(30L);
        when(securityHelper.getCurrentUserEmail()).thenReturn("doc@x.com");
        when(ipdAdmissionRepository.findById(1L)).thenReturn(Optional.of(admission()));
        Doctor d = new Doctor(); d.setId(11L); d.setHospitalId(7L);
        when(doctorRepository.findByEmailAndHospitalId("doc@x.com", 7L)).thenReturn(Optional.of(d));
        when(surgeryRepository.findByPatientIdAndStatusIn(eq(500L), any())).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(surgeryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Surgery saved = service.createRequest(createReq());

        assertThat(saved.getStatus()).isEqualTo(Surgery.REQUESTED);
        assertThat(saved.getProcedureName()).isEqualTo("Appendectomy");
        assertThat(saved.getRequestedByDoctorId()).isEqualTo(11L);
        assertThat(saved.getPatientId()).isEqualTo(500L);
    }

    @Test
    void createRequest_rejectsSecondActiveSurgery() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(ipdAdmissionRepository.findById(1L)).thenReturn(Optional.of(admission()));
        Surgery existing = new Surgery(); existing.setStatus(Surgery.REQUESTED);
        when(surgeryRepository.findByPatientIdAndStatusIn(eq(500L), any())).thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.createRequest(createReq()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("active surgery");
    }

    @Test
    void schedule_setsScheduledAndNotifiesNurse() {
        Surgery s = new Surgery();
        s.setId(9L); s.setHospitalId(7L); s.setIpdAdmissionId(1L); s.setStatus(Surgery.REQUESTED);
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(securityHelper.getCurrentUserId()).thenReturn(40L);
        when(surgeryRepository.findByPublicIdAndHospitalIdForUpdate("s-pub", 7L)).thenReturn(Optional.of(s));
        Doctor surgeon = new Doctor(); surgeon.setId(11L); surgeon.setHospitalId(7L);
        // Any doctor (not just surgeons) can be assigned as the operating surgeon.
        surgeon.setSpecialization("Cardiologist"); surgeon.setEmail("surg@x.com"); surgeon.setName("Dr Surg");
        when(doctorRepository.findByIdAndHospitalIdAndIsActiveTrue(11L, 7L)).thenReturn(Optional.of(surgeon));
        Ward ward = new Ward(); ward.setWardId(3L); ward.setHospitalId(7L); ward.setWardName("OT");
        when(wardRepository.findById(3L)).thenReturn(Optional.of(ward));
        PatientNurseAssignment asg = new PatientNurseAssignment(); asg.setNurseUserId(77L);
        when(assignmentRepository.findByIpdAdmissionIdAndIsActiveTrue(1L)).thenReturn(Optional.of(asg));
        when(ipdAdmissionRepository.findById(1L)).thenReturn(Optional.of(admission()));
        when(patientRepository.findById(500L)).thenReturn(Optional.empty());
        when(userRepository.findByEmail("surg@x.com")).thenReturn(Optional.empty());
        org.mockito.Mockito.lenient().when(surgeryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ScheduleSurgeryRequest req = new ScheduleSurgeryRequest();
        req.setExpectedVersion(0L);
        req.setSurgeonDoctorId(11L);
        req.setScheduledAt(LocalDateTime.now().plusDays(1));
        req.setOtWardId(3L);

        Surgery out = service.schedule("s-pub", req);

        assertThat(out.getStatus()).isEqualTo(Surgery.SCHEDULED);
        assertThat(out.getSurgeonDoctorId()).isEqualTo(11L);
        verify(notificationService).create(eq(77L), eq(7L), eq("OT_SCHEDULED"), any(), any(), any(), any());
    }

    @Test
    void schedule_allowsOtherOperator_withFreeTextName() {
        Surgery s = new Surgery();
        s.setId(9L); s.setHospitalId(7L); s.setIpdAdmissionId(1L); s.setStatus(Surgery.REQUESTED);
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(securityHelper.getCurrentUserId()).thenReturn(40L);
        when(surgeryRepository.findByPublicIdAndHospitalIdForUpdate("s-pub", 7L)).thenReturn(Optional.of(s));
        Ward ward = new Ward(); ward.setWardId(3L); ward.setHospitalId(7L); ward.setWardName("OT");
        when(wardRepository.findById(3L)).thenReturn(Optional.of(ward));
        when(assignmentRepository.findByIpdAdmissionIdAndIsActiveTrue(1L)).thenReturn(Optional.empty());
        org.mockito.Mockito.lenient().when(surgeryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ScheduleSurgeryRequest req = new ScheduleSurgeryRequest();
        req.setExpectedVersion(0L);
        req.setSurgeonName("Dr. Visiting Anaesthetist");   // no surgeonDoctorId → "Other"
        req.setScheduledAt(LocalDateTime.now().plusDays(1));
        req.setOtWardId(3L);

        Surgery out = service.schedule("s-pub", req);

        assertThat(out.getStatus()).isEqualTo(Surgery.SCHEDULED);
        assertThat(out.getSurgeonDoctorId()).isNull();
        assertThat(out.getSurgeonName()).isEqualTo("Dr. Visiting Anaesthetist");
    }

    @Test
    void schedule_rejectsOtherWithoutName() {
        Surgery s = new Surgery();
        s.setId(9L); s.setHospitalId(7L); s.setIpdAdmissionId(1L); s.setStatus(Surgery.REQUESTED);
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(surgeryRepository.findByPublicIdAndHospitalIdForUpdate("s-pub", 7L)).thenReturn(Optional.of(s));

        ScheduleSurgeryRequest req = new ScheduleSurgeryRequest();  // no doctor id, no name
        req.setExpectedVersion(0L);
        req.setScheduledAt(LocalDateTime.now().plusDays(1));
        req.setOtWardId(3L);

        assertThatThrownBy(() -> service.schedule("s-pub", req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("operator's name");
    }

    @Test
    void start_requiresAvailableOtBed_thenMarksOccupied() {
        Surgery s = new Surgery();
        s.setId(9L); s.setHospitalId(7L); s.setIpdAdmissionId(1L); s.setStatus(Surgery.SCHEDULED); s.setOtWardId(3L);
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(surgeryRepository.findByPublicIdAndHospitalIdForUpdate("s-pub", 7L)).thenReturn(Optional.of(s));
        Bed bed = new Bed(); bed.setBedId(50L); bed.setHospitalId(7L); bed.setWardId(3L); bed.setStatus("available");
        when(bedRepository.findAvailableBedIdsInWard(3L, 7L)).thenReturn(List.of(50L));
        when(bedRepository.findByBedIdAndHospitalIdForUpdate(50L, 7L)).thenReturn(Optional.of(bed));
        when(bedRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        org.mockito.Mockito.lenient().when(surgeryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Surgery out = service.start("s-pub");

        assertThat(out.getStatus()).isEqualTo(Surgery.IN_PROGRESS);
        assertThat(out.getStartedAt()).isNotNull();
        verify(bedStatusService).change(eq(50L), eq(com.hms.entity.BedStatus.OCCUPIED), any());
    }

    /**
     * Phase 7 exit criterion: with WHO_CHECKLIST_MODE=BLOCKING, a case cannot start until
     * the Time-Out is signed. Rejected by the SERVICE, not merely hidden in the UI.
     */
    @Test
    void start_isBlocked_whenWhoTimeOutUnsigned_underBlockingPolicy() {
        Surgery s = new Surgery();
        s.setId(9L); s.setHospitalId(7L); s.setStatus(Surgery.SCHEDULED); s.setOtWardId(3L);
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(surgeryRepository.findByPublicIdAndHospitalIdForUpdate("s-pub", 7L)).thenReturn(Optional.of(s));
        when(otPolicyService.resolve(any(), eq("WHO_CHECKLIST_MODE"), any())).thenReturn("BLOCKING");
        when(surgeryExecutionService.timeOutSigned(9L)).thenReturn(false);

        assertThatThrownBy(() -> service.start("s-pub"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("WHO Time-Out must be signed");
    }

    @Test
    void start_isAllowed_whenWhoTimeOutSigned_underBlockingPolicy() {
        Surgery s = new Surgery();
        s.setId(9L); s.setHospitalId(7L); s.setStatus(Surgery.SCHEDULED); s.setOtWardId(3L);
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(surgeryRepository.findByPublicIdAndHospitalIdForUpdate("s-pub", 7L)).thenReturn(Optional.of(s));
        when(otPolicyService.resolve(any(), eq("WHO_CHECKLIST_MODE"), any())).thenReturn("BLOCKING");
        when(surgeryExecutionService.timeOutSigned(9L)).thenReturn(true);
        Bed bed = new Bed(); bed.setBedId(50L); bed.setHospitalId(7L); bed.setWardId(3L); bed.setStatus("available");
        when(bedRepository.findAvailableBedIdsInWard(3L, 7L)).thenReturn(List.of(50L));
        when(bedRepository.findByBedIdAndHospitalIdForUpdate(50L, 7L)).thenReturn(Optional.of(bed));
        when(bedRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        org.mockito.Mockito.lenient().when(surgeryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.start("s-pub").getStatus()).isEqualTo(Surgery.IN_PROGRESS);
    }

    @Test
    void complete_marksOtBedForCleaning() {
        Surgery s = new Surgery();
        s.setId(9L); s.setHospitalId(7L); s.setIpdAdmissionId(1L); s.setStatus(Surgery.IN_PROGRESS);
        s.setOtWardId(3L); s.setOtBedId(50L); s.setOtRoomId(8L);
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(surgeryRepository.findByPublicIdAndHospitalIdForUpdate("s-pub", 7L)).thenReturn(Optional.of(s));
        Bed otBed = new Bed(); otBed.setBedId(50L); otBed.setHospitalId(7L); otBed.setStatus(BedStatus.OCCUPIED);
        when(bedRepository.findByBedIdAndHospitalIdForUpdate(50L, 7L)).thenReturn(Optional.of(otBed));
        OtRoom room = new OtRoom(); room.setId(8L); room.setHospitalId(7L); room.setStatus(OtRoom.OCCUPIED); room.setCurrentSurgeryId(9L);
        when(otSchedulingService.lockRoom(8L)).thenReturn(room);
        OtRoomOccupancy occupancy = new OtRoomOccupancy(); occupancy.setSurgeryId(9L);
        when(occupancyRepository.findOpenBySurgeryIdForUpdate(9L)).thenReturn(Optional.of(occupancy));
        org.mockito.Mockito.lenient().when(surgeryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Surgery out = service.complete("s-pub");

        assertThat(out.getStatus()).isEqualTo(Surgery.COMPLETED);
        assertThat(out.getCompletedAt()).isNotNull();
        verify(bedStatusService).changeLocked(otBed, BedStatus.CLEANING, "Surgery completed");
        verify(otRoomRepository).save(room);
        verify(occupancyRepository).save(occupancy);
        verify(stateMachine).transition(s, SurgeryStatus.COMPLETED, null, null, null);
        verifyNoInteractions(ipdAdmissionRepository);
        org.mockito.InOrder locks = inOrder(bedRepository, otSchedulingService);
        locks.verify(bedRepository).findByBedIdAndHospitalIdForUpdate(50L, 7L);
        locks.verify(otSchedulingService).lockRoom(8L);
    }

    @Test
    void complete_doesNotTransitionWhenOtBedReleaseFails() {
        Surgery s = inProgressSurgeryWithResources();
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(surgeryRepository.findByPublicIdAndHospitalIdForUpdate("s-pub", 7L)).thenReturn(Optional.of(s));
        when(bedRepository.findByBedIdAndHospitalIdForUpdate(50L, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.complete("s-pub")).hasMessageContaining("OT bed");

        assertThat(s.getStatus()).isEqualTo(Surgery.IN_PROGRESS);
        verifyNoInteractions(otSchedulingService, stateMachine);
    }

    @Test
    void complete_doesNotTransitionWhenOtRoomReleaseFails() {
        Surgery s = inProgressSurgeryWithResources();
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(surgeryRepository.findByPublicIdAndHospitalIdForUpdate("s-pub", 7L)).thenReturn(Optional.of(s));
        Bed otBed = new Bed(); otBed.setBedId(50L); otBed.setHospitalId(7L); otBed.setStatus(BedStatus.OCCUPIED);
        when(bedRepository.findByBedIdAndHospitalIdForUpdate(50L, 7L)).thenReturn(Optional.of(otBed));
        doThrow(new IllegalStateException("room release failed")).when(otSchedulingService).lockRoom(8L);

        assertThatThrownBy(() -> service.complete("s-pub")).hasMessageContaining("room release failed");

        verify(bedStatusService).changeLocked(otBed, BedStatus.CLEANING, "Surgery completed");
        verify(stateMachine, never()).transition(any(), eq(SurgeryStatus.COMPLETED), any(), any(), any());
        assertThat(s.getStatus()).isEqualTo(Surgery.IN_PROGRESS);
    }

    @Test
    void complete_doesNotTransitionWhenOccupancyClosureFails() {
        Surgery s = inProgressSurgeryWithResources();
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(surgeryRepository.findByPublicIdAndHospitalIdForUpdate("s-pub", 7L)).thenReturn(Optional.of(s));
        Bed otBed = new Bed(); otBed.setBedId(50L); otBed.setHospitalId(7L); otBed.setStatus(BedStatus.OCCUPIED);
        when(bedRepository.findByBedIdAndHospitalIdForUpdate(50L, 7L)).thenReturn(Optional.of(otBed));
        OtRoom room = new OtRoom(); room.setId(8L); room.setHospitalId(7L); room.setStatus(OtRoom.OCCUPIED); room.setCurrentSurgeryId(9L);
        when(otSchedulingService.lockRoom(8L)).thenReturn(room);
        OtRoomOccupancy occupancy = new OtRoomOccupancy(); occupancy.setSurgeryId(9L);
        when(occupancyRepository.findOpenBySurgeryIdForUpdate(9L)).thenReturn(Optional.of(occupancy));
        doThrow(new IllegalStateException("occupancy close failed")).when(occupancyRepository).save(occupancy);

        assertThatThrownBy(() -> service.complete("s-pub")).hasMessageContaining("occupancy close failed");

        verify(stateMachine, never()).transition(any(), eq(SurgeryStatus.COMPLETED), any(), any(), any());
        assertThat(s.getStatus()).isEqualTo(Surgery.IN_PROGRESS);
    }

    @Test
    void schedule_rejectsAStaleExpectedVersionBeforeMutatingTheSlot() {
        Surgery s = new Surgery();
        s.setId(9L); s.setHospitalId(7L); s.setStatus(Surgery.SCHEDULED); s.setLifecycleVersion(4L);
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(surgeryRepository.findByPublicIdAndHospitalIdForUpdate("s-pub", 7L)).thenReturn(Optional.of(s));
        ScheduleSurgeryRequest req = new ScheduleSurgeryRequest();
        req.setExpectedVersion(3L); req.setScheduledAt(LocalDateTime.now().plusDays(1)); req.setOtWardId(3L);

        assertThatThrownBy(() -> service.schedule("s-pub", req))
                .isInstanceOf(com.hms.exception.ConflictException.class)
                .hasMessage("Surgery was modified by another request. Refresh and retry.");

        verifyNoInteractions(wardRepository, otSchedulingService, stateMachine);
    }

    @Test
    void cancel_inProgressSurgeryDoesNotAttemptTentativeResourceCleanup() {
        Surgery s = inProgressSurgeryWithResources();
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(surgeryRepository.findByPublicIdAndHospitalIdForUpdate("s-pub", 7L)).thenReturn(Optional.of(s));
        doThrow(new IllegalArgumentException("A in progress surgery cannot move to cancelled"))
                .when(stateMachine).transition(s, com.hms.entity.SurgeryStatus.CANCELLED, "OTHER", null, null);

        assertThatThrownBy(() -> service.cancel("s-pub", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A in progress surgery cannot move to cancelled");

        assertThat(s.getStatus()).isEqualTo(Surgery.IN_PROGRESS);
        verifyNoInteractions(bedStatusService, otRoomRepository, occupancyRepository);
    }

    private Surgery inProgressSurgeryWithResources() {
        Surgery s = new Surgery();
        s.setId(9L); s.setHospitalId(7L); s.setIpdAdmissionId(1L); s.setStatus(Surgery.IN_PROGRESS);
        s.setOtWardId(3L); s.setOtBedId(50L); s.setOtRoomId(8L);
        return s;
    }

    @Test
    void schedule_crossTenant_isNotFound() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(surgeryRepository.findByPublicIdAndHospitalIdForUpdate("s-pub", 7L)).thenReturn(Optional.empty());

        ScheduleSurgeryRequest req = new ScheduleSurgeryRequest();
        req.setExpectedVersion(0L); req.setSurgeonDoctorId(11L); req.setScheduledAt(LocalDateTime.now()); req.setOtWardId(3L);

        assertThatThrownBy(() -> service.schedule("s-pub", req))
                .isInstanceOf(com.hms.exception.ResourceNotFoundException.class);
    }
}
