-- ============================================================
-- Migration: v2-module-system-migration.sql
-- Run this BEFORE or immediately AFTER deploying the new code.
-- Safe to run multiple times (uses INSERT ... WHERE NOT EXISTS).
-- ============================================================

-- ────────────────────────────────────────────────────────────
-- 1. APPOINTMENTS — add to every hospital that has OPD
-- ────────────────────────────────────────────────────────────
INSERT INTO hospital_modules (hospital_id, module_name)
SELECT hm.hospital_id, 'APPOINTMENTS'
FROM hospital_modules hm
WHERE hm.module_name = 'OPD'
  AND hm.hospital_id NOT IN (
      SELECT hospital_id FROM hospital_modules WHERE module_name = 'APPOINTMENTS'
  );

-- ────────────────────────────────────────────────────────────
-- 2. MEDICAL_INVENTORY — add to every hospital that has OPD
-- ────────────────────────────────────────────────────────────
INSERT INTO hospital_modules (hospital_id, module_name)
SELECT hm.hospital_id, 'MEDICAL_INVENTORY'
FROM hospital_modules hm
WHERE hm.module_name = 'OPD'
  AND hm.hospital_id NOT IN (
      SELECT hospital_id FROM hospital_modules WHERE module_name = 'MEDICAL_INVENTORY'
  );

-- ────────────────────────────────────────────────────────────
-- 3. HOSPITAL_INVENTORY — add to every hospital that has IPD
-- ────────────────────────────────────────────────────────────
INSERT INTO hospital_modules (hospital_id, module_name)
SELECT hm.hospital_id, 'HOSPITAL_INVENTORY'
FROM hospital_modules hm
WHERE hm.module_name = 'IPD'
  AND hm.hospital_id NOT IN (
      SELECT hospital_id FROM hospital_modules WHERE module_name = 'HOSPITAL_INVENTORY'
  );

-- ────────────────────────────────────────────────────────────
-- 4. REPORTS — add to every hospital (analytics tab is
--    available to all hospital/clinic/pharmacy types)
-- ────────────────────────────────────────────────────────────
INSERT INTO hospital_modules (hospital_id, module_name)
SELECT h.id, 'REPORTS'
FROM hospitals h
WHERE h.id NOT IN (
    SELECT hospital_id FROM hospital_modules WHERE module_name = 'REPORTS'
);

-- ────────────────────────────────────────────────────────────
-- 5. IN_CLINIC — add to hospitals where in_clinic = true
--    in their settings (preserves their existing toggle state)
-- ────────────────────────────────────────────────────────────
INSERT INTO hospital_modules (hospital_id, module_name)
SELECT hs.hospital_id, 'IN_CLINIC'
FROM hospital_settings hs
WHERE hs.in_clinic = true
  AND hs.hospital_id NOT IN (
      SELECT hospital_id FROM hospital_modules WHERE module_name = 'IN_CLINIC'
  );

-- ────────────────────────────────────────────────────────────
-- 6. Sync existing PLANS so re-assignment doesn't strip new modules
--    Adds each new module to every plan whose type would use it.
-- ────────────────────────────────────────────────────────────

-- APPOINTMENTS → plans that have OPD
INSERT INTO plan_modules (plan_id, module_name)
SELECT pm.plan_id, 'APPOINTMENTS'
FROM plan_modules pm
WHERE pm.module_name = 'OPD'
  AND pm.plan_id NOT IN (
      SELECT plan_id FROM plan_modules WHERE module_name = 'APPOINTMENTS'
  );

-- MEDICAL_INVENTORY → plans that have OPD
INSERT INTO plan_modules (plan_id, module_name)
SELECT pm.plan_id, 'MEDICAL_INVENTORY'
FROM plan_modules pm
WHERE pm.module_name = 'OPD'
  AND pm.plan_id NOT IN (
      SELECT plan_id FROM plan_modules WHERE module_name = 'MEDICAL_INVENTORY'
  );

-- HOSPITAL_INVENTORY → plans that have IPD
INSERT INTO plan_modules (plan_id, module_name)
SELECT pm.plan_id, 'HOSPITAL_INVENTORY'
FROM plan_modules pm
WHERE pm.module_name = 'IPD'
  AND pm.plan_id NOT IN (
      SELECT plan_id FROM plan_modules WHERE module_name = 'HOSPITAL_INVENTORY'
  );

-- REPORTS → all plans
INSERT INTO plan_modules (plan_id, module_name)
SELECT p.id, 'REPORTS'
FROM plans p
WHERE p.id NOT IN (
    SELECT plan_id FROM plan_modules WHERE module_name = 'REPORTS'
);
