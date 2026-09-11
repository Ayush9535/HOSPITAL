package com.hms.service.hospital.icu;

import com.hms.dto.icu.IcuAlertThresholdRequest;
import com.hms.entity.Hospital;
import com.hms.repository.IcuAlertThresholdRepository;
import com.hms.security.SecurityContextHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * ICU Phase 9 - alert threshold configuration.
 *
 * <p>The property that separates this from every other config table in the module: <b>no row means
 * no alert</b>. There is no lazy default and nothing ships configured, because a default threshold
 * would be the system deciding what a normal value is.
 */
@SpringBootTest
@ActiveProfiles("test")
class IcuAlertThresholdServiceTest {

    @Autowired IcuAlertThresholdService service;
    @Autowired IcuAlertThresholdRepository repository;
    @Autowired com.hms.repository.HospitalRepository hospitalRepository;

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
        when(securityHelper.getCurrentUserId()).thenReturn(1212L);
        when(securityHelper.getCurrentUserEmail()).thenReturn("admin@icu.test");
    }

    private IcuAlertThresholdRequest req(String min, String max, Boolean enabled) {
        IcuAlertThresholdRequest r = new IcuAlertThresholdRequest();
        r.setMinValue(min == null ? null : new BigDecimal(min));
        r.setMaxValue(max == null ? null : new BigDecimal(max));
        r.setEnabled(enabled);
        return r;
    }

    private Map<String, Object> metric(String key) {
        return service.list().stream()
                .filter(m -> key.equals(m.get("key"))).findFirst().orElse(null);
    }

    // ── nothing is configured until someone configures it ────────────────────

    @Test
    void noThresholdShipsConfiguredOrEnabled() {
        assertThat(repository.findByHospitalIdAndIsActiveTrue(hospitalId)).isEmpty();
        assertThat(service.list()).isNotEmpty();
        assertThat(service.list()).allSatisfy(m -> {
            assertThat(m.get("minValue")).isNull();
            assertThat(m.get("maxValue")).isNull();
            assertThat(m.get("enabled")).as("no row means no alert").isEqualTo(false);
            assertThat(m.get("configured")).isEqualTo(false);
        });
        assertThat(service.activeFor(hospitalId, AlertMetricRegistry.SOURCE_VITALS)).isEmpty();
    }

    @Test
    void onlyIcu4VitalsMetricsAreOffered() {
        // D-1: no I/O, infusion, ventilator, severity-score or lab metric in ICU-9.
        assertThat(service.list()).extracting(m -> m.get("key")).containsExactly(
                "pulse", "spo2", "map_mmhg", "cvp_cmh2o", "urine_output_ml", "gcs_total");
        assertThat(AlertMetricRegistry.isValid("VENTILATOR", "fio2")).isFalse();
        assertThat(AlertMetricRegistry.isValid("SEVERITY_SCORE", "SOFA")).isFalse();
        assertThat(AlertMetricRegistry.isValid("LAB", "creatinine")).isFalse();
        assertThat(AlertMetricRegistry.isValid(AlertMetricRegistry.SOURCE_VITALS, "map_mmhg"))
                .isTrue();
    }

    // ── setting, editing, disabling ──────────────────────────────────────────

    @Test
    void settingABoundCreatesTheRowAndItReadsBack() {
        service.upsert("map_mmhg", req("65", null, true));

        Map<String, Object> map = metric("map_mmhg");
        assertThat(map.get("configured")).isEqualTo(true);
        assertThat(map.get("enabled")).isEqualTo(true);
        assertThat((BigDecimal) map.get("minValue")).isEqualByComparingTo("65");
        assertThat(map.get("maxValue")).isNull();
        assertThat(service.activeFor(hospitalId, AlertMetricRegistry.SOURCE_VITALS)).hasSize(1);
    }

    @Test
    void editingUpdatesTheSameRowRatherThanAddingAnother() {
        service.upsert("map_mmhg", req("65", null, true));
        service.upsert("map_mmhg", req("60", "110", true));

        assertThat(repository.findByHospitalIdAndIsActiveTrue(hospitalId)).hasSize(1);
        assertThat((BigDecimal) metric("map_mmhg").get("minValue")).isEqualByComparingTo("60");
        assertThat((BigDecimal) metric("map_mmhg").get("maxValue")).isEqualByComparingTo("110");
    }

    @Test
    void disablingKeepsTheNumbersButStopsItFiring() {
        service.upsert("map_mmhg", req("65", null, true));
        service.upsert("map_mmhg", req("65", null, false));

        assertThat(service.activeFor(hospitalId, AlertMetricRegistry.SOURCE_VITALS)).isEmpty();
        assertThat(metric("map_mmhg").get("enabled")).isEqualTo(false);
        assertThat((BigDecimal) metric("map_mmhg").get("minValue"))
                .as("turning it back on must not mean retyping").isEqualByComparingTo("65");
    }

    @Test
    void anEnabledThresholdWithNoBoundIsRejected() {
        // It could never fire, so it is a mistake rather than a configuration.
        assertThatThrownBy(() -> service.upsert("map_mmhg", req(null, null, true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minimum");
    }

    @Test
    void anInvertedRangeIsRejected() {
        assertThatThrownBy(() -> service.upsert("map_mmhg", req("120", "60", true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than maximum");
    }

    @Test
    void anUnknownMetricIsNotFound() {
        assertThatThrownBy(() -> service.upsert("creatinine", req("1", "2", true)))
                .isInstanceOf(com.hms.exception.ResourceNotFoundException.class);
    }

    // ── tenancy ──────────────────────────────────────────────────────────────

    @Test
    void oneHospitalsThresholdsAreInvisibleAndInertForAnother() {
        service.upsert("map_mmhg", req("65", null, true));

        Long otherId = newHospital();
        when(securityHelper.getCurrentHospitalId()).thenReturn(otherId);

        assertThat(metric("map_mmhg").get("configured")).isEqualTo(false);
        assertThat(metric("map_mmhg").get("minValue")).isNull();
        assertThat(service.activeFor(otherId, AlertMetricRegistry.SOURCE_VITALS))
                .as("nothing of the other tenant's can fire here").isEmpty();

        when(securityHelper.getCurrentHospitalId()).thenReturn(hospitalId);
        assertThat(service.activeFor(hospitalId, AlertMetricRegistry.SOURCE_VITALS)).hasSize(1);
    }

    @Test
    void thresholdsAreScopedToTheHospitalNotTheWardOrPatient() {
        // D-3, pinned: the row carries no ward or patient column, so there is no path to one.
        service.upsert("pulse", req(null, "130", true));
        var row = repository.findByHospitalIdAndSourceAndMetricKey(
                hospitalId, AlertMetricRegistry.SOURCE_VITALS, "pulse").orElseThrow();

        assertThat(row.getHospitalId()).isEqualTo(hospitalId);
        assertThat(com.hms.entity.IcuAlertThreshold.class.getDeclaredFields())
                .extracting(java.lang.reflect.Field::getName)
                .doesNotContain("wardId", "ipdAdmissionId", "patientId");
    }
}
