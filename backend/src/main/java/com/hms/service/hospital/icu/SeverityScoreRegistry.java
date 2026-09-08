package com.hms.service.hospital.icu;

import java.util.List;
import java.util.Optional;

/**
 * SeverityScoreRegistry - the severity scores ICU-8 records, and their components (D-2).
 *
 * <p>A fixed Java registry, deliberately, and the one place ICU-8 departs from ICU-7's shape.
 * Ventilator parameters are configurable because ventilator practice genuinely varies between
 * units. Severity scores are the opposite case: SOFA's six organ systems are internationally
 * standardised, and a hospital that renamed or dropped one would no longer have a SOFA score.
 * Standardisation is the entire point of a score, so administrators choose which score TYPES they
 * use and never touch the components.
 *
 * <p><b>This registry describes what may be entered. It does not compute anything.</b> No band
 * table maps a creatinine to a renal subscore, no vasopressor is classified, no PaO2/FiO2 is
 * derived. The clinician judges each component and enters it; the service adds up what they
 * entered, exactly as {@code VitalsService.gcsTotalOf} sums E+V+M and {@code RecoveryObservation}
 * sums the five Aldrete components.
 *
 * <p><b>GCS is not here</b> (D-1). It lives on the vitals chart, where ICU-4 put it, because it is
 * a bedside observation taken with the pulse and the BP rather than a daily scoring exercise.
 */
public final class SeverityScoreRegistry {

    private SeverityScoreRegistry() {
    }

    public static final String SOFA = "SOFA";
    public static final String APACHE_II = "APACHE_II";

    /** One component of a score. {@code min}/{@code max} bound the entry field, nothing more. */
    public record Component(String key, String label, int min, int max) {
    }

    /**
     * A score type.
     *
     * @param components empty when the score is recorded as a total only (D-4)
     * @param totalMin   lower bound of the total
     * @param totalMax   upper bound of the total
     */
    public record ScoreType(String key, String label, List<Component> components,
                            int totalMin, int totalMax) {

        /** True when a clinician enters the total directly rather than its parts. */
        public boolean isTotalOnly() {
            return components.isEmpty();
        }

        public Optional<Component> component(String componentKey) {
            return components.stream().filter(c -> c.key().equals(componentKey)).findFirst();
        }
    }

    private static final List<Component> SOFA_COMPONENTS = List.of(
            new Component("respiratory", "Respiratory", 0, 4),
            new Component("coagulation", "Coagulation", 0, 4),
            new Component("liver", "Liver", 0, 4),
            new Component("cardiovascular", "Cardiovascular", 0, 4),
            new Component("cns", "CNS", 0, 4),
            new Component("renal", "Renal", 0, 4));

    public static final List<ScoreType> TYPES = List.of(
            new ScoreType(SOFA, "SOFA", SOFA_COMPONENTS, 0, 24),
            // D-4: APACHE II's twelve variables are largely laboratory values this system does not
            // hold. Offering twelve inputs would invite copying numbers off another screen into a
            // form that cannot check them, so the clinician records the total they calculated.
            new ScoreType(APACHE_II, "APACHE II", List.of(), 0, 71));

    public static Optional<ScoreType> find(String key) {
        return TYPES.stream().filter(t -> t.key().equals(key)).findFirst();
    }

    public static boolean isValidType(String key) {
        return key != null && find(key).isPresent();
    }
}
