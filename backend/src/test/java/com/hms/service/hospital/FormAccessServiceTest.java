package com.hms.service.hospital;

import com.hms.dto.FormAccessRequest;
import com.hms.entity.HospitalFormAccess;
import com.hms.repository.HospitalFormAccessRepository;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FormAccessServiceTest {
    @Mock HospitalFormAccessRepository repository;
    @Mock SecurityContextHelper securityHelper;
    @Mock AuditLogService auditLogService;
    @Mock com.hms.security.HospitalWebSocketHandler webSocketHandler;
    @InjectMocks FormAccessService service;

    @Test void list_defaultsToEnabledBoth_whenNoRows() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(repository.findByHospitalId(7L)).thenReturn(List.of());

        List<Map<String, Object>> list = service.list();

        assertThat(list).hasSize(23); // 8 nursing/IPD forms (incl. Ventilator, Severity Scores) + 15 OT
        Map<String, Object> vitals = list.stream()
                .filter(m -> "VITALS".equals(m.get("key"))).findFirst().orElseThrow();
        assertThat(vitals.get("enabled")).isEqualTo(true);
        assertThat(vitals.get("accessRole")).isEqualTo("BOTH");
        assertThat(vitals.get("label")).isEqualTo("Vitals");
    }

    @Test void list_appliesOverride() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        HospitalFormAccess row = new HospitalFormAccess();
        row.setHospitalId(7L); row.setFormKey("VITALS"); row.setEnabled(false); row.setAccessRole("DOCTOR");
        when(repository.findByHospitalId(7L)).thenReturn(List.of(row));

        Map<String, Object> vitals = service.list().stream()
                .filter(m -> "VITALS".equals(m.get("key"))).findFirst().orElseThrow();
        assertThat(vitals.get("enabled")).isEqualTo(false);
        assertThat(vitals.get("accessRole")).isEqualTo("DOCTOR");
    }

    @Test void effectiveForRole_nurse_readOnlyWhenDoctorOnly() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        HospitalFormAccess docOnly = new HospitalFormAccess();
        docOnly.setHospitalId(7L); docOnly.setFormKey("VITALS"); docOnly.setEnabled(true); docOnly.setAccessRole("DOCTOR");
        HospitalFormAccess off = new HospitalFormAccess();
        off.setHospitalId(7L); off.setFormKey("SUGAR_CHART"); off.setEnabled(false); off.setAccessRole("BOTH");
        when(repository.findByHospitalId(7L)).thenReturn(List.of(docOnly, off));

        Map<String, String> verdicts = service.effectiveForRole("NURSE");

        assertThat(verdicts.get("VITALS")).isEqualTo("READ_ONLY");
        assertThat(verdicts.get("SUGAR_CHART")).isEqualTo("HIDDEN");
        assertThat(verdicts.get("INITIAL_ASSESSMENT")).isEqualTo("EDITABLE");
    }

    @Test void effectiveForRole_normalizesInchargeToNurse() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        HospitalFormAccess nurseOnly = new HospitalFormAccess();
        nurseOnly.setHospitalId(7L); nurseOnly.setFormKey("VITALS"); nurseOnly.setEnabled(true); nurseOnly.setAccessRole("NURSE");
        when(repository.findByHospitalId(7L)).thenReturn(List.of(nurseOnly));

        assertThat(service.effectiveForRole("NURSE_INCHARGE").get("VITALS")).isEqualTo("EDITABLE");
        assertThat(service.effectiveForRole("DOCTOR").get("VITALS")).isEqualTo("READ_ONLY");
    }

    @Test void update_rejectsUnknownKey() {
        lenient().when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        FormAccessRequest req = new FormAccessRequest();
        req.setEnabled(true); req.setAccessRole("BOTH");
        assertThatThrownBy(() -> service.update("NOPE", req))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void update_rejectsBadRole() {
        lenient().when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        FormAccessRequest req = new FormAccessRequest();
        req.setEnabled(true); req.setAccessRole("EVERYONE");
        assertThatThrownBy(() -> service.update("VITALS", req))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void update_upsertsRow() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(repository.findByHospitalIdAndFormKey(7L, "VITALS")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        FormAccessRequest req = new FormAccessRequest();
        req.setEnabled(false); req.setAccessRole("DOCTOR");

        HospitalFormAccess saved = service.update("VITALS", req);

        assertThat(saved.getFormKey()).isEqualTo("VITALS");
        assertThat(saved.getEnabled()).isEqualTo(false);
        assertThat(saved.getAccessRole()).isEqualTo("DOCTOR");
        assertThat(saved.getHospitalId()).isEqualTo(7L);
    }

    @Test void assertCanEdit_passesWhenEditable() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(securityHelper.getCurrentUserRole()).thenReturn("NURSE");
        when(repository.findByHospitalId(7L)).thenReturn(java.util.List.of()); // default enabled+BOTH
        service.assertCanEdit("VITALS"); // no throw
    }

    @Test void assertCanEdit_throwsWhenReadOnly() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(securityHelper.getCurrentUserRole()).thenReturn("NURSE");
        com.hms.entity.HospitalFormAccess docOnly = new com.hms.entity.HospitalFormAccess();
        docOnly.setHospitalId(7L); docOnly.setFormKey("VITALS"); docOnly.setEnabled(true); docOnly.setAccessRole("DOCTOR");
        when(repository.findByHospitalId(7L)).thenReturn(java.util.List.of(docOnly));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.assertCanEdit("VITALS"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
}
