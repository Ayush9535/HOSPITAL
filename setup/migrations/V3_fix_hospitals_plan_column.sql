-- V3_fix_hospitals_plan_column.sql
-- Run once against the hospital_management database.
-- This is also applied automatically on startup via DatabaseMigrationRunner.java.

-- 1. The legacy `hospitals.plan VARCHAR(20) NOT NULL` column was replaced by
--    hospital_plan_subscriptions in V2. Hibernate no longer writes this column,
--    causing every INSERT to fail with "Column 'plan' cannot be null".
ALTER TABLE hospitals MODIFY COLUMN plan VARCHAR(20) DEFAULT NULL;

-- 2. Ensure hospital_settings.in_clinic exists (may be missing if ddl-auto=update
--    failed to add it while rows were present).
ALTER TABLE hospital_settings
  ADD COLUMN IF NOT EXISTS in_clinic TINYINT(1) NOT NULL DEFAULT 0;

-- 3. Ensure hospitals.is_single_doctor exists.
ALTER TABLE hospitals
  ADD COLUMN IF NOT EXISTS is_single_doctor TINYINT(1) NOT NULL DEFAULT 0;
