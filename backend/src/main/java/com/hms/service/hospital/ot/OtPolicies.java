package com.hms.service.hospital.ot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OtPolicies - the workflow policy catalogue and the archetype presets.
 *
 * The workflow engine reads policy only; it never learns a role. A step a hospital does
 * not require is auto-satisfied by the system, so the same state machine serves a 10-bed
 * nursing home and a 1000-bed corporate chain -- the difference is rows in
 * ot_workflow_policies, not code.
 *
 * A registry, like OtPermissions and CancellationReasons: the keys and their legal values
 * are fixed vocabulary. Only a hospital's overrides are stored, and an absent row means
 * the default here.
 */
public final class OtPolicies {

    // Keys.
    public static final String APPROVAL_MODE = "APPROVAL_MODE";                 // NONE | SINGLE | DUAL
    public static final String WHO_CHECKLIST_MODE = "WHO_CHECKLIST_MODE";       // OFF | ADVISORY | BLOCKING
    public static final String PRE_OP_CHECKLIST = "PRE_OP_CHECKLIST";           // OFF | REQUIRED
    public static final String ANAESTHESIA_CLEARANCE = "ANAESTHESIA_CLEARANCE"; // OFF | REQUIRED
    public static final String FINANCIAL_CLEARANCE = "FINANCIAL_CLEARANCE";     // OFF | CASH_ONLY | ALL
    public static final String RECOVERY_TRACKING = "RECOVERY_TRACKING";         // NONE | MILESTONE | PACU_EPISODE
    public static final String CANCELLATION_REASON = "CANCELLATION_REASON";     // OPTIONAL | REQUIRED
    public static final String TEAM_CAPTURE = "TEAM_CAPTURE";                    // SURGEON_ONLY | SURGEON_ANAES | FULL_TEAM

    // Priority scopes: a policy can differ for emergencies. Emergency is a scope, not a flow.
    public static final String SCOPE_ANY = "ANY";
    public static final String SCOPE_ELECTIVE = "ELECTIVE";
    public static final String SCOPE_EMERGENCY = "EMERGENCY";

    public record Policy(String key, String label, List<String> values, String defaultValue) {}

    private static final Map<String, Policy> BY_KEY = new LinkedHashMap<>();

    private static void add(String key, String label, List<String> values, String def) {
        BY_KEY.put(key, new Policy(key, label, values, def));
    }

    static {
        add(APPROVAL_MODE, "Approval", List.of("NONE", "SINGLE", "DUAL"), "NONE");
        add(WHO_CHECKLIST_MODE, "WHO Safety Checklist", List.of("OFF", "ADVISORY", "BLOCKING"), "ADVISORY");
        add(PRE_OP_CHECKLIST, "Pre-op Checklist", List.of("OFF", "REQUIRED"), "OFF");
        add(ANAESTHESIA_CLEARANCE, "Anaesthesia Clearance", List.of("OFF", "REQUIRED"), "OFF");
        add(FINANCIAL_CLEARANCE, "Financial Clearance", List.of("OFF", "CASH_ONLY", "ALL"), "OFF");
        add(RECOVERY_TRACKING, "Recovery Tracking", List.of("NONE", "MILESTONE", "PACU_EPISODE"), "NONE");
        add(CANCELLATION_REASON, "Cancellation Reason", List.of("OPTIONAL", "REQUIRED"), "OPTIONAL");
        add(TEAM_CAPTURE, "Team Capture", List.of("SURGEON_ONLY", "SURGEON_ANAES", "FULL_TEAM"), "SURGEON_ONLY");
    }

    public static List<Policy> all() {
        return List.copyOf(BY_KEY.values());
    }

    public static boolean isValidKey(String key) {
        return BY_KEY.containsKey(key);
    }

    public static boolean isValidValue(String key, String value) {
        Policy p = BY_KEY.get(key);
        return p != null && p.values().contains(value);
    }

    public static String defaultValue(String key) {
        Policy p = BY_KEY.get(key);
        return p == null ? null : p.defaultValue();
    }

    // Archetype presets: a one-click bulk write, NOT stored on the hospital. A hospital that
    // later diverges from its preset must not be a special case -- it is just its own rows.
    private static final Map<String, Map<String, String>> ARCHETYPES = new LinkedHashMap<>();

    private static void archetype(String name, Map<String, String> policies) {
        ARCHETYPES.put(name, policies);
    }

    static {
        archetype("SMALL", Map.of(
                APPROVAL_MODE, "NONE", WHO_CHECKLIST_MODE, "ADVISORY", PRE_OP_CHECKLIST, "OFF",
                ANAESTHESIA_CLEARANCE, "OFF", FINANCIAL_CLEARANCE, "OFF", RECOVERY_TRACKING, "NONE",
                CANCELLATION_REASON, "OPTIONAL", TEAM_CAPTURE, "SURGEON_ONLY"));
        archetype("MEDIUM", Map.of(
                APPROVAL_MODE, "SINGLE", WHO_CHECKLIST_MODE, "BLOCKING", PRE_OP_CHECKLIST, "REQUIRED",
                ANAESTHESIA_CLEARANCE, "REQUIRED", FINANCIAL_CLEARANCE, "CASH_ONLY", RECOVERY_TRACKING, "MILESTONE",
                CANCELLATION_REASON, "REQUIRED", TEAM_CAPTURE, "SURGEON_ANAES"));
        archetype("LARGE", Map.of(
                APPROVAL_MODE, "SINGLE", WHO_CHECKLIST_MODE, "BLOCKING", PRE_OP_CHECKLIST, "REQUIRED",
                ANAESTHESIA_CLEARANCE, "REQUIRED", FINANCIAL_CLEARANCE, "CASH_ONLY", RECOVERY_TRACKING, "PACU_EPISODE",
                CANCELLATION_REASON, "REQUIRED", TEAM_CAPTURE, "FULL_TEAM"));
        archetype("CORPORATE", Map.of(
                APPROVAL_MODE, "DUAL", WHO_CHECKLIST_MODE, "BLOCKING", PRE_OP_CHECKLIST, "REQUIRED",
                ANAESTHESIA_CLEARANCE, "REQUIRED", FINANCIAL_CLEARANCE, "ALL", RECOVERY_TRACKING, "PACU_EPISODE",
                CANCELLATION_REASON, "REQUIRED", TEAM_CAPTURE, "FULL_TEAM"));
    }

    public static List<String> archetypeNames() {
        return List.copyOf(ARCHETYPES.keySet());
    }

    public static Map<String, String> archetype(String name) {
        return ARCHETYPES.get(name == null ? "" : name.toUpperCase());
    }

    private OtPolicies() {
    }
}
