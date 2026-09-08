-- Migration V11: Add Indexes for Performance Optimization
-- This script adds indexes to support multi-tenant filtering (hospital_id),
-- foreign key joins, and common search/filter criteria.

-- 1. Index hospital_id on all multi-tenant tables (if not already indexed)
CREATE INDEX idx_appointments_hospital_id ON appointments(hospital_id);
CREATE INDEX idx_audit_logs_hospital_id ON audit_logs(hospital_id);
CREATE INDEX idx_beds_hospital_id ON beds(hospital_id);
CREATE INDEX idx_billing_hospital_id ON billing(hospital_id);
CREATE INDEX idx_billing_items_hospital_id ON billing_items(hospital_id);
CREATE INDEX idx_billing_payments_hospital_id ON billing_payments(hospital_id);
CREATE INDEX idx_doctors_hospital_id ON doctors(hospital_id);
CREATE INDEX idx_ipd_admission_hospital_id ON ipd_admission(hospital_id);
CREATE INDEX idx_lab_orders_hospital_id ON lab_orders(hospital_id);
CREATE INDEX idx_manufacturers_hospital_id ON manufacturers(hospital_id);
CREATE INDEX idx_medical_records_hospital_id ON medical_records(hospital_id);
CREATE INDEX idx_medicine_batches_hospital_id ON medicine_batches(hospital_id);
CREATE INDEX idx_medicine_categories_hospital_id ON medicine_categories(hospital_id);
CREATE INDEX idx_medicine_master_hospital_id ON medicine_master(hospital_id);
CREATE INDEX idx_medicines_hospital_id ON medicines(hospital_id);
CREATE INDEX idx_patients_hospital_id ON patients(hospital_id);
CREATE INDEX idx_pharmacy_sales_hospital_id ON pharmacy_sales(hospital_id);
CREATE INDEX idx_prescriptions_hospital_id ON prescriptions(hospital_id);
CREATE INDEX idx_purchase_invoices_hospital_id ON purchase_invoices(hospital_id);
CREATE INDEX idx_sale_returns_hospital_id ON sale_returns(hospital_id);
CREATE INDEX idx_storage_locations_hospital_id ON storage_locations(hospital_id);
CREATE INDEX idx_suppliers_hospital_id ON suppliers(hospital_id);
CREATE INDEX idx_users_hospital_id ON users(hospital_id);
CREATE INDEX idx_wards_hospital_id ON wards(hospital_id);

-- 2. Create Composite Indexes for Common Query Patterns
-- Today's appointments / Overview
CREATE INDEX idx_appointments_date_active ON appointments(hospital_id, appointment_date, is_active);

-- Doctor's appointments
CREATE INDEX idx_appointments_doctor_active ON appointments(hospital_id, doctor_id, is_active);

-- Patient listing sorted by creation date
CREATE INDEX idx_patients_active_created ON patients(hospital_id, is_active, created_at DESC);

-- Latest bill retrieval for N+1 optimization
CREATE INDEX idx_billing_patient_created ON billing(patient_id, created_at DESC);

-- Medical Record patient history sorting
CREATE INDEX idx_medical_records_patient_created ON medical_records(patient_id, created_at DESC);

-- User role queries (e.g. findByHospitalIdAndRole)
CREATE INDEX idx_users_hospital_role ON users(hospital_id, role);
