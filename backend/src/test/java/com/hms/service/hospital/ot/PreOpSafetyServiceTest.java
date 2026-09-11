package com.hms.service.hospital.ot;

import com.hms.dto.RecordAnaesthesiaClearanceRequest;
import com.hms.dto.RecordEmergencyOverrideRequest;
import com.hms.entity.*;
import com.hms.exception.ResourceNotFoundException;
import com.hms.repository.*;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PreOpSafetyServiceTest {
    private static final long HOSPITAL = 7L;
    private static final long USER = 99L;

    @Mock SurgeryRepository surgeryRepository;
    @Mock SurgeryFormRepository formRepository;
    @Mock SurgeryAnaesthesiaClearanceRepository clearanceRepository;
    @Mock SurgeryEmergencyOverrideRepository overrideRepository;
    @Mock SurgeryStateTransitionRepository transitionRepository;
    @Mock SurgeryStateMachine stateMachine;
    @Mock OtPolicyService policyService;
    @Mock SecurityContextHelper securityHelper;
    @Mock AuditLogService auditLogService;
    @InjectMocks PreOpSafetyService service;

    private Surgery surgery(String status) {
        Surgery s = new Surgery();
        s.setId(41L); s.setHospitalId(HOSPITAL); s.setPublicId("s-1"); s.setStatus(status); s.setPriority("ELECTIVE");
        return s;
    }

    @BeforeEach
    void setup() {
        lenient().when(securityHelper.getCurrentHospitalId()).thenReturn(HOSPITAL);
        lenient().when(securityHelper.getCurrentUserId()).thenReturn(USER);
        lenient().when(securityHelper.getCurrentUserEmail()).thenReturn("doctor@example.test");
        lenient().when(policyService.resolve(eq(HOSPITAL), any(), any())).thenReturn("OFF");
        lenient().when(clearanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(overrideRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void entersPreOpThroughStateMachineAndIsTenantScoped() {
        Surgery s = surgery(SurgeryStatus.SCHEDULED.name());
        when(surgeryRepository.findByPublicIdAndHospitalIdForUpdate("s-1", HOSPITAL)).thenReturn(Optional.of(s));
        when(stateMachine.transition(s, SurgeryStatus.PRE_OP, null, null, null)).thenReturn(s);

        assertThat(service.enterPreOp("s-1")).isSameAs(s);
        verify(stateMachine).transition(s, SurgeryStatus.PRE_OP, null, null, null);
    }

    @Test
    void foreignOrMissingSurgeryIsNotFoundForClinicalCommands() {
        when(surgeryRepository.findByPublicIdAndHospitalIdForUpdate("foreign", HOSPITAL)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.enterPreOp("foreign"))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage("Surgery not found");

        RecordAnaesthesiaClearanceRequest clearance = new RecordAnaesthesiaClearanceRequest();
        clearance.setOutcome(AnaesthesiaClearanceOutcome.CLEARED);
        assertThatThrownBy(() -> service.recordClearance("foreign", clearance))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage("Surgery not found");
        RecordEmergencyOverrideRequest override = new RecordEmergencyOverrideRequest();
        override.setReason("urgent");
        override.setBypassedGates(Set.of(PreOpGate.PRE_OP_CHECKLIST));
        assertThatThrownBy(() -> service.recordEmergencyOverride("foreign", override))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage("Surgery not found");
    }

    @Test
    void requiredPoliciesRequirePreOpAndSignedCurrentChecklist() {
        Surgery s = surgery(SurgeryStatus.SCHEDULED.name());
        requiredChecklist();
        assertThatThrownBy(() -> service.assertStartAllowed(s, HOSPITAL))
                .hasMessageContaining("PRE_OP");

        s.setStatus(SurgeryStatus.PRE_OP.name());
        SurgeryForm unsigned = new SurgeryForm(); unsigned.setHospitalId(HOSPITAL);
        when(formRepository.findBySurgeryIdAndFormTypeAndIsCurrentTrue(41L, "PRE_OP_CHECKLIST"))
                .thenReturn(Optional.of(unsigned));
        assertThatThrownBy(() -> service.assertStartAllowed(s, HOSPITAL))
                .hasMessageContaining("signed PRE-OP checklist");

        unsigned.setSignedAt(LocalDateTime.now());
        assertThatCode(() -> service.assertStartAllowed(s, HOSPITAL)).doesNotThrowAnyException();
    }

    @Test
    void foreignChecklistCannotSatisfyGate() {
        Surgery s = surgery(SurgeryStatus.PRE_OP.name());
        requiredChecklist();
        SurgeryForm foreign = new SurgeryForm(); foreign.setHospitalId(8L); foreign.setSignedAt(LocalDateTime.now());
        when(formRepository.findBySurgeryIdAndFormTypeAndIsCurrentTrue(41L, "PRE_OP_CHECKLIST"))
                .thenReturn(Optional.of(foreign));
        assertThatThrownBy(() -> service.assertStartAllowed(s, HOSPITAL)).hasMessageContaining("checklist");
        verify(formRepository).findBySurgeryIdAndFormTypeAndIsCurrentTrue(41L, "PRE_OP_CHECKLIST");
    }

    @Test
    void latestClearanceGovernsAndConditionalNeedsComments() {
        Surgery s = surgery(SurgeryStatus.PRE_OP.name());
        requiredClearance();
        assertThatThrownBy(() -> service.assertStartAllowed(s, HOSPITAL)).hasMessageContaining("clearance");

        SurgeryAnaesthesiaClearance c = clearance(AnaesthesiaClearanceOutcome.CLEARED_WITH_CONDITIONS, null);
        when(clearanceRepository.findTopByHospitalIdAndSurgeryIdOrderByRecordedAtDescIdDesc(HOSPITAL, 41L)).thenReturn(Optional.of(c));
        assertThatThrownBy(() -> service.assertStartAllowed(s, HOSPITAL)).hasMessageContaining("clearance");
        c.setConditionsComments("Proceed with monitoring");
        assertThatCode(() -> service.assertStartAllowed(s, HOSPITAL)).doesNotThrowAnyException();
        c.setOutcome(AnaesthesiaClearanceOutcome.NOT_CLEARED);
        assertThatThrownBy(() -> service.assertStartAllowed(s, HOSPITAL)).hasMessageContaining("clearance");
    }

    @Test
    void latestClearanceSupersedesEarlierHistoryWithoutMutation() {
        Surgery s = surgery(SurgeryStatus.PRE_OP.name());
        requiredClearance();
        SurgeryAnaesthesiaClearance notCleared = clearance(AnaesthesiaClearanceOutcome.NOT_CLEARED, null);
        when(clearanceRepository.findTopByHospitalIdAndSurgeryIdOrderByRecordedAtDescIdDesc(HOSPITAL, 41L))
                .thenReturn(Optional.of(notCleared));
        assertThatThrownBy(() -> service.assertStartAllowed(s, HOSPITAL)).hasMessageContaining("clearance");

        SurgeryAnaesthesiaClearance cleared = clearance(AnaesthesiaClearanceOutcome.CLEARED, null);
        when(clearanceRepository.findTopByHospitalIdAndSurgeryIdOrderByRecordedAtDescIdDesc(HOSPITAL, 41L))
                .thenReturn(Optional.of(cleared));
        assertThatCode(() -> service.assertStartAllowed(s, HOSPITAL)).doesNotThrowAnyException();
        verify(clearanceRepository, never()).save(any());
    }

    @Test
    void clearanceIsImmutableHistoryAndActorIsServerDerived() {
        Surgery s = surgery(SurgeryStatus.PRE_OP.name());
        when(surgeryRepository.findByPublicIdAndHospitalId("s-1", HOSPITAL)).thenReturn(Optional.of(s));
        RecordAnaesthesiaClearanceRequest request = new RecordAnaesthesiaClearanceRequest();
        request.setOutcome(AnaesthesiaClearanceOutcome.CLEARED);
        service.recordClearance("s-1", request);
        ArgumentCaptor<SurgeryAnaesthesiaClearance> capture = ArgumentCaptor.forClass(SurgeryAnaesthesiaClearance.class);
        verify(clearanceRepository).save(capture.capture());
        assertThat(capture.getValue().getRecordedByUserId()).isEqualTo(USER);
        assertThat(capture.getValue().getRecordedAt()).isNotNull();
    }

    @Test
    void conditionalClearanceWithoutCommentsIsRejected() {
        Surgery s = surgery(SurgeryStatus.PRE_OP.name());
        when(surgeryRepository.findByPublicIdAndHospitalId("s-1", HOSPITAL)).thenReturn(Optional.of(s));
        RecordAnaesthesiaClearanceRequest request = new RecordAnaesthesiaClearanceRequest();
        request.setOutcome(AnaesthesiaClearanceOutcome.CLEARED_WITH_CONDITIONS);
        assertThatThrownBy(() -> service.recordClearance("s-1", request)).hasMessageContaining("Conditions/comments");
    }

    @Test
    void emergencyOverrideIsExplicitAndGateSpecific() {
        Surgery s = surgery(SurgeryStatus.PRE_OP.name()); s.setPriority("EMERGENCY");
        when(surgeryRepository.findByPublicIdAndHospitalId("s-1", HOSPITAL)).thenReturn(Optional.of(s));
        RecordEmergencyOverrideRequest request = new RecordEmergencyOverrideRequest();
        request.setReason("Life-threatening bleed"); request.setBypassedGates(Set.of(PreOpGate.PRE_OP_CHECKLIST));
        service.recordEmergencyOverride("s-1", request);
        ArgumentCaptor<SurgeryEmergencyOverride> capture = ArgumentCaptor.forClass(SurgeryEmergencyOverride.class);
        verify(overrideRepository).save(capture.capture());
        assertThat(capture.getValue().getBypassedGates()).isEqualTo("PRE_OP_CHECKLIST");
        assertThat(capture.getValue().getRecordedByUserId()).isEqualTo(USER);
        assertThat(capture.getValue().getRecordedAt()).isNotNull();
    }

    @Test
    void nonEmergencyAndReasonlessOverridesAreRejected() {
        Surgery normal = surgery(SurgeryStatus.PRE_OP.name());
        when(surgeryRepository.findByPublicIdAndHospitalId("s-1", HOSPITAL)).thenReturn(Optional.of(normal));
        RecordEmergencyOverrideRequest request = new RecordEmergencyOverrideRequest();
        request.setReason("urgent"); request.setBypassedGates(Set.of(PreOpGate.PRE_OP_CHECKLIST));
        assertThatThrownBy(() -> service.recordEmergencyOverride("s-1", request)).hasMessageContaining("emergency surgery");
    }

    @Test
    void emergencyOverrideBypassesOnlyItsExplicitGateForCurrentSchedule() {
        Surgery s = surgery(SurgeryStatus.PRE_OP.name());
        s.setPriority("EMERGENCY");
        requiredBoth();
        SurgeryEmergencyOverride override = override(PreOpGate.PRE_OP_CHECKLIST);
        when(overrideRepository.findTopByHospitalIdAndSurgeryIdOrderByRecordedAtDescIdDesc(HOSPITAL, 41L))
                .thenReturn(Optional.of(override));
        when(transitionRepository.findTopBySurgeryIdAndToStatusOrderByCreatedAtDescIdDesc(41L, SurgeryStatus.SCHEDULED.name()))
                .thenReturn(Optional.of(scheduledBefore(override.getRecordedAt())));

        assertThatThrownBy(() -> service.assertStartAllowed(s, HOSPITAL)).hasMessageContaining("clearance");

        SurgeryAnaesthesiaClearance cleared = clearance(AnaesthesiaClearanceOutcome.CLEARED, null);
        when(clearanceRepository.findTopByHospitalIdAndSurgeryIdOrderByRecordedAtDescIdDesc(HOSPITAL, 41L))
                .thenReturn(Optional.of(cleared));
        assertThatCode(() -> service.assertStartAllowed(s, HOSPITAL)).doesNotThrowAnyException();
    }

    @Test
    void oldOverrideIsInvalidAfterReschedule() {
        Surgery s = surgery(SurgeryStatus.PRE_OP.name());
        s.setPriority("EMERGENCY");
        requiredEmergencyChecklist();
        SurgeryEmergencyOverride override = override(PreOpGate.PRE_OP_CHECKLIST);
        when(overrideRepository.findTopByHospitalIdAndSurgeryIdOrderByRecordedAtDescIdDesc(HOSPITAL, 41L))
                .thenReturn(Optional.of(override));
        when(transitionRepository.findTopBySurgeryIdAndToStatusOrderByCreatedAtDescIdDesc(41L, SurgeryStatus.SCHEDULED.name()))
                .thenReturn(Optional.of(scheduledAfter(override.getRecordedAt())));

        assertThatThrownBy(() -> service.assertStartAllowed(s, HOSPITAL)).hasMessageContaining("checklist");
    }

    @Test
    void policiesOffPreserveDirectStartCompatibility() {
        assertThatCode(() -> service.assertStartAllowed(surgery(SurgeryStatus.SCHEDULED.name()), HOSPITAL))
                .doesNotThrowAnyException();
    }

    private void requiredChecklist() {
        when(policyService.resolve(HOSPITAL, OtPolicies.PRE_OP_CHECKLIST, "ELECTIVE")).thenReturn("REQUIRED");
        when(policyService.resolve(HOSPITAL, OtPolicies.ANAESTHESIA_CLEARANCE, "ELECTIVE")).thenReturn("OFF");
    }

    private void requiredClearance() {
        when(policyService.resolve(HOSPITAL, OtPolicies.PRE_OP_CHECKLIST, "ELECTIVE")).thenReturn("OFF");
        when(policyService.resolve(HOSPITAL, OtPolicies.ANAESTHESIA_CLEARANCE, "ELECTIVE")).thenReturn("REQUIRED");
    }

    private void requiredBoth() {
        when(policyService.resolve(HOSPITAL, OtPolicies.PRE_OP_CHECKLIST, "EMERGENCY")).thenReturn("REQUIRED");
        when(policyService.resolve(HOSPITAL, OtPolicies.ANAESTHESIA_CLEARANCE, "EMERGENCY")).thenReturn("REQUIRED");
    }

    private void requiredEmergencyChecklist() {
        when(policyService.resolve(HOSPITAL, OtPolicies.PRE_OP_CHECKLIST, "EMERGENCY")).thenReturn("REQUIRED");
        when(policyService.resolve(HOSPITAL, OtPolicies.ANAESTHESIA_CLEARANCE, "EMERGENCY")).thenReturn("OFF");
    }

    private SurgeryAnaesthesiaClearance clearance(AnaesthesiaClearanceOutcome outcome, String comments) {
        SurgeryAnaesthesiaClearance c = new SurgeryAnaesthesiaClearance(); c.setOutcome(outcome); c.setConditionsComments(comments); return c;
    }

    private SurgeryEmergencyOverride override(PreOpGate gate) {
        SurgeryEmergencyOverride override = new SurgeryEmergencyOverride();
        override.setReason("Immediate life threat");
        override.setBypassedGates(gate.name());
        override.setRecordedAt(LocalDateTime.now());
        return override;
    }

    private SurgeryStateTransition scheduledBefore(LocalDateTime time) {
        SurgeryStateTransition transition = new SurgeryStateTransition();
        transition.setCreatedAt(time.minusMinutes(1));
        return transition;
    }

    private SurgeryStateTransition scheduledAfter(LocalDateTime time) {
        SurgeryStateTransition transition = new SurgeryStateTransition();
        transition.setCreatedAt(time.plusMinutes(1));
        return transition;
    }
}
