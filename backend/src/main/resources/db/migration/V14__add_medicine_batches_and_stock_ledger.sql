-- Batch-aware Hospital/Clinic medicine stock + an append-only movement ledger.
--
-- Idempotent by construction, like V12/V13: Flyway runs before Hibernate ddl-auto in this
-- application, but a database can still arrive with these objects already present if it booted
-- once with Flyway disabled and let ddl-auto create them from the entities. A plain CREATE TABLE
-- would then fail and record a failed row that blocks every later migration.

CREATE TABLE IF NOT EXISTS medicine_stock_batches (
  id BIGINT NOT NULL AUTO_INCREMENT,
  public_id VARCHAR(36) NOT NULL,
  hospital_id BIGINT NOT NULL,
  medicine_id BIGINT NOT NULL,
  batch_number VARCHAR(100) NOT NULL,
  expiry_date DATE NOT NULL,
  received_quantity INT NOT NULL DEFAULT 0,
  current_quantity INT NOT NULL DEFAULT 0,
  unit_price DOUBLE NULL,
  is_active TINYINT(1) NOT NULL DEFAULT 1,
  received_at DATETIME(6) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_medicine_stock_batch_public_id (public_id),
  UNIQUE KEY uk_medicine_batch (hospital_id, medicine_id, batch_number),
  KEY idx_medicine_batch_fefo (hospital_id, medicine_id, expiry_date),
  CONSTRAINT FK_medicine_batch_medicine FOREIGN KEY (medicine_id)
    REFERENCES medicines (id) ON DELETE CASCADE,
  -- Stock can never be negative at rest. The application decrements with a conditional UPDATE
  -- guarded on current_quantity >= qty; this is the backstop for anything that bypasses it.
  CONSTRAINT ck_medicine_batch_qty_non_negative CHECK (current_quantity >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS stock_movements (
  id BIGINT NOT NULL AUTO_INCREMENT,
  public_id VARCHAR(36) NOT NULL,
  hospital_id BIGINT NOT NULL,
  inventory_domain VARCHAR(20) NOT NULL,
  item_id BIGINT NOT NULL,
  batch_id BIGINT NULL,
  movement_type VARCHAR(30) NOT NULL,
  direction VARCHAR(3) NOT NULL,
  quantity INT NOT NULL,
  balance_after INT NULL,
  reference_type VARCHAR(40) NULL,
  reference_id BIGINT NULL,
  idempotency_key VARCHAR(100) NULL,
  performed_by_user_id BIGINT NULL,
  remarks VARCHAR(255) NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_movement_public_id (public_id),
  -- The actual idempotency guarantee: a replayed stock-posting command cannot insert twice.
  -- Keyed on the batch as well, because one FEFO consumption spans several lots and writes one
  -- row per lot under a single key; a replay still collides on every row it would rewrite.
  -- NULL keys are exempt (MySQL treats NULLs as distinct in a unique index), which is what we
  -- want for movements that carry no caller-supplied key.
  UNIQUE KEY uk_stock_movement_idempotency (hospital_id, idempotency_key, batch_id),
  KEY idx_stock_movement_item (hospital_id, inventory_domain, item_id, id),
  KEY idx_stock_movement_batch (hospital_id, batch_id, id),
  CONSTRAINT ck_stock_movement_qty_positive CHECK (quantity > 0),
  CONSTRAINT ck_stock_movement_direction CHECK (direction IN ('IN','OUT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Legacy conversion. Every existing medicines row carrying stock becomes one opening batch, so
-- no stock is lost and no expiry is invented: the row's own expiry_date is preserved, and a row
-- with no expiry gets a far-future sentinel rather than being treated as expired (which would
-- silently make real stock undispensable). Guarded on NOT EXISTS so re-running changes nothing.
INSERT INTO medicine_stock_batches
  (public_id, hospital_id, medicine_id, batch_number, expiry_date,
   received_quantity, current_quantity, unit_price, is_active, received_at, created_at)
SELECT UUID(), m.hospital_id, m.id, 'LEGACY-OPENING',
       COALESCE(m.expiry_date, '2099-12-31'),
       m.stock_quantity, m.stock_quantity, m.unit_price, 1, NOW(6), NOW(6)
FROM medicines m
WHERE m.hospital_id IS NOT NULL
  AND m.stock_quantity > 0
  AND NOT EXISTS (
    SELECT 1 FROM medicine_stock_batches b
    WHERE b.hospital_id = m.hospital_id AND b.medicine_id = m.id AND b.batch_number = 'LEGACY-OPENING');

-- Each converted batch gets its OPENING movement, so the ledger reconciles from the first day.
INSERT INTO stock_movements
  (public_id, hospital_id, inventory_domain, item_id, batch_id, movement_type, direction,
   quantity, balance_after, reference_type, idempotency_key, remarks, created_at)
SELECT UUID(), b.hospital_id, 'MEDICINE', b.medicine_id, b.id, 'OPENING', 'IN',
       b.current_quantity, b.current_quantity, 'LEGACY_MIGRATION',
       CONCAT('legacy-opening-', b.id), 'Opening balance carried over from medicines.stock_quantity', NOW(6)
FROM medicine_stock_batches b
WHERE b.batch_number = 'LEGACY-OPENING'
  AND NOT EXISTS (
    SELECT 1 FROM stock_movements s
    WHERE s.batch_id = b.id AND s.movement_type = 'OPENING');

-- The optional link from a clinical order to the facility's own medicine row.
--
-- Nullable, and it stays that way: prescribing a drug the facility does not stock is ordinary
-- practice, and an order with no link is UNLINKED, not an order for nothing. Nothing backfills
-- this column by matching names -- doing so would attach existing clinical orders to whichever
-- inventory row sorted first, which is exactly the guess this design refuses to make. Historical
-- orders therefore stay UNLINKED until someone reconciles them deliberately.
-- Guarded on the TABLE as well as the column. Checking only information_schema.COLUMNS cannot
-- tell "the column is already there" apart from "the table is not there at all": both count zero,
-- and the second one then runs an ALTER against a table that does not exist, fails with 1146, and
-- records a failed migration that blocks every later one. Facilities differ in which optional
-- tables they carry, so this is a real state, not a hypothetical.
SET @sql := (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.TABLES
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'prescriptions') = 0
  OR (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'prescriptions' AND COLUMN_NAME = 'medicine_id') > 0,
  'SELECT 1',
  'ALTER TABLE prescriptions ADD COLUMN medicine_id BIGINT NULL'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Supplier batch/lot number on a delivery, where the invoice carries one.
SET @sql := (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.TABLES
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'medicine_purchases') = 0
  OR (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'medicine_purchases' AND COLUMN_NAME = 'batch_number') > 0,
  'SELECT 1',
  'ALTER TABLE medicine_purchases ADD COLUMN batch_number VARCHAR(100) NULL'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
