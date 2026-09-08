package com.hms.service.hospital.icu;

import com.hms.entity.IcuVentilatorParameter;

import java.util.List;
import java.util.Optional;

/**
 * VentilatorParameterRegistry - the built-in ventilator parameter catalogue (ICU Phase 7).
 *
 * <p>A Java registry, matching {@code VitalRegistry}, {@code FormRegistry} and
 * {@code CareUnitRegistry}: these keys are compile-time constants that clinical rows reference by
 * name, so they cannot live in a table a hospital can edit.
 *
 * <p>A hospital may rename, re-unit, re-categorise or disable any of these through
 * {@code icu_ventilator_parameter}, and may add its own. What it cannot do is change a key —
 * every value ever charted is stored against one.
 *
 * <p>Split into what is dialled INTO the ventilator ({@code SETTING}) and what is read OFF it
 * ({@code OBSERVATION}). Peak and plateau pressures are measurements, not settings, and say so.
 */
public final class VentilatorParameterRegistry {

    private VentilatorParameterRegistry() {
    }

    public static final String MODE = "mode";
    public static final String FIO2 = "fio2";
    public static final String PEEP = "peep";
    public static final String TIDAL_VOLUME = "tidal_volume";
    public static final String SET_RESPIRATORY_RATE = "set_respiratory_rate";
    public static final String PRESSURE_SUPPORT = "pressure_support";
    public static final String IE_RATIO = "ie_ratio";
    public static final String PEAK_PRESSURE = "peak_pressure";
    public static final String PLATEAU_PRESSURE = "plateau_pressure";

    public record Parameter(String key, String displayName, String unit,
                            String category, String valueType) {
    }

    public static final List<Parameter> BUILT_INS = List.of(
            new Parameter(MODE, "Mode", null,
                    IcuVentilatorParameter.SETTING, IcuVentilatorParameter.MODE),
            new Parameter(FIO2, "FiO₂", "%",
                    IcuVentilatorParameter.SETTING, IcuVentilatorParameter.NUMBER),
            new Parameter(PEEP, "PEEP", "cmH₂O",
                    IcuVentilatorParameter.SETTING, IcuVentilatorParameter.NUMBER),
            new Parameter(TIDAL_VOLUME, "Tidal Volume", "mL",
                    IcuVentilatorParameter.SETTING, IcuVentilatorParameter.NUMBER),
            new Parameter(SET_RESPIRATORY_RATE, "Set Respiratory Rate", "/min",
                    IcuVentilatorParameter.SETTING, IcuVentilatorParameter.NUMBER),
            new Parameter(PRESSURE_SUPPORT, "Pressure Support", "cmH₂O",
                    IcuVentilatorParameter.SETTING, IcuVentilatorParameter.NUMBER),
            new Parameter(IE_RATIO, "I:E Ratio", null,
                    IcuVentilatorParameter.SETTING, IcuVentilatorParameter.TEXT),
            new Parameter(PEAK_PRESSURE, "Peak Pressure", "cmH₂O",
                    IcuVentilatorParameter.OBSERVATION, IcuVentilatorParameter.NUMBER),
            new Parameter(PLATEAU_PRESSURE, "Plateau Pressure", "cmH₂O",
                    IcuVentilatorParameter.OBSERVATION, IcuVentilatorParameter.NUMBER));

    public static boolean isBuiltIn(String key) {
        return BUILT_INS.stream().anyMatch(p -> p.key().equals(key));
    }

    public static Optional<Parameter> find(String key) {
        return BUILT_INS.stream().filter(p -> p.key().equals(key)).findFirst();
    }

    /**
     * Derives a key from a custom parameter's name, ONCE, at creation.
     *
     * <p>"Minute Ventilation" becomes {@code minute_ventilation}. Never called again afterwards —
     * re-deriving on rename is exactly how a rename would orphan history.
     */
    public static String toKey(String name) {
        return name.trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("(^_)|(_$)", "");
    }

    public static boolean isValidCategory(String category) {
        return IcuVentilatorParameter.SETTING.equals(category)
                || IcuVentilatorParameter.OBSERVATION.equals(category);
    }

    /** MODE is reserved for the built-in mode parameter, so its values stay controlled. */
    public static boolean isValidCustomValueType(String valueType) {
        return IcuVentilatorParameter.NUMBER.equals(valueType)
                || IcuVentilatorParameter.TEXT.equals(valueType);
    }
}
