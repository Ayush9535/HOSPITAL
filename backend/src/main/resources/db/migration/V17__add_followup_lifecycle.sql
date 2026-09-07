-- Follow-up lifecycle on medical_records.
--
-- Additive only. Existing consultations keep their follow_up_date untouched and receive a NULL
-- status, which the application reads as OPEN: nobody recorded that those patients came back, so
-- nothing here may claim they did. No encounter, queue entry or bill is created by this
-- migration or by anything reading its results.
--
-- medical_records carries a NOT NULL hospital_id of its own, so the due query is scoped directly
-- and the index below is genuinely (tenant, date) rather than a scan.
--
-- Guarded in the same shape as V15, and for the same reason: a database bootstrapped from
-- setup/schema-full.sql already has these columns, and Flyway still baselines at V11 and walks
-- forward from V12. A bare ALTER fails there with "duplicate column", records a failed migration
-- and blocks every later one -- so a brand new deployment would never start.

SET @sql := (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.TABLES
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'medical_records') = 0
  OR (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'medical_records'
     AND COLUMN_NAME = 'follow_up_instructions') > 0,
  'SELECT 1',
  'ALTER TABLE medical_records ADD COLUMN follow_up_instructions VARCHAR(1000) NULL'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.TABLES
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'medical_records') = 0
  OR (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'medical_records'
     AND COLUMN_NAME = 'follow_up_status') > 0,
  'SELECT 1',
  'ALTER TABLE medical_records ADD COLUMN follow_up_status VARCHAR(20) NULL'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- The due/overdue/upcoming list reads one facility across a date window, repeatedly, all day.
SET @sql := (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.TABLES
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'medical_records') = 0
  OR (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'medical_records'
     AND INDEX_NAME = 'idx_medical_records_followup') > 0,
  'SELECT 1',
  'CREATE INDEX idx_medical_records_followup ON medical_records (hospital_id, follow_up_date)'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
