-- Clinical documents a patient brought with them: an outside blood report, a scan from another
-- hospital, a photographed prescription.
--
-- The file itself is not here. This table holds metadata and an opaque storage key; the bytes sit
-- in a private directory the web server does not publish, and the only route to them is the
-- authenticated endpoint, which resolves the row tenant-scoped first. There is deliberately no
-- column that could hold a public URL.
--
-- Owned by hospital and patient. opd_id and ipd_admission_id are optional context, because a
-- report the patient carried in may predate anything this system knows about them.
--
-- Guarded like V15/V17/V18/V19: a database bootstrapped from setup/schema-full.sql already has
-- this table, and Flyway still baselines at V11 and walks forward over it.

SET @sql := (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.TABLES
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'patient_documents') > 0,
  'SELECT 1',
  'CREATE TABLE patient_documents (
     id BIGINT NOT NULL AUTO_INCREMENT,
     public_id VARCHAR(64) NOT NULL,
     hospital_id BIGINT NOT NULL,
     patient_id BIGINT NOT NULL,
     opd_id BIGINT NULL,
     ipd_admission_id BIGINT NULL,
     document_type VARCHAR(40) NOT NULL,
     title VARCHAR(200) NOT NULL,
     report_date DATE NULL,
     source VARCHAR(200) NULL,
     notes VARCHAR(1000) NULL,
     original_file_name VARCHAR(255) NOT NULL,
     mime_type VARCHAR(100) NOT NULL,
     file_size_bytes BIGINT NOT NULL,
     storage_key VARCHAR(255) NOT NULL,
     uploaded_by_user_id BIGINT NULL,
     is_active TINYINT(1) NOT NULL DEFAULT 1,
     archived_by_user_id BIGINT NULL,
     archived_at DATETIME(6) NULL,
     archive_reason VARCHAR(500) NULL,
     created_at DATETIME(6) NOT NULL,
     PRIMARY KEY (id),
     UNIQUE KEY uk_patient_documents_public_id (public_id),
     KEY idx_patient_documents_patient (hospital_id, patient_id, report_date)
   ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
