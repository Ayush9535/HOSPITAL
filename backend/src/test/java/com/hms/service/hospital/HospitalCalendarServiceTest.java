package com.hms.service.hospital;

import com.hms.entity.CalendarEvent;
import com.hms.entity.NurseShiftSchedule;
import com.hms.entity.Surgery;
import com.hms.repository.*;
import com.hms.security.NurseInchargeGuard;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HospitalCalendarServiceTest {
    @Mock SecurityContextHelper securityHelper;
    @Mock NurseInchargeGuard nurseInchargeGuard;
    @Mock WardRepository wardRepository;
    @Mock NurseShiftScheduleRepository shiftScheduleRepository;
    @Mock NurseAttendanceRepository attendanceRepository;
    @Mock SurgeryRepository surgeryRepository;
    @Mock IpdAdmissionRepository ipdAdmissionRepository;
    @Mock PatientRepository patientRepository;
    @Mock NurseProfileRepository nurseProfileRepository;
    @Mock CalendarEventRepository calendarEventRepository;
    @Mock AuditLogService auditLogService;
    @InjectMocks HospitalCalendarService service;

    private NurseShiftSchedule shift(Long wardId, LocalDate date) {
        NurseShiftSchedule s = new NurseShiftSchedule();
        s.setWardId(wardId); s.setShiftDate(date);
        s.setStartTime(LocalTime.of(8, 0)); s.setEndTime(LocalTime.of(16, 0));
        s.setNurseProfileId(1L);
        return s;
    }

    @Test void monthSummary_bucketsShiftsAndEventsOntoDays() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(nurseInchargeGuard.myWardIds()).thenReturn(List.of(3L));
        when(shiftScheduleRepository.findByWardIdInAndShiftDateBetween(any(), any(), any()))
                .thenReturn(List.of(shift(3L, LocalDate.of(2026, 7, 2)), shift(3L, LocalDate.of(2026, 7, 2))));
        when(surgeryRepository.findByHospitalIdAndStatusInOrderByScheduledAtAsc(any(), any()))
                .thenReturn(List.of());
        CalendarEvent holiday = new CalendarEvent();
        holiday.setPublicId("evt-1"); holiday.setTitle("Founder's Day"); holiday.setEventType(CalendarEvent.HOLIDAY);
        holiday.setFromDate(LocalDate.of(2026, 7, 2)); holiday.setToDate(LocalDate.of(2026, 7, 2));
        when(calendarEventRepository.findByHospitalIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(any(), any(), any()))
                .thenReturn(List.of(holiday));

        List<Map<String, Object>> days = service.monthSummary(2026, 7);

        assertThat(days).hasSize(31);
        Map<String, Object> jul2 = days.stream()
                .filter(d -> "2026-07-02".equals(String.valueOf(d.get("date")))).findFirst().orElseThrow();
        assertThat(jul2.get("shiftCount")).isEqualTo(2);
        assertThat(jul2.get("hasHoliday")).isEqualTo(true);
        assertThat((List<?>) jul2.get("events")).hasSize(1);
    }

    @Test void monthSummary_incharge_excludesSurgeryOutsideTheirWards() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(securityHelper.getCurrentUserRole()).thenReturn("NURSE_INCHARGE");
        when(nurseInchargeGuard.myWardIds()).thenReturn(List.of(3L));
        when(shiftScheduleRepository.findByWardIdInAndShiftDateBetween(any(), any(), any())).thenReturn(List.of());
        when(calendarEventRepository.findByHospitalIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(any(), any(), any()))
                .thenReturn(List.of());

        Surgery inWard = new Surgery();
        inWard.setIpdAdmissionId(100L); inWard.setStatus(Surgery.SCHEDULED);
        inWard.setScheduledAt(LocalDate.of(2026, 7, 5).atTime(10, 0));
        Surgery outWard = new Surgery();
        outWard.setIpdAdmissionId(200L); outWard.setStatus(Surgery.SCHEDULED);
        outWard.setScheduledAt(LocalDate.of(2026, 7, 5).atTime(12, 0));
        when(surgeryRepository.findByHospitalIdAndStatusInOrderByScheduledAtAsc(any(), any()))
                .thenReturn(List.of(inWard, outWard));

        com.hms.entity.IpdAdmission a1 = new com.hms.entity.IpdAdmission(); a1.setWardId(3L);
        com.hms.entity.IpdAdmission a2 = new com.hms.entity.IpdAdmission(); a2.setWardId(9L);
        when(ipdAdmissionRepository.findById(100L)).thenReturn(java.util.Optional.of(a1));
        when(ipdAdmissionRepository.findById(200L)).thenReturn(java.util.Optional.of(a2));

        List<Map<String, Object>> days = service.monthSummary(2026, 7);
        Map<String, Object> jul5 = days.stream()
                .filter(d -> "2026-07-05".equals(String.valueOf(d.get("date")))).findFirst().orElseThrow();
        assertThat(jul5.get("surgeryCount")).isEqualTo(1);
    }

    @Test void createEvent_rejectsBadType() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        com.hms.dto.CalendarEventRequest req = new com.hms.dto.CalendarEventRequest();
        req.setTitle("X"); req.setEventType("PARTY");
        req.setFromDate(LocalDate.of(2026, 7, 1)); req.setToDate(LocalDate.of(2026, 7, 1));
        assertThatThrownBy(() -> service.createEvent(req)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void createEvent_rejectsToBeforeFrom() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        com.hms.dto.CalendarEventRequest req = new com.hms.dto.CalendarEventRequest();
        req.setTitle("X"); req.setEventType(CalendarEvent.EVENT);
        req.setFromDate(LocalDate.of(2026, 7, 5)); req.setToDate(LocalDate.of(2026, 7, 1));
        assertThatThrownBy(() -> service.createEvent(req)).isInstanceOf(IllegalArgumentException.class);
    }
}
