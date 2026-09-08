package com.hms.service.hospital.ot;

import com.hms.entity.Surgery;
import com.hms.entity.SurgeryStatus;
import com.hms.entity.WhoChecklist;
import com.hms.repository.SurgeryRepository;
import com.hms.repository.SurgeryStateTransitionRepository;
import com.hms.repository.WhoChecklistRepository;
import com.hms.security.SecurityContextHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OtAnalyticsServiceTest {

    private static final Long HOSPITAL = 7L;

    @Mock SurgeryStateTransitionRepository transitionRepository;
    @Mock SurgeryRepository surgeryRepository;
    @Mock WhoChecklistRepository whoRepository;
    @Mock com.hms.repository.OtRoomOccupancyRepository occupancyRepository;
    @Mock SecurityContextHelper securityHelper;
    @InjectMocks OtAnalyticsService service;

    @BeforeEach
    void setUp() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(HOSPITAL);
    }

    private Surgery completed(long id) {
        Surgery s = new Surgery();
        s.setId(id);
        s.setHospitalId(HOSPITAL);
        s.setStatus(SurgeryStatus.COMPLETED.name());
        s.setEstimatedDurationMinutes(60);
        return s;
    }

    private WhoChecklist fullySigned() {
        WhoChecklist c = new WhoChecklist();
        c.setSignInAt(LocalDateTime.now());
        c.setTimeOutAt(LocalDateTime.now());
        c.setSignOutAt(LocalDateTime.now());
        return c;
    }

    /** WHO compliance is the fraction of completed cases with all three phases signed. */
    @Test
    void whoCompliance_isThePercentOfCompletedCasesFullySigned() {
        when(surgeryRepository.findScheduledBetween(any(), any(), any()))
                .thenReturn(List.of(completed(1L), completed(2L)));
        when(whoRepository.findBySurgeryId(1L)).thenReturn(Optional.of(fullySigned()));
        when(whoRepository.findBySurgeryId(2L)).thenReturn(Optional.empty()); // not signed
        lenient().when(transitionRepository.countReaching(any(), any(), any(), any())).thenReturn(0L);
        lenient().when(transitionRepository.cancellationsByReason(any(), any(), any())).thenReturn(List.of());
        when(occupancyRepository.findClosedSpans(any(), any(), any())).thenReturn(List.of());
        lenient().when(occupancyRepository.findBySurgeryIdAndOccupiedToIsNull(any())).thenReturn(java.util.Optional.empty());
        when(surgeryRepository.countUnplannedReturns(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(0L);

        Map<String, Object> out = service.nabhIndicators(null, null);

        assertThat(out.get("completed")).isEqualTo(2L);
        assertThat(out.get("whoCompliancePercent")).isEqualTo(50.0); // one of two
    }

    @Test
    void withNoCompletedCases_whoComplianceIsNull_notZeroDivide() {
        when(surgeryRepository.findScheduledBetween(any(), any(), any())).thenReturn(List.of());
        lenient().when(transitionRepository.countReaching(any(), any(), any(), any())).thenReturn(0L);
        lenient().when(transitionRepository.cancellationsByReason(any(), any(), any())).thenReturn(List.of());
        when(occupancyRepository.findClosedSpans(any(), any(), any())).thenReturn(List.of());
        when(surgeryRepository.countUnplannedReturns(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(0L);

        Map<String, Object> out = service.nabhIndicators(null, null);

        assertThat(out.get("whoCompliancePercent")).isNull();
    }

    @Test
    void summary_countsScheduledCompletedAndCancelled() {
        when(surgeryRepository.findScheduledBetween(any(), any(), any())).thenReturn(List.of(completed(1L)));
        when(transitionRepository.countReaching(any(), eq("COMPLETED"), any(), any())).thenReturn(1L);
        when(transitionRepository.countReaching(any(), eq("CANCELLED"), any(), any())).thenReturn(2L);
        when(transitionRepository.cancellationsByReason(any(), any(), any()))
                .thenReturn(java.util.List.<Object[]>of(new Object[]{"PATIENT_REFUSED", 2L}));

        Map<String, Object> out = service.summary(null);

        assertThat(out.get("scheduledToday")).isEqualTo(1);
        assertThat(out.get("completedToday")).isEqualTo(1L);
        assertThat(out.get("cancelledToday")).isEqualTo(2L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byReason = (List<Map<String, Object>>) out.get("cancellationsByReason");
        assertThat(byReason).hasSize(1);
        assertThat(byReason.get(0).get("reason")).isEqualTo("PATIENT_REFUSED");
    }

    /** Turnover = gap between one span's close and the next span's open in the SAME room. */
    @Test
    void turnoverAndUtilisation_areComputedFromOccupancySpans() {
        java.time.LocalDateTime base = java.time.LocalDateTime.of(2026, 7, 20, 9, 0);
        // Room 1: 09:00-10:00 (60 min), then 10:20-11:00 (40 min). Turnover gap = 20 min.
        com.hms.entity.OtRoomOccupancy a = span(1L, base, base.plusMinutes(60));
        com.hms.entity.OtRoomOccupancy b = span(1L, base.plusMinutes(80), base.plusMinutes(120));
        when(surgeryRepository.findScheduledBetween(any(), any(), any())).thenReturn(List.of());
        lenient().when(transitionRepository.countReaching(any(), any(), any(), any())).thenReturn(0L);
        lenient().when(transitionRepository.cancellationsByReason(any(), any(), any())).thenReturn(List.of());
        when(occupancyRepository.findClosedSpans(any(), any(), any())).thenReturn(List.of(a, b));
        when(surgeryRepository.countUnplannedReturns(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(0L);

        Map<String, Object> out = service.nabhIndicators(null, null);

        assertThat(out.get("occupiedTheatreMinutes")).isEqualTo(100L); // 60 + 40
        assertThat(out.get("averageTurnoverMinutes")).isEqualTo(20.0);
    }

    private com.hms.entity.OtRoomOccupancy span(long roomId, java.time.LocalDateTime from, java.time.LocalDateTime to) {
        com.hms.entity.OtRoomOccupancy o = new com.hms.entity.OtRoomOccupancy();
        o.setHospitalId(HOSPITAL);
        o.setOtRoomId(roomId);
        o.setOccupiedFrom(from);
        o.setOccupiedTo(to);
        return o;
    }
}
