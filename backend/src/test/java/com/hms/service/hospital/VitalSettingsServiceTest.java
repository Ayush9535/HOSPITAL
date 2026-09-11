package com.hms.service.hospital;

import com.hms.dto.VitalSettingRequest;
import com.hms.entity.HospitalVital;
import com.hms.repository.HospitalVitalRepository;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VitalSettingsServiceTest {
    @Mock HospitalVitalRepository repository;
    @Mock SecurityContextHelper securityHelper;
    @Mock AuditLogService auditLogService;
    @Mock com.hms.security.HospitalWebSocketHandler webSocketHandler;
    @InjectMocks VitalSettingsService service;

    private HospitalVital custom(String key, String label, boolean enabled) {
        HospitalVital v = new HospitalVital();
        v.setHospitalId(7L); v.setVitalKey(key); v.setLabel(label); v.setUnit("mg/dL");
        v.setEnabled(enabled); v.setIsCustom(true); v.setSortOrder(1); v.setPublicId("pub-" + key);
        return v;
    }

    @Test void list_builtInsEnabledByDefault_andCustomsAppended() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(repository.findByHospitalId(7L)).thenReturn(List.of(custom("GRBS", "GRBS", true)));

        List<Map<String, Object>> list = service.list();

        assertThat(list).hasSize(7); // 6 built-ins + 1 custom
        assertThat(list.get(0).get("key")).isEqualTo("BP");
        assertThat(list.get(0).get("enabled")).isEqualTo(true);
        assertThat(list.get(0).get("isCustom")).isEqualTo(false);
        Map<String, Object> grbs = list.get(6);
        assertThat(grbs.get("key")).isEqualTo("GRBS");
        assertThat(grbs.get("isCustom")).isEqualTo(true);
    }

    @Test void enabledVitals_omitsDisabled() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        HospitalVital bpOff = new HospitalVital();
        bpOff.setHospitalId(7L); bpOff.setVitalKey("BP"); bpOff.setLabel("Blood Pressure");
        bpOff.setEnabled(false); bpOff.setIsCustom(false);
        when(repository.findByHospitalId(7L)).thenReturn(List.of(bpOff, custom("GRBS", "GRBS", false)));

        List<String> keys = service.enabledVitals().stream().map(m -> (String) m.get("key")).toList();

        assertThat(keys).doesNotContain("BP", "GRBS");
        assertThat(keys).contains("PULSE", "HEIGHT", "WEIGHT");
    }

    @Test void enabledBuiltInKeys_defaultsToAllWhenNoRows() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(repository.findByHospitalId(7L)).thenReturn(List.of());
        assertThat(service.enabledBuiltInKeys())
                .containsExactlyInAnyOrder("BP", "TEMPERATURE", "PULSE", "HEIGHT", "WEIGHT", "SPO2");
    }

    @Test void toggle_builtIn_createsOverrideRow() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(repository.findByHospitalIdAndVitalKey(7L, "BP")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        VitalSettingRequest req = new VitalSettingRequest();
        req.setEnabled(false);

        HospitalVital saved = service.toggle("BP", req);

        assertThat(saved.getVitalKey()).isEqualTo("BP");
        assertThat(saved.getEnabled()).isFalse();
        assertThat(saved.getIsCustom()).isFalse();
    }

    @Test void toggle_unknownKey_throws() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(repository.findByHospitalIdAndVitalKey(7L, "NOPE")).thenReturn(Optional.empty());
        VitalSettingRequest req = new VitalSettingRequest();
        req.setEnabled(true);
        assertThatThrownBy(() -> service.toggle("NOPE", req)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void addCustom_derivesKey_andRejectsDuplicate() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(repository.findByHospitalIdAndVitalKey(7L, "RANDOM_SUGAR")).thenReturn(Optional.empty());
        when(repository.findByHospitalId(7L)).thenReturn(List.of());
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        VitalSettingRequest req = new VitalSettingRequest();
        req.setName("Random Sugar"); req.setUnit("mg/dL");

        HospitalVital saved = service.addCustom(req);

        assertThat(saved.getVitalKey()).isEqualTo("RANDOM_SUGAR");
        assertThat(saved.getIsCustom()).isTrue();
        assertThat(saved.getEnabled()).isTrue();
    }

    @Test void addCustom_rejectsBuiltInName() {
        lenient().when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        VitalSettingRequest req = new VitalSettingRequest();
        req.setName("Pulse");
        assertThatThrownBy(() -> service.addCustom(req)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void addCustom_rejectsBlankName() {
        lenient().when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        VitalSettingRequest req = new VitalSettingRequest();
        req.setName("   ");
        assertThatThrownBy(() -> service.addCustom(req)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void deleteCustom_rejectsBuiltInOverride() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        HospitalVital builtIn = new HospitalVital();
        builtIn.setHospitalId(7L); builtIn.setVitalKey("BP"); builtIn.setIsCustom(false); builtIn.setPublicId("pub-bp");
        when(repository.findByPublicId("pub-bp")).thenReturn(Optional.of(builtIn));

        assertThatThrownBy(() -> service.deleteCustom("pub-bp")).isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).delete(any());
    }

    @Test void deleteCustom_deletesDefinitionOnly() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        HospitalVital v = custom("GRBS", "GRBS", true);
        when(repository.findByPublicId("pub-GRBS")).thenReturn(Optional.of(v));

        service.deleteCustom("pub-GRBS");

        verify(repository).delete(v);
    }
}
