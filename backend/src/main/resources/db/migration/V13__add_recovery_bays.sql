-- A recovery bay is a named PACU/recovery location, as a first-class tenant-owned resource --
-- not an ot_rooms row (a theatre frees the moment its case COMPLETEs; recovery is a separate
-- resource) and not a Ward/Bed. Occupancy is derived from ot_recovery_episodes (an undischarged
-- row referencing a bay means it is occupied), not stored here.
--
-- Idempotent by construction, for the same reason V12 is: Flyway runs BEFORE Hibernate
-- ddl-auto=update in this application, but a database can still arrive here with these objects
-- already present -- any environment that booted this build once with Flyway disabled (dev
-- default, or an env toggling it) lets ddl-auto create them from the entities first. A plain
-- CREATE TABLE / ADD COLUMN then fails ("Table 'recovery_bays' already exists"), writing a
-- failed row that blocks every later migration on that database. Verified by rehearsal against
-- a real MySQL 8 schema built exactly that way.

CREATE TABLE IF NOT EXISTS recovery_bays (
  id BIGINT NOT NULL AUTO_INCREMENT,
  public_id VARCHAR(36) NOT NULL,
  hospital_id BIGINT NOT NULL,
  name VARCHAR(100) NOT NULL,
  is_active TINYINT(1) NOT NULL DEFAULT 1,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_recovery_bay_public_id (public_id),
  UNIQUE KEY uk_recovery_bay_name (hospital_id, name),
  CONSTRAINT FK_recovery_bay_hospital FOREIGN KEY (hospital_id)
    REFERENCES hospitals (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- MySQL 8 has no ADD COLUMN IF NOT EXISTS (that is MariaDB), so the guard is an
-- information_schema check driving a prepared statement -- the standard MySQL 8 idiom.
SET @recovery_bay_id_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'ot_recovery_episodes'
      AND COLUMN_NAME = 'recovery_bay_id');

SET @recovery_bay_id_ddl := IF(@recovery_bay_id_exists = 0,
    'ALTER TABLE ot_recovery_episodes ADD COLUMN recovery_bay_id BIGINT NULL',
    'DO 0');

PREPARE recovery_bay_id_stmt FROM @recovery_bay_id_ddl;
EXECUTE recovery_bay_id_stmt;
DEALLOCATE PREPARE recovery_bay_id_stmt;
