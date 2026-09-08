package com.hms.service.hospital.ot;

import com.hms.entity.Surgery;
import com.hms.entity.SurgeryMilestone;
import com.hms.entity.WhoChecklist;
import com.hms.repository.SurgeryMilestoneRepository;
import com.hms.repository.SurgeryRepository;
import com.hms.repository.WhoChecklistRepository;
import com.hms.security.PerformingNurseResolver;
import com.hms.security.SecurityContextHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SurgeryExecutionServiceTest {

    private static final Long HOSPITAL = 7L;

    @Mock SurgeryRepository surgeryRepository;
    @Mock SurgeryMilestoneRepository milestoneRepository;
    @Mock WhoChecklistRepository whoRepository;
    @Mock SecurityContextHelper securityHelper;
    @Mock PerformingNurseResolver performingNurseResolver;
    @InjectMocks SurgeryExecutionService service;

    private final WhoChecklist stored = new WhoChecklist();

    @BeforeEach
    void setUp() {
        lenient().when(securityHelper.getCurrentHospitalId()).thenReturn(HOSPITAL);
        lenient().when(securityHelper.getCurrentUserId()).thenReturn(42L);
        Surgery s = new Surgery();
        s.setId(1L);
        s.setHospitalId(HOSPITAL);
        lenient().when(surgeryRepository.findById(1L)).thenReturn(Optional.of(s));
        lenient().when(surgeryRepository.save(any(Surgery.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(performingNurseResolver.resolve(any())).thenReturn(null);
        stored.setHospitalId(HOSPITAL);
        stored.setSurgeryId(1L);
        lenient().when(whoRepository.save(any(WhoChecklist.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(whoRepository.findBySurgeryId(1L)).thenReturn(Optional.of(stored));
    }

    @Test
    void signingInThenTimingOut_recordsBothPhases() {
        // Behave like the table: reads return whatever was last saved.
        java.util.concurrent.atomic.AtomicReference<WhoChecklist> holder = new java.util.concurrent.atomic.AtomicReference<>();
        when(whoRepository.findBySurgeryId(1L)).thenAnswer(i -> Optional.ofNullable(holder.get()));
        when(whoRepository.save(any(WhoChecklist.class))).thenAnswer(i -> { holder.set(i.getArgument(0)); return i.getArgument(0); });

        WhoChecklist afterSignIn = service.signPhase(1L, "SIGN_IN", true, null);
        assertThat(afterSignIn.getSignInAt()).isNotNull();
        assertThat(afterSignIn.getSiteMarked()).isTrue();

        WhoChecklist afterTimeOut = service.signPhase(1L, "TIME_OUT", null, null);
        assertThat(afterTimeOut.getTimeOutAt()).isNotNull();
    }

    /** The phases are ordered: Time-Out before Sign-In is a clinical error. */
    @Test
    void timingOutBeforeSigningIn_isRejected() {
        when(whoRepository.findBySurgeryId(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.signPhase(1L, "TIME_OUT", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Complete Sign-In before Time-Out");
    }

    /** Sign-Out records the counts; an incorrect count cannot be signed off. */
    @Test
    void signingOutWithIncorrectCounts_isRejected() {
        stored.setSignInAt(java.time.LocalDateTime.now());
        stored.setTimeOutAt(java.time.LocalDateTime.now());
        when(whoRepository.findBySurgeryId(1L)).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.signPhase(1L, "SIGN_OUT", null, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("counts are not correct");
    }

    @Test
    void signingTheSamePhaseTwice_isRejected() {
        stored.setSignInAt(java.time.LocalDateTime.now());
        when(whoRepository.findBySurgeryId(1L)).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.signPhase(1L, "SIGN_IN", true, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already signed");
    }

    /** The state machine's gate reads this: true only once Time-Out is signed. */
    @Test
    void timeOutSigned_reflectsTheChecklistState() {
        when(whoRepository.findBySurgeryId(1L)).thenReturn(Optional.empty());
        assertThat(service.timeOutSigned(1L)).isFalse();

        stored.setTimeOutAt(java.time.LocalDateTime.now());
        when(whoRepository.findBySurgeryId(1L)).thenReturn(Optional.of(stored));
        assertThat(service.timeOutSigned(1L)).isTrue();
    }

    @Test
    void anUnknownMilestone_isRejected() {
        assertThatThrownBy(() -> service.recordMilestone(1L, "TELEPORTED", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown milestone");
    }

    @Test
    void aKnownMilestone_isRecorded() {
        when(milestoneRepository.save(any(SurgeryMilestone.class))).thenAnswer(i -> i.getArgument(0));
        SurgeryMilestone m = service.recordMilestone(1L, SurgeryMilestone.INCISION, null, null, "clean");
        assertThat(m.getMilestone()).isEqualTo("INCISION");
        assertThat(m.getOccurredAt()).isNotNull();
    }

    @Test
    void anEmptyOperativeNote_isRejected() {
        assertThatThrownBy(() -> service.saveOperativeNote(1L, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Operative note is required");
    }

    @Test
    void anOperativeNote_isStampedWithAuthorAndTime() {
        Surgery s = service.saveOperativeNote(1L, "Uneventful procedure.");
        assertThat(s.getOperativeNote()).isEqualTo("Uneventful procedure.");
        assertThat(s.getOperativeNoteByUserId()).isEqualTo(42L);
        assertThat(s.getOperativeNoteAt()).isNotNull();
    }
}
