package com.hms.service.hospital.icu;

import java.util.List;

/**
 * InfusionRateUnitRegistry - the units an infusion rate may be recorded in (ICU Phase 6).
 *
 * <p>A fixed list rather than free text, so a rate history stays readable and comparable: "5" is
 * meaningless unless the unit beside it is one of a known few, and free text would produce
 * ml/hr, mL/h and mls/hour in the same chart.
 *
 * <p><b>No conversion exists between these, deliberately.</b> Turning MCG_KG_MIN into ML_HR needs
 * a drug concentration and a body weight the system does not hold; deriving one would be clinical
 * dose calculation, which ICU records rather than performs. The unit is stored exactly as entered
 * and displayed exactly as stored.
 *
 * <p>A Java registry, matching {@code CareUnitRegistry}, {@code FormRegistry} and
 * {@code VitalRegistry}: these keys are compile-time constants referenced by name.
 */
public final class InfusionRateUnitRegistry {

    private InfusionRateUnitRegistry() {
    }

    public static final String ML_HR = "ML_HR";
    public static final String MCG_MIN = "MCG_MIN";
    public static final String MCG_KG_MIN = "MCG_KG_MIN";
    public static final String UNITS_HR = "UNITS_HR";

    public record RateUnit(String key, String label) {
    }

    public static final List<RateUnit> UNITS = List.of(
            new RateUnit(ML_HR, "mL/hr"),
            new RateUnit(MCG_MIN, "mcg/min"),
            new RateUnit(MCG_KG_MIN, "mcg/kg/min"),
            new RateUnit(UNITS_HR, "units/hr"));

    public static boolean isValid(String key) {
        return key != null && UNITS.stream().anyMatch(u -> u.key().equals(key));
    }

    /** Display label for a key, falling back to the key for anything unrecognised. */
    public static String labelOf(String key) {
        if (key == null) return "";
        return UNITS.stream().filter(u -> u.key().equals(key)).findFirst()
                .map(RateUnit::label).orElse(key);
    }
}
