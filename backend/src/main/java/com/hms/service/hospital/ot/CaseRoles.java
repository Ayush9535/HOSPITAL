package com.hms.service.hospital.ot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CaseRoles - the built-in roles a person can hold on a surgical team.
 *
 * Principle 3 in practice: a transplant centre adds HARVEST_SURGEON and a cardiac centre
 * adds PERFUSIONIST as DATA (a hospital custom role), never as a code change. The
 * built-ins here cover the common cases; custom roles live per-hospital in case_roles,
 * exactly as custom vitals extend the built-in vital set.
 */
public final class CaseRoles {

    public record Role(String code, String label) {}

    private static final Map<String, Role> BUILT_INS = new LinkedHashMap<>();

    private static void add(String code, String label) {
        BUILT_INS.put(code, new Role(code, label));
    }

    static {
        add("PRIMARY_SURGEON", "Primary Surgeon");
        add("ASSISTANT_SURGEON", "Assistant Surgeon");
        add("ANAESTHETIST", "Anaesthetist");
        add("SCRUB_NURSE", "Scrub Nurse");
        add("CIRCULATING_NURSE", "Circulating Nurse");
        add("TECHNICIAN", "OT Technician");
    }

    public static List<Role> builtIns() {
        return List.copyOf(BUILT_INS.values());
    }

    public static boolean isBuiltIn(String code) {
        return BUILT_INS.containsKey(code);
    }

    /** Normalise a free-typed role name into a stable code, as VitalRegistry does. */
    public static String toCode(String name) {
        if (name == null) return null;
        return name.trim().toUpperCase().replaceAll("[^A-Z0-9]+", "_").replaceAll("(^_)|(_$)", "");
    }

    private CaseRoles() {
    }
}
