package com.hms.service.hospital.ot;

import com.hms.entity.OtWorkflowPolicy;
import com.hms.repository.OtWorkflowPolicyRepository;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OtPolicyServiceTest {

    private static final Long HOSPITAL = 7L;

    @Mock OtWorkflowPolicyRepository repository;
    @Mock SecurityContextHelper securityHelper;
    @Mock AuditLogService auditLogService;
    @InjectMocks OtPolicyService service;

    private OtWorkflowPolicy row(String key, String scope, String value) {
        OtWorkflowPolicy p = new OtWorkflowPolicy();
        p.setHospitalId(HOSPITAL);
        p.setPolicyKey(key);
        p.setPriorityScope(scope);
        p.setValue(value);
        return p;
    }

    /** A hospital with no rows uses the built-in default, without seeding. */
    @Test
    void resolve_fallsBackToTheDefault_whenNoRow() {
        when(repository.findByHospitalIdAndPolicyKey(HOSPITAL, OtPolicies.APPROVAL_MODE))
                .thenReturn(List.of());
        assertThat(service.resolve(HOSPITAL, OtPolicies.APPROVAL_MODE, "ELECTIVE")).isEqualTo("NONE");
    }

    @Test
    void resolve_prefersAnAnyScopeOverride() {
        when(repository.findByHospitalIdAndPolicyKey(HOSPITAL, OtPolicies.APPROVAL_MODE))
                .thenReturn(List.of(row(OtPolicies.APPROVAL_MODE, "ANY", "SINGLE")));
        assertThat(service.resolve(HOSPITAL, OtPolicies.APPROVAL_MODE, "ELECTIVE")).isEqualTo("SINGLE");
    }

    /** Emergency is a scope, not a flow: the same key resolves differently for an emergency. */
    @Test
    void resolve_prefersTheEmergencyScope_forAnEmergency() {
        when(repository.findByHospitalIdAndPolicyKey(HOSPITAL, OtPolicies.APPROVAL_MODE))
                .thenReturn(List.of(
                        row(OtPolicies.APPROVAL_MODE, "ANY", "DUAL"),
                        row(OtPolicies.APPROVAL_MODE, "EMERGENCY", "NONE")));

        assertThat(service.resolve(HOSPITAL, OtPolicies.APPROVAL_MODE, "ELECTIVE")).isEqualTo("DUAL");
        assertThat(service.resolve(HOSPITAL, OtPolicies.APPROVAL_MODE, "EMERGENCY")).isEqualTo("NONE");
    }

    @Test
    void updateDefaults_rejectsAnInvalidValue() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(HOSPITAL);
        assertThatThrownBy(() -> service.updateDefaults(Map.of(OtPolicies.APPROVAL_MODE, "SOMETIMES")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid value");
    }

    @Test
    void updateDefaults_rejectsAnUnknownKey() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(HOSPITAL);
        assertThatThrownBy(() -> service.updateDefaults(Map.of("PLEASE_HURRY", "YES")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown policy");
    }

    /**
     * The archetype is the proof of the thesis: four hospital shapes, one codebase, the
     * difference is rows. Each preset also seeds the standard emergency waivers.
     */
    @Test
    void applyArchetype_writesThePresetPlusEmergencyWaivers() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(HOSPITAL);
        lenient().when(repository.findByHospitalId(HOSPITAL)).thenReturn(List.of());
        lenient().when(repository.findByHospitalIdAndPolicyKey(any(), any())).thenReturn(List.of());

        service.applyArchetype("CORPORATE");

        verify(repository).deleteByHospitalId(HOSPITAL);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OtWorkflowPolicy>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        List<OtWorkflowPolicy> saved = captor.getValue();

        // 8 ANY-scope rows + 3 emergency waivers.
        assertThat(saved).hasSize(OtPolicies.all().size() + 3);
        assertThat(saved).anySatisfy(p -> {
            assertThat(p.getPolicyKey()).isEqualTo(OtPolicies.APPROVAL_MODE);
            assertThat(p.getPriorityScope()).isEqualTo("ANY");
            assertThat(p.getValue()).isEqualTo("DUAL");
        });
        assertThat(saved).anySatisfy(p -> {
            assertThat(p.getPolicyKey()).isEqualTo(OtPolicies.APPROVAL_MODE);
            assertThat(p.getPriorityScope()).isEqualTo("EMERGENCY");
            assertThat(p.getValue()).isEqualTo("NONE");
        });
    }

    @Test
    void applyArchetype_rejectsAnUnknownName() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(HOSPITAL);
        assertThatThrownBy(() -> service.applyArchetype("ENORMOUS"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown archetype");
    }

    /**
     * The archetype matrix asserted end to end: for each of the four shapes, the approval
     * mode a scheduler would face matches the intended design. This is the "policy matrix"
     * exit criterion, at the resolution layer.
     */
    @Test
    void theFourArchetypes_resolveToTheirIntendedApprovalMode() {
        Map<String, String> expected = Map.of(
                "SMALL", "NONE", "MEDIUM", "SINGLE", "LARGE", "SINGLE", "CORPORATE", "DUAL");
        expected.forEach((archetype, mode) -> {
            Map<String, String> preset = OtPolicies.archetype(archetype);
            assertThat(preset.get(OtPolicies.APPROVAL_MODE))
                    .as("%s approval mode", archetype).isEqualTo(mode);
            // And every value in every preset is legal for its key.
            preset.forEach((k, v) ->
                    assertThat(OtPolicies.isValidValue(k, v)).as("%s=%s", k, v).isTrue());
        });
    }
}
