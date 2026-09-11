package com.hms.service.hospital;

import com.hms.dto.ShiftTemplateRequest;
import com.hms.entity.ShiftTemplate;
import com.hms.repository.ShiftTemplateRepository;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks; import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShiftTemplateServiceTest {
    @Mock ShiftTemplateRepository repository;
    @Mock SecurityContextHelper securityHelper;
    @Mock AuditLogService auditLogService;
    @Mock NurseShiftScheduleService nurseShiftScheduleService;
    @InjectMocks ShiftTemplateService service;

    @Test void create_savesWithHospitalAndTimes() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        ShiftTemplateRequest r = new ShiftTemplateRequest();
        r.setName("Morning"); r.setStartTime(LocalTime.of(8,0)); r.setEndTime(LocalTime.of(16,0));
        ShiftTemplate t = service.create(r);
        assertThat(t.getHospitalId()).isEqualTo(7L);
        assertThat(t.getName()).isEqualTo("Morning");
        assertThat(t.getStartTime()).isEqualTo(LocalTime.of(8,0));
    }

    @Test void create_rejectsEqualStartEnd() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        ShiftTemplateRequest r = new ShiftTemplateRequest();
        r.setName("Bad"); r.setStartTime(LocalTime.of(8,0)); r.setEndTime(LocalTime.of(8,0));
        assertThatThrownBy(() -> service.create(r)).isInstanceOf(IllegalArgumentException.class);
    }
}
