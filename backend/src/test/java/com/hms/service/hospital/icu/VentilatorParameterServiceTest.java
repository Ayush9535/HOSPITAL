package com.hms.service.hospital.icu;

import com.hms.dto.icu.IcuVentilatorParameterRequest;
import com.hms.entity.Hospital;
import com.hms.entity.IcuVentilatorParameter;
import com.hms.repository.HospitalRepository;
import com.hms.repository.IcuVentilatorParameterRepository;
import com.hms.security.SecurityContextHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * ICU Phase 7 - the ventilator parameter catalogue (D-5).
 *
 * <p>The property the whole design turns on: <b>{@code param_key} is identity and
 * {@code display_name} is only a label</b>. Rename freely; every value ever charted still resolves.
 * The vitals implementation cannot do this — it derives a custom vital's key from its name and
 * offers no rename at all — which is why this is tested first and hardest.
 */
@SpringBootTest
@ActiveProfiles("test")
class VentilatorParameterServiceTest {

    @Autowired VentilatorParameterService parameterService;
    @Autowired IcuVentilatorParameterRepository parameterRepository;
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
        when(securityHelper.getCurrentUserId()).thenReturn(4242L);
        when(securityHelper.getCurrentUserEmail()).thenReturn("admin@icu.test");
    }

    private Map<String, Object> paramNamed(String key) {
        return parameterService.list().stream()
                .filter(p -> key.equals(p.get("key"))).findFirst().orElse(null);
    }

    private IcuVentilatorParameterRequest req() {
        return new IcuVentilatorParameterRequest();
    }

    // ── built-ins and lazy defaults ──────────────────────────────────────────

    @Test
    void allNineBuiltInsResolveWithTheirCategoryAndValueType() {
        List<Map<String, Object>> all = parameterService.list();

        assertThat(all).extracting(p -> p.get("key")).containsExactly(
                "mode", "fio2", "peep", "tidal_volume", "set_respiratory_rate",
                "pressure_support", "ie_ratio", "peak_pressure", "plateau_pressure");
        assertThat(paramNamed("mode").get("valueType")).isEqualTo(IcuVentilatorParameter.MODE);
        assertThat(paramNamed("fio2").get("category")).isEqualTo(IcuVentilatorParameter.SETTING);
        // Peak and plateau are read off the machine, not dialled into it.
        assertThat(paramNamed("peak_pressure").get("category"))
                .isEqualTo(IcuVentilatorParameter.OBSERVATION);
        assertThat(paramNamed("plateau_pressure").get("category"))
                .isEqualTo(IcuVentilatorParameter.OBSERVATION);
    }

    @Test
    void aBuiltInWithNoRowIsEnabled() {
        assertThat(parameterRepository.findByHospitalId(hospitalId)).isEmpty();
        assertThat(parameterService.list()).allMatch(p -> Boolean.TRUE.equals(p.get("enabled")));
        assertThat(parameterService.enabledKeys(hospitalId)).hasSize(9);
    }

    // ── enable / disable ─────────────────────────────────────────────────────

    @Test
    void disablingHidesAParameterFromChartingButNotFromTheCatalogue() {
        IcuVentilatorParameterRequest r = req();
        r.setEnabled(false);
        parameterService.update(VentilatorParameterRegistry.FIO2, r);

        assertThat(parameterService.enabledKeys(hospitalId)).doesNotContain("fio2");
        assertThat(parameterService.enabledParameters()).hasSize(8);
        assertThat(paramNamed("fio2")).as("still listed for the admin").isNotNull();
        assertThat(paramNamed("fio2").get("enabled")).isEqualTo(false);
    }

    @Test
    void modeMayBeDisabledLikeAnyOtherParameter() {
        // D-6: allowed, with no special case.
        IcuVentilatorParameterRequest r = req();
        r.setEnabled(false);
        parameterService.update(VentilatorParameterRegistry.MODE, r);

        assertThat(parameterService.enabledKeys(hospitalId)).doesNotContain("mode");
    }

    @Test
    void reEnablingRestoresCharting() {
        IcuVentilatorParameterRequest off = req();
        off.setEnabled(false);
        parameterService.update(VentilatorParameterRegistry.PEEP, off);
        IcuVentilatorParameterRequest on = req();
        on.setEnabled(true);
        parameterService.update(VentilatorParameterRegistry.PEEP, on);

        assertThat(parameterService.enabledKeys(hospitalId)).contains("peep");
    }

    // ── identity: the core of D-5 ────────────────────────────────────────────

    @Test
    void renamingABuiltInChangesTheLabelAndNeverTheKey() {
        IcuVentilatorParameterRequest r = req();
        r.setDisplayName("Inspired O₂");
        IcuVentilatorParameter saved = parameterService.update(VentilatorParameterRegistry.FIO2, r);

        assertThat(saved.getParamKey()).as("identity is untouched").isEqualTo("fio2");
        assertThat(paramNamed("fio2").get("displayName")).isEqualTo("Inspired O₂");
        assertThat(parameterService.enabledKeys(hospitalId)).contains("fio2");
    }

    @Test
    void renamingACustomParameterDoesNotReDeriveItsKey() {
        IcuVentilatorParameterRequest add = req();
        add.setDisplayName("Minute Ventilation");
        add.setUnit("L/min");
        add.setCategory(IcuVentilatorParameter.OBSERVATION);
        IcuVentilatorParameter created = parameterService.addCustom(add);
        assertThat(created.getParamKey()).isEqualTo("minute_ventilation");

        IcuVentilatorParameterRequest rename = req();
        rename.setDisplayName("Minute Volume");
        IcuVentilatorParameter renamed =
                parameterService.update("minute_ventilation", rename);

        assertThat(renamed.getParamKey())
                .as("re-deriving here is exactly how a rename would orphan history")
                .isEqualTo("minute_ventilation");
        assertThat(renamed.getDisplayName()).isEqualTo("Minute Volume");
    }

    @Test
    void unitAndCategoryAreEditable() {
        IcuVentilatorParameterRequest r = req();
        r.setUnit("kPa");
        r.setCategory(IcuVentilatorParameter.OBSERVATION);
        parameterService.update(VentilatorParameterRegistry.PEEP, r);

        assertThat(paramNamed("peep").get("unit")).isEqualTo("kPa");
        assertThat(paramNamed("peep").get("category"))
                .isEqualTo(IcuVentilatorParameter.OBSERVATION);
        assertThat(paramNamed("peep").get("key")).isEqualTo("peep");
    }

    @Test
    void anUnknownCategoryIsRejected() {
        IcuVentilatorParameterRequest r = req();
        r.setCategory("SOMETHING_ELSE");
        assertThatThrownBy(() -> parameterService.update(VentilatorParameterRegistry.PEEP, r))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── custom parameters ────────────────────────────────────────────────────

    @Test
    void aCustomParameterCanBeAddedInEitherCategory() {
        IcuVentilatorParameterRequest r = req();
        r.setDisplayName("Minute Ventilation");
        r.setUnit("L/min");
        r.setCategory(IcuVentilatorParameter.OBSERVATION);
        parameterService.addCustom(r);

        Map<String, Object> added = paramNamed("minute_ventilation");
        assertThat(added).isNotNull();
        assertThat(added.get("isCustom")).isEqualTo(true);
        assertThat(added.get("enabled")).isEqualTo(true);
        assertThat(added.get("category")).isEqualTo(IcuVentilatorParameter.OBSERVATION);
        assertThat(parameterService.enabledKeys(hospitalId)).hasSize(10);
    }

    @Test
    void aCustomParameterCannotClaimTheModeValueType() {
        // MODE is reserved, so mode values stay controlled even though names are configurable.
        IcuVentilatorParameterRequest r = req();
        r.setDisplayName("My Mode");
        r.setValueType(IcuVentilatorParameter.MODE);

        assertThatThrownBy(() -> parameterService.addCustom(r))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NUMBER or TEXT");
    }

    @Test
    void aDuplicateKeyIsRejected() {
        IcuVentilatorParameterRequest r = req();
        r.setDisplayName("Minute Ventilation");
        parameterService.addCustom(r);

        IcuVentilatorParameterRequest again = req();
        again.setDisplayName("minute ventilation");
        assertThatThrownBy(() -> parameterService.addCustom(again))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aCustomParameterCannotShadowABuiltIn() {
        IcuVentilatorParameterRequest r = req();
        r.setDisplayName("PEEP");
        assertThatThrownBy(() -> parameterService.addCustom(r))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("built-in");
    }

    @Test
    void anEmptyNameIsRejected() {
        IcuVentilatorParameterRequest r = req();
        r.setDisplayName("   ");
        assertThatThrownBy(() -> parameterService.addCustom(r))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── label resolution ─────────────────────────────────────────────────────

    @Test
    void labelsResolveFromConfigThenRegistryThenTheRawKey() {
        IcuVentilatorParameterRequest r = req();
        r.setDisplayName("Inspired O₂");
        parameterService.update(VentilatorParameterRegistry.FIO2, r);

        Map<String, Map<String, Object>> labels = parameterService.labelMapFor(
                hospitalId, new java.util.LinkedHashSet<>(List.of("fio2", "peep", "gone_away")));

        assertThat(labels.get("fio2").get("displayName")).isEqualTo("Inspired O₂");
        assertThat(labels.get("peep").get("displayName")).as("registry fallback").isEqualTo("PEEP");
        // A key nothing defines is shown rather than hidden — hiding it would lose the value.
        assertThat(labels.get("gone_away").get("displayName")).isEqualTo("gone_away");
        assertThat(labels.get("gone_away").get("enabled")).isEqualTo(false);
    }

    @Test
    void aDisabledParameterStillResolvesToItsName() {
        IcuVentilatorParameterRequest r = req();
        r.setEnabled(false);
        parameterService.update(VentilatorParameterRegistry.FIO2, r);

        Map<String, Map<String, Object>> labels =
                parameterService.labelMapFor(hospitalId, Set.of("fio2"));

        assertThat(labels.get("fio2").get("displayName")).isEqualTo("FiO₂");
        assertThat(labels.get("fio2").get("enabled"))
                .as("so the chart can mark it no longer charted").isEqualTo(false);
    }

    // ── tenancy ──────────────────────────────────────────────────────────────

    @Test
    void catalogueChangesDoNotLeakBetweenTenants() {
        IcuVentilatorParameterRequest r = req();
        r.setDisplayName("Inspired O₂");
        r.setEnabled(false);
        parameterService.update(VentilatorParameterRegistry.FIO2, r);

        Long otherId = newHospital();
        when(securityHelper.getCurrentHospitalId()).thenReturn(otherId);

        assertThat(paramNamed("fio2").get("displayName"))
                .as("the other tenant sees the registry default").isEqualTo("FiO₂");
        assertThat(paramNamed("fio2").get("enabled")).isEqualTo(true);
        assertThat(parameterService.enabledKeys(otherId)).hasSize(9);
    }

    @Test
    void anotherTenantsCustomParameterIsIndistinguishableFromAMissingOne() {
        IcuVentilatorParameterRequest add = req();
        add.setDisplayName("Minute Ventilation");
        parameterService.addCustom(add);

        Long otherId = newHospital();
        when(securityHelper.getCurrentHospitalId()).thenReturn(otherId);

        IcuVentilatorParameterRequest r = req();
        r.setEnabled(false);
        assertThatThrownBy(() -> parameterService.update("minute_ventilation", r))
                .as("404, never 403 — the vitals code's 401 is deliberately not copied")
                .isInstanceOf(com.hms.exception.ResourceNotFoundException.class);
    }

    @Test
    void anUnknownParameterKeyIsNotFound() {
        IcuVentilatorParameterRequest r = req();
        r.setEnabled(false);
        assertThatThrownBy(() -> parameterService.update("no_such_parameter", r))
                .isInstanceOf(com.hms.exception.ResourceNotFoundException.class);
    }

    // ── modes ────────────────────────────────────────────────────────────────

    @Test
    void theModeCatalogueIsControlledNotFreeText() {
        assertThat(parameterService.modes()).extracting(VentilatorModeRegistry.Mode::key)
                .contains("VC", "PC", "SIMV", "PSV", "CPAP", "BIPAP");
        assertThat(VentilatorModeRegistry.isValid("VC")).isTrue();
        assertThat(VentilatorModeRegistry.isValid("whatever the nurse typed")).isFalse();
    }
}
