-- V2_plan_subscription_type.sql
-- Run once against the hospital_management database

-- 1. Add type column to hospitals (default HOSPITAL for all existing rows)
ALTER TABLE hospitals
  ADD COLUMN type VARCHAR(20) NOT NULL DEFAULT 'HOSPITAL',
  ADD COLUMN subscription_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';

-- 2. Create plans table
CREATE TABLE plans (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  public_id     VARCHAR(255) NOT NULL,
  name          VARCHAR(100) NOT NULL,
  type          VARCHAR(20)  NOT NULL,
  monthly_price DECIMAL(10,2) NOT NULL DEFAULT 0.00,
  yearly_price  DECIMAL(10,2) NOT NULL DEFAULT 0.00,
  in_clinic     BIT(1)       NOT NULL DEFAULT 0,
  is_active     BIT(1)       NOT NULL DEFAULT 1,
  created_at    DATETIME(6)  NOT NULL,
  updated_at    DATETIME(6)  DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_plan_public_id (public_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3. Create plan_modules join table (modules enforced at runtime)
CREATE TABLE plan_modules (
  plan_id     BIGINT       NOT NULL,
  module_name VARCHAR(100) NOT NULL,
  FOREIGN KEY (plan_id) REFERENCES plans(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 4. Create plan_features join table (display labels only, not enforced)
CREATE TABLE plan_features (
  plan_id      BIGINT       NOT NULL,
  feature_name VARCHAR(200) NOT NULL,
  FOREIGN KEY (plan_id) REFERENCES plans(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 5. Create hospital_plan_subscriptions table
CREATE TABLE hospital_plan_subscriptions (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  hospital_id    BIGINT       NOT NULL,
  plan_id        BIGINT       NOT NULL,
  billing_period VARCHAR(20)  NOT NULL,
  assigned_at    DATETIME(6)  NOT NULL,
  expires_at     DATETIME(6)  NOT NULL,
  assigned_by    BIGINT       DEFAULT NULL,
  is_current     BIT(1)       NOT NULL DEFAULT 1,
  PRIMARY KEY (id),
  FOREIGN KEY (hospital_id) REFERENCES hospitals(id),
  FOREIGN KEY (plan_id)     REFERENCES plans(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- NOTE: The existing `plan` VARCHAR(20) column on hospitals is intentionally left in place.
-- It is no longer written by application code; subscription is now via hospital_plan_subscriptions.
