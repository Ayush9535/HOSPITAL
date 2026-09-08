package com.hms.service.hospital;

import com.hms.dto.CreateSurgeryRequest;
import com.hms.entity.IpdAdmission;
import com.hms.entity.Surgery;
import com.hms.repository.*;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * OT Phase 1 (ADR D13): a surgery is its own aggregate, anchored on the patient.
 * A day-care procedure (cataract, endoscopy, minor orthopaedics) has no IPD admission.
 */
@ExtendWith(MockitoExtension.class)
class SurgeryServiceDayCareTest {

    private static final Long HOSPITAL = 7L;

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
    // Creating a case now records its first transition; legality lives in SurgeryStateMachineTest.
    @Mock com.hms.service.hospital.ot.SurgeryStateMachine stateMachine;

    @Mock com.hms.service.RealtimeNotifier notifier;

    @InjectMocks SurgeryService service;

    @BeforeEach
    void setUp() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(HOSPITAL);
        lenient().when(securityHelper.getCurrentUserId()).thenReturn(42L);
        lenient().when(securityHelper.getCurrentUserEmail()).thenReturn("doc@x.com");
        lenient().when(doctorRepository.findByEmailAndHospitalId(any(), any())).thenReturn(Optional.empty());
        lenient().when(surgeryRepository.save(any(Surgery.class))).thenAnswer(inv -> {
            Surgery s = inv.getArgument(0);
            s.setId(11L);
            return s;
        });
    }

    private CreateSurgeryRequest req() {
        CreateSurgeryRequest r = new CreateSurgeryRequest();
        r.setProcedureName("Cataract extraction");
        return r;
    }

    @Test
    void dayCareSurgery_isCreatedWithNoAdmission() {
        when(surgeryRepository.findByPatientIdAndStatusIn(any(), any())).thenReturn(List.of());
        CreateSurgeryRequest r = req();
        r.setPatientId(500L);

        Surgery s = service.createRequest(r);

        assertThat(s.getIpdAdmissionId()).isNull();
        assertThat(s.getEncounterType()).isEqualTo(Surgery.ENCOUNTER_DAY_CARE);
        assertThat(s.getPatientId()).isEqualTo(500L);
        assertThat(s.getStatus()).isEqualTo(Surgery.REQUESTED);
    }

    @Test
    void inpatientSurgery_stillHangsOffTheAdmission() {
        IpdAdmission a = new IpdAdmission();
        a.setId(900L);
        a.setHospitalId(HOSPITAL);
        a.setPatientId(500L);
        a.setIpdNumber("IPD-1");
        when(ipdAdmissionRepository.findById(900L)).thenReturn(Optional.of(a));
        when(surgeryRepository.findByPatientIdAndStatusIn(any(), any())).thenReturn(List.of());

        CreateSurgeryRequest r = req();
        r.setIpdAdmissionId(900L);

        Surgery s = service.createRequest(r);

        assertThat(s.getIpdAdmissionId()).isEqualTo(900L);
        assertThat(s.getEncounterType()).isEqualTo(Surgery.ENCOUNTER_IPD);
    }

    /** The duplicate check is patient-scoped, so it also catches a second day-care case. */
    @Test
    void secondActiveSurgeryForTheSamePatient_isRejected() {
        when(surgeryRepository.findByPatientIdAndStatusIn(any(), any())).thenReturn(List.of(new Surgery()));
        CreateSurgeryRequest r = req();
        r.setPatientId(500L);

        assertThatThrownBy(() -> service.createRequest(r))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already has an active surgery");
    }

    @Test
    void neitherAdmissionNorPatient_isRejected() {
        assertThatThrownBy(() -> service.createRequest(req()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ipdAdmissionId or patientId is required");
    }
}
