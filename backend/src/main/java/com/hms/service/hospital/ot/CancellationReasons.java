package com.hms.service.hospital.ot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CancellationReasons - the taxonomy a cancelled or postponed case must be tagged with.
 *
 * NABH tracks "% of elective surgeries cancelled, by reason", which is uncomputable if
 * the reason is free text. The categories mirror how hospitals actually review
 * cancellations: was it the patient, the surgeon, the facility, or administration?
 *
 * A Java registry rather than a table, matching FormRegistry, VitalRegistry and
 * OtPermissions: these codes are referenced by reporting and must not vary per hospital.
 * (The ADR sketched a cancellation_reasons table; a registry gives the same reporting
 * guarantee with one fewer table. Per-hospital custom reasons can be layered on later
 * exactly as custom vitals were.)
 */
public final class CancellationReasons {

    public static final String CATEGORY_PATIENT = "PATIENT";
    public static final String CATEGORY_SURGEON = "SURGEON";
    public static final String CATEGORY_FACILITY = "FACILITY";
    public static final String CATEGORY_ADMINISTRATIVE = "ADMINISTRATIVE";
    public static final String CATEGORY_CLINICAL = "CLINICAL";

    public record Reason(String code, String label, String category) {}

    private static final Map<String, Reason> BY_CODE = new LinkedHashMap<>();

    private static void add(String code, String label, String category) {
        BY_CODE.put(code, new Reason(code, label, category));
    }

    static {
        add("PATIENT_UNFIT", "Patient medically unfit", CATEGORY_CLINICAL);
        add("PATIENT_REFUSED", "Patient refused / withdrew consent", CATEGORY_PATIENT);
        add("PATIENT_NOT_FASTING", "Patient not adequately fasted", CATEGORY_PATIENT);
        add("PATIENT_ABSENT", "Patient did not attend", CATEGORY_PATIENT);
        add("SURGEON_UNAVAILABLE", "Surgeon unavailable", CATEGORY_SURGEON);
        add("ANAESTHETIST_UNAVAILABLE", "Anaesthetist unavailable", CATEGORY_SURGEON);
        add("THEATRE_UNAVAILABLE", "Theatre unavailable / overrun", CATEGORY_FACILITY);
        add("EQUIPMENT_UNAVAILABLE", "Equipment or implant unavailable", CATEGORY_FACILITY);
        add("EMERGENCY_PRIORITISED", "Displaced by an emergency case", CATEGORY_FACILITY);
        add("FINANCIAL_CLEARANCE", "Financial clearance not obtained", CATEGORY_ADMINISTRATIVE);
        add("INVESTIGATIONS_PENDING", "Investigations or clearance pending", CATEGORY_CLINICAL);
        add("PROCEDURE_NO_LONGER_REQUIRED", "Procedure no longer required", CATEGORY_CLINICAL);
        add("OTHER", "Other", CATEGORY_ADMINISTRATIVE);
    }

    public static List<Reason> all() {
        return List.copyOf(BY_CODE.values());
    }

    public static boolean isValid(String code) {
        return code != null && BY_CODE.containsKey(code);
    }

    public static Reason of(String code) {
        Reason r = BY_CODE.get(code);
        if (r == null) throw new IllegalArgumentException("Unknown cancellation reason: " + code);
        return r;
    }

    private CancellationReasons() {
    }
}
