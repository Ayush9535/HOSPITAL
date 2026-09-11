package com.hms.service.hospital;

import java.util.List;

/** Canonical list of access-controlled clinical forms (Files & Access, Phase 1).
 *  Keys reuse the frontend registry `type`/panel ids so both sides agree. */
public final class FormRegistry {
    private FormRegistry() {}

    public record Form(String key, String label, String category) {}

    public static final List<Form> FORMS = List.of(
        new Form("VITALS", "Vitals", "NURSING"),
        new Form("MEDICATION", "Medication", "NURSING"),
        new Form("NOTES", "Re-Assessment Sheet (Notes & Orders)", "NURSING"),
        new Form("INITIAL_ASSESSMENT", "Initial Assessment", "NURSING"),
        new Form("VULNERABILITY_ASSESSMENT", "Vulnerability Assessment", "NURSING"),
        new Form("SUGAR_CHART", "Sugar Chart", "NURSING"),
        new Form("VENTILATOR", "Ventilator Chart", "NURSING"),
        new Form("SEVERITY_SCORE", "Severity Scores", "NURSING"),
        new Form("BLOOD_CONSENT", "Blood Consent Form", "OT"),
        new Form("IO_CHART", "Input & Output Chart", "OT"),
        new Form("GA_CONSENT", "Consent Form for General Anaesthesia", "OT"),
        new Form("DRUG_ADMIN_SHEET", "Drug Administration Sheet", "OT"),
        new Form("INFORMED_CONSENT_ANAES", "Informed Consent — Anaesthesia", "OT"),
        new Form("INFORMED_CONSENT_SURGERY", "Informed Consent — Surgery", "OT"),
        new Form("PRE_OP_CHECKLIST", "Pre-Operative Checklist", "OT"),
        new Form("PRE_ANAES_EVAL", "Pre-Anaesthesia Evaluation", "OT"),
        new Form("GENERAL_ANAESTHESIA", "General Anaesthesia", "OT"),
        new Form("SURGICAL_CASE_RECORD", "Surgical Case Record", "OT"),
        new Form("POST_OP_CARE_PLAN", "Post-Operative Care Plan", "OT"),
        new Form("POST_OP_CHECKLIST_10", "Post-Operative Checklist", "OT"),
        new Form("POST_OP_CHECKLIST_02", "Post-Operative Checklist (+ I/O page)", "OT"),
        new Form("POST_ANAES_RECOVERY", "Post-Anaesthesia Recovery Chart", "OT"),
        new Form("WHO_CHECKLIST", "WHO Surgical Safety Checklist", "OT")
    );

    public static boolean isValidKey(String key) {
        return FORMS.stream().anyMatch(f -> f.key().equals(key));
    }
}
