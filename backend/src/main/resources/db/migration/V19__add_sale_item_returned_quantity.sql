-- How much of a pharmacy sale line has already been returned and refunded.
--
-- Without it, each return request was checked against the originally sold quantity and nothing
-- recorded what had already gone back: selling ten units and returning ten twice refunded the
-- same ten units twice. This column is the authoritative remaining-returnable figure.
--
-- Existing rows get 0. Returns processed before this migration cannot be reconstructed — the
-- previous implementation persisted no returned quantity anywhere, and the RETURN ledger rows it
-- wrote record zero stock movement rather than a refunded amount. Inferring history from them
-- would be guessing at money, so historical sales become fully returnable again. See the
-- checkpoint report for the rollout containment this implies.
--
-- Guarded like V15/V17/V18: a database bootstrapped from setup/schema-full.sql already has the
-- column, and Flyway still baselines at V11 and walks forward over it.

SET @sql := (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.TABLES
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pharmacy_sale_items') = 0
  OR (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pharmacy_sale_items'
     AND COLUMN_NAME = 'returned_quantity') > 0,
  'SELECT 1',
  'ALTER TABLE pharmacy_sale_items ADD COLUMN returned_quantity DECIMAL(10,2) NOT NULL DEFAULT 0'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
