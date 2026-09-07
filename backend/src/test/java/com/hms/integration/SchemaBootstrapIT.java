package com.hms.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Guardrail against the staging/prod boot outage of Jul 2026: the app 502'd because it started
 * against a REAL MySQL under {@code ddl-auto=validate}, which aborts startup on the first entity
 * whose table {@link com.hms.config.DatabaseMigrationRunner} hasn't created yet — and the runner
 * only runs on {@code ApplicationReadyEvent}, i.e. after a successful start. The Nurse module was
 * added without a matching table, so {@code validate} found {@code admission_forms} missing and
 * killed the app.
 *
 * <p>This test reproduces the PRODUCTION schema-build path on real MySQL — {@code ddl-auto=update}
 * (Hibernate reconciles the schema to the entities before startup) plus the migration runner — and
 * asserts the context boots AND the previously-missing, runner-owned tables actually exist. If a
 * future change reintroduces a schema the boot path cannot produce, this fails in CI ({@code mvn
 * verify}) instead of taking the deployed site down. Flyway is left at its non-staging default
 * (disabled): on real staging/prod databases it is a validated no-op at baseline V11, and the
 * legacy V6-V11 files contain non-MySQL syntax that must not run against a fresh container.
 *
 * <p>Requires Docker; skipped (not failed) where Docker is absent. See docs/testing/TESTING_STRATEGY.md.
 */
@Testcontainers(disabledWithoutDocker = true)
@org.springframework.boot.test.context.SpringBootTest
class SchemaBootstrapIT {

    @Container
    @SuppressWarnings("resource") // lifecycle managed by the @Testcontainers extension
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("hms_bootstrap")
            .withUsername("hms")
            .withPassword("hms");

    @DynamicPropertySource
    static void productionSchemaPath(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        // The production schema-build path: Hibernate reconciles the schema to the entities
        // before startup, then the runner applies its idempotent DDL/backfills.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
        registry.add("hms.migrations.enabled", () -> "true");
        registry.add("spring.cache.type", () -> "simple");
        // Keep Flyway off (the non-staging default) so the legacy, non-MySQL V6-V11 files never run
        // against this fresh container — on real staging/prod DBs Flyway is a validated no-op at V11.
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired
    private JdbcTemplate jdbc;

    private boolean tableExists(String table) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                Integer.class,
                table);
        return count != null && count > 0;
    }

    @Test
    @Timeout(300)
    void productionBootPathCreatesRunnerOwnedTables() {
        // The exact table whose absence 502'd staging, plus a spread of other runner-owned
        // Nurse/OT tables that `validate` would have tripped on next.
        List<String> mustExist = List.of(
                "admission_forms",
                "initial_assessments",
                "vulnerability_assessments",
                "sugar_chart_entries",
                "nurse_profiles",
                "vitals_records",
                "surgeries",
                "ot_rooms");

        assertThat(mustExist)
                .allSatisfy(table -> assertThat(tableExists(table))
                        .as("table '%s' must be created by the production boot path (ddl-auto=update + runner)", table)
                        .isTrue());
    }
}
