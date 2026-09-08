package com.hms.service.hospital;

import java.util.List;

/**
 * Canonical list of built-in OPD vitals. Each maps to a typed column on the Opd
 * entity. Built-ins may be toggled off per hospital but never deleted; custom
 * vitals live in hospital_vitals with isCustom = true and are stored in
 * opd.custom_vitals JSON.
 */
public final class VitalRegistry {
    private VitalRegistry() {}

    /** type: TEXT (free-form, e.g. "120/80") or NUMBER. */
    public record Vital(String key, String label, String unit, String type) {}

    public static final List<Vital> BUILT_INS = List.of(
        new Vital("BP", "Blood Pressure", "mmHg", "TEXT"),
        new Vital("TEMPERATURE", "Temperature", "°F", "NUMBER"),
        new Vital("PULSE", "Pulse", "bpm", "NUMBER"),
        new Vital("HEIGHT", "Height", "cm", "NUMBER"),
        new Vital("WEIGHT", "Weight", "kg", "NUMBER"),
        new Vital("SPO2", "SpO2", "%", "NUMBER")
    );

    public static boolean isBuiltIn(String key) {
        return BUILT_INS.stream().anyMatch(v -> v.key().equals(key));
    }

    /** Derive a stable key from a custom vital's name: "Random Sugar" -> "RANDOM_SUGAR". */
    public static String toKey(String name) {
        return name.trim().toUpperCase().replaceAll("[^A-Z0-9]+", "_").replaceAll("(^_)|(_$)", "");
    }
}
