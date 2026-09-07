package com.hms.security;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OtPermissions - the OT authorization vocabulary, and the role mapping a hospital
 * starts with.
 *
 * Authorization must not depend on a staff designation: the workflow asks "may this
 * caller schedule?", never "is this caller a receptionist?". Permissions are the
 * abstraction; a role is merely the default carrier of a set of them.
 *
 * Kept as a Java registry rather than a table, matching FormRegistry and VitalRegistry:
 * the codes are compile-time constants that endpoints reference by name. Only the
 * hospital's *overrides* are stored (role_permissions).
 *
 * The DEFAULTS below reproduce the pre-Phase-2 @PreAuthorize(hasRole(...)) checks
 * exactly. Do not "improve" them here -- a hospital changes its own mapping through
 * the permission matrix. Any edit to this map silently changes access for every
 * hospital that has not customised it.
 */
public final class OtPermissions {

    public static final String OT_VIEW = "OT_VIEW";
    public static final String OT_CREATE = "OT_CREATE";
    public static final String OT_APPROVE = "OT_APPROVE";
    public static final String OT_SCHEDULE = "OT_SCHEDULE";
    public static final String OT_RESCHEDULE = "OT_RESCHEDULE";
    public static final String OT_CANCEL = "OT_CANCEL";
    public static final String OT_ASSIGN_ROOM = "OT_ASSIGN_ROOM";
    public static final String OT_ASSIGN_TEAM = "OT_ASSIGN_TEAM";
    public static final String OT_PRE_OP = "OT_PRE_OP";
    public static final String OT_ANAESTHESIA_CLEARANCE = "OT_ANAESTHESIA_CLEARANCE";
    public static final String OT_EMERGENCY_OVERRIDE = "OT_EMERGENCY_OVERRIDE";
    public static final String OT_TIME_OUT = "OT_TIME_OUT";
    public static final String OT_START = "OT_START";
    public static final String OT_COMPLETE = "OT_COMPLETE";
    public static final String OT_RECOVERY = "OT_RECOVERY";
    public static final String OT_TRANSFER = "OT_TRANSFER";
    public static final String OT_CLOSE = "OT_CLOSE";
    public static final String OT_SETTINGS = "OT_SETTINGS";

    /**
     * The OT/NABH forms carry their own read and write codes. Folding them into OT_VIEW
     * would hand reception -- which needs OT_VIEW to run the board -- read access to
     * consents and checklists it does not have today.
     */
    public static final String OT_FORM_VIEW = "OT_FORM_VIEW";
    public static final String OT_FORM_EDIT = "OT_FORM_EDIT";

    /** Every code, in display order, for the admin matrix. */
    public static final List<String> ALL = List.of(
            OT_VIEW, OT_CREATE, OT_APPROVE, OT_SCHEDULE, OT_RESCHEDULE, OT_CANCEL,
            OT_ASSIGN_ROOM, OT_ASSIGN_TEAM, OT_PRE_OP, OT_ANAESTHESIA_CLEARANCE, OT_EMERGENCY_OVERRIDE,
            OT_TIME_OUT, OT_START, OT_COMPLETE,
            OT_RECOVERY, OT_TRANSFER, OT_CLOSE, OT_SETTINGS, OT_FORM_VIEW, OT_FORM_EDIT);

    private static final Map<String, String> DESCRIPTIONS = new LinkedHashMap<>();
    static {
        DESCRIPTIONS.put(OT_VIEW, "See surgeries and the OT board");
        DESCRIPTIONS.put(OT_CREATE, "Request a surgery");
        DESCRIPTIONS.put(OT_APPROVE, "Approve a requested surgery");
        DESCRIPTIONS.put(OT_SCHEDULE, "Schedule a surgery and see the scheduling worklist");
        DESCRIPTIONS.put(OT_RESCHEDULE, "Reschedule or postpone a surgery");
        DESCRIPTIONS.put(OT_CANCEL, "Cancel a surgery");
        DESCRIPTIONS.put(OT_ASSIGN_ROOM, "Assign the operation theatre");
        DESCRIPTIONS.put(OT_ASSIGN_TEAM, "Assign the surgical team");
        DESCRIPTIONS.put(OT_PRE_OP, "Complete pre-operative preparation");
        DESCRIPTIONS.put(OT_ANAESTHESIA_CLEARANCE, "Record anaesthesia clearance");
        DESCRIPTIONS.put(OT_EMERGENCY_OVERRIDE, "Override required pre-operative gates for an emergency");
        DESCRIPTIONS.put(OT_TIME_OUT, "Sign the WHO surgical safety checklist");
        DESCRIPTIONS.put(OT_START, "Start a surgery");
        DESCRIPTIONS.put(OT_COMPLETE, "Complete a surgery");
        DESCRIPTIONS.put(OT_RECOVERY, "Record post-anaesthesia recovery");
        DESCRIPTIONS.put(OT_TRANSFER, "Transfer the patient out of theatre");
        DESCRIPTIONS.put(OT_CLOSE, "Close a surgical case");
        DESCRIPTIONS.put(OT_SETTINGS, "Configure OT policies and permissions");
        DESCRIPTIONS.put(OT_FORM_VIEW, "Read OT/NABH forms");
        DESCRIPTIONS.put(OT_FORM_EDIT, "Fill and sign OT/NABH forms");
    }

    /**
     * Day-2 mapping (v2). Day-1 reproduced the pre-Phase-2 hasRole(...) checks exactly, but left
     * three codes -- OT_ASSIGN_TEAM, OT_RECOVERY, OT_TRANSFER -- granted to nobody, and gave
     * HOSPITAL_ADMIN clinical powers (start/complete/schedule/...) it never exercised through the
     * UI. Corrected here:
     *  DOCTOR         requests surgeries, reads its own board, gives anaesthesia clearance,
     *                 and -- as the surgeon of record -- proposes the surgical team.
     *  RECEPTIONIST   the front-desk scheduling role: approves, schedules, starts, completes,
     *                 closes, and transfers a recovered patient onward.
     *  NURSE          fills the OT/NABH forms, runs the WHO checklist bedside, and records
     *                 post-anaesthesia recovery observations.
     *  NURSE_INCHARGE owns ward-side recovery and the onward transfer out of PACU. (An earlier
     *                 draft of this note claimed it also assigns the surgical team; it never has
     *                 in DEFAULTS below, and the sentence was wrong rather than the grant.)
     *  OT_INCHARGE    the theatre-owning role: the full clinical set except HOSPITAL_ADMIN's
     *                 configuration authority. Previously granted nothing at all.
     *  HOSPITAL_ADMIN configuration and read access only -- OT_SETTINGS plus OT_VIEW/OT_FORM_VIEW.
     *                 Routine clinical execution (schedule/start/complete/recovery/...) is
     *                 deliberately withheld: an admin who needs to act clinically customises the
     *                 matrix like any other hospital, rather than the default silently allowing it.
     *
     * OT_ASSIGN_TEAM, OT_RECOVERY and OT_TRANSFER are not new codes -- OtPermissions.ALL and the
     * admin matrix catalogue always listed them -- so a hospital that already granted one of them
     * to a role has an explicit row and is untouched by this change or by the reconciliation
     * migration in DatabaseMigrationRunner. Only a hospital that never granted a code at all
     * (zero role_permissions rows for that permission_code, across every role) receives the
     * backfilled default below.
     */
    private static final Map<String, Set<String>> DEFAULTS = Map.of(
            "DOCTOR", Set.of(OT_VIEW, OT_CREATE, OT_FORM_VIEW, OT_ANAESTHESIA_CLEARANCE,
                    OT_EMERGENCY_OVERRIDE, OT_ASSIGN_TEAM),
            "RECEPTIONIST", Set.of(OT_VIEW, OT_APPROVE, OT_SCHEDULE, OT_RESCHEDULE, OT_CANCEL,
                    OT_ASSIGN_ROOM, OT_START, OT_COMPLETE, OT_CLOSE, OT_TRANSFER),
            "NURSE", Set.of(OT_VIEW, OT_PRE_OP, OT_TIME_OUT, OT_FORM_VIEW, OT_FORM_EDIT, OT_RECOVERY),
            "NURSE_INCHARGE", Set.of(OT_VIEW, OT_RECOVERY, OT_TRANSFER, OT_FORM_VIEW),
            "OT_INCHARGE", Set.of(OT_VIEW, OT_CREATE, OT_APPROVE, OT_SCHEDULE, OT_RESCHEDULE, OT_CANCEL,
                    OT_ASSIGN_ROOM, OT_ASSIGN_TEAM, OT_PRE_OP, OT_ANAESTHESIA_CLEARANCE,
                    OT_EMERGENCY_OVERRIDE, OT_TIME_OUT, OT_START, OT_COMPLETE, OT_RECOVERY,
                    OT_TRANSFER, OT_CLOSE, OT_FORM_VIEW, OT_FORM_EDIT),
            "HOSPITAL_ADMIN", Set.of(OT_VIEW, OT_SETTINGS, OT_FORM_VIEW));

    /** The roles a hospital can grant OT permissions to. */
    public static final List<String> ROLES = List.of(
            "DOCTOR", "RECEPTIONIST", "NURSE", "NURSE_INCHARGE", "OT_INCHARGE", "HOSPITAL_ADMIN");

    public static Set<String> defaultsFor(String role) {
        return new LinkedHashSet<>(DEFAULTS.getOrDefault(role, Set.of()));
    }

    public static String describe(String code) {
        return DESCRIPTIONS.getOrDefault(code, code);
    }

    public static boolean isValid(String code) {
        return ALL.contains(code);
    }

    private OtPermissions() {
    }
}
