-- One claimed OPD registration key, so a double-click or a retry produces one registration.
--
-- Registering a patient inserts the OPD, a queue entry and -- under "bill before OPD" -- a PAID
-- bill. None of that is repeatable, so a resubmitted request charged the patient twice and queued
-- them twice, with nothing able to detect it afterwards.
--
-- A table of its own rather than a column on opd, for two reasons. Tenancy: opd carries no
-- hospital_id, so a global unique key would let one facility's key suppress another's
-- registration. Nullability: a key column on the business entity must be nullable, and MySQL
-- treats NULLs in a unique index as distinct -- which silently disables the guarantee for exactly
-- the rows that have none. A row here exists only when a key was supplied, so both columns are
-- NOT NULL and the index always means something.
--
-- Idempotent by construction, like V12-V15: this application runs Flyway, Hibernate ddl-auto and
-- DatabaseMigrationRunner in sequence, so the table can already exist on a database that booted
-- once with Flyway disabled.
CREATE TABLE IF NOT EXISTS opd_idempotency (
  id BIGINT NOT NULL AUTO_INCREMENT,
  hospital_id BIGINT NOT NULL,
  idempotency_key VARCHAR(100) NOT NULL,
  opd_id BIGINT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_opd_idempotency (hospital_id, idempotency_key),
  KEY idx_opd_idempotency_opd (opd_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
