-- Optimistic revision supplied by schedule/reschedule clients to reject stale commands.
--
-- Idempotent by construction. This app runs Flyway ALONGSIDE Hibernate ddl-auto=update (see
-- application-staging/-prod), and Flyway runs FIRST -- before Hibernate and before
-- DatabaseMigrationRunner. So a database whose schema was built by ddl-auto on an app build
-- that already carried Surgery.lifecycleVersion arrives here with the column ALREADY PRESENT
-- and no V12 row in flyway_schema_history (baseline-on-migrate adopts it at V11). A plain
-- ALTER ... ADD COLUMN then dies with "Duplicate column name 'lifecycle_version'", writing a
-- failed row that blocks every later migration on that database.
--
-- MySQL 8 has no ADD COLUMN IF NOT EXISTS (that is MariaDB), so the guard is an
-- information_schema check driving a prepared statement -- the standard MySQL 8 idiom.
SET @lifecycle_version_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'surgeries'
      AND COLUMN_NAME = 'lifecycle_version');

SET @lifecycle_version_ddl := IF(@lifecycle_version_exists = 0,
    'ALTER TABLE surgeries ADD COLUMN lifecycle_version BIGINT NOT NULL DEFAULT 0',
    'DO 0');

PREPARE lifecycle_version_stmt FROM @lifecycle_version_ddl;
EXECUTE lifecycle_version_stmt;
DEALLOCATE PREPARE lifecycle_version_stmt;

-- Converge the column's SHAPE, not just its existence. When ddl-auto created it first it is
-- BIGINT NOT NULL but carries NO default (Hibernate does not emit one), whereas the ADD COLUMN
-- above creates it with DEFAULT 0 -- so without this the same release yields two different
-- schemas depending on which authority won the race. SET DEFAULT is metadata-only on MySQL 8:
-- it does not rewrite the table, touch existing rows, or alter nullability. Running it
-- unconditionally is safe and idempotent -- on the freshly-added column it is already 0.
ALTER TABLE surgeries ALTER COLUMN lifecycle_version SET DEFAULT 0;
