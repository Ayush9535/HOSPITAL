package com.hms.service.hospital;

import com.hms.dto.AssignShiftRequest;
import com.hms.entity.NurseProfile;
import com.hms.entity.NurseShiftSchedule;
import com.hms.entity.ShiftTemplate;
import com.hms.repository.NurseProfileRepository;
import com.hms.repository.NurseShiftScheduleRepository;
import com.hms.repository.ShiftTemplateRepository;
import com.hms.security.NurseInchargeGuard;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks; import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate; import java.time.LocalTime; import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NurseShiftScheduleServiceTest {
    @Mock NurseShiftScheduleRepository scheduleRepository;
    @Mock ShiftTemplateRepository shiftTemplateRepository;
    @Mock NurseProfileRepository nurseProfileRepository;
    @Mock NurseInchargeGuard nurseInchargeGuard;
    @Mock SecurityContextHelper securityHelper;
    @Mock AuditLogService auditLogService;
    @InjectMocks NurseShiftScheduleService service;

    @Test void assign_snapshotsTemplateTimes_andWardScopes() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        NurseProfile p = new NurseProfile(); p.setId(11L); p.setHospitalId(7L); p.setWardId(3L); p.setName("Priya");
        when(nurseProfileRepository.findById(11L)).thenReturn(Optional.of(p));
        ShiftTemplate t = new ShiftTemplate(); t.setId(5L); t.setHospitalId(7L); t.setName("Morning");
        t.setStartTime(LocalTime.of(8,0)); t.setEndTime(LocalTime.of(16,0));
        when(shiftTemplateRepository.findByPublicId("st-1")).thenReturn(Optional.of(t));
        when(scheduleRepository.findByNurseProfileIdAndShiftDate(11L, LocalDate.of(2026,7,10))).thenReturn(Optional.empty());
        when(scheduleRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        AssignShiftRequest req = new AssignShiftRequest();
        req.setNurseProfileId(11L); req.setDate(LocalDate.of(2026,7,10)); req.setShiftTemplatePublicId("st-1");
        NurseShiftSchedule s = service.assign(req);

        verify(nurseInchargeGuard).assertWardAccess(3L);
        assertThat(s.getStartTime()).isEqualTo(LocalTime.of(8,0));
        assertThat(s.getEndTime()).isEqualTo(LocalTime.of(16,0));
        assertThat(s.getShiftTemplateId()).isEqualTo(5L);
    }

    @Test void isOnShiftNow_trueWithinWindow() {
        LocalTime now = LocalTime.now();
        NurseShiftSchedule s = new NurseShiftSchedule();
        s.setStartTime(now.minusHours(1)); s.setEndTime(now.plusHours(1));
        when(scheduleRepository.findByNurseProfileIdAndShiftDate(eq(11L), any())).thenReturn(Optional.of(s));
        assertThat(service.isOnShiftNow(11L)).isTrue();
    }

    @Test void withinWindow_handlesMidnightCrossing() {
        // night shift 22:00 -> 06:00
        assertThat(NurseShiftScheduleService.withinWindow(LocalTime.of(22,0), LocalTime.of(6,0), LocalTime.of(23,30))).isTrue();
        assertThat(NurseShiftScheduleService.withinWindow(LocalTime.of(22,0), LocalTime.of(6,0), LocalTime.of(2,0))).isTrue();
        assertThat(NurseShiftScheduleService.withinWindow(LocalTime.of(22,0), LocalTime.of(6,0), LocalTime.of(12,0))).isFalse();
    }
}
