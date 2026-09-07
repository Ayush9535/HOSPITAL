package com.hms.service.hospital.icu;

import java.util.List;

/**
 * CareUnitRegistry - the canonical list of ward unit types (ICU Phase 2).
 *
 * <p>A ward is classified, not renamed: {@code wards.unit_type} holds one of these keys while
 * {@code wards.ward_name} stays free text. A hospital may therefore run "ICU-1", "ICU-2" and
 * "Cardiac ICU" as three wards all typed {@link #ICU}, or one {@code MICU} and one {@code SICU}.
 * Any number of units of any number of types is supported because a unit <em>is</em> a ward.
 *
 * <p>Nothing anywhere tests for the literal string "ICU". {@link #isCriticalCare(String)} is the
 * single predicate every gate uses, so adding a type here is the only edit a new critical-care
 * unit needs.
 *
 * <p>This is deliberately the OPPOSITE of how OT first identified a theatre — "any ward whose
 * name contains the substring OT", which matched "FOOT WARD" (see {@code OtRoom}'s javadoc).
 * Classification is explicit and administrator-set, never inferred from a name.
 *
 * <p>Kept as a Java registry rather than a table, matching {@code FormRegistry},
 * {@code VitalRegistry}, {@code OtPermissions} and {@code EntitlementRegistry}: these keys are
 * compile-time constants that endpoints and gates reference by name.
 */
public final class CareUnitRegistry {

    private CareUnitRegistry() {
    }

    /** The default for every ward. Not critical care. */
    public static final String GENERAL = "GENERAL";

    public static final String ICU = "ICU";
    public static final String MICU = "MICU";
    public static final String SICU = "SICU";
    public static final String NICU = "NICU";
    public static final String PICU = "PICU";
    public static final String CCU = "CCU";
    public static final String HDU = "HDU";

    /**
     * One ward unit type.
     *
     * @param key           stored in {@code wards.unit_type}
     * @param label         display name
     * @param criticalCare  whether wards of this type belong to the ICU module's board
     */
    public record UnitType(String key, String label, boolean criticalCare) {
    }

    public static final List<UnitType> UNIT_TYPES = List.of(
            new UnitType(GENERAL, "General Ward", false),
            new UnitType(ICU, "Intensive Care Unit", true),
            new UnitType(MICU, "Medical ICU", true),
            new UnitType(SICU, "Surgical ICU", true),
            new UnitType(NICU, "Neonatal ICU", true),
            new UnitType(PICU, "Paediatric ICU", true),
            new UnitType(CCU, "Coronary Care Unit", true),
            new UnitType(HDU, "High Dependency Unit", true));

    public static boolean isValidKey(String key) {
        return key != null && UNIT_TYPES.stream().anyMatch(t -> t.key().equals(key));
    }

    /**
     * Whether a ward of this type is a critical-care unit.
     *
     * <p>A null or unrecognised value reads as NOT critical care. A ward whose column was never
     * migrated, or which somehow carries a key this build does not know, must not silently appear
     * on the ICU board — the safe direction is to exclude it.
     */
    public static boolean isCriticalCare(String key) {
        if (key == null) return false;
        return UNIT_TYPES.stream()
                .filter(t -> t.key().equals(key))
                .findFirst()
                .map(UnitType::criticalCare)
                .orElse(false);
    }

    /** Display label for a key, falling back to the key itself for an unknown value. */
    public static String labelOf(String key) {
        if (key == null) return GENERAL;
        return UNIT_TYPES.stream()
                .filter(t -> t.key().equals(key))
                .findFirst()
                .map(UnitType::label)
                .orElse(key);
    }

    /** Keys that put a ward on the ICU board. */
    public static List<String> criticalCareKeys() {
        return UNIT_TYPES.stream().filter(UnitType::criticalCare).map(UnitType::key).toList();
    }

    /** Normalises a caller-supplied value: blank/null means the default, anything else must be valid. */
    public static String normalize(String key) {
        if (key == null || key.isBlank()) return GENERAL;
        String trimmed = key.trim().toUpperCase(java.util.Locale.ROOT);
        if (!isValidKey(trimmed)) {
            throw new IllegalArgumentException("Unknown ward unit type: " + key);
        }
        return trimmed;
    }
}
