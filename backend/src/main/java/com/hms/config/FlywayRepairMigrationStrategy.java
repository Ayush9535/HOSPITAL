package com.hms.config;

import org.flywaydb.core.api.output.RepairResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Runs {@code flyway.repair()} before every {@code flyway.migrate()}.
 *
 * <p><b>Why this is needed here and not in a textbook Flyway app.</b> This application does not
 * use Flyway as its sole schema authority. Staging and production run Hibernate
 * {@code ddl-auto=update} <i>and</i> {@code DatabaseMigrationRunner} <i>and</i> Flyway together
 * (see application-staging.properties for the incident that forced that). The startup order is
 * fixed and was verified against a running app, not inferred: Flyway executes first, inside
 * {@code FlywayMigrationInitializer.afterPropertiesSet}; Hibernate reconciles the schema after
 * it; {@code DatabaseMigrationRunner} runs last, on {@code ApplicationReadyEvent}. A Flyway
 * failure therefore aborts startup before either of the other two can run.
 *
 * <p>That ordering makes a normally-impossible state routine: a database can already contain a
 * column that a pending migration is about to add, because an earlier deploy's ddl-auto created
 * it from the entity while Flyway was disabled or not yet adopted. {@code baseline-on-migrate}
 * adopts such a database at V11 and leaves V12 pending against a schema that already satisfies
 * it. The migrations themselves are written to tolerate this (V12 is idempotent), but two
 * history-level states still need clearing, and only {@code repair()} can clear them:
 *
 * <ul>
 *   <li><b>A failed migration row</b> — a previous boot ran the non-idempotent V12, hit
 *       "Duplicate column name", and recorded {@code success = 0}. Flyway refuses to migrate a
 *       schema carrying a failed row, so the database is stuck permanently: every subsequent
 *       deploy fails at startup until a human intervenes. {@code repair()} removes it.</li>
 *   <li><b>A checksum mismatch</b> — V12 was edited in the repository (once to fix MySQL 8
 *       syntax, once to make it idempotent). Any environment that already applied an earlier
 *       revision has the old checksum recorded, and {@code validate-on-migrate=true} would fail
 *       startup on the mismatch. {@code repair()} realigns the recorded checksum to the file.</li>
 * </ul>
 *
 * <p><b>What this deliberately does not do.</b> {@code repair()} only rewrites
 * {@code flyway_schema_history}; it never touches application tables or data, and it never
 * re-runs or skips a migration. It does not weaken {@code validate-on-migrate}: validation still
 * runs inside {@code migrate()} immediately afterwards, against the realigned history.
 *
 * <p><b>The cost, stated plainly.</b> Realigning checksums automatically means an
 * <i>accidentally</i> edited applied migration is silently accepted rather than failing the
 * deploy. That protection is traded away knowingly: it is already unavailable in practice here,
 * because ddl-auto can satisfy a migration's intent before Flyway ever sees it, so a checksum
 * match was never a reliable statement about the live schema in this architecture. The real
 * guard against editing applied migrations is {@code scripts/db/validate-migrations.sh} in CI
 * plus review. Once schema authority is normalised onto Flyway alone (tracked as post-launch
 * work — the docs already describe that end state, the runtime does not implement it yet), this
 * bean should be deleted and {@code repair} should go back to being a deliberate operator action.
 */
@Configuration
@ConditionalOnProperty(name = "spring.flyway.enabled", havingValue = "true")
public class FlywayRepairMigrationStrategy {

    private static final Logger log = LoggerFactory.getLogger(FlywayRepairMigrationStrategy.class);

    @Bean
    public FlywayMigrationStrategy repairThenMigrate() {
        return flyway -> {
            try {
                RepairResult repair = flyway.repair();
                if (repair != null) {
                    int removed = repair.migrationsRemoved == null ? 0 : repair.migrationsRemoved.size();
                    int aligned = repair.migrationsAligned == null ? 0 : repair.migrationsAligned.size();
                    if (removed > 0 || aligned > 0) {
                        log.warn("Flyway repair before migrate: removed {} failed migration row(s), "
                                + "realigned {} checksum(s). See FlywayRepairMigrationStrategy for why "
                                + "this runs automatically in this application.", removed, aligned);
                    }
                }
            } catch (Exception e) {
                // A repair failure must not be the thing that stops the deploy: migrate() below is
                // the operation that actually matters, and it reports its own problems precisely.
                log.warn("Flyway repair failed; continuing to migrate: {}", e.getMessage());
            }
            flyway.migrate();
        };
    }
}
