package com.hms.service.platform;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every table that holds tenant data, what kind of tenant data it is, and whether deleting a
 * facility may remove it.
 *
 * <p>The point of writing this down is the fence around it: {@code TenantTablesFenceTest} fails
 * if a table carrying a {@code hospital_id} column is not listed here. Thirty-nine tenant tables
 * accumulated outside the delete path precisely because nothing forced anyone to notice them; a
 * developer adding a tenant table now has to say what it is, and that is a deliberate edit rather
 * than an omission.
 *
 * <p><b>Declared, not derived.</b> Deletion never enumerates {@code information_schema} at
 * runtime: a delete that changes shape with the database it happens to be pointed at is not a
 * delete anyone can reason about, and it would happily purge audit history the moment someone
 * added a column. The schema is consulted only by the fence test, where a mismatch is a build
 * failure rather than data loss.
 *
 * <p><b>{@link Retention#RETAIN_DECISION_REQUIRED} does not mean "currently retained."</b> It
 * means SA-7a takes no position: whatever {@code deleteHospital} does with that table today, it
 * still does. Clinical, financial, HR and audit history are open product decisions, and this
 * registry records that they are open rather than quietly resolving them.
 */
public final class TenantTables {

    private TenantTables() {
    }

    /** How a row is tied to a facility. */
    public enum Ownership {
        /** Carries its own {@code hospital_id}. */
        DIRECT,
        /** Reached only through a parent row; has no tenant column of its own. */
        INDIRECT,
        /** Platform-owned master data shared by every tenant. */
        GLOBAL
    }

    /** Whether a facility delete may remove the rows. */
    public enum Retention {
        /**
         * Configuration, presets, scheduling and permissions. No clinical, financial, HR or audit
         * content, nothing a retention rule reaches, and nothing another tenant can miss.
         */
        PURGE_SAFE,
        /**
         * Clinical, financial, HR or audit history. SA-7a neither adds nor removes deletion here;
         * whether these should be deleted, retained or anonymised is an open product decision.
         */
        RETAIN_DECISION_REQUIRED,
        /** Shared master data. A tenant delete must never touch it. */
        GLOBAL_NEVER_DELETE
    }

    /**
     * One table.
     *
     * @param table      physical table name
     * @param ownership  how it is tied to a facility
     * @param retention  whether a facility delete may remove its rows
     * @param purgeOrder position in the purge sequence, children before parents;
     *                   {@link #NOT_PURGED} for everything SA-7a does not delete
     * @param where      predicate selecting one facility's rows, with {@code ?} for the hospital id
     */
    public record TenantTable(String table, Ownership ownership, Retention retention,
            int purgeOrder, String where) {

        /** How many times the hospital id must be bound for {@link #where()}. */
        public int binds() {
            return (int) where.chars().filter(c -> c == '?').count();
        }
    }

    /** purgeOrder for a table this registry does not delete. */
    public static final int NOT_PURGED = -1;

    private static final String BY_HOSPITAL = "hospital_id = ?";
    private static final Map<String, TenantTable> BY_NAME = new LinkedHashMap<>();

    private static void purge(int order, String table, String where) {
        add(table, where.equals(BY_HOSPITAL) ? Ownership.DIRECT : Ownership.INDIRECT,
                Retention.PURGE_SAFE, order, where);
    }

    private static void retain(String table, Ownership ownership) {
        add(table, ownership, Retention.RETAIN_DECISION_REQUIRED, NOT_PURGED, BY_HOSPITAL);
    }

    private static void global(String table) {
        add(table, Ownership.GLOBAL, Retention.GLOBAL_NEVER_DELETE, NOT_PURGED, "");
    }

    private static void add(String table, Ownership ownership, Retention retention, int order,
            String where) {
        BY_NAME.put(table, new TenantTable(table, ownership, retention, order, where));
    }

    static {
        // ── PURGE_SAFE ──────────────────────────────────────────────────────────────────
        // Ordered children-before-parents. Nothing in the current schema declares a foreign key
        // into these, but the order is the contract regardless: a schema built from
        // setup/schema-full.sql carries constraints the application-built one does not.

        // First, and deliberately so. A facility id can be reused if the AUTO_INCREMENT counter is
        // ever reset (TRUNCATE, an environment rebuild, MySQL < 8.0). OtPermissionService treats
        // "no rows for this hospital" as "not configured yet" and falls back to safe defaults, so
        // a surviving matrix would silently hand a stranger's permissions to whoever inherits the
        // id. That is the one leftover here with a security consequence.
        purge(1, "role_permissions", BY_HOSPITAL);

        // Prescription presets: items reference their preset, which carries the tenant column.
        purge(2, "prescription_preset_items",
                "preset_id IN (SELECT id FROM prescription_presets WHERE hospital_id = ?)");
        purge(3, "prescription_presets", BY_HOSPITAL);

        // Service catalogue: the link row goes, the global master item it points at stays.
        purge(4, "hospital_service_items",
                "service_id IN (SELECT id FROM hospital_services WHERE hospital_id = ?)");
        purge(5, "hospital_services", BY_HOSPITAL);

        // Nurse scheduling. The schedules/assignments reference nurse_profiles and
        // shift_templates, so they go first; nurse_profiles itself is an employment record and is
        // NOT purged.
        purge(6, "nurse_shift_schedules", BY_HOSPITAL);
        purge(7, "nurse_ward_assignments", BY_HOSPITAL);
        purge(8, "nurse_substitutions", BY_HOSPITAL);
        purge(9, "shift_templates", BY_HOSPITAL);

        // Theatre configuration.
        purge(10, "ot_rooms", BY_HOSPITAL);
        purge(11, "ot_incharges", BY_HOSPITAL);
        purge(12, "ot_workflow_policies", BY_HOSPITAL);
        // Recovery-location configuration (OT-P0B) -- a named bay, not a clinical record; the
        // episodes/observations that happened in it are ot_recovery_episodes/observations below,
        // already classified retain().
        purge(21, "recovery_bays", BY_HOSPITAL);

        // Per-facility settings and presets.
        purge(13, "hospital_form_access", BY_HOSPITAL);
        purge(14, "hospital_vitals", BY_HOSPITAL);
        purge(15, "appointment_slots", BY_HOSPITAL);
        purge(16, "calendar_events", BY_HOSPITAL);
        purge(17, "consultation_note_presets", BY_HOSPITAL);
        purge(18, "notifications", BY_HOSPITAL);
        purge(19, "pharmacy_branch", BY_HOSPITAL);
        purge(20, "manual_tasks", BY_HOSPITAL);

        // ── RETAIN_DECISION_REQUIRED ────────────────────────────────────────────────────
        // Listed so the fence can see them, not so they can be deleted. deleteHospital's
        // behaviour for these is exactly what it was before SA-7a.

        // Patient identity and encounters.
        retain("patients", Ownership.DIRECT);
        retain("appointments", Ownership.DIRECT);
        retain("ipd_admission", Ownership.DIRECT);
        add("opd", Ownership.INDIRECT, Retention.RETAIN_DECISION_REQUIRED, NOT_PURGED, "");
        add("queue_entry", Ownership.INDIRECT, Retention.RETAIN_DECISION_REQUIRED, NOT_PURGED, "");
        add("discharge_summary", Ownership.INDIRECT, Retention.RETAIN_DECISION_REQUIRED,
                NOT_PURGED, "");
        add("ipd_bed_history", Ownership.INDIRECT, Retention.RETAIN_DECISION_REQUIRED,
                NOT_PURGED, "");

        // Clinical records.
        retain("prescriptions", Ownership.DIRECT);
// Documents a patient supplied. Clinical record: the file stays with the row.
        retain("patient_documents", Ownership.DIRECT);
                retain("medical_records", Ownership.DIRECT);
        retain("lab_orders", Ownership.DIRECT);
        retain("admission_forms", Ownership.DIRECT);
        retain("initial_assessments", Ownership.DIRECT);
        retain("vulnerability_assessments", Ownership.DIRECT);
        retain("vitals_records", Ownership.DIRECT);
        retain("sugar_chart_entries", Ownership.DIRECT);
        retain("nursing_notes", Ownership.DIRECT);
        retain("medication_administrations", Ownership.DIRECT);
        retain("patient_nurse_assignments", Ownership.DIRECT);

        // OT / NABH records. Accreditation evidence and the retained-instrument defence.
        retain("surgeries", Ownership.DIRECT);
        retain("surgery_forms", Ownership.DIRECT);
        retain("surgery_milestones", Ownership.DIRECT);
        retain("surgery_state_transitions", Ownership.DIRECT);
        retain("surgery_team_members", Ownership.DIRECT);
        retain("who_checklists", Ownership.DIRECT);
        retain("case_roles", Ownership.DIRECT);
        // INV-2/INV-3: clinical + financial stock records. Retained, never purge-safe --
        // a medicine batch and its movement ledger are dispensing history.
        // Request bookkeeping: which OPD registration keys a facility has already claimed. No
        // clinical or financial content, and meaningless once the facility is gone.
        purge(22, "opd_idempotency", BY_HOSPITAL);

        // ICU configuration, not ICU records: a ventilator parameter catalogue, which severity
        // scores a facility uses, and the numbers it wants alerting on. The observations recorded
        // against them are the icu_* tables classified retain() below.
        purge(23, "icu_ventilator_parameter", BY_HOSPITAL);
        purge(24, "icu_score_type_setting", BY_HOSPITAL);
        purge(25, "icu_alert_threshold", BY_HOSPITAL);

        // An ICU episode and everything charted during it is clinical record. The stay itself carries
        // the admission and discharge times a later review depends on; the rest is what was
        // observed, given and titrated at the bedside.
        retain("icu_stay", Ownership.DIRECT);
        retain("icu_io_entry", Ownership.DIRECT);
        retain("icu_infusion", Ownership.DIRECT);
        retain("icu_infusion_rate", Ownership.DIRECT);
        retain("icu_ventilator_setting", Ownership.DIRECT);
        retain("icu_severity_score", Ownership.DIRECT);

        retain("medicine_stock_batches", Ownership.DIRECT);
        retain("stock_movements", Ownership.DIRECT);
        retain("ot_recovery_episodes", Ownership.DIRECT);
        retain("ot_recovery_observations", Ownership.DIRECT);
        retain("ot_room_occupancy", Ownership.DIRECT);
        // Landed on staging after this registry was first written; classified here so the fence
        // does not fail the moment the branches meet. Both are clinical decisions with an audit
        // character -- an anaesthetist's clearance and a recorded decision to bypass a pre-op
        // gate -- so they retain, exactly like the rest of the OT record.
        retain("surgery_anaesthesia_clearances", Ownership.DIRECT);
        retain("surgery_emergency_overrides", Ownership.DIRECT);

        // Financial: books of account, GST-relevant.
        retain("billing", Ownership.DIRECT);
        retain("billing_items", Ownership.DIRECT);
        retain("billing_medicines", Ownership.DIRECT);
        retain("billing_payments", Ownership.DIRECT);
        retain("hospital_fees", Ownership.DIRECT);
        retain("pharmacy_sales", Ownership.DIRECT);
        add("pharmacy_sale_items", Ownership.INDIRECT, Retention.RETAIN_DECISION_REQUIRED,
                NOT_PURGED, "");
        retain("purchase_invoices", Ownership.DIRECT);
        add("purchase_invoice_items", Ownership.INDIRECT, Retention.RETAIN_DECISION_REQUIRED,
                NOT_PURGED, "");
        retain("medicine_purchase", Ownership.DIRECT);
        retain("hospital_inventory_purchase", Ownership.DIRECT);
        retain("inventory_transactions", Ownership.DIRECT);
        retain("hospital_plan_subscriptions", Ownership.DIRECT);

        // Pharmacy and inventory stock.
        retain("medicine_master", Ownership.DIRECT);
        retain("medicine_batches", Ownership.DIRECT);
        retain("medicine_categories", Ownership.DIRECT);
        retain("medicines", Ownership.DIRECT);
        retain("manufacturers", Ownership.DIRECT);
        retain("suppliers", Ownership.DIRECT);
        retain("hospital_inventory", Ownership.DIRECT);
        retain("inventory_items", Ownership.DIRECT);

        // Staff and access. nurse_profiles/nurse_attendance are employment records.
        retain("users", Ownership.DIRECT);
        retain("doctors", Ownership.DIRECT);
        retain("receptionists", Ownership.DIRECT);
        retain("pharmacists", Ownership.DIRECT);
        retain("hospital_admins", Ownership.DIRECT);
        retain("clinic_admins", Ownership.DIRECT);
        retain("pharmacy_admins", Ownership.DIRECT);
        retain("nurse_profiles", Ownership.DIRECT);
        retain("nurse_attendance", Ownership.DIRECT);

        // Audit trail.
        retain("audit_logs", Ownership.DIRECT);
        retain("bed_status_audits", Ownership.DIRECT);
        retain("support_tickets", Ownership.DIRECT);

        // Facility configuration the existing delete already removes.
        retain("hospital_settings", Ownership.DIRECT);
        retain("hospital_modules", Ownership.DIRECT);
        retain("wards", Ownership.DIRECT);
        retain("beds", Ownership.DIRECT);

        // The facility row. Retained under an ARCHIVE lifecycle, removed under a hard delete.
        add("hospitals", Ownership.DIRECT, Retention.RETAIN_DECISION_REQUIRED, NOT_PURGED, "");

        // ── GLOBAL_NEVER_DELETE ─────────────────────────────────────────────────────────
        global("medicine_list");
        global("faqs");
        global("inventory_master_items");
        global("plans");
        global("plan_features");
        global("plan_modules");
    }

    /** Every declared table. */
    public static List<TenantTable> all() {
        return List.copyOf(BY_NAME.values());
    }

    public static TenantTable get(String table) {
        return BY_NAME.get(table);
    }

    public static boolean isDeclared(String table) {
        return BY_NAME.containsKey(table);
    }

    /** The purge sequence, children before parents. The only list deleteHospital executes. */
    public static List<TenantTable> purgeSafeInOrder() {
        List<TenantTable> out = new ArrayList<>();
        for (TenantTable t : BY_NAME.values()) {
            if (t.retention() == Retention.PURGE_SAFE) out.add(t);
        }
        out.sort(Comparator.comparingInt(TenantTable::purgeOrder));
        return List.copyOf(out);
    }

    public static List<String> namesWithRetention(Retention retention) {
        List<String> out = new ArrayList<>();
        for (TenantTable t : BY_NAME.values()) {
            if (t.retention() == retention) out.add(t.table());
        }
        return List.copyOf(out);
    }
}
