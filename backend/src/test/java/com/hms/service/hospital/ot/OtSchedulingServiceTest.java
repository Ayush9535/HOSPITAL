package com.hms.service.hospital.ot;

import com.hms.entity.OtRoom;
import com.hms.entity.Surgery;
import com.hms.repository.OtRoomRepository;
import com.hms.repository.SurgeryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OtSchedulingServiceTest {

    private static final Long HOSPITAL = 7L;
    private static final LocalDateTime START = LocalDateTime.of(2026, 7, 20, 9, 0);

    @Mock SurgeryRepository surgeryRepository;
    @Mock OtRoomRepository roomRepository;
    @InjectMocks OtSchedulingService service;

    private OtRoom room() {
        OtRoom r = new OtRoom();
        r.setId(1L);
        r.setName("OT-1");
        r.setTurnoverMinutes(15);
        return r;
    }

    private Surgery surgery(Long id, Long surgeonId, Integer duration) {
        Surgery s = new Surgery();
        s.setId(id);
        s.setHospitalId(HOSPITAL);
        s.setSurgeonDoctorId(surgeonId);
        s.setEstimatedDurationMinutes(duration);
        return s;
    }

    @Test
    void aFreeTheatre_isAccepted() {
        when(surgeryRepository.countRoomOverlaps(any(), any(), any(), any(), any(Integer.class), any()))
                .thenReturn(0L);
        lenient().when(surgeryRepository.countSurgeonOverlaps(any(), any(), any(), any(), any()))
                .thenReturn(0L);

        assertThatCode(() -> service.assertSlotIsFree(HOSPITAL, room(), surgery(5L, 99L, 60), START))
                .doesNotThrowAnyException();
    }

    @Test
    void anOccupiedTheatre_isRejected() {
        when(surgeryRepository.countRoomOverlaps(any(), any(), any(), any(), any(Integer.class), any()))
                .thenReturn(1L);

        assertThatThrownBy(() -> service.assertSlotIsFree(HOSPITAL, room(), surgery(5L, 99L, 60), START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already booked");
    }

    @Test
    void aBusySurgeon_isRejected_evenWhenTheTheatreIsFree() {
        when(surgeryRepository.countRoomOverlaps(any(), any(), any(), any(), any(Integer.class), any()))
                .thenReturn(0L);
        when(surgeryRepository.countSurgeonOverlaps(any(), any(), any(), any(), any()))
                .thenReturn(1L);

        assertThatThrownBy(() -> service.assertSlotIsFree(HOSPITAL, room(), surgery(5L, 99L, 60), START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("surgeon is already operating");
    }

    /** An external operator (free-text name, no doctor id) has no surgeon clash to check. */
    @Test
    void anExternalOperator_skipsTheSurgeonCheck() {
        when(surgeryRepository.countRoomOverlaps(any(), any(), any(), any(), any(Integer.class), any()))
                .thenReturn(0L);

        assertThatCode(() -> service.assertSlotIsFree(HOSPITAL, room(), surgery(5L, null, 60), START))
                .doesNotThrowAnyException();
    }

    /** A reschedule excludes its own row, or the case would clash with itself. */
    @Test
    void aRescheduledCase_excludesItselfFromTheClashCheck() {
        when(surgeryRepository.countRoomOverlaps(eq(HOSPITAL), eq(1L), any(), any(), eq(15), eq(5L)))
                .thenReturn(0L);
        lenient().when(surgeryRepository.countSurgeonOverlaps(any(), any(), any(), any(), eq(5L)))
                .thenReturn(0L);

        assertThatCode(() -> service.assertSlotIsFree(HOSPITAL, room(), surgery(5L, 99L, 60), START))
                .doesNotThrowAnyException();
    }

    /** An unsaved surgery uses a sentinel exclude id, never null (which SQL would drop). */
    @Test
    void anUnsavedCase_usesASentinelExcludeId() {
        when(surgeryRepository.countRoomOverlaps(eq(HOSPITAL), eq(1L), any(), any(), eq(15), eq(-1L)))
                .thenReturn(0L);

        assertThatCode(() -> service.assertSlotIsFree(HOSPITAL, room(), surgery(null, null, 60), START))
                .doesNotThrowAnyException();
    }

    @Test
    void missingDateTime_isRejected() {
        assertThatThrownBy(() -> service.assertSlotIsFree(HOSPITAL, room(), surgery(5L, 99L, 60), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("date and time");
    }

    @Test
    void aMissingDuration_fallsBackToTheDefault() {
        assertThatCode(() -> {
            int d = service.durationOf(surgery(5L, 99L, null));
            org.assertj.core.api.Assertions.assertThat(d).isEqualTo(OtSchedulingService.DEFAULT_DURATION_MINUTES);
        }).doesNotThrowAnyException();
    }
}
