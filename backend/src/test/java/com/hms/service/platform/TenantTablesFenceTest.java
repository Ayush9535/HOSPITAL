package com.hms.service.platform;

import com.hms.service.platform.TenantTables.Retention;
import com.hms.service.platform.TenantTables.TenantTable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SA-7a — the fence around {@link TenantTables}.
 *
 * <p>Thirty-nine tenant tables drifted outside the delete path because nothing failed when one was
 * added. This test reads the live schema and fails if a table carrying a {@code hospital_id} column
 * is not declared in the registry. Adding a tenant table now forces a deliberate classification.
 *
 * <p>Note the direction: the schema is consulted here, in a test, where a mismatch is a build
 * failure. Deletion itself never enumerates the schema at runtime.
 */
@SpringBootTest
@ActiveProfiles("test")
class TenantTablesFenceTest {

    @Autowired JdbcTemplate jdbc;

    /** Tables with a hospital_id column, straight from the database. */
    private List<String> tablesWithHospitalId() {
        return jdbc.queryForList(
                "SELECT DISTINCT LOWER(t.TABLE_NAME) FROM INFORMATION_SCHEMA.TABLES t "
                        + "JOIN INFORMATION_SCHEMA.COLUMNS c "
                        + "  ON c.TABLE_SCHEMA = t.TABLE_SCHEMA AND c.TABLE_NAME = t.TABLE_NAME "
                        + "WHERE t.TABLE_SCHEMA = SCHEMA() AND t.TABLE_TYPE = 'BASE TABLE' "
                        + "  AND LOWER(c.COLUMN_NAME) = 'hospital_id' "
                        + "ORDER BY 1",
                String.class);
    }

    @Test
    void everyTableWithAHospitalIdIsDeclared() {
        List<String> undeclared = new ArrayList<>();
        for (String table : tablesWithHospitalId()) {
            if (!TenantTables.isDeclared(table)) undeclared.add(table);
        }

        assertThat(undeclared)
                .as("tables carrying hospital_id but absent from TenantTables. Add each one with "
                        + "an ownership and a retention class: PURGE_SAFE only if it holds no "
                        + "clinical, financial, HR or audit content")
                .isEmpty();
    }

    /** The schema must actually have been created, or the fence proves nothing. */
    @Test
    void theFenceIsLookingAtARealSchema() {
        assertThat(tablesWithHospitalId())
                .as("no hospital_id tables found — the fence would pass vacuously")
                .hasSizeGreaterThan(50);
    }

    @Test
    void everyRegistryEntryIsClassified() {
        for (TenantTable t : TenantTables.all()) {
            assertThat(t.table()).isNotBlank();
            assertThat(t.ownership()).as("ownership for %s", t.table()).isNotNull();
            assertThat(t.retention()).as("retention for %s", t.table()).isNotNull();
        }
        assertThat(TenantTables.all()).isNotEmpty();
    }

    /** A purge entry has to be executable: an order, a predicate, and a bind for the id. */
    @Test
    void everyPurgeSafeEntryIsExecutable() {
        List<TenantTable> purge = TenantTables.purgeSafeInOrder();
        assertThat(purge).isNotEmpty();

        for (TenantTable t : purge) {
            assertThat(t.purgeOrder()).as("purge order for %s", t.table())
                    .isNotEqualTo(TenantTables.NOT_PURGED);
            assertThat(t.where()).as("predicate for %s", t.table()).isNotBlank();
            assertThat(t.binds()).as("hospital-id binds for %s", t.table()).isPositive();
        }
    }

    /** Ordering is the contract; two tables sharing a slot makes it ambiguous. */
    @Test
    void purgeOrderIsStrictAndAscending() {
        List<TenantTable> purge = TenantTables.purgeSafeInOrder();
        for (int i = 1; i < purge.size(); i++) {
            assertThat(purge.get(i).purgeOrder())
                    .as("%s must come after %s", purge.get(i).table(), purge.get(i - 1).table())
                    .isGreaterThan(purge.get(i - 1).purgeOrder());
        }
    }

    /** Nothing that is not purged may carry an order, or the two would disagree. */
    @Test
    void nonPurgedEntriesCarryNoOrder() {
        for (TenantTable t : TenantTables.all()) {
            if (t.retention() != Retention.PURGE_SAFE) {
                assertThat(t.purgeOrder()).as("%s is not purged and must not declare an order",
                        t.table()).isEqualTo(TenantTables.NOT_PURGED);
            }
        }
    }

    /** Every purge statement must prepare against the real schema. */
    @Test
    void everyPurgeStatementPreparesAgainstTheSchema() throws Exception {
        List<String> broken = new ArrayList<>();
        try (java.sql.Connection c = jdbc.getDataSource().getConnection()) {
            for (TenantTable t : TenantTables.purgeSafeInOrder()) {
                String sql = "DELETE FROM " + t.table() + " WHERE " + t.where();
                try (java.sql.PreparedStatement ps = c.prepareStatement(sql)) {
                    assertThat(ps).isNotNull();
                } catch (Exception e) {
                    broken.add(sql + "  ->  " + e.getMessage());
                }
            }
        }
        assertThat(broken).as("purge statements the schema will not accept").isEmpty();
    }

    /** Global master data must never acquire a purge order by accident. */
    @Test
    void globalTablesAreNeverPurged() {
        for (TenantTable t : TenantTables.all()) {
            if (t.retention() == Retention.GLOBAL_NEVER_DELETE) {
                assertThat(TenantTables.purgeSafeInOrder()).doesNotContain(t);
            }
        }
        assertThat(TenantTables.namesWithRetention(Retention.GLOBAL_NEVER_DELETE))
                .contains("medicine_list", "faqs", "inventory_master_items",
                        "plans", "plan_features", "plan_modules");
    }

    /**
     * The categories SA-7a is explicitly not deciding. If one of these ever turns up as
     * PURGE_SAFE it is a product decision being made by accident.
     */
    @Test
    void retainedCategoriesAreNotPurgeSafe() {
        List<String> mustNotBePurged = List.of(
                "patients", "opd", "ipd_admission", "prescriptions", "medical_records",
                "vitals_records", "nursing_notes", "medication_administrations",
                "initial_assessments", "vulnerability_assessments", "sugar_chart_entries",
                "admission_forms", "patient_nurse_assignments",
                "surgeries", "surgery_forms", "who_checklists", "surgery_milestones",
                "surgery_state_transitions", "surgery_team_members", "ot_recovery_episodes",
                "ot_recovery_observations", "ot_room_occupancy", "case_roles",
                "billing", "billing_payments", "billing_items", "billing_medicines",
                "pharmacy_sales", "purchase_invoices", "inventory_transactions",
                "hospital_plan_subscriptions", "hospital_fees",
                "nurse_profiles", "nurse_attendance",
                "audit_logs", "bed_status_audits",
                "surgery_anaesthesia_clearances", "surgery_emergency_overrides");

        List<String> purged = TenantTables.namesWithRetention(Retention.PURGE_SAFE);
        assertThat(purged)
                .as("SA-7a must not delete clinical, financial, HR or audit history")
                .doesNotContainAnyElementsOf(mustNotBePurged);
    }
}
