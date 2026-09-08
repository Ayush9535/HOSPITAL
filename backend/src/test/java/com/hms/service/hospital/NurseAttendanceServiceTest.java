package com.hms.service.hospital;

import com.hms.dto.MarkAttendanceRequest;
import com.hms.entity.AttendanceStatus;
import com.hms.entity.NurseAttendance;
import com.hms.entity.NurseProfile;
import com.hms.entity.NurseShiftSchedule;
import com.hms.repository.NurseAttendanceRepository;
import com.hms.repository.NurseProfileRepository;
import com.hms.repository.NurseShiftScheduleRepository;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NurseAttendanceServiceTest {
    @Mock NurseAttendanceRepository attendanceRepository;
    @Mock NurseProfileRepository nurseProfileRepository;
    @Mock NurseShiftScheduleRepository scheduleRepository;
    @Mock NurseInchargeGuard nurseInchargeGuard;
    @Mock SecurityContextHelper securityHelper;
    @Mock AuditLogService auditLogService;
    @Mock com.hms.service.RealtimeNotifier notifier;
    @InjectMocks NurseAttendanceService service;

    private static final LocalDate D = LocalDate.of(2026, 7, 10);

    private NurseProfile nurse() {
        NurseProfile p = new NurseProfile();
        p.setId(11L); p.setHospitalId(7L); p.setWardId(3L); p.setName("Priya"); p.setIsActive(true);
        return p;
    }
    private MarkAttendanceRequest req(String status) {
        MarkAttendanceRequest r = new MarkAttendanceRequest();
        r.setNurseProfileId(11L); r.setDate(D); r.setStatus(status);
        return r;
    }

    @Test void mark_createsRow_andSnapshotsScheduledShift() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(securityHelper.getCurrentUserId()).thenReturn(20L);
        when(nurseProfileRepository.findById(11L)).thenReturn(Optional.of(nurse()));
        NurseShiftSchedule s = new NurseShiftSchedule();
        s.setShiftTemplateId(5L); s.setStartTime(LocalTime.of(8, 0)); s.setEndTime(LocalTime.of(16, 0));
        when(scheduleRepository.findByNurseProfileIdAndShiftDate(11L, D)).thenReturn(Optional.of(s));
        when(attendanceRepository.findByNurseProfileIdAndAttendanceDate(11L, D)).thenReturn(Optional.empty());
        when(attendanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        NurseAttendance a = service.mark(req(AttendanceStatus.PRESENT));

        verify(nurseInchargeGuard).assertWardAccess(3L);
        assertThat(a.getStatus()).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(a.getShiftTemplateId()).isEqualTo(5L);
        assertThat(a.getShiftStartTime()).isEqualTo(LocalTime.of(8, 0));
        assertThat(a.getMarkedByUserId()).isEqualTo(20L);
    }

    @Test void mark_upsertsExistingRow() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(securityHelper.getCurrentUserId()).thenReturn(20L);
        when(nurseProfileRepository.findById(11L)).thenReturn(Optional.of(nurse()));
        when(scheduleRepository.findByNurseProfileIdAndShiftDate(11L, D)).thenReturn(Optional.empty());
        NurseAttendance existing = new NurseAttendance();
        existing.setId(99L); existing.setHospitalId(7L); existing.setNurseProfileId(11L);
        existing.setAttendanceDate(D); existing.setStatus(AttendanceStatus.ABSENT);
        when(attendanceRepository.findByNurseProfileIdAndAttendanceDate(11L, D)).thenReturn(Optional.of(existing));
        when(attendanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        NurseAttendance a = service.mark(req(AttendanceStatus.PRESENT));

        assertThat(a.getId()).isEqualTo(99L);              // same row, upserted
        assertThat(a.getStatus()).isEqualTo(AttendanceStatus.PRESENT);
    }

    @Test void mark_rejectsInvalidStatus() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(nurseProfileRepository.findById(11L)).thenReturn(Optional.of(nurse()));
        assertThatThrownBy(() -> service.mark(req("NAPPING")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
