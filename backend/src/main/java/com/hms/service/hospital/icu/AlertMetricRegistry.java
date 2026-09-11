package com.hms.service.hospital.icu;

import com.hms.entity.VitalsRecord;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * AlertMetricRegistry - the vitals a hospital may set an alert threshold on (ICU Phase 9, D-1).
 *
 * <p>A fixed Java registry, matching SeverityScoreRegistry and VentilatorModeRegistry: a threshold
 * row names a metric by key, and that key has to mean the same thing next year as it does today.
 *
 * <p><b>Scope is ICU-4 vitals only (D-1).</b> No I/O, infusion, ventilator, severity-score or lab
 * metric appears here — those sources are not evaluated in ICU-9 at all.
 *
 * <p><b>No default threshold ships with any metric.</b> The registry says what a hospital MAY
 * alert on; it never says what a normal value is. A shipped default would be the system asserting
 * a clinical norm, which ICU records rather than decides.
 */
public final class AlertMetricRegistry {

    private AlertMetricRegistry() {
    }

    /** The only source ICU-9 evaluates (D-1). Stored on the threshold row so later phases can add. */
    public static final String SOURCE_VITALS = "VITALS";

    /**
     * One alertable metric.
     *
     * @param reader pulls the value off a saved record, or null when it was not measured
     */
    public record Metric(String key, String label, String unit,
                         Function<VitalsRecord, Integer> reader) {
    }

    public static final List<Metric> METRICS = List.of(
            new Metric("pulse", "Pulse", "bpm", VitalsRecord::getPulse),
            new Metric("spo2", "SpO₂", "%", VitalsRecord::getSpo2),
            new Metric("map_mmhg", "MAP", "mmHg", VitalsRecord::getMapMmhg),
            new Metric("cvp_cmh2o", "CVP", "cmH₂O", VitalsRecord::getCvpCmh2o),
            new Metric("urine_output_ml", "Urine Output", "mL", VitalsRecord::getUrineOutputMl),
            new Metric("gcs_total", "GCS Total", null, VitalsRecord::getGcsTotal));

    public static Optional<Metric> find(String source, String key) {
        if (!SOURCE_VITALS.equals(source)) return Optional.empty();
        return METRICS.stream().filter(m -> m.key().equals(key)).findFirst();
    }

    public static boolean isValid(String source, String key) {
        return find(source, key).isPresent();
    }
}
