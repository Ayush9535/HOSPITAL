-- Links a follow-up to the visit created when the patient returned.
--
-- actioned_opd_id is the invariant, not merely a record of one. A follow-up is a single row, so
-- it can hold one actioned OPD and no more, and the claim is taken with a conditional UPDATE
-- that only matches while this column is still NULL -- two people pressing "Patient Arrived" at
-- the same moment cannot both succeed. The unique index closes the other direction: one OPD
-- cannot be claimed by two different follow-ups.
--
-- Unique rather than a plain index because NULLs do not collide in MySQL, so every follow-up
-- that has not been actioned is exempt while every actioned one is bound.
--
-- Guarded like V15 and V17: a database bootstrapped from setup/schema-full.sql already has these,
-- and Flyway still walks V12 forward on it.

SET @sql := (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.TABLES
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'medical_records') = 0
  OR (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'medical_records'
     AND COLUMN_NAME = 'actioned_opd_id') > 0,
  'SELECT 1',
  'ALTER TABLE medical_records ADD COLUMN actioned_opd_id BIGINT NULL'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.TABLES
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'medical_records') = 0
  OR (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'medical_records'
     AND COLUMN_NAME = 'actioned_by_user_id') > 0,
  'SELECT 1',
  'ALTER TABLE medical_records ADD COLUMN actioned_by_user_id BIGINT NULL'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.TABLES
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'medical_records') = 0
  OR (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'medical_records'
     AND COLUMN_NAME = 'actioned_at') > 0,
  'SELECT 1',
  'ALTER TABLE medical_records ADD COLUMN actioned_at DATETIME(6) NULL'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.TABLES
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'medical_records') = 0
  OR (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'medical_records'
     AND INDEX_NAME = 'uk_medical_records_actioned_opd') > 0,
  'SELECT 1',
  'CREATE UNIQUE INDEX uk_medical_records_actioned_opd ON medical_records (actioned_opd_id)'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
