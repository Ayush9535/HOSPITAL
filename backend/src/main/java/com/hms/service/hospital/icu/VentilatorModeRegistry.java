package com.hms.service.hospital.icu;

import java.util.List;

/**
 * VentilatorModeRegistry - the allowed values of the ventilator mode parameter (ICU Phase 7).
 *
 * <p>Parameter <i>names</i> are configurable; mode <i>values</i> are not. A fixed list keeps a
 * ventilator history comparable — free text would produce SIMV, S.I.M.V. and simv in one chart
 * and make "what mode was the patient on?" unanswerable across a shift change.
 *
 * <p>No relationship between modes is encoded, deliberately. Which mode suits which patient, and
 * which is a step up or down from another, is clinical judgement that ICU records rather than
 * makes.
 */
public final class VentilatorModeRegistry {

    private VentilatorModeRegistry() {
    }

    public record Mode(String key, String label) {
    }

    public static final List<Mode> MODES = List.of(
            new Mode("VC", "Volume Control (VC)"),
            new Mode("PC", "Pressure Control (PC)"),
            new Mode("SIMV", "SIMV"),
            new Mode("PSV", "Pressure Support (PSV)"),
            new Mode("CPAP", "CPAP"),
            new Mode("BIPAP", "BiPAP"));

    public static boolean isValid(String key) {
        return key != null && MODES.stream().anyMatch(m -> m.key().equals(key));
    }

    public static String labelOf(String key) {
        if (key == null) return "";
        return MODES.stream().filter(m -> m.key().equals(key)).findFirst()
                .map(Mode::label).orElse(key);
    }
}
