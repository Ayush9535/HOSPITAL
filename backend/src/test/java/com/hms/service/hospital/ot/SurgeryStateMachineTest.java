package com.hms.service.hospital.ot;

import com.hms.entity.Surgery;
import com.hms.entity.SurgeryStateTransition;
import com.hms.entity.SurgeryStatus;
import com.hms.repository.SurgeryRepository;
import com.hms.repository.SurgeryStateTransitionRepository;
import com.hms.security.SecurityContextHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.hms.entity.SurgeryStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The transition table is the contract. This test walks every from x to pair, so an
 * accidental widening of the machine cannot pass unnoticed.
 */
@ExtendWith(MockitoExtension.class)
class SurgeryStateMachineTest {

    /** Independent restatement of the legal moves -- deliberately NOT imported from the machine. */
    private static final Map<SurgeryStatus, Set<SurgeryStatus>> EXPECTED = new EnumMap<>(SurgeryStatus.class);
    static {
        EXPECTED.put(REQUESTED, EnumSet.of(APPROVED, CANCELLED));
        EXPECTED.put(APPROVED, EnumSet.of(SCHEDULED, CANCELLED));
        EXPECTED.put(SCHEDULED, EnumSet.of(SCHEDULED, PRE_OP, IN_PROGRESS, POSTPONED, CANCELLED));
        EXPECTED.put(PRE_OP, EnumSet.of(IN_PROGRESS, POSTPONED, CANCELLED));
        EXPECTED.put(IN_PROGRESS, EnumSet.of(COMPLETED));
        EXPECTED.put(COMPLETED, EnumSet.of(CLOSED));
        EXPECTED.put(POSTPONED, EnumSet.of(APPROVED, CANCELLED));
        EXPECTED.put(CLOSED, EnumSet.noneOf(SurgeryStatus.class));
        EXPECTED.put(CANCELLED, EnumSet.noneOf(SurgeryStatus.class));
    }

    @Mock SurgeryRepository surgeryRepository;
    @Mock SurgeryStateTransitionRepository transitionRepository;
    @Mock SecurityContextHelper securityHelper;
    @InjectMocks SurgeryStateMachine machine;

    @BeforeEach
    void setUp() {
        lenient().when(securityHelper.getCurrentUserId()).thenReturn(42L);
        lenient().when(surgeryRepository.save(any(Surgery.class))).thenAnswer(i -> i.getArgument(0));
    }

    private Surgery at(SurgeryStatus status) {
        Surgery s = new Surgery();
        s.setId(1L);
        s.setHospitalId(7L);
        s.setStatus(status.name());
        return s;
    }

    @Test
    void everyFromToPair_matchesTheTransitionTable() {
        for (SurgeryStatus from : SurgeryStatus.values()) {
            for (SurgeryStatus to : SurgeryStatus.values()) {
                boolean expected = EXPECTED.get(from).contains(to);
                assertThat(machine.canMove(from, to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    void anIllegalMove_isRejectedWithAReadableMessage() {
        assertThatThrownBy(() -> machine.transition(at(REQUESTED), IN_PROGRESS, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requested surgery cannot move to in progress");
    }

    @Test
    void terminalStates_goNowhere() {
        assertThat(machine.allowedFrom(CANCELLED)).isEmpty();
        assertThat(machine.allowedFrom(CLOSED)).isEmpty();
        assertThat(CANCELLED.isTerminal()).isTrue();
        assertThat(CLOSED.isTerminal()).isTrue();
        assertThat(POSTPONED.isTerminal()).isFalse(); // postponed cases come back
    }

    @Test
    void cancelling_requiresAReasonFromTheTaxonomy() {
        assertThatThrownBy(() -> machine.transition(at(SCHEDULED), CANCELLED, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason is required");

        assertThatThrownBy(() -> machine.transition(at(SCHEDULED), CANCELLED, "BECAUSE_I_SAID_SO", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason is required");
    }

    @Test
    void postponing_requiresAReason_too() {
        assertThatThrownBy(() -> machine.transition(at(SCHEDULED), POSTPONED, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason is required");
    }

    @Test
    void aLegalMove_writesExactlyOneAuditRow_attributedToTheUser() {
        machine.transition(at(APPROVED), SCHEDULED, null, null, null);

        ArgumentCaptor<SurgeryStateTransition> captor = ArgumentCaptor.forClass(SurgeryStateTransition.class);
        verify(transitionRepository).save(captor.capture());
        SurgeryStateTransition t = captor.getValue();
        assertThat(t.getFromStatus()).isEqualTo("APPROVED");
        assertThat(t.getToStatus()).isEqualTo("SCHEDULED");
        assertThat(t.getActorKind()).isEqualTo(SurgeryStateTransition.ACTOR_USER);
        assertThat(t.getActorUserId()).isEqualTo(42L);
    }

    /** An approval nobody performed must never look like one somebody performed. */
    @Test
    void anAutoTransition_isAttributedToTheSystem_notToAUser() {
        machine.autoTransition(at(REQUESTED), APPROVED, SurgeryStateTransition.REASON_AUTO_APPROVED);

        ArgumentCaptor<SurgeryStateTransition> captor = ArgumentCaptor.forClass(SurgeryStateTransition.class);
        verify(transitionRepository).save(captor.capture());
        SurgeryStateTransition t = captor.getValue();
        assertThat(t.getActorKind()).isEqualTo(SurgeryStateTransition.ACTOR_SYSTEM);
        assertThat(t.getActorUserId()).isNull();
        assertThat(t.getReasonCode()).isEqualTo("AUTO_APPROVED_BY_POLICY");
    }

    @Test
    void approving_stampsApprovedAt_soTheWaitingListCanOrderByIt() {
        Surgery s = machine.autoTransition(at(REQUESTED), APPROVED, SurgeryStateTransition.REASON_AUTO_APPROVED);
        assertThat(s.getApprovedAt()).isNotNull();
    }

    /** A reschedule stays SCHEDULED and carries both slots on the transition row. */
    @Test
    void aReschedule_isASchedulesToScheduledMove_carryingBothSlots() {
        machine.transition(at(SCHEDULED), SCHEDULED, null, null, "{\"oldSlot\":\"A\",\"newSlot\":\"B\"}");

        ArgumentCaptor<SurgeryStateTransition> captor = ArgumentCaptor.forClass(SurgeryStateTransition.class);
        verify(transitionRepository).save(captor.capture());
        assertThat(captor.getValue().getPayloadJson()).contains("oldSlot").contains("newSlot");
    }

    @Test
    void recordCreation_writesARowWithNoPriorStatus() {
        machine.recordCreation(at(REQUESTED));

        ArgumentCaptor<SurgeryStateTransition> captor = ArgumentCaptor.forClass(SurgeryStateTransition.class);
        verify(transitionRepository).save(captor.capture());
        assertThat(captor.getValue().getFromStatus()).isNull();
        assertThat(captor.getValue().getToStatus()).isEqualTo("REQUESTED");
    }

    @Test
    void anUnknownStatusString_failsLoudly() {
        assertThatThrownBy(() -> SurgeryStatus.of("IN_THEATRE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown surgery status");
    }
}
