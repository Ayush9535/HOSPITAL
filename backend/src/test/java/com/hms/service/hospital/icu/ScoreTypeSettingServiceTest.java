package com.hms.service.hospital.icu;

import com.hms.dto.icu.IcuScoreTypeSettingRequest;
import com.hms.entity.Hospital;
import com.hms.repository.HospitalRepository;
import com.hms.repository.IcuScoreTypeSettingRepository;
import com.hms.security.SecurityContextHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * ICU Phase 8 - which severity scores a hospital uses (D-2).
 *
 * <p>Deliberately the smallest configuration surface in the module: a hospital chooses whether it
 * runs SOFA, not what SOFA is. These tests pin that boundary as much as the toggling — a
 * configurable component would produce a score nobody else could compare against.
 */
@SpringBootTest
@ActiveProfiles("test")
class ScoreTypeSettingServiceTest {

    @Autowired ScoreTypeSettingService service;
    @Autowired IcuScoreTypeSettingRepository repository;
    @Autowired HospitalRepository hospitalRepository;

    @MockBean SecurityContextHelper securityHelper;

    private Long hospitalId;

    private String uniq() { return Long.toString(System.nanoTime()); }

    private Long newHospital() {
        Hospital h = new Hospital();
        h.setName("H-" + uniq());
        h.setCustomId("HID-" + uniq());
        h.setSubscriptionStatus("ACTIVE");
        h.setIsActive(true);
        h.setModules(List.of("OPD", "IPD", "ICU"));
        h.setIsSingleDoctor(false);
        return hospitalRepository.save(h).getId();
    }

    @BeforeEach
    void setUp() {
        hospitalId = newHospital();
        when(securityHelper.getCurrentHospitalId()).thenReturn(hospitalId);
        when(securityHelper.getCurrentUserId()).thenReturn(2121L);
        when(securityHelper.getCurrentUserEmail()).thenReturn("admin@icu.test");
    }

    private Map<String, Object> typeNamed(String key) {
        return service.list().stream()
                .filter(t -> key.equals(t.get("key"))).findFirst().orElse(null);
    }

    private IcuScoreTypeSettingRequest req(boolean enabled) {
        IcuScoreTypeSettingRequest r = new IcuScoreTypeSettingRequest();
        r.setEnabled(enabled);
        return r;
    }

    // ── the registry ─────────────────────────────────────────────────────────

    @Test
    void bothScoreTypesResolveWithTheirComponentsAndRanges() {
        List<Map<String, Object>> all = service.list();

        assertThat(all).extracting(t -> t.get("key"))
                .containsExactly(SeverityScoreRegistry.SOFA, SeverityScoreRegistry.APACHE_II);

        Map<String, Object> sofa = typeNamed(SeverityScoreRegistry.SOFA);
        assertThat(sofa.get("totalOnly")).isEqualTo(false);
        assertThat(sofa.get("totalMax")).isEqualTo(24);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> components = (List<Map<String, Object>>) sofa.get("components");
        assertThat(components).extracting(c -> c.get("key")).containsExactly(
                "respiratory", "coagulation", "liver", "cardiovascular", "cns", "renal");
        assertThat(components).allMatch(c -> Integer.valueOf(0).equals(c.get("min"))
                && Integer.valueOf(4).equals(c.get("max")));

        Map<String, Object> apache = typeNamed(SeverityScoreRegistry.APACHE_II);
        assertThat(apache.get("totalOnly")).as("D-4").isEqualTo(true);
        assertThat((List<?>) apache.get("components")).isEmpty();
        assertThat(apache.get("totalMax")).isEqualTo(71);
    }

    @Test
    void onlySofaAndApacheExist() {
        // D-5: qSOFA, SAPS II and NEWS are not in §12.3 and are not offered.
        assertThat(SeverityScoreRegistry.TYPES).hasSize(2);
        assertThat(SeverityScoreRegistry.isValidType("QSOFA")).isFalse();
        assertThat(SeverityScoreRegistry.isValidType("SAPS_II")).isFalse();
        assertThat(SeverityScoreRegistry.isValidType("NEWS")).isFalse();
        assertThat(SeverityScoreRegistry.isValidType("GCS")).as("D-1: GCS lives in vitals").isFalse();
    }

    // ── lazy default and toggling ────────────────────────────────────────────

    @Test
    void aScoreTypeWithNoRowIsEnabled() {
        assertThat(repository.findByHospitalId(hospitalId)).isEmpty();
        assertThat(service.list()).allMatch(t -> Boolean.TRUE.equals(t.get("enabled")));
        assertThat(service.enabledTypeKeys(hospitalId)).hasSize(2);
    }

    @Test
    void disablingHidesAScoreFromChartingButNotFromTheCatalogue() {
        service.toggle(SeverityScoreRegistry.APACHE_II, req(false));

        assertThat(service.enabledTypeKeys(hospitalId))
                .containsExactly(SeverityScoreRegistry.SOFA);
        assertThat(service.enabledTypes()).hasSize(1);
        assertThat(typeNamed(SeverityScoreRegistry.APACHE_II))
                .as("still listed for the admin").isNotNull();
        assertThat(typeNamed(SeverityScoreRegistry.APACHE_II).get("enabled")).isEqualTo(false);
        assertThat(service.isEnabled(hospitalId, SeverityScoreRegistry.APACHE_II)).isFalse();
    }

    @Test
    void reEnablingRestoresCharting() {
        service.toggle(SeverityScoreRegistry.SOFA, req(false));
        service.toggle(SeverityScoreRegistry.SOFA, req(true));

        assertThat(service.isEnabled(hospitalId, SeverityScoreRegistry.SOFA)).isTrue();
    }

    @Test
    void togglingWritesExactlyOneOverrideRowPerType() {
        service.toggle(SeverityScoreRegistry.SOFA, req(false));
        service.toggle(SeverityScoreRegistry.SOFA, req(true));
        service.toggle(SeverityScoreRegistry.SOFA, req(false));

        assertThat(repository.findByHospitalId(hospitalId)).hasSize(1);
    }

    @Test
    void componentsAreNotConfigurable() {
        // D-2, pinned: the toggle request carries nothing but `enabled`, so there is no path
        // through this service that could rename a component or change a range.
        assertThat(IcuScoreTypeSettingRequest.class.getDeclaredFields())
                .extracting(java.lang.reflect.Field::getName)
                .containsExactly("enabled");
    }

    // ── tenancy ──────────────────────────────────────────────────────────────

    @Test
    void anUnknownScoreTypeIsNotFound() {
        assertThatThrownBy(() -> service.toggle("QSOFA", req(false)))
                .isInstanceOf(com.hms.exception.ResourceNotFoundException.class);
    }

    @Test
    void configurationDoesNotLeakBetweenTenants() {
        service.toggle(SeverityScoreRegistry.APACHE_II, req(false));

        Long otherId = newHospital();
        when(securityHelper.getCurrentHospitalId()).thenReturn(otherId);

        assertThat(typeNamed(SeverityScoreRegistry.APACHE_II).get("enabled"))
                .as("the other tenant keeps the default").isEqualTo(true);
        assertThat(service.enabledTypeKeys(otherId)).hasSize(2);

        when(securityHelper.getCurrentHospitalId()).thenReturn(hospitalId);
        assertThat(service.isEnabled(hospitalId, SeverityScoreRegistry.APACHE_II)).isFalse();
    }
}
