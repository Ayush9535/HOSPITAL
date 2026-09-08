-- Food timing on a medication order: BEFORE_FOOD, AFTER_FOOD, WITH_FOOD, NOT_SPECIFIED.
--
-- Its own column rather than a vocabulary squeezed into prescriptions.instructions. That field is
-- general -- "take with plenty of water", "crush before giving" -- and adopting a four-value
-- dropdown there would have removed the ability to record any of it.
--
-- V15, not part of V14: V14 is the inventory wave and has already been rehearsed against real
-- data. Editing an applied migration to slip an unrelated column into it would invalidate its
-- checksum for every environment that has already run it, which is exactly the failure mode this
-- project has been through once.
--
-- Deliberately no backfill. An existing order's food timing is unknown, and reading it out of the
-- old free-text instructions would be guessing at a medication instruction. Historical rows keep
-- their instructions and simply have no food timing.
--
-- Guarded on the table as well as the column: checking information_schema.COLUMNS alone cannot
-- tell "already added" from "table absent" -- both count zero -- and the second case would run an
-- ALTER against a missing table, fail, and record a failed migration that blocks every later one.
SET @sql := (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.TABLES
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'prescriptions') = 0
  OR (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'prescriptions' AND COLUMN_NAME = 'food_timing') > 0,
  'SELECT 1',
  'ALTER TABLE prescriptions ADD COLUMN food_timing VARCHAR(20) NULL'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
