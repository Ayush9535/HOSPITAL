package com.hms.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Runs idempotent schema patches on every startup.
 *
 * ddl-auto=update can add columns but never removes them or changes nullability.
 * This runner bridges that gap for the few historical mismatches that need fixing.
 * Each patch is wrapped individually so one failure does not block the others.
 *
 * The statements are MySQL-specific. Set hms.migrations.enabled=false to skip them
 * (the test profile does this, since it boots against H2 with a Hibernate-built schema).
 */
@Component
@ConditionalOnProperty(name = "hms.migrations.enabled", havingValue = "true", matchIfMissing = true)
public class DatabaseMigrationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseMigrationRunner.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void runMigrations() {
        fixHospitalsPlanColumn();
        ensureHospitalSettingsInClinic();
        ensureHospitalsIsSingleDoctor();
        ensureMissingIndexes();
        simplifyMedicineListTable();
        migratePatientAgeToDateOfBirth(); // NEW
        ensureConsultationNotePresetsTable(); // NEW
        ensurePrescriptionPresetTables(); // NEW
        ensurePresetDoctorIdColumns(); // NEW — per-doctor preset isolation
        ensureInventoryItemHasOwnStockColumn(); // NEW
        ensureInventoryServicesTables(); // NEW
        ensureHospitalSettingsBarcodeEnabled(); // NEW — pharmacy barcode toggle
        ensurePlanMultiOutletColumns(); // NEW — pharmacy multi-outlet plan support
        dropLegacyNotNullNoDefaultColumns(); // NEW — remove NOT-NULL-no-default landmines
        ensureMedicineMasterManufacturerName(); // NEW — free-text manufacturer from purchase
        ensurePharmacyBranchSupport(); // NEW — Multi Pharmacy branches
        ensurePharmacyDataBranchColumns(); // NEW — branch_id on pharmacy data tables
        ensureAuditLogsBranchColumn(); // NEW — branch_id on audit_logs for pharmacy audit trails
        ensureNurseProfilesTable(); // NEW — Nurse module (Phase 1)
        ensureNurseProfileWardColumn(); // NEW — Nurse ward assignment
        ensureNurseProfileShiftColumn(); // NEW — Nurse shift availability
        ensureAdmissionConfirmedColumns(); // NEW — nurse-confirmed admission
        ensureAdmittedByColumn(); // NEW — admitting receptionist
        ensureAdmissionFormsTable(); // NEW — nurse admission form
        ensureInitialAssessmentsTable(); // NEW — nurse initial assessment
        ensureInitialAssessmentPainColumn(); // NEW — pain score
        ensureVulnerabilityAssessmentsTable(); // NEW — nurse vulnerability assessment
        ensureSugarChartEntriesTable(); // NEW — nurse sugar chart
        ensurePatientNurseAssignmentsTable(); // NEW — Nurse module (Phase 1)
        ensureVitalsRecordsTable(); // NEW — Nurse module (Phase 1)
        ensureNursingNotesTable(); // NEW — Nurse module (Phase 1)
        ensureMedicationAdministrationsTable(); // NEW — Nurse module (Phase 1)
        ensureManualTasksTable(); // NEW — Nurse module (Phase 1, M7)
        ensureNotificationsTable(); // NEW — Nurse module (Phase 1, M8)
        ensureOpdAdmitRecommendedColumn(); // NEW
        ensureSurgeriesTable(); // NEW — OT module (Phase 2)
        ensureSurgerySurgeonNameColumn(); // NEW — OT "Other" operator name
        ensureSurgeryAnaesthetistNameColumn(); // NEW — OT optional anaesthetist
        ensureSurgeryFormsTable(); // NEW — OT/NABH surgery forms store
        ensureSurgeryDayCareColumns(); // OT Phase 1 — surgery is its own aggregate
        ensureSurgeryFormProcedureScope(); // OT Phase 1 — one signed form per PROCEDURE
        ensureRolePermissionsTable(); // OT Phase 2 — authorization decoupled from role checks
        ensureSurgeryStateTransitionsTable(); // OT Phase 3 — append-only status audit
        ensureSurgeryWaitlistColumns(); // OT Phase 3 — the waiting list is a query, not a status
        ensureSurgeryLifecycleVersionColumn(); // OT 4.6D-A — stale schedule command detection
        ensureOtRoomsTable(); // OT Phase 4 — a theatre is a resource, not a ward named "OT"
        ensureSurgeryRoomColumns(); // OT Phase 4 — interval booking
        ensureOtWorkflowPoliciesTable(); // OT Phase 5 — hospital variation is configuration
        ensureSurgeryTeamTables(); // OT Phase 6 — who was in the room
        ensureSurgeryExecutionTables(); // OT Phase 7 — WHO checklist, milestones, operative note
        ensureSurgeryPreOpSafetyTables(); // OT 4.6A — anaesthesia decisions and emergency bypasses
        ensureRecoveryTables(); // OT Phase 8 — PACU recovery (never a case state)
        ensureOtRoomOccupancyTable(); // OT Phase 9 — utilisation & turnover from real spans
        backfillSupportTicketHospitalType(); // tickets created before hospital_type was set
        ensureNursingNoteSurgeryIdColumn(); // NEW — OT notes link
        ensureNurseProfilePhaseAColumns(); // NEW — Nursing Mgmt Phase A
        ensureWardInchargeColumn();
        ensureSeparateNurseLoginColumn();
        ensureOtInchargeEnabledColumn();
        ensurePerformedByNurseIdColumns(); // NEW — Nursing Mgmt Phase A3, "Performed By"
        ensureShiftTemplatesTable(); // NEW — Nursing Mgmt Phase B1
        ensureAppointmentSlotsTable(); // NEW — Nursing Mgmt Phase B1
        ensureNurseShiftSchedulesTable(); // NEW — Nursing Mgmt Phase B2
        ensureNurseAttendanceTable(); // NEW — Nursing Mgmt Phase D
        ensureNurseWardAssignmentsTable(); // NEW — Nursing Mgmt Phase F
        ensureNurseSubstitutionsTable(); // NEW — Nursing Mgmt Phase F
        ensureCalendarEventsTable();
        ensureHospitalFormAccessTable();
        // Orders (drugs / IV fluids) captured alongside a nursing note; printed beside it.
        addColumnIfMissing("nursing_notes", "orders", "TEXT NULL");
        // Height (cm) captured with the OPD vitals, alongside weight.
        addColumnIfMissing("opd", "height", "DOUBLE NULL");
        // Per-hospital OPD vitals config + values of hospital-defined custom vitals.
        ensureHospitalVitalsTable();
        addColumnIfMissing("opd", "custom_vitals", "TEXT NULL");
        ensureBedStatusAuditsTable(); // NEW — Nursing Mgmt Phase C1
        makeInventoryItemHospitalIdNullable();

        // In-Clinic presets: bundles of stock medicines administered in the clinic. They reuse
        // the prescription-preset tables, split by preset_type (existing rows are PRESCRIPTION).
        // The items carry a stock link + quantity so applying a preset still deducts stock.
        addColumnIfMissing("prescription_presets", "preset_type",
                "VARCHAR(20) NOT NULL DEFAULT 'PRESCRIPTION'");
        addColumnIfMissing("prescription_preset_items", "medicine_id", "BIGINT NULL");
        addColumnIfMissing("prescription_preset_items", "quantity", "INT NULL");
        backfillPrescriptionPresetType();

        // Print Settings (pages in the consultation-complete print) + bill payment timing.
        addColumnIfMissing("hospital_settings", "print_case_paper", "TINYINT(1) NOT NULL DEFAULT 1");
        addColumnIfMissing("hospital_settings", "print_bill", "TINYINT(1) NOT NULL DEFAULT 1");
        addColumnIfMissing("hospital_settings", "print_prescription", "TINYINT(1) NOT NULL DEFAULT 1");
        addColumnIfMissing("hospital_settings", "print_in_clinic", "TINYINT(1) NOT NULL DEFAULT 1");
        addColumnIfMissing("hospital_settings", "bill_payment_timing", "VARCHAR(10) NOT NULL DEFAULT 'LAST'");
        backfillPrintPaymentDefaults();
        widenVitalsDecimalColumns();
        backfillStrandedOpdStatuses();
        reconcileOtPermissionOrphanDefaults(); // OT-P0A — v2 defaults for already-configured hospitals
        ensureRecoveryBaysTable(); // OT-P0B — recovery admission needs a tenant-owned location
        addColumnIfMissing("ot_recovery_episodes", "recovery_bay_id", "BIGINT NULL");
        ensureMedicineStockTables(); // INV-2/3 — batch-aware medicine stock + append-only ledger
        addColumnIfMissing("prescriptions", "medicine_id", "BIGINT NULL");
        addColumnIfMissing("medicine_purchases", "batch_number", "VARCHAR(100) NULL");
        addColumnIfMissing("prescriptions", "food_timing", "VARCHAR(20) NULL"); // V15
        ensureOpdIdempotencyTable(); // V16 — one OPD registration per logical submission

        // V17 — follow-up lifecycle. A NULL status reads as OPEN, so historical consultations
        // stay actionable and nothing is rewritten.
        addColumnIfMissing("medical_records", "follow_up_instructions", "VARCHAR(1000) NULL");
        addColumnIfMissing("medical_records", "follow_up_status", "VARCHAR(20) NULL");
        addCompositeIndexIfMissing("medical_records", "idx_medical_records_followup",
                "`hospital_id`, `follow_up_date`");

        // V18 — the arrival link. The unique index is the invariant that stops one follow-up
        // becoming two visits; NULLs do not collide, so unactioned follow-ups are unaffected.
        addColumnIfMissing("medical_records", "actioned_opd_id", "BIGINT NULL");
        addColumnIfMissing("medical_records", "actioned_by_user_id", "BIGINT NULL");
        addColumnIfMissing("medical_records", "actioned_at", "DATETIME(6) NULL");
        addUniqueIndexIfMissing("medical_records", "uk_medical_records_actioned_opd",
                "`actioned_opd_id`");

        // V19 — what a pharmacy sale line has already had returned, so the same sold units
        // cannot be refunded twice. Existing rows start at zero; see the migration.
        addColumnIfMissing("pharmacy_sale_items", "returned_quantity",
                "DECIMAL(10,2) NOT NULL DEFAULT 0");

        ensurePatientDocumentsTable(); // V20 -- documents patients bring with them

        // ICU Phase 2 — ward classification (CareUnitRegistry). GENERAL by default, so every
        // existing ward keeps behaving exactly as before and no backfill is needed.
        addColumnIfMissing("wards", "unit_type", "VARCHAR(20) NOT NULL DEFAULT 'GENERAL'");
        backfillWardUnitType();
        ensureIcuAlertThresholdTable();// ICU Phase 9
        ensureIcuSeverityScoreTables();// ICU Phase 8
        ensureIcuVentilatorTables();   // ICU Phase 7
        ensureIcuInfusionTables();     // ICU Phase 6
        ensureIcuIoEntryTable();       // ICU Phase 5
        ensureVitalsIcuColumns();      // ICU Phase 4
        ensureIcuStayTable();          // ICU Phase 3
        backfillIcuStaysForCurrentOccupants();
    }

    /**
     * Repairs OPD cases stranded by the old rule that only *payment* moved an OPD to COMPLETED.
     *
     * Two ways a consulted case got stuck:
     *  - CONSULTED: the doctor finished, but the bill was never collected through the pay-at-the-end
     *    path (e.g. the hospital switched to Before-OPD, where the bill is already PAID at entry, so
     *    nothing ever ran the payment step). CONSULTED is now a legacy value — nothing writes it.
     *  - QUEUED with a medical record: the consultation was recorded but the status never moved, so
     *    the case still shows "In Queue" and keeps its place in the doctor's queue.
     *
     * A consultation is now what completes an OPD (DoctorService), so both shapes are simply history.
     * IN_IPD is left alone — that case moved on to an admission, it did not end at the OPD desk.
     * A QUEUED case with no medical record is genuinely still waiting and is left untouched.
     * Idempotent: matches nothing once repaired.
     */
    private void backfillStrandedOpdStatuses() {
        try {
            int consulted = jdbcTemplate.update(
                    "UPDATE opd SET status = 'COMPLETED' WHERE status = 'CONSULTED'");

            int queuedButConsulted = jdbcTemplate.update(
                    "UPDATE opd o SET o.status = 'COMPLETED' WHERE o.status = 'QUEUED' " +
                    "AND EXISTS (SELECT 1 FROM medical_records m WHERE m.opd_id = o.id)");

            // A completed case must not keep holding a slot in the doctor's live queue.
            int queueRows = jdbcTemplate.update(
                    "DELETE q FROM queue_entry q JOIN opd o ON o.id = q.opd_id " +
                    "WHERE o.status IN ('COMPLETED', 'IN_IPD')");

            if (consulted + queuedButConsulted + queueRows > 0) {
                log.info("DB migration applied: completed {} CONSULTED + {} stranded QUEUED OPD case(s), " +
                        "cleared {} stale queue entr(ies)", consulted, queuedButConsulted, queueRows);
            }
        } catch (Exception e) {
            log.warn("Could not backfill stranded OPD statuses: {}", e.getMessage());
        }
    }

    /**
     * Vitals no longer have an upper limit (only >= 0), but weight/temperature were still
     * DECIMAL(5,2)/(4,1) — sized for the removed caps (weight <= 500, temp <= 113). Anything
     * bigger failed the insert with "Data truncation: Out of range value". Hibernate's
     * ddl-auto=update never widens an existing column, so widen them here. Idempotent: only
     * alters when the current precision is still too small.
     */
    private void widenVitalsDecimalColumns() {
        widenDecimal("vitals_records", "weight", "DECIMAL(12,2) NULL", 12);
        widenDecimal("vitals_records", "temperature", "DECIMAL(12,1) NULL", 12);
    }

    private void widenDecimal(String table, String column, String newType, int wantPrecision) {
        try {
            Integer precision = jdbcTemplate.queryForObject(
                    "SELECT NUMERIC_PRECISION FROM information_schema.COLUMNS " +
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                    Integer.class, table, column);
            if (precision != null && precision < wantPrecision) {
                jdbcTemplate.execute("ALTER TABLE " + table + " MODIFY COLUMN " + column + " " + newType);
                log.info("DB migration applied: widened {}.{} to {}", table, column, newType);
            }
        } catch (Exception e) {
            log.warn("Could not widen {}.{}: {}", table, column, e.getMessage());
        }
    }

    /**
     * Hibernate's ddl-auto=update can add the print/payment columns as NOT NULL *without* the
     * DEFAULT, so MySQL backfills existing rows with 0 / '' — which would turn every existing
     * hospital's consultation print off and leave an invalid payment timing. Repair such rows
     * to the intended defaults (all pages on, LAST). Idempotent: only touches rows that were
     * never set intentionally — a brand-new install writes 1s directly and skips this.
     */
    private void backfillPrintPaymentDefaults() {
        try {
            // An invalid bill_payment_timing ('' / NULL) is the definitive signature of a row the
            // Hibernate race backfilled — a JPA-managed row always has 'LAST'/'FIRST'. Fix print
            // pages AND timing together, keyed on that signature, so this never re-runs once the
            // deploy boot repairs the row (and never overrides an intentional all-pages-off later).
            int fixed = jdbcTemplate.update(
                    "UPDATE hospital_settings SET print_case_paper = 1, print_bill = 1, " +
                    "print_prescription = 1, print_in_clinic = 1, bill_payment_timing = 'LAST' " +
                    "WHERE bill_payment_timing IS NULL OR bill_payment_timing NOT IN ('FIRST','LAST')");
            if (fixed > 0) {
                log.info("DB migration applied: backfilled print/payment defaults on {} settings row(s)", fixed);
            }
        } catch (Exception e) {
            log.warn("Could not backfill hospital_settings print/payment defaults: {}", e.getMessage());
        }
    }

    /**
     * Existing presets predate preset_type and must stay in the PRESCRIPTION list. Hibernate's
     * ddl-auto=update can win the race and add the NOT NULL column itself *without* the default,
     * in which case MySQL backfills old rows with '' — which matches neither preset type, so
     * every existing prescription preset would silently vanish from the doctor's list. Repair
     * any such row. Idempotent.
     */
    private void backfillPrescriptionPresetType() {
        try {
            int fixed = jdbcTemplate.update(
                    "UPDATE prescription_presets SET preset_type = 'PRESCRIPTION' " +
                    "WHERE preset_type IS NULL OR preset_type = ''");
            if (fixed > 0) {
                log.info("DB migration applied: backfilled preset_type=PRESCRIPTION on {} preset(s)", fixed);
            }
        } catch (Exception e) {
            log.warn("Could not backfill prescription_presets.preset_type: {}", e.getMessage());
        }
    }

    private void ensureHospitalVitalsTable() {
        try {
            Integer exists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'hospital_vitals'",
                    Integer.class);
            if (exists == null || exists == 0) {
                jdbcTemplate.execute(
                        "CREATE TABLE hospital_vitals (" +
                        "  id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                        "  public_id VARCHAR(64) NOT NULL UNIQUE," +
                        "  hospital_id BIGINT NOT NULL," +
                        "  vital_key VARCHAR(60) NOT NULL," +
                        "  label VARCHAR(60) NOT NULL," +
                        "  unit VARCHAR(20)," +
                        "  enabled TINYINT(1) NOT NULL DEFAULT 1," +
                        "  is_custom TINYINT(1) NOT NULL DEFAULT 0," +
                        "  sort_order INT DEFAULT 0," +
                        "  created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP," +
                        "  UNIQUE KEY uq_hosp_vital (hospital_id, vital_key)," +
                        "  CONSTRAINT fk_hosp_vital_hospital FOREIGN KEY (hospital_id) REFERENCES hospitals(id) ON DELETE CASCADE" +
                        ")");
                log.info("Created hospital_vitals table");
            }
        } catch (Exception e) {
            log.warn("ensureHospitalVitalsTable failed: {}", e.getMessage());
        }
    }

    private void ensureHospitalFormAccessTable() {
        try {
            Integer exists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'hospital_form_access'",
                    Integer.class);
            if (exists == null || exists == 0) {
                jdbcTemplate.execute(
                        "CREATE TABLE hospital_form_access (" +
                        "  id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                        "  hospital_id BIGINT NOT NULL," +
                        "  form_key VARCHAR(60) NOT NULL," +
                        "  enabled TINYINT(1) NOT NULL DEFAULT 1," +
                        "  access_role VARCHAR(10) NOT NULL DEFAULT 'BOTH'," +
                        "  updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP," +
                        "  UNIQUE KEY uq_form_access_hosp_key (hospital_id, form_key)," +
                        "  CONSTRAINT fk_form_access_hospital FOREIGN KEY (hospital_id) REFERENCES hospitals(id) ON DELETE CASCADE" +
                        ")");
                log.info("Created hospital_form_access table");
            }
        } catch (Exception e) {
            log.warn("ensureHospitalFormAccessTable failed: {}", e.getMessage());
        }
    }

    private void ensureCalendarEventsTable() {
        try {
            Integer exists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'calendar_events'",
                    Integer.class);
            if (exists == null || exists == 0) {
                jdbcTemplate.execute(
                        "CREATE TABLE calendar_events (" +
                        "  id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                        "  public_id VARCHAR(64) NOT NULL UNIQUE," +
                        "  hospital_id BIGINT NOT NULL," +
                        "  title VARCHAR(160) NOT NULL," +
                        "  event_type VARCHAR(20) NOT NULL," +
                        "  from_date DATE NOT NULL," +
                        "  to_date DATE NOT NULL," +
                        "  description VARCHAR(500)," +
                        "  created_by_user_id BIGINT," +
                        "  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                        "  INDEX idx_calevent_hosp_dates (hospital_id, from_date, to_date)," +
                        "  CONSTRAINT fk_calevent_hospital FOREIGN KEY (hospital_id) REFERENCES hospitals(id) ON DELETE CASCADE" +
                        ")");
                log.info("Created calendar_events table");
            }
        } catch (Exception e) {
            log.warn("ensureCalendarEventsTable failed: {}", e.getMessage());
        }
    }

    /**
     * Creates the bed_status_audits table if absent (Nursing Mgmt Phase C1).
     * Idempotent; mirrors setup/schema-full.sql.
     */
    private void ensureBedStatusAuditsTable() {
        try {
            Integer c = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bed_status_audits'", Integer.class);
            if (c != null && c == 0) {
                jdbcTemplate.execute("CREATE TABLE bed_status_audits (" +
                    "id BIGINT NOT NULL AUTO_INCREMENT, public_id VARCHAR(255) NOT NULL, hospital_id BIGINT NOT NULL," +
                    "bed_id BIGINT NOT NULL, ward_id BIGINT, previous_status VARCHAR(20), new_status VARCHAR(20) NOT NULL," +
                    "changed_by_user_id BIGINT, remarks VARCHAR(255), changed_at DATETIME(6) NOT NULL," +
                    "PRIMARY KEY (id), UNIQUE KEY UK_bsa_public (public_id)," +
                    "KEY idx_bsa_bed_time (bed_id, changed_at), KEY idx_bsa_hospital (hospital_id)," +
                    "FOREIGN KEY (hospital_id) REFERENCES hospitals(id) ON DELETE CASCADE)");
                log.info("DB migration applied: bed_status_audits table created");
            }
        } catch (Exception e) { log.warn("DB migration skipped (bed_status_audits): {}", e.getMessage()); }
    }

    private void makeInventoryItemHospitalIdNullable() {
        try {
            // Check if column is currently NOT NULL
            Integer isNotNull = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'inventory_items' " +
                "AND COLUMN_NAME = 'hospital_id' AND IS_NULLABLE = 'NO'",
                Integer.class
            );
            if (isNotNull != null && isNotNull > 0) {
                jdbcTemplate.execute("ALTER TABLE inventory_items MODIFY COLUMN hospital_id BIGINT NULL");
                log.info("DB migration applied: hospital_id column in inventory_items made NULLABLE");
            }
        } catch (Exception e) {
            log.warn("DB migration failed (make inventory_items.hospital_id nullable): {}", e.getMessage());
        }
    }


    /**
     * Creates the nurse_shift_schedules table if absent (Nursing Mgmt Phase B2).
     * Idempotent; mirrors setup/schema-full.sql.
     */
    private void ensureNurseShiftSchedulesTable() {
        try {
            Integer c = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nurse_shift_schedules'", Integer.class);
            if (c != null && c == 0) {
                jdbcTemplate.execute("CREATE TABLE nurse_shift_schedules (" +
                    "id BIGINT NOT NULL AUTO_INCREMENT, public_id VARCHAR(255) NOT NULL, hospital_id BIGINT NOT NULL," +
                    "nurse_profile_id BIGINT NOT NULL, ward_id BIGINT, shift_date DATE NOT NULL, shift_template_id BIGINT NOT NULL," +
                    "start_time TIME NOT NULL, end_time TIME NOT NULL, created_by_user_id BIGINT, created_at DATETIME(6) NOT NULL," +
                    "PRIMARY KEY (id), UNIQUE KEY UK_nss_public (public_id), UNIQUE KEY UK_nss_nurse_date (nurse_profile_id, shift_date)," +
                    "KEY idx_nss_hospital_date (hospital_id, shift_date), KEY idx_nss_ward_date (ward_id, shift_date)," +
                    "FOREIGN KEY (hospital_id) REFERENCES hospitals(id) ON DELETE CASCADE)");
                log.info("DB migration applied: nurse_shift_schedules table created");
            }
        } catch (Exception e) { log.warn("DB migration skipped (nurse_shift_schedules): {}", e.getMessage()); }
    }

    private void ensureNurseWardAssignmentsTable() {
        try {
            Integer c = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nurse_ward_assignments'", Integer.class);
            if (c != null && c == 0) {
                jdbcTemplate.execute("CREATE TABLE nurse_ward_assignments (" +
                    "id BIGINT NOT NULL AUTO_INCREMENT, public_id VARCHAR(255) NOT NULL, hospital_id BIGINT NOT NULL," +
                    "nurse_profile_id BIGINT NOT NULL, temp_ward_id BIGINT NOT NULL, from_date DATE NOT NULL, to_date DATE NOT NULL," +
                    "reason VARCHAR(255), created_by_user_id BIGINT, created_at DATETIME(6) NOT NULL," +
                    "PRIMARY KEY (id), UNIQUE KEY UK_nwa_public (public_id)," +
                    "KEY idx_nwa_nurse (nurse_profile_id, from_date, to_date), KEY idx_nwa_ward (temp_ward_id, from_date, to_date)," +
                    "KEY idx_nwa_hospital (hospital_id, to_date)," +
                    "FOREIGN KEY (hospital_id) REFERENCES hospitals(id) ON DELETE CASCADE)");
                log.info("DB migration applied: nurse_ward_assignments table created");
            }
        } catch (Exception e) { log.warn("DB migration skipped (nurse_ward_assignments): {}", e.getMessage()); }
    }

    private void ensureNurseSubstitutionsTable() {
        try {
            Integer c = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nurse_substitutions'", Integer.class);
            if (c != null && c == 0) {
                jdbcTemplate.execute("CREATE TABLE nurse_substitutions (" +
                    "id BIGINT NOT NULL AUTO_INCREMENT, public_id VARCHAR(255) NOT NULL, hospital_id BIGINT NOT NULL," +
                    "primary_nurse_profile_id BIGINT NOT NULL, replacement_nurse_profile_id BIGINT NOT NULL," +
                    "from_date DATE NOT NULL, to_date DATE NOT NULL, reason VARCHAR(255), created_by_user_id BIGINT, created_at DATETIME(6) NOT NULL," +
                    "PRIMARY KEY (id), UNIQUE KEY UK_nsub_public (public_id)," +
                    "KEY idx_nsub_repl (replacement_nurse_profile_id, from_date, to_date), KEY idx_nsub_hospital (hospital_id, to_date)," +
                    "FOREIGN KEY (hospital_id) REFERENCES hospitals(id) ON DELETE CASCADE)");
                log.info("DB migration applied: nurse_substitutions table created");
            }
        } catch (Exception e) { log.warn("DB migration skipped (nurse_substitutions): {}", e.getMessage()); }
    }

    private void ensureNurseAttendanceTable() {
        try {
            Integer c = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nurse_attendance'", Integer.class);
            if (c != null && c == 0) {
                jdbcTemplate.execute("CREATE TABLE nurse_attendance (" +
                    "id BIGINT NOT NULL AUTO_INCREMENT, public_id VARCHAR(255) NOT NULL, hospital_id BIGINT NOT NULL," +
                    "nurse_profile_id BIGINT NOT NULL, ward_id BIGINT, attendance_date DATE NOT NULL, status VARCHAR(20) NOT NULL," +
                    "shift_template_id BIGINT, shift_start_time TIME, shift_end_time TIME," +
                    "check_in_time TIME, check_out_time TIME, remarks VARCHAR(255), marked_by_user_id BIGINT," +
                    "created_at DATETIME(6) NOT NULL, updated_at DATETIME(6)," +
                    "PRIMARY KEY (id), UNIQUE KEY UK_na_public (public_id)," +
                    "UNIQUE KEY UK_na_nurse_date (nurse_profile_id, attendance_date)," +
                    "KEY idx_na_hospital_date (hospital_id, attendance_date), KEY idx_na_ward_date (ward_id, attendance_date)," +
                    "FOREIGN KEY (hospital_id) REFERENCES hospitals(id) ON DELETE CASCADE)");
                log.info("DB migration applied: nurse_attendance table created");
            }
        } catch (Exception e) { log.warn("DB migration skipped (nurse_attendance): {}", e.getMessage()); }
    }

    /**
     * Creates the shift_templates table if absent (Nursing Mgmt Phase B1).
     * Idempotent; mirrors setup/schema-full.sql.
     */
    private void ensureShiftTemplatesTable() {
        try {
            Integer c = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'shift_templates'", Integer.class);
            if (c != null && c == 0) {
                jdbcTemplate.execute("CREATE TABLE shift_templates (" +
                    "id BIGINT NOT NULL AUTO_INCREMENT, public_id VARCHAR(255) NOT NULL, hospital_id BIGINT NOT NULL," +
                    "name VARCHAR(60) NOT NULL, start_time TIME NOT NULL, end_time TIME NOT NULL," +
                    "is_active TINYINT(1) NOT NULL DEFAULT 1, created_at DATETIME(6) NOT NULL," +
                    "PRIMARY KEY (id), UNIQUE KEY UK_shift_template_public (public_id), KEY idx_shift_template_hospital (hospital_id)," +
                    "FOREIGN KEY (hospital_id) REFERENCES hospitals(id) ON DELETE CASCADE)");
                log.info("DB migration applied: shift_templates table created");
            }
        } catch (Exception e) { log.warn("DB migration skipped (shift_templates): {}", e.getMessage()); }
    }

    /**
     * Creates the appointment_slots table if absent (Nursing Mgmt Phase B1).
     * Idempotent; mirrors setup/schema-full.sql.
     */
    private void ensureAppointmentSlotsTable() {
        try {
            Integer c = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'appointment_slots'", Integer.class);
            if (c != null && c == 0) {
                jdbcTemplate.execute("CREATE TABLE appointment_slots (" +
                    "id BIGINT NOT NULL AUTO_INCREMENT, public_id VARCHAR(255) NOT NULL, hospital_id BIGINT NOT NULL," +
                    "start_time TIME NOT NULL, end_time TIME NOT NULL, is_active TINYINT(1) NOT NULL DEFAULT 1, created_at DATETIME(6) NOT NULL," +
                    "PRIMARY KEY (id), UNIQUE KEY UK_appt_slot_public (public_id), KEY idx_appt_slot_hospital (hospital_id)," +
                    "FOREIGN KEY (hospital_id) REFERENCES hospitals(id) ON DELETE CASCADE)");
                log.info("DB migration applied: appointment_slots table created");
            }
        } catch (Exception e) { log.warn("DB migration skipped (appointment_slots): {}", e.getMessage()); }
    }

    /**
     * Creates the manual_tasks table if absent (Phase 1 Nurse module, M7).
     * Idempotent; mirrors setup/schema-full.sql.
     */
    private void ensureManualTasksTable() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'manual_tasks'",
                Integer.class
            );
            if (count != null && count == 0) {
                jdbcTemplate.execute(
                    "CREATE TABLE manual_tasks (" +
                    "  id BIGINT NOT NULL AUTO_INCREMENT," +
                    "  public_id VARCHAR(255) NOT NULL," +
                    "  hospital_id BIGINT NOT NULL," +
                    "  title VARCHAR(150) NOT NULL," +
                    "  description TEXT," +
                    "  assigned_to_nurse_user_id BIGINT NOT NULL," +
                    "  assigned_by_user_id BIGINT NOT NULL," +
                    "  ipd_admission_id BIGINT," +
                    "  priority VARCHAR(10) NOT NULL DEFAULT 'MEDIUM'," +
                    "  status VARCHAR(15) NOT NULL DEFAULT 'PENDING'," +
                    "  due_date DATETIME(6)," +
                    "  completed_at DATETIME(6)," +
                    "  completion_remarks VARCHAR(500)," +
                    "  is_active TINYINT(1) NOT NULL DEFAULT 1," +
                    "  created_at DATETIME(6) NOT NULL," +
                    "  updated_at DATETIME(6)," +
                    "  PRIMARY KEY (id)," +
                    "  UNIQUE KEY UK_manual_tasks_public_id (public_id)," +
                    "  KEY idx_tasks_nurse_status (assigned_to_nurse_user_id, status)," +
                    "  KEY idx_tasks_hospital_status (hospital_id, status)," +
                    "  KEY idx_tasks_admission (ipd_admission_id)," +
                    "  FOREIGN KEY (hospital_id) REFERENCES hospitals(id) ON DELETE CASCADE" +
                    ")"
                );
                log.info("DB migration applied: manual_tasks table created");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (manual_tasks): {}", e.getMessage());
        }
    }

    /**
     * Creates the notifications table if absent (Phase 1 Nurse module, M8).
     * Idempotent; mirrors setup/schema-full.sql.
     */
    private void ensureNotificationsTable() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'notifications'",
                Integer.class
            );
            if (count != null && count == 0) {
                jdbcTemplate.execute(
                    "CREATE TABLE notifications (" +
                    "  id BIGINT NOT NULL AUTO_INCREMENT," +
                    "  public_id VARCHAR(255) NOT NULL," +
                    "  hospital_id BIGINT NOT NULL," +
                    "  recipient_user_id BIGINT NOT NULL," +
                    "  type VARCHAR(30) NOT NULL," +
                    "  title VARCHAR(150) NOT NULL," +
                    "  message TEXT NOT NULL," +
                    "  reference_type VARCHAR(50) DEFAULT NULL," +
                    "  reference_id BIGINT DEFAULT NULL," +
                    "  is_read TINYINT(1) NOT NULL DEFAULT 0," +
                    "  read_at DATETIME(6) DEFAULT NULL," +
                    "  created_at DATETIME(6) NOT NULL," +
                    "  PRIMARY KEY (id)," +
                    "  UNIQUE KEY UK_notifications_public_id (public_id)," +
                    "  KEY idx_ntf_recipient (recipient_user_id)," +
                    "  KEY idx_ntf_hospital (hospital_id)," +
                    "  FOREIGN KEY (hospital_id) REFERENCES hospitals(id) ON DELETE CASCADE" +
                    ")"
                );
                log.info("DB migration applied: notifications table created");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (notifications): {}", e.getMessage());
        }
    }

    /**
     * Creates the medication_administrations table if absent (Phase 1 Nurse
     * module). Idempotent; mirrors setup/schema-full.sql.
     */
    private void ensureMedicationAdministrationsTable() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'medication_administrations'",
                Integer.class
            );
            if (count != null && count == 0) {
                jdbcTemplate.execute(
                    "CREATE TABLE medication_administrations (" +
                    "  id BIGINT NOT NULL AUTO_INCREMENT," +
                    "  public_id VARCHAR(255) NOT NULL," +
                    "  hospital_id BIGINT NOT NULL," +
                    "  ipd_admission_id BIGINT NOT NULL," +
                    "  prescription_id BIGINT NOT NULL," +
                    "  patient_id BIGINT NOT NULL," +
                    "  nurse_user_id BIGINT NOT NULL," +
                    "  scheduled_time DATETIME(6)," +
                    "  administered_time DATETIME(6)," +
                    "  status VARCHAR(20) NOT NULL," +
                    "  remarks VARCHAR(500)," +
                    "  is_active TINYINT(1) NOT NULL DEFAULT 1," +
                    "  created_at DATETIME(6) NOT NULL," +
                    "  PRIMARY KEY (id)," +
                    "  UNIQUE KEY UK_mar_public_id (public_id)," +
                    "  KEY idx_mar_admission_time (ipd_admission_id, administered_time)," +
                    "  KEY idx_mar_prescription (prescription_id)," +
                    "  KEY idx_mar_hospital (hospital_id)," +
                    "  FOREIGN KEY (hospital_id) REFERENCES hospitals(id) ON DELETE CASCADE" +
                    ")"
                );
                log.info("DB migration applied: medication_administrations table created");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (medication_administrations): {}", e.getMessage());
        }
    }

    /**
     * Creates the nursing_notes table if absent (Phase 1 Nurse module).
     * Idempotent; mirrors setup/schema-full.sql.
     */
    private void ensureNursingNotesTable() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nursing_notes'",
                Integer.class
            );
            if (count != null && count == 0) {
                jdbcTemplate.execute(
                    "CREATE TABLE nursing_notes (" +
                    "  id BIGINT NOT NULL AUTO_INCREMENT," +
                    "  public_id VARCHAR(255) NOT NULL," +
                    "  hospital_id BIGINT NOT NULL," +
                    "  ipd_admission_id BIGINT NOT NULL," +
                    "  patient_id BIGINT NOT NULL," +
                    "  nurse_user_id BIGINT NOT NULL," +
                    "  note_text TEXT NOT NULL," +
                    "  category VARCHAR(40)," +
                    "  recorded_at DATETIME(6) NOT NULL," +
                    "  is_active TINYINT(1) NOT NULL DEFAULT 1," +
                    "  created_at DATETIME(6) NOT NULL," +
                    "  updated_at DATETIME(6)," +
                    "  PRIMARY KEY (id)," +
                    "  UNIQUE KEY UK_nursing_notes_public_id (public_id)," +
                    "  KEY idx_nursing_notes_admission_time (ipd_admission_id, recorded_at)," +
                    "  KEY idx_nursing_notes_hospital (hospital_id)," +
                    "  FOREIGN KEY (hospital_id) REFERENCES hospitals(id) ON DELETE CASCADE" +
                    ")"
                );
                log.info("DB migration applied: nursing_notes table created");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (nursing_notes): {}", e.getMessage());
        }
    }

    /**
     * Creates the vitals_records table if absent (Phase 1 Nurse module).
     * Idempotent; mirrors setup/schema-full.sql.
     */
    private void ensureVitalsRecordsTable() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'vitals_records'",
                Integer.class
            );
            if (count != null && count == 0) {
                jdbcTemplate.execute(
                    "CREATE TABLE vitals_records (" +
                    "  id BIGINT NOT NULL AUTO_INCREMENT," +
                    "  public_id VARCHAR(255) NOT NULL," +
                    "  hospital_id BIGINT NOT NULL," +
                    "  ipd_admission_id BIGINT NOT NULL," +
                    "  patient_id BIGINT NOT NULL," +
                    "  recorded_by_user_id BIGINT NOT NULL," +
                    "  recorded_at DATETIME(6) NOT NULL," +
                    "  temperature DECIMAL(4,1)," +
                    "  pulse INT," +
                    "  bp_systolic INT," +
                    "  bp_diastolic INT," +
                    "  respiratory_rate INT," +
                    "  spo2 INT," +
                    "  weight DECIMAL(5,2)," +
                    "  pain_score INT," +
                    "  remarks VARCHAR(500)," +
                    "  is_active TINYINT(1) NOT NULL DEFAULT 1," +
                    "  created_at DATETIME(6) NOT NULL," +
                    "  updated_at DATETIME(6)," +
                    "  PRIMARY KEY (id)," +
                    "  UNIQUE KEY UK_vitals_public_id (public_id)," +
                    "  KEY idx_vitals_admission_time (ipd_admission_id, recorded_at)," +
                    "  KEY idx_vitals_hospital (hospital_id)," +
                    "  FOREIGN KEY (hospital_id) REFERENCES hospitals(id) ON DELETE CASCADE" +
                    ")"
                );
                log.info("DB migration applied: vitals_records table created");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (vitals_records): {}", e.getMessage());
        }
    }

    /**
     * Creates the patient_nurse_assignments table if absent (Phase 1 Nurse
     * module). Idempotent; mirrors setup/schema-full.sql.
     */
    private void ensurePatientNurseAssignmentsTable() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'patient_nurse_assignments'",
                Integer.class
            );
            if (count != null && count == 0) {
                jdbcTemplate.execute(
                    "CREATE TABLE patient_nurse_assignments (" +
                    "  id BIGINT NOT NULL AUTO_INCREMENT," +
                    "  public_id VARCHAR(255) NOT NULL," +
                    "  hospital_id BIGINT NOT NULL," +
                    "  ipd_admission_id BIGINT NOT NULL," +
                    "  patient_id BIGINT NOT NULL," +
                    "  nurse_user_id BIGINT NOT NULL," +
                    "  assigned_by_user_id BIGINT NOT NULL," +
                    "  assigned_at DATETIME(6) NOT NULL," +
                    "  unassigned_at DATETIME(6)," +
                    "  notes VARCHAR(255)," +
                    "  is_active TINYINT(1) NOT NULL DEFAULT 1," +
                    "  created_at DATETIME(6) NOT NULL," +
                    "  PRIMARY KEY (id)," +
                    "  UNIQUE KEY UK_pna_public_id (public_id)," +
                    "  KEY idx_pna_admission_active (ipd_admission_id, is_active)," +
                    "  KEY idx_pna_nurse_active (nurse_user_id, is_active)," +
                    "  KEY idx_pna_hospital_active (hospital_id, is_active)," +
                    "  FOREIGN KEY (hospital_id) REFERENCES hospitals(id) ON DELETE CASCADE" +
                    ")"
                );
                log.info("DB migration applied: patient_nurse_assignments table created");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (patient_nurse_assignments): {}", e.getMessage());
        }
    }

    /**
     * Creates the nurse_profiles table if absent (Phase 1 Nurse module).
     * ddl-auto=update also creates it from the entity; this keeps the schema
     * reproducible on databases where ddl-auto is disabled and mirrors
     * setup/schema-full.sql. Idempotent.
     */
    private void ensureNurseProfilesTable() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nurse_profiles'",
                Integer.class
            );
            if (count != null && count == 0) {
                jdbcTemplate.execute(
                    "CREATE TABLE nurse_profiles (" +
                    "  id BIGINT NOT NULL AUTO_INCREMENT," +
                    "  public_id VARCHAR(255) NOT NULL," +
                    "  custom_id VARCHAR(255)," +
                    "  user_id BIGINT," +
                    "  hospital_id BIGINT NOT NULL," +
                    "  name VARCHAR(100) NOT NULL," +
                    "  phone VARCHAR(20)," +
                    "  email VARCHAR(100) NOT NULL," +
                    "  license_number VARCHAR(50)," +
                    "  is_active TINYINT(1) NOT NULL DEFAULT 1," +
                    "  created_at DATETIME(6) NOT NULL," +
                    "  PRIMARY KEY (id)," +
                    "  UNIQUE KEY UK_nurse_profiles_public_id (public_id)," +
                    "  KEY idx_nurse_profiles_hospital (hospital_id)," +
                    "  KEY idx_nurse_profiles_user (user_id)," +
                    "  FOREIGN KEY (hospital_id) REFERENCES hospitals(id) ON DELETE CASCADE" +
                    ")"
                );
                log.info("DB migration applied: nurse_profiles table created");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (nurse_profiles): {}", e.getMessage());
        }
    }

    /**
     * Adds the nullable ward_id column to nurse_profiles (nurse-to-ward
     * assignment). Idempotent; mirrors setup/schema-full.sql.
     */
    private void ensureNurseProfileWardColumn() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nurse_profiles' AND COLUMN_NAME = 'ward_id'",
                Integer.class
            );
            if (count != null && count == 0) {
                jdbcTemplate.execute("ALTER TABLE nurse_profiles ADD COLUMN ward_id BIGINT NULL");
                log.info("DB migration applied: added ward_id column to nurse_profiles");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (nurse_profiles.ward_id): {}", e.getMessage());
        }
    }

    /**
     * Adds the on_shift column to nurse_profiles (nurse shift availability).
     * Idempotent; mirrors setup/schema-full.sql.
     */
    private void ensureNurseProfileShiftColumn() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nurse_profiles' AND COLUMN_NAME = 'on_shift'",
                Integer.class
            );
            if (count != null && count == 0) {
                jdbcTemplate.execute("ALTER TABLE nurse_profiles ADD COLUMN on_shift TINYINT(1) NOT NULL DEFAULT 0");
                log.info("DB migration applied: added on_shift column to nurse_profiles");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (nurse_profiles.on_shift): {}", e.getMessage());
        }
    }

    /**
     * Adds admission_confirmed + admission_confirmed_at to ipd_admission
     * (nurse-confirmed admission after the signed form is collected). Idempotent.
     */
    private void ensureAdmissionConfirmedColumns() {
        try {
            Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ipd_admission' AND COLUMN_NAME = 'admission_confirmed'",
                Integer.class);
            if (c != null && c == 0) {
                jdbcTemplate.execute("ALTER TABLE ipd_admission ADD COLUMN admission_confirmed TINYINT(1) NOT NULL DEFAULT 0");
                jdbcTemplate.execute("ALTER TABLE ipd_admission ADD COLUMN admission_confirmed_at DATETIME(6) NULL");
                log.info("DB migration applied: added admission_confirmed columns to ipd_admission");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (ipd_admission.admission_confirmed): {}", e.getMessage());
        }
    }

    /**
     * Adds admitted_by_user_id to ipd_admission (the receptionist/admin who
     * admitted the patient). Idempotent.
     */
    private void ensureAdmittedByColumn() {
        try {
            Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ipd_admission' AND COLUMN_NAME = 'admitted_by_user_id'",
                Integer.class);
            if (c != null && c == 0) {
                jdbcTemplate.execute("ALTER TABLE ipd_admission ADD COLUMN admitted_by_user_id BIGINT NULL");
                log.info("DB migration applied: added admitted_by_user_id column to ipd_admission");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (ipd_admission.admitted_by_user_id): {}", e.getMessage());
        }
    }

    /**
     * Creates the admission_forms table if absent (Phase 1 Nurse module).
     * Idempotent; mirrors setup/schema-full.sql.
     */
    private void ensureAdmissionFormsTable() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'admission_forms'",
                Integer.class);
            if (count != null && count == 0) {
                jdbcTemplate.execute(
                    "CREATE TABLE admission_forms (" +
                    "  id BIGINT NOT NULL AUTO_INCREMENT," +
                    "  public_id VARCHAR(255) NOT NULL," +
                    "  hospital_id BIGINT NOT NULL," +
                    "  ipd_admission_id BIGINT NOT NULL," +
                    "  prn_no VARCHAR(60), bed_no VARCHAR(60), category VARCHAR(40)," +
                    "  patient_surname VARCHAR(100), patient_first_name VARCHAR(100), husband_father_name VARCHAR(150)," +
                    "  patient_address TEXT, age VARCHAR(20), sex VARCHAR(20), occupation VARCHAR(100)," +
                    "  patient_category VARCHAR(40), mediclaim VARCHAR(20), tpa_name VARCHAR(200)," +
                    "  relative_name VARCHAR(150), email VARCHAR(120), telephone VARCHAR(40)," +
                    "  receptionist_name VARCHAR(120), ref_dr VARCHAR(150)," +
                    "  ipd_registration_no VARCHAR(60), department VARCHAR(120), under_care_of_dr VARCHAR(150)," +
                    "  admitted_date VARCHAR(40), admitted_time VARCHAR(40)," +
                    "  prov_diagnosis1 TEXT, prov_diagnosis2 TEXT, hypersensitivity_history TEXT," +
                    "  relative_address TEXT, relative_phone VARCHAR(40)," +
                    "  created_at DATETIME(6) NOT NULL, updated_at DATETIME(6)," +
                    "  PRIMARY KEY (id)," +
                    "  UNIQUE KEY UK_admission_forms_public_id (public_id)," +
                    "  UNIQUE KEY UK_admission_forms_ipd (ipd_admission_id)," +
                    "  KEY idx_admission_forms_hospital (hospital_id)," +
                    "  FOREIGN KEY (hospital_id) REFERENCES hospitals(id) ON DELETE CASCADE" +
                    ")");
                log.info("DB migration applied: admission_forms table created");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (admission_forms): {}", e.getMessage());
        }
    }

    /**
     * Creates the initial_assessments table if absent (Phase 1 Nurse module).
     * Idempotent; mirrors setup/schema-full.sql.
     */
    private void ensureInitialAssessmentsTable() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'initial_assessments'",
                Integer.class);
            if (count != null && count == 0) {
                jdbcTemplate.execute(
                    "CREATE TABLE initial_assessments (" +
                    "  id BIGINT NOT NULL AUTO_INCREMENT," +
                    "  public_id VARCHAR(255) NOT NULL," +
                    "  hospital_id BIGINT NOT NULL," +
                    "  ipd_admission_id BIGINT NOT NULL," +
                    "  chief_complaints TEXT, associated_illness TEXT, relevant_investigations TEXT," +
                    "  allergies TEXT, vaccination_history TEXT, others TEXT," +
                    "  past_history TEXT, family_history TEXT, personal_history TEXT," +
                    "  provisional_diagnosis TEXT, care_plan TEXT," +
                    "  created_at DATETIME(6) NOT NULL, updated_at DATETIME(6)," +
                    "  PRIMARY KEY (id)," +
                    "  UNIQUE KEY UK_initial_assessments_public_id (public_id)," +
                    "  UNIQUE KEY UK_initial_assessments_ipd (ipd_admission_id)," +
                    "  KEY idx_initial_assessments_hospital (hospital_id)," +
                    "  FOREIGN KEY (hospital_id) REFERENCES hospitals(id) ON DELETE CASCADE" +
                    ")");
                log.info("DB migration applied: initial_assessments table created");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (initial_assessments): {}", e.getMessage());
        }
    }

    /** Adds the pain_score column to initial_assessments. Idempotent. */
    private void ensureInitialAssessmentPainColumn() {
        try {
            Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'initial_assessments' AND COLUMN_NAME = 'pain_score'",
                Integer.class);
            if (c != null && c == 0) {
                jdbcTemplate.execute("ALTER TABLE initial_assessments ADD COLUMN pain_score INT NULL");
                log.info("DB migration applied: added pain_score column to initial_assessments");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (initial_assessments.pain_score): {}", e.getMessage());
        }
    }

    /**
     * Creates the vulnerability_assessments table if absent (Phase 1 Nurse module).
     * Idempotent; mirrors setup/schema-full.sql.
     */
    private void ensureVulnerabilityAssessmentsTable() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'vulnerability_assessments'",
                Integer.class);
            if (count != null && count == 0) {
                jdbcTemplate.execute(
                    "CREATE TABLE vulnerability_assessments (" +
                    "  id BIGINT NOT NULL AUTO_INCREMENT," +
                    "  public_id VARCHAR(255) NOT NULL," +
                    "  hospital_id BIGINT NOT NULL," +
                    "  ipd_admission_id BIGINT NOT NULL," +
                    "  category VARCHAR(120), fall_risk_assessment TEXT, sensory_deficit TEXT," +
                    "  disorientation VARCHAR(120), self_care_deficit VARCHAR(10), mobility_problem VARCHAR(10)," +
                    "  history_of_fall VARCHAR(10), impaired_judgement VARCHAR(10)," +
                    "  psychological_status VARCHAR(200), remarks TEXT, nursing_intervention TEXT," +
                    "  reason_for_transfer TEXT, investigation_lab TEXT, investigation_radiology TEXT," +
                    "  transfer_provisional_diagnosis TEXT, transfer_doctor_name VARCHAR(150)," +
                    "  created_at DATETIME(6) NOT NULL, updated_at DATETIME(6)," +
                    "  PRIMARY KEY (id)," +
                    "  UNIQUE KEY UK_vuln_public_id (public_id)," +
                    "  UNIQUE KEY UK_vuln_ipd (ipd_admission_id)," +
                    "  KEY idx_vuln_hospital (hospital_id)," +
                    "  FOREIGN KEY (hospital_id) REFERENCES hospitals(id) ON DELETE CASCADE" +
                    ")");
                log.info("DB migration applied: vulnerability_assessments table created");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (vulnerability_assessments): {}", e.getMessage());
        }
    }

    /**
     * Creates the sugar_chart_entries table if absent (Phase 1 Nurse module).
     * Idempotent; mirrors setup/schema-full.sql.
     */
    private void ensureSugarChartEntriesTable() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sugar_chart_entries'",
                Integer.class);
            if (count != null && count == 0) {
                jdbcTemplate.execute(
                    "CREATE TABLE sugar_chart_entries (" +
                    "  id BIGINT NOT NULL AUTO_INCREMENT," +
                    "  public_id VARCHAR(255) NOT NULL," +
                    "  hospital_id BIGINT NOT NULL," +
                    "  ipd_admission_id BIGINT NOT NULL," +
                    "  patient_id BIGINT NOT NULL," +
                    "  nurse_user_id BIGINT NOT NULL," +
                    "  blood_sugar VARCHAR(60)," +
                    "  treatment TEXT," +
                    "  recorded_at DATETIME(6) NOT NULL," +
                    "  is_active TINYINT(1) NOT NULL DEFAULT 1," +
                    "  created_at DATETIME(6) NOT NULL, updated_at DATETIME(6)," +
                    "  PRIMARY KEY (id)," +
                    "  UNIQUE KEY UK_sugar_public_id (public_id)," +
                    "  KEY idx_sugar_admission_time (ipd_admission_id, recorded_at)," +
                    "  KEY idx_sugar_hospital (hospital_id)," +
                    "  FOREIGN KEY (hospital_id) REFERENCES hospitals(id) ON DELETE CASCADE" +
                    ")");
                log.info("DB migration applied: sugar_chart_entries table created");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (sugar_chart_entries): {}", e.getMessage());
        }
    }

    /**
     * Creates the surgeries table if absent (OT module, Phase 2).
     * Idempotent; mirrors setup/schema-full.sql.
     */
    private void ensureSurgeriesTable() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'surgeries'",
                Integer.class);
            if (count != null && count == 0) {
                jdbcTemplate.execute(
                    "CREATE TABLE surgeries (" +
                    "  id BIGINT NOT NULL AUTO_INCREMENT," +
                    "  public_id VARCHAR(255) NOT NULL," +
                    "  hospital_id BIGINT NOT NULL," +
                    "  ipd_admission_id BIGINT NOT NULL," +
                    "  patient_id BIGINT NOT NULL," +
                    "  procedure_name VARCHAR(255)," +
                    "  clinical_notes TEXT," +
                    "  priority VARCHAR(20)," +
                    "  preferred_date DATE," +
                    "  requested_by_doctor_id BIGINT," +
                    "  requested_by_user_id BIGINT," +
                    "  requested_at DATETIME(6) NOT NULL," +
                    "  status VARCHAR(20) NOT NULL," +
                    "  surgeon_doctor_id BIGINT," +
                    "  surgeon_name VARCHAR(255)," +
                    "  anaesthetist_name VARCHAR(255)," +
                    "  scheduled_at DATETIME(6)," +
                    "  ot_ward_id BIGINT," +
                    "  ot_bed_id BIGINT," +
                    "  scheduled_by_user_id BIGINT," +
                    "  started_at DATETIME(6)," +
                    "  completed_at DATETIME(6)," +
                    "  created_at DATETIME(6) NOT NULL, updated_at DATETIME(6)," +
                    "  PRIMARY KEY (id)," +
                    "  UNIQUE KEY UK_surgery_public_id (public_id)," +
                    "  KEY idx_surgery_hospital_status (hospital_id, status)," +
                    "  KEY idx_surgery_admission (ipd_admission_id)," +
                    "  FOREIGN KEY (hospital_id) REFERENCES hospitals(id) ON DELETE CASCADE" +
                    ")");
                log.info("DB migration applied: surgeries table created");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (surgeries): {}", e.getMessage());
        }
    }

    /**
     * Adds the nullable surgeon_name column to surgeries (free-text "Other"
     * operator name). Idempotent; mirrors setup/schema-full.sql.
     */
    private void ensureSurgerySurgeonNameColumn() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'surgeries' AND COLUMN_NAME = 'surgeon_name'",
                Integer.class);
            if (count != null && count == 0) {
                jdbcTemplate.execute("ALTER TABLE surgeries ADD COLUMN surgeon_name VARCHAR(255) NULL");
                log.info("DB migration applied: added surgeon_name column to surgeries");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (surgeries.surgeon_name): {}", e.getMessage());
        }
    }

    /**
     * Adds the nullable anaesthetist_name column to surgeries (optional
     * anaesthetist for the surgery). Idempotent; mirrors setup/schema-full.sql.
     */
    private void ensureSurgeryAnaesthetistNameColumn() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'surgeries' AND COLUMN_NAME = 'anaesthetist_name'",
                Integer.class);
            if (count != null && count == 0) {
                jdbcTemplate.execute("ALTER TABLE surgeries ADD COLUMN anaesthetist_name VARCHAR(255) NULL");
                log.info("DB migration applied: added anaesthetist_name column to surgeries");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (surgeries.anaesthetist_name): {}", e.getMessage());
        }
    }

    private void ensureSurgeryFormsTable() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'surgery_forms'",
                Integer.class);
            if (count != null && count == 0) {
                jdbcTemplate.execute(
                    "CREATE TABLE surgery_forms (" +
                    "  id BIGINT NOT NULL AUTO_INCREMENT," +
                    "  public_id VARCHAR(255) NOT NULL," +
                    "  hospital_id BIGINT NOT NULL," +
                    "  ipd_admission_id BIGINT NOT NULL," +
                    "  surgery_id BIGINT," +
                    "  form_type VARCHAR(60) NOT NULL," +
                    "  data_json LONGTEXT," +
                    "  saved_by_user_id BIGINT," +
                    "  created_at DATETIME(6) NOT NULL, updated_at DATETIME(6)," +
                    "  PRIMARY KEY (id)," +
                    "  UNIQUE KEY UK_surgery_form_public_id (public_id)," +
                    "  UNIQUE KEY UK_surgery_form_admission_type (ipd_admission_id, form_type)," +
                    "  KEY idx_surgery_form_hospital (hospital_id)," +
                    "  FOREIGN KEY (hospital_id) REFERENCES hospitals(id) ON DELETE CASCADE" +
                    ")");
                log.info("DB migration applied: surgery_forms table created");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (surgery_forms): {}", e.getMessage());
        }
    }

    /**
     * Adds the nullable surgery_id column to nursing_notes (links an OT note
     * to its surgery). Idempotent; mirrors setup/schema-full.sql.
     */
    private void ensureNursingNoteSurgeryIdColumn() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nursing_notes' AND COLUMN_NAME = 'surgery_id'",
                Integer.class);
            if (count != null && count == 0) {
                jdbcTemplate.execute("ALTER TABLE nursing_notes ADD COLUMN surgery_id BIGINT NULL");
                log.info("DB migration applied: added surgery_id column to nursing_notes");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (nursing_notes.surgery_id): {}", e.getMessage());
        }
    }

    /**
     * Add nullable branch_id to the branch-scoped pharmacy data tables (Multi Pharmacy
     * isolation). Null = not branch-scoped (single-shop / hospital / clinic pharmacy).
     */
    private void ensurePharmacyDataBranchColumns() {
        String[] tables = {
            "medicine_master", "medicine_batches", "suppliers",
            "purchase_invoices", "inventory_transactions", "pharmacy_sales"
        };
        for (String table : tables) {
            try {
                Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = 'branch_id'",
                    Integer.class, table
                );
                if (count != null && count == 0) {
                    jdbcTemplate.execute("ALTER TABLE `" + table + "` ADD COLUMN branch_id BIGINT DEFAULT NULL");
                    log.info("DB migration applied: {}.branch_id column added", table);
                }
            } catch (Exception e) {
                log.warn("DB migration skipped ({}.branch_id): {}", table, e.getMessage());
            }
        }
    }

    /**
     * Ensure the pharmacy_branch table and users.branch_id column exist (Multi Pharmacy).
     */
    private void ensurePharmacyBranchSupport() {
        try {
            Integer tableExists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pharmacy_branch'",
                Integer.class
            );
            if (tableExists != null && tableExists == 0) {
                jdbcTemplate.execute(
                    "CREATE TABLE pharmacy_branch (" +
                    "  id BIGINT NOT NULL AUTO_INCREMENT," +
                    "  hospital_id BIGINT NOT NULL," +
                    "  name VARCHAR(255) NOT NULL," +
                    "  address VARCHAR(255) DEFAULT NULL," +
                    "  phone VARCHAR(30) DEFAULT NULL," +
                    "  login_user_id BIGINT DEFAULT NULL," +
                    "  is_active TINYINT(1) NOT NULL DEFAULT 1," +
                    "  created_at DATETIME(6) DEFAULT NULL," +
                    "  updated_at DATETIME(6) DEFAULT NULL," +
                    "  PRIMARY KEY (id)," +
                    "  KEY idx_pharmacy_branch_hospital (hospital_id)" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
                );
                log.info("DB migration applied: pharmacy_branch table created");
            }

            Integer colExists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'branch_id'",
                Integer.class
            );
            if (colExists != null && colExists == 0) {
                jdbcTemplate.execute("ALTER TABLE users ADD COLUMN branch_id BIGINT DEFAULT NULL");
                log.info("DB migration applied: users.branch_id column added");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (pharmacy_branch support): {}", e.getMessage());
        }
    }

    /**
     * Ensure medicine_master.manufacturer_name exists. Nullable free-text manufacturer
     * captured on the pharmacy purchase form (standalone pharmacy ERP).
     */
    private void ensureMedicineMasterManufacturerName() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'medicine_master' AND COLUMN_NAME = 'manufacturer_name'",
                Integer.class
            );
            if (count != null && count == 0) {
                jdbcTemplate.execute("ALTER TABLE medicine_master ADD COLUMN manufacturer_name VARCHAR(255) DEFAULT NULL");
                log.info("DB migration applied: medicine_master.manufacturer_name column added");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (medicine_master.manufacturer_name): {}", e.getMessage());
        }
    }

    /**
     * Drop orphaned columns that are NOT NULL with no default and no longer mapped by
     * any entity (hospital_settings.shift_mode, users.is_trainer). Hibernate omits them
     * on INSERT, so MySQL rejects every insert on those tables (error 1364), breaking
     * hospital/clinic/pharmacy onboarding and user creation. The canonical schema has
     * no such columns — this realigns older databases with it.
     */
    private void dropLegacyNotNullNoDefaultColumns() {
        String[][] orphanColumns = {
            {"hospital_settings", "shift_mode"},
            {"users", "is_trainer"},
            {"doctors", "is_anaesthetist"},
        };
        for (String[] tc : orphanColumns) {
            String table = tc[0], column = tc[1];
            try {
                Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                    Integer.class, table, column
                );
                if (count != null && count > 0) {
                    jdbcTemplate.execute("ALTER TABLE `" + table + "` DROP COLUMN `" + column + "`");
                    log.info("DB migration applied: dropped legacy {}.{} column", table, column);
                }
            } catch (Exception e) {
                log.warn("DB migration skipped (drop {}.{}): {}", table, column, e.getMessage());
            }
        }
    }

    /**
     * Ensure plans.multi_outlet and plans.max_outlets exist for pharmacy chain plans.
     */
    private void ensurePlanMultiOutletColumns() {
        try {
            Integer hasMulti = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'plans' AND COLUMN_NAME = 'multi_outlet'",
                Integer.class
            );
            if (hasMulti != null && hasMulti == 0) {
                jdbcTemplate.execute("ALTER TABLE plans ADD COLUMN multi_outlet TINYINT(1) NOT NULL DEFAULT 0");
                log.info("DB migration applied: plans.multi_outlet column added");
            }
            Integer hasMax = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'plans' AND COLUMN_NAME = 'max_outlets'",
                Integer.class
            );
            if (hasMax != null && hasMax == 0) {
                jdbcTemplate.execute("ALTER TABLE plans ADD COLUMN max_outlets INT DEFAULT NULL");
                log.info("DB migration applied: plans.max_outlets column added");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (plans.multi_outlet/max_outlets): {}", e.getMessage());
        }
    }

    /**
     * Ensure hospital_settings.barcode_enabled exists. Defaults to enabled so
     * existing pharmacies keep their barcode workflow until an admin turns it off.
     */
    private void ensureHospitalSettingsBarcodeEnabled() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'hospital_settings' AND COLUMN_NAME = 'barcode_enabled'",
                Integer.class
            );
            if (count != null && count == 0) {
                jdbcTemplate.execute(
                    "ALTER TABLE hospital_settings ADD COLUMN barcode_enabled TINYINT(1) NOT NULL DEFAULT 1"
                );
                log.info("DB migration applied: hospital_settings.barcode_enabled column added");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (hospital_settings.barcode_enabled): {}", e.getMessage());
        }
    }

    /**
     * The legacy `hospitals.plan VARCHAR(20) NOT NULL` column was replaced by the
     * hospital_plan_subscriptions table. Hibernate no longer writes this column, so
     * any INSERT fails with "Column 'plan' cannot be null". Make it nullable.
     */
    private void fixHospitalsPlanColumn() {
        try {
            // COUNT(*) always yields exactly one row. Selecting the column's IS_NULLABLE
            // directly returns *zero* rows on a database where `plan` was never created
            // (any fresh install), which made queryForObject throw and this migration log
            // a spurious "skipped" warning on every clean boot.
            Integer notNullable = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'hospitals' " +
                "AND COLUMN_NAME = 'plan' AND IS_NULLABLE = 'NO'",
                Integer.class
            );
            if (notNullable != null && notNullable > 0) {
                jdbcTemplate.execute("ALTER TABLE hospitals MODIFY COLUMN plan VARCHAR(20) DEFAULT NULL");
                log.info("DB migration applied: hospitals.plan is now nullable");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (hospitals.plan): {}", e.getMessage());
        }
    }

    /**
     * Ensure hospital_settings.in_clinic exists and is NOT NULL.
     * ddl-auto=update may fail to add this column if rows existed at the time.
     */
    private void ensureHospitalSettingsInClinic() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'hospital_settings' AND COLUMN_NAME = 'in_clinic'",
                Integer.class
            );
            if (count != null && count == 0) {
                jdbcTemplate.execute(
                    "ALTER TABLE hospital_settings ADD COLUMN in_clinic TINYINT(1) NOT NULL DEFAULT 0"
                );
                log.info("DB migration applied: hospital_settings.in_clinic column added");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (hospital_settings.in_clinic): {}", e.getMessage());
        }
    }

    /**
     * Ensure hospitals.is_single_doctor exists (added when single-doctor mode feature landed).
     */
    private void ensureHospitalsIsSingleDoctor() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'hospitals' AND COLUMN_NAME = 'is_single_doctor'",
                Integer.class
            );
            if (count != null && count == 0) {
                jdbcTemplate.execute(
                    "ALTER TABLE hospitals ADD COLUMN is_single_doctor TINYINT(1) NOT NULL DEFAULT 0"
                );
                log.info("DB migration applied: hospitals.is_single_doctor column added");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (hospitals.is_single_doctor): {}", e.getMessage());
        }
    }

    private void ensureMissingIndexes() {
        addIndexIfMissing("appointments", "idx_appt_date", "appointment_date");
        addIndexIfMissing("patients",     "idx_patient_hospital", "hospital_id");
        addIndexIfMissing("doctors",      "idx_doctor_hospital",  "hospital_id");
    }

    /**
     * Composite sibling of {@link #addIndexIfMissing}, which quotes a single column and so cannot
     * express one. {@code columns} is inserted as written, already quoted by the caller.
     */
    /**
     * V20 -- clinical documents a patient supplied. Metadata plus an opaque storage key; the file
     * lives in a private directory, never anywhere the web server publishes.
     */
    private void ensurePatientDocumentsTable() {
        createTableIfMissing("patient_documents",
            "CREATE TABLE patient_documents (" +
            "  id BIGINT NOT NULL AUTO_INCREMENT," +
            "  public_id VARCHAR(64) NOT NULL," +
            "  hospital_id BIGINT NOT NULL," +
            "  patient_id BIGINT NOT NULL," +
            "  opd_id BIGINT NULL," +
            "  ipd_admission_id BIGINT NULL," +
            "  document_type VARCHAR(40) NOT NULL," +
            "  title VARCHAR(200) NOT NULL," +
            "  report_date DATE NULL," +
            "  source VARCHAR(200) NULL," +
            "  notes VARCHAR(1000) NULL," +
            "  original_file_name VARCHAR(255) NOT NULL," +
            "  mime_type VARCHAR(100) NOT NULL," +
            "  file_size_bytes BIGINT NOT NULL," +
            "  storage_key VARCHAR(255) NOT NULL," +
            "  uploaded_by_user_id BIGINT NULL," +
            "  is_active TINYINT(1) NOT NULL DEFAULT 1," +
            "  archived_by_user_id BIGINT NULL," +
            "  archived_at DATETIME(6) NULL," +
            "  archive_reason VARCHAR(500) NULL," +
            "  created_at DATETIME(6) NOT NULL," +
            "  PRIMARY KEY (id)," +
            "  UNIQUE KEY uk_patient_documents_public_id (public_id)," +
            "  KEY idx_patient_documents_patient (hospital_id, patient_id, report_date)" +
            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci");
    }

    /** As {@link #addCompositeIndexIfMissing}, but the index enforces uniqueness. */
    private void addUniqueIndexIfMissing(String table, String indexName, String columns) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.STATISTICS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?",
                Integer.class, table, indexName
            );
            if (count != null && count == 0) {
                jdbcTemplate.execute(
                    "ALTER TABLE `" + table + "` ADD UNIQUE INDEX `" + indexName + "` (" + columns + ")"
                );
                log.info("DB migration applied: unique index {} added on {} ({})", indexName, table, columns);
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (unique index {} on {}): {}", indexName, table, e.getMessage());
        }
    }

    private void addCompositeIndexIfMissing(String table, String indexName, String columns) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.STATISTICS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?",
                Integer.class, table, indexName
            );
            if (count != null && count == 0) {
                jdbcTemplate.execute(
                    "ALTER TABLE `" + table + "` ADD INDEX `" + indexName + "` (" + columns + ")"
                );
                log.info("DB migration applied: index {} added on {} ({})", indexName, table, columns);
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (index {} on {}): {}", indexName, table, e.getMessage());
        }
    }

    private void addIndexIfMissing(String table, String indexName, String column) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.STATISTICS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?",
                Integer.class, table, indexName
            );
            if (count != null && count == 0) {
                jdbcTemplate.execute(
                    "ALTER TABLE `" + table + "` ADD INDEX `" + indexName + "` (`" + column + "`)"
                );
                log.info("DB migration applied: index {} added on {}.{}", indexName, table, column);
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (index {} on {}): {}", indexName, table, e.getMessage());
        }
    }

    private void simplifyMedicineListTable() {
        try {
            // 1. Deduplicate by name and type, keeping only the first id
            jdbcTemplate.execute(
                "DELETE m1 FROM medicine_list m1 " +
                "INNER JOIN medicine_list m2 " +
                "ON LOWER(m1.name) = LOWER(m2.name) AND LOWER(m1.type) = LOWER(m2.type) " +
                "WHERE m1.id > m2.id"
            );
            log.info("DB migration applied: deduplicated medicine_list table");

            // 2. Drop columns if they exist
            String[] colsToDrop = {"default_dosage", "default_frequency", "default_duration", "manufacturer", "hospital_id", "is_active", "created_at"};
            for (String col : colsToDrop) {
                Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'medicine_list' AND COLUMN_NAME = ?",
                    Integer.class, col
                );
                if (count != null && count > 0) {
                    jdbcTemplate.execute("ALTER TABLE medicine_list DROP COLUMN `" + col + "`");
                    log.info("DB migration applied: dropped column medicine_list." + col);
                }
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (simplifyMedicineListTable): {}", e.getMessage());
        }
    }

    /**
     * Replaces patients.age (stored, goes stale every year) with
     * patients.date_of_birth (computed live by Patient.getAge()).
     *
     * date_of_birth is added and left nullable — NOT promoted to NOT NULL —
     * deliberately. PatientService already enforces "always required" at
     * the application layer, and this project has twice hit real incidents
     * (hospital_settings.shift_mode, users.is_trainer) where a NOT NULL
     * column with no default broke every insert on a populated table. This
     * migration avoids adding a third one.
     */
    private void migratePatientAgeToDateOfBirth() {
        try {
            Integer dobExists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'patients' AND COLUMN_NAME = 'date_of_birth'",
                Integer.class
            );
            if (dobExists != null && dobExists == 0) {
                jdbcTemplate.execute("ALTER TABLE patients ADD COLUMN date_of_birth DATE DEFAULT NULL");
                log.info("DB migration applied: patients.date_of_birth column added");
            }

            Integer ageExists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'patients' AND COLUMN_NAME = 'age'",
                Integer.class
            );
            if (ageExists != null && ageExists > 0) {
                int updated = jdbcTemplate.update(
                    "UPDATE patients SET date_of_birth = DATE_SUB(CURDATE(), INTERVAL age YEAR) " +
                    "WHERE date_of_birth IS NULL"
                );
                log.info("DB migration applied: backfilled date_of_birth for {} patient(s) from age", updated);

                jdbcTemplate.execute("ALTER TABLE patients DROP COLUMN age");
                log.info("DB migration applied: dropped patients.age column");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (patients.age -> date_of_birth): {}", e.getMessage());
        }
    }

    /**
     * Creates the consultation_note_presets table if it does not exist.
     * Stores per-hospital quick-note phrases doctors can insert with one
     * click into Treatment Notes (and, in future, other consultation
     * fields — see field_type).
     * ddl-auto=update cannot create tables from scratch — this runner
     * bridges that gap.
     */
    private void ensureConsultationNotePresetsTable() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'consultation_note_presets'",
                Integer.class
            );
            if (count != null && count == 0) {
                jdbcTemplate.execute(
                    "CREATE TABLE consultation_note_presets (" +
                    "  id BIGINT NOT NULL AUTO_INCREMENT," +
                    "  hospital_id BIGINT NOT NULL," +
                    "  field_type VARCHAR(30) NOT NULL," +
                    "  text VARCHAR(255) NOT NULL," +
                    "  display_order INT NOT NULL DEFAULT 0," +
                    "  is_active TINYINT(1) NOT NULL DEFAULT 1," +
                    "  created_at DATETIME(6) NOT NULL," +
                    "  PRIMARY KEY (id)," +
                    "  FOREIGN KEY (hospital_id) REFERENCES hospitals(id) ON DELETE CASCADE" +
                    ")"
                );
                log.info("DB migration applied: consultation_note_presets table created");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (consultation_note_presets): {}", e.getMessage());
        }
    }

    /**
     * Creates the prescription_presets and prescription_preset_items tables
     * if they do not exist. Stores per-hospital named bundles of medicines
     * a doctor can apply to a prescription in one action.
     * ddl-auto=update cannot create tables from scratch — this runner
     * bridges that gap.
     */
    private void ensurePrescriptionPresetTables() {
        try {
            Integer presetCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'prescription_presets'",
                Integer.class
            );
            if (presetCount != null && presetCount == 0) {
                jdbcTemplate.execute(
                    "CREATE TABLE prescription_presets (" +
                    "  id BIGINT NOT NULL AUTO_INCREMENT," +
                    "  hospital_id BIGINT NOT NULL," +
                    "  name VARCHAR(150) NOT NULL," +
                    "  display_order INT NOT NULL DEFAULT 0," +
                    "  is_active TINYINT(1) NOT NULL DEFAULT 1," +
                    "  created_at DATETIME(6) NOT NULL," +
                    "  PRIMARY KEY (id)," +
                    "  FOREIGN KEY (hospital_id) REFERENCES hospitals(id) ON DELETE CASCADE" +
                    ")"
                );
                log.info("DB migration applied: prescription_presets table created");
            }

            Integer itemCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'prescription_preset_items'",
                Integer.class
            );
            if (itemCount != null && itemCount == 0) {
                jdbcTemplate.execute(
                    "CREATE TABLE prescription_preset_items (" +
                    "  id BIGINT NOT NULL AUTO_INCREMENT," +
                    "  preset_id BIGINT NOT NULL," +
                    "  medicine_name VARCHAR(255) NOT NULL," +
                    "  dosage VARCHAR(50) DEFAULT NULL," +
                    "  frequency VARCHAR(50) DEFAULT NULL," +
                    "  duration VARCHAR(50) DEFAULT NULL," +
                    "  instructions VARCHAR(200) DEFAULT NULL," +
                    "  sort_order INT NOT NULL DEFAULT 0," +
                    "  PRIMARY KEY (id)," +
                    "  FOREIGN KEY (preset_id) REFERENCES prescription_presets(id) ON DELETE CASCADE" +
                    ")"
                );
                log.info("DB migration applied: prescription_preset_items table created");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (prescription presets): {}", e.getMessage());
        }
    }

    /**
     * Adds a nullable doctor_id column to prescription_presets and
     * consultation_note_presets for per-doctor preset isolation. A NULL doctor_id
     * means the preset is shared (visible to every doctor in the hospital); a set
     * value scopes it privately to that doctor. Existing rows stay NULL, so they
     * remain shared — no behaviour change for data created before this migration.
     */
    private void ensurePresetDoctorIdColumns() {
        addNullableDoctorIdColumn("prescription_presets");
        addNullableDoctorIdColumn("consultation_note_presets");
    }

    private void addNullableDoctorIdColumn(String table) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table + "' AND COLUMN_NAME = 'doctor_id'",
                Integer.class
            );
            if (count != null && count == 0) {
                jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN doctor_id BIGINT DEFAULT NULL");
                log.info("DB migration applied: {}.doctor_id column added", table);
            }
        } catch (Exception e) {
            log.warn("DB migration skipped ({}.doctor_id): {}", table, e.getMessage());
        }
    }

    /**
     * Adds inventory_items.has_own_stock if it does not exist, defaulting
     * every existing row to true (1) so current catalog items keep their
     * exact current behavior (own-stock check + cascade to related items)
     * until an admin explicitly marks one as a service item.
     * ddl-auto=update can add columns but not backfill a specific default
     * for pre-existing rows in every MySQL configuration -- this runner
     * makes that explicit and idempotent.
     */
    private void ensureInventoryItemHasOwnStockColumn() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'inventory_items' AND COLUMN_NAME = 'has_own_stock'",
                Integer.class
            );
            if (count != null && count == 0) {
                jdbcTemplate.execute("ALTER TABLE inventory_items ADD COLUMN has_own_stock TINYINT(1) NOT NULL DEFAULT 1");
                log.info("DB migration applied: inventory_items.has_own_stock column added (defaulted to true for existing rows)");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (inventory_items.has_own_stock): {}", e.getMessage());
        }
    }

    /**
     * Creates the global inventory catalog + per-hospital services tables if
     * absent: inventory_master_items (platform-global item names),
     * hospital_services (per-hospital billable procedures), and
     * hospital_service_items (join to master items). Idempotent, each checked
     * independently.
     */
    private void ensureInventoryServicesTables() {
        try {
            Integer masterCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'inventory_master_items'",
                Integer.class);
            if (masterCount != null && masterCount == 0) {
                jdbcTemplate.execute(
                    "CREATE TABLE inventory_master_items (" +
                    "  id BIGINT NOT NULL AUTO_INCREMENT," +
                    "  name VARCHAR(255) NOT NULL," +
                    "  created_at DATETIME(6) NOT NULL," +
                    "  PRIMARY KEY (id)" +
                    ")");
                log.info("DB migration applied: inventory_master_items table created");
            }

            Integer svcCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'hospital_services'",
                Integer.class);
            if (svcCount != null && svcCount == 0) {
                jdbcTemplate.execute(
                    "CREATE TABLE hospital_services (" +
                    "  id BIGINT NOT NULL AUTO_INCREMENT," +
                    "  hospital_id BIGINT NOT NULL," +
                    "  name VARCHAR(150) NOT NULL," +
                    "  charge DECIMAL(10,2) NOT NULL," +
                    "  is_active TINYINT(1) NOT NULL DEFAULT 1," +
                    "  created_at DATETIME(6) NOT NULL," +
                    "  PRIMARY KEY (id)," +
                    "  FOREIGN KEY (hospital_id) REFERENCES hospitals(id) ON DELETE CASCADE" +
                    ")");
                log.info("DB migration applied: hospital_services table created");
            }

            Integer itemCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'hospital_service_items'",
                Integer.class);
            if (itemCount != null && itemCount == 0) {
                jdbcTemplate.execute(
                    "CREATE TABLE hospital_service_items (" +
                    "  id BIGINT NOT NULL AUTO_INCREMENT," +
                    "  service_id BIGINT NOT NULL," +
                    "  master_item_id BIGINT NOT NULL," +
                    "  PRIMARY KEY (id)," +
                    "  FOREIGN KEY (service_id) REFERENCES hospital_services(id) ON DELETE CASCADE" +
                    ")");
                log.info("DB migration applied: hospital_service_items table created");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (inventory services tables): {}", e.getMessage());
        }
    }

    /**
     * Add nullable branch_id to audit_logs for pharmacy audit trail isolation.
     * Null = not branch-scoped (single-shop / hospital / clinic pharmacy, platform actions).
     */
    private void ensureAuditLogsBranchColumn() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'audit_logs' AND COLUMN_NAME = 'branch_id'",
                Integer.class
            );
            if (count != null && count == 0) {
                jdbcTemplate.execute("ALTER TABLE audit_logs ADD COLUMN branch_id BIGINT DEFAULT NULL");
                log.info("DB migration applied: audit_logs.branch_id column added");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (audit_logs.branch_id): {}", e.getMessage());
        }
    }

    private void ensureOpdAdmitRecommendedColumn() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'opd' AND COLUMN_NAME = 'ipd_admit_recommended'",
                Integer.class
            );
            if (count != null && count == 0) {
                jdbcTemplate.execute("ALTER TABLE opd ADD COLUMN ipd_admit_recommended TINYINT(1) NOT NULL DEFAULT 0");
                log.info("DB migration applied: added ipd_admit_recommended column to opd table");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (ipd_admit_recommended): {}", e.getMessage());
        }
    }

    private void ensureNurseProfilePhaseAColumns() {
        addColumnIfMissing("nurse_profiles", "is_incharge", "TINYINT(1) NOT NULL DEFAULT 0");
        addColumnIfMissing("nurse_profiles", "gender", "VARCHAR(10) NULL");
        addColumnIfMissing("nurse_profiles", "qualification", "VARCHAR(120) NULL");
        addColumnIfMissing("nurse_profiles", "registration_number", "VARCHAR(60) NULL");
        addColumnIfMissing("nurse_profiles", "joining_date", "DATE NULL");
    }

    private void ensureWardInchargeColumn() {
        addColumnIfMissing("wards", "incharge_nurse_id", "BIGINT NULL");
    }

    private void ensureSeparateNurseLoginColumn() {
        addColumnIfMissing("hospital_settings", "separate_nurse_login", "TINYINT(1) NOT NULL DEFAULT 0");
    }

    private void ensureOtInchargeEnabledColumn() {
        addColumnIfMissing("hospital_settings", "ot_incharge_enabled", "TINYINT(1) NOT NULL DEFAULT 0");
    }

    private void ensurePerformedByNurseIdColumns() {
        for (String t : new String[]{"vitals_records","nursing_notes","medication_administrations","sugar_chart_entries","surgery_forms"}) {
            addColumnIfMissing(t, "performed_by_nurse_id", "BIGINT NULL");
        }
    }

    /** Adds a column only if it does not already exist. Idempotent. */
    /**
     * OT Phase 9 — the theatre occupancy timeline. A span opens when a case starts and
     * closes when it completes or is cancelled; utilisation and turnover are queries over it.
     */
    private void ensureOtRoomOccupancyTable() {
        try {
            Integer c = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.TABLES " +
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ot_room_occupancy'", Integer.class);
            if (c != null && c == 0) {
                jdbcTemplate.execute(
                        "CREATE TABLE ot_room_occupancy (" +
                        "  id BIGINT NOT NULL AUTO_INCREMENT," +
                        "  hospital_id BIGINT NOT NULL," +
                        "  ot_room_id BIGINT NOT NULL," +
                        "  surgery_id BIGINT NOT NULL," +
                        "  occupied_from DATETIME(6) NOT NULL," +
                        "  occupied_to DATETIME(6) NULL," +
                        "  PRIMARY KEY (id)," +
                        "  KEY idx_occupancy_room (ot_room_id, occupied_from)," +
                        "  KEY idx_occupancy_hospital (hospital_id, occupied_from)," +
                        "  CONSTRAINT FK_occupancy_hospital FOREIGN KEY (hospital_id) " +
                        "    REFERENCES hospitals (id) ON DELETE CASCADE" +
                        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                log.info("DB migration applied: created ot_room_occupancy table");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (ot_room_occupancy): {}", e.getMessage());
        }
    }

    /**
     * OT Phase 8 — PACU recovery. An episode is created only when the hospital's
     * RECOVERY_TRACKING policy asks for it, and it is never a case state: the theatre is
     * free while the patient recovers.
     */
    private void ensureRecoveryTables() {
        try {
            Integer ep = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.TABLES " +
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ot_recovery_episodes'", Integer.class);
            if (ep != null && ep == 0) {
                jdbcTemplate.execute(
                        "CREATE TABLE ot_recovery_episodes (" +
                        "  id BIGINT NOT NULL AUTO_INCREMENT," +
                        "  hospital_id BIGINT NOT NULL," +
                        "  surgery_id BIGINT NOT NULL," +
                        "  patient_id BIGINT NOT NULL," +
                        "  arrived_at DATETIME(6) NOT NULL," +
                        "  discharged_at DATETIME(6) NULL," +
                        "  transfer_destination VARCHAR(20) NULL," +
                        "  arrived_by_user_id BIGINT NULL," +
                        "  discharged_by_user_id BIGINT NULL," +
                        "  PRIMARY KEY (id)," +
                        "  UNIQUE KEY uk_recovery_surgery (surgery_id)," +
                        "  CONSTRAINT FK_recovery_hospital FOREIGN KEY (hospital_id) " +
                        "    REFERENCES hospitals (id) ON DELETE CASCADE" +
                        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                log.info("DB migration applied: created ot_recovery_episodes table");
            }
            Integer obs = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.TABLES " +
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ot_recovery_observations'", Integer.class);
            if (obs != null && obs == 0) {
                jdbcTemplate.execute(
                        "CREATE TABLE ot_recovery_observations (" +
                        "  id BIGINT NOT NULL AUTO_INCREMENT," +
                        "  hospital_id BIGINT NOT NULL," +
                        "  episode_id BIGINT NOT NULL," +
                        "  observed_at DATETIME(6) NOT NULL," +
                        "  aldrete_score INT NULL," +
                        "  recorded_by_user_id BIGINT NULL," +
                        "  performed_by_nurse_id BIGINT NULL," +
                        "  note VARCHAR(255) NULL," +
                        "  created_at DATETIME(6) NOT NULL," +
                        "  PRIMARY KEY (id)," +
                        "  KEY idx_recovery_obs_episode (episode_id, observed_at)," +
                        "  CONSTRAINT FK_recovery_obs_hospital FOREIGN KEY (hospital_id) " +
                        "    REFERENCES hospitals (id) ON DELETE CASCADE" +
                        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                log.info("DB migration applied: created ot_recovery_observations table");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (recovery tables): {}", e.getMessage());
        }
    }

    /**
     * OT Phase 7 — WHO checklist (phases as signed columns, so compliance is a query),
     * clinical milestones (append-only facts, never states), and the operative note.
     */
    /**
     * OT 4.6A — immutable clinician clearance decisions and explicit emergency bypasses.
     * Mirrors setup/schema-full.sql and remains safe on an already populated database.
     */
    private void ensureSurgeryPreOpSafetyTables() {
        try {
            Integer clearances = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'surgery_anaesthesia_clearances'",
                    Integer.class);
            if (clearances != null && clearances == 0) {
                jdbcTemplate.execute("CREATE TABLE surgery_anaesthesia_clearances ("
                        + "id BIGINT NOT NULL AUTO_INCREMENT, surgery_id BIGINT NOT NULL, hospital_id BIGINT NOT NULL, "
                        + "outcome VARCHAR(40) NOT NULL, conditions_comments TEXT NULL, recorded_by_user_id BIGINT NOT NULL, "
                        + "recorded_at DATETIME(6) NOT NULL, PRIMARY KEY (id), "
                        + "KEY idx_sac_hospital_surgery_time (hospital_id,surgery_id,recorded_at), "
                        + "KEY idx_sac_surgery (surgery_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            }
            Integer overrides = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'surgery_emergency_overrides'",
                    Integer.class);
            if (overrides != null && overrides == 0) {
                jdbcTemplate.execute("CREATE TABLE surgery_emergency_overrides ("
                        + "id BIGINT NOT NULL AUTO_INCREMENT, surgery_id BIGINT NOT NULL, hospital_id BIGINT NOT NULL, "
                        + "reason TEXT NOT NULL, bypassed_gates VARCHAR(100) NOT NULL, recorded_by_user_id BIGINT NOT NULL, "
                        + "recorded_at DATETIME(6) NOT NULL, PRIMARY KEY (id), "
                        + "KEY idx_seo_hospital_surgery_time (hospital_id,surgery_id,recorded_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (OT pre-op safety): {}", e.getMessage());
        }
    }

    private void ensureSurgeryExecutionTables() {
        try {
            Integer who = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.TABLES " +
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'who_checklists'", Integer.class);
            if (who != null && who == 0) {
                jdbcTemplate.execute(
                        "CREATE TABLE who_checklists (" +
                        "  id BIGINT NOT NULL AUTO_INCREMENT," +
                        "  hospital_id BIGINT NOT NULL," +
                        "  surgery_id BIGINT NOT NULL," +
                        "  sign_in_at DATETIME(6) NULL, sign_in_by_user_id BIGINT NULL," +
                        "  time_out_at DATETIME(6) NULL, time_out_by_user_id BIGINT NULL," +
                        "  sign_out_at DATETIME(6) NULL, sign_out_by_user_id BIGINT NULL," +
                        "  site_marked TINYINT(1) NULL, counts_correct TINYINT(1) NULL," +
                        "  PRIMARY KEY (id)," +
                        "  UNIQUE KEY uk_who_surgery (surgery_id)," +
                        "  CONSTRAINT FK_who_hospital FOREIGN KEY (hospital_id) " +
                        "    REFERENCES hospitals (id) ON DELETE CASCADE" +
                        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                log.info("DB migration applied: created who_checklists table");
            }
            Integer milestones = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.TABLES " +
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'surgery_milestones'", Integer.class);
            if (milestones != null && milestones == 0) {
                jdbcTemplate.execute(
                        "CREATE TABLE surgery_milestones (" +
                        "  id BIGINT NOT NULL AUTO_INCREMENT," +
                        "  hospital_id BIGINT NOT NULL," +
                        "  surgery_id BIGINT NOT NULL," +
                        "  milestone VARCHAR(30) NOT NULL," +
                        "  occurred_at DATETIME(6) NOT NULL," +
                        "  recorded_by_user_id BIGINT NULL," +
                        "  performed_by_nurse_id BIGINT NULL," +
                        "  note VARCHAR(255) NULL," +
                        "  created_at DATETIME(6) NOT NULL," +
                        "  PRIMARY KEY (id)," +
                        "  KEY idx_milestone_surgery (surgery_id, occurred_at)," +
                        "  CONSTRAINT FK_milestone_hospital FOREIGN KEY (hospital_id) " +
                        "    REFERENCES hospitals (id) ON DELETE CASCADE" +
                        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                log.info("DB migration applied: created surgery_milestones table");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (surgery execution tables): {}", e.getMessage());
        }
        addColumnIfMissing("surgeries", "operative_note", "TEXT NULL");
        addColumnIfMissing("surgeries", "operative_note_by_user_id", "BIGINT NULL");
        addColumnIfMissing("surgeries", "operative_note_at", "DATETIME(6) NULL");
    }

    /**
     * OT Phase 6 — the surgical team, and a hospital's custom case roles.
     * A new specialty role (HARVEST_SURGEON, PERFUSIONIST) is a row in case_roles, never
     * a code change. The free-text surgeon_name/anaesthetist_name columns on surgeries stay
     * as a fallback for an external operator with no user row.
     */
    private void ensureSurgeryTeamTables() {
        try {
            Integer roles = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.TABLES " +
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_roles'", Integer.class);
            if (roles != null && roles == 0) {
                jdbcTemplate.execute(
                        "CREATE TABLE case_roles (" +
                        "  id BIGINT NOT NULL AUTO_INCREMENT," +
                        "  hospital_id BIGINT NOT NULL," +
                        "  code VARCHAR(40) NOT NULL," +
                        "  label VARCHAR(100) NOT NULL," +
                        "  is_active TINYINT(1) NOT NULL DEFAULT 1," +
                        "  PRIMARY KEY (id)," +
                        "  UNIQUE KEY uk_case_role (hospital_id, code)," +
                        "  CONSTRAINT FK_case_role_hospital FOREIGN KEY (hospital_id) " +
                        "    REFERENCES hospitals (id) ON DELETE CASCADE" +
                        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                log.info("DB migration applied: created case_roles table");
            }
            Integer team = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.TABLES " +
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'surgery_team_members'", Integer.class);
            if (team != null && team == 0) {
                jdbcTemplate.execute(
                        "CREATE TABLE surgery_team_members (" +
                        "  id BIGINT NOT NULL AUTO_INCREMENT," +
                        "  hospital_id BIGINT NOT NULL," +
                        "  surgery_id BIGINT NOT NULL," +
                        "  case_role_code VARCHAR(40) NOT NULL," +
                        "  user_id BIGINT NULL," +
                        "  external_name VARCHAR(255) NULL," +
                        "  created_at DATETIME(6) NOT NULL," +
                        "  PRIMARY KEY (id)," +
                        "  KEY idx_team_surgery (surgery_id)," +
                        "  CONSTRAINT FK_team_hospital FOREIGN KEY (hospital_id) " +
                        "    REFERENCES hospitals (id) ON DELETE CASCADE" +
                        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                log.info("DB migration applied: created surgery_team_members table");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (surgery team tables): {}", e.getMessage());
        }
    }

    /**
     * OT Phase 5 — a hospital's workflow policy overrides, optionally scoped to a case
     * priority. Overrides only: an absent row means OtPolicies.defaultValue(key), so a
     * hospital that never opens Settings behaves exactly as the small-hospital default.
     */
    private void ensureOtWorkflowPoliciesTable() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.TABLES " +
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ot_workflow_policies'", Integer.class);
            if (count != null && count == 0) {
                jdbcTemplate.execute(
                        "CREATE TABLE ot_workflow_policies (" +
                        "  id BIGINT NOT NULL AUTO_INCREMENT," +
                        "  hospital_id BIGINT NOT NULL," +
                        "  policy_key VARCHAR(40) NOT NULL," +
                        "  priority_scope VARCHAR(10) NOT NULL DEFAULT 'ANY'," +
                        "  value VARCHAR(120) NOT NULL," +
                        "  PRIMARY KEY (id)," +
                        "  UNIQUE KEY uk_ot_policy (hospital_id, policy_key, priority_scope)," +
                        "  KEY idx_ot_policy_hospital (hospital_id)," +
                        "  CONSTRAINT FK_ot_policy_hospital FOREIGN KEY (hospital_id) " +
                        "    REFERENCES hospitals (id) ON DELETE CASCADE" +
                        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                log.info("DB migration applied: created ot_workflow_policies table");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (ot_workflow_policies): {}", e.getMessage());
        }
    }

    /**
     * OT Phase 4 — an operation theatre is a first-class resource.
     * An OT used to be "any ward whose name contains OT", filtered in the browser.
     * Existing OT wards are NOT auto-converted: the same heuristic matches "FOOT WARD",
     * so OtRoomService.suggestFromWards() proposes them and an admin confirms.
     */
    private void ensureOtRoomsTable() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.TABLES " +
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ot_rooms'", Integer.class);
            if (count != null && count == 0) {
                jdbcTemplate.execute(
                        "CREATE TABLE ot_rooms (" +
                        "  id BIGINT NOT NULL AUTO_INCREMENT," +
                        "  public_id VARCHAR(255) NOT NULL," +
                        "  hospital_id BIGINT NOT NULL," +
                        "  name VARCHAR(100) NOT NULL," +
                        "  status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE'," +
                        "  current_surgery_id BIGINT NULL," +
                        "  turnover_minutes INT NOT NULL DEFAULT 15," +
                        "  source_ward_id BIGINT NULL," +
                        "  is_active TINYINT(1) NOT NULL DEFAULT 1," +
                        "  created_at DATETIME(6) NOT NULL," +
                        "  PRIMARY KEY (id)," +
                        "  UNIQUE KEY UK_ot_room_public_id (public_id)," +
                        "  UNIQUE KEY uk_ot_room_name (hospital_id, name)," +
                        "  CONSTRAINT FK_ot_room_hospital FOREIGN KEY (hospital_id) " +
                        "    REFERENCES hospitals (id) ON DELETE CASCADE" +
                        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                log.info("DB migration applied: created ot_rooms table");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (ot_rooms): {}", e.getMessage());
        }
    }

    /** OT Phase 4 — the theatre link and the duration that makes interval booking possible. */
    private void ensureSurgeryRoomColumns() {
        addColumnIfMissing("surgeries", "ot_room_id", "BIGINT NULL");
        addColumnIfMissing("surgeries", "estimated_duration_minutes", "INT NULL");
        try {
            jdbcTemplate.execute("CREATE INDEX idx_surgery_room_scheduled ON surgeries(ot_room_id, scheduled_at)");
            log.info("DB migration applied: index surgeries(ot_room_id, scheduled_at)");
        } catch (Exception e) {
            // already present
        }
    }

    /**
     * OT Phase 3 — append-only audit of every case status change.
     * Every board metric (turnover, on-time start, cancellation rate by reason) is a query
     * over this table, and NABH asks who moved the case, when, and why.
     */
    private void ensureSurgeryStateTransitionsTable() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.TABLES " +
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'surgery_state_transitions'", Integer.class);
            if (count != null && count == 0) {
                jdbcTemplate.execute(
                        "CREATE TABLE surgery_state_transitions (" +
                        "  id BIGINT NOT NULL AUTO_INCREMENT," +
                        "  hospital_id BIGINT NOT NULL," +
                        "  surgery_id BIGINT NOT NULL," +
                        "  from_status VARCHAR(20) NULL," +
                        "  to_status VARCHAR(20) NOT NULL," +
                        "  actor_user_id BIGINT NULL," +
                        "  actor_kind VARCHAR(10) NOT NULL," +
                        "  reason_code VARCHAR(60) NULL," +
                        "  reason_text VARCHAR(255) NULL," +
                        "  payload_json TEXT NULL," +
                        "  created_at DATETIME(6) NOT NULL," +
                        "  PRIMARY KEY (id)," +
                        "  KEY idx_sst_surgery (surgery_id, created_at)," +
                        "  KEY idx_sst_hospital_to (hospital_id, to_status, created_at)," +
                        "  CONSTRAINT FK_sst_hospital FOREIGN KEY (hospital_id) " +
                        "    REFERENCES hospitals (id) ON DELETE CASCADE" +
                        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                log.info("DB migration applied: created surgery_state_transitions table");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (surgery_state_transitions): {}", e.getMessage());
        }
    }

    /** OT Phase 3 — waiting-list ordering lives on the surgery, not in a WAITLISTED status. */
    private void ensureSurgeryWaitlistColumns() {
        addColumnIfMissing("surgeries", "waitlist_priority", "INT NOT NULL DEFAULT 0");
        addColumnIfMissing("surgeries", "target_date", "DATE NULL");
        addColumnIfMissing("surgeries", "approved_at", "DATETIME(6) NULL");
        try {
            jdbcTemplate.execute("CREATE INDEX idx_surgery_hospital_scheduled ON surgeries(hospital_id, scheduled_at)");
            log.info("DB migration applied: index surgeries(hospital_id, scheduled_at)");
        } catch (Exception e) {
            // already present
        }
    }

    /** Adds the optimistic lifecycle revision used by schedule/reschedule commands. */
    private void ensureSurgeryLifecycleVersionColumn() {
        addColumnIfMissing("surgeries", "lifecycle_version", "BIGINT NOT NULL DEFAULT 0");
    }

    /**
     * OT Phase 2 — a hospital's OT permission grants, keyed on the role STRING.
     * Overrides only: zero rows for a hospital means "use OtPermissions.defaultsFor(role)",
     * so nothing needs seeding and existing hospitals behave exactly as before.
     */
    /**
     * Support tickets used to be saved without hospital_type, so they never matched the
     * platform admin's type-filtered ticket tab. Backfill the tenant type from each ticket's
     * hospital. Idempotent: only touches rows still NULL.
     */
    private void backfillSupportTicketHospitalType() {
        try {
            int updated = jdbcTemplate.update(
                    "UPDATE support_tickets t JOIN hospitals h ON h.id = t.hospital_id " +
                    "SET t.hospital_type = h.type WHERE t.hospital_type IS NULL");
            if (updated > 0) log.info("DB migration applied: backfilled hospital_type on {} support tickets", updated);
        } catch (Exception e) {
            log.warn("DB migration skipped (support_tickets hospital_type backfill): {}", e.getMessage());
        }
    }

    private void ensureRolePermissionsTable() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.TABLES " +
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'role_permissions'", Integer.class);
            if (count != null && count == 0) {
                jdbcTemplate.execute(
                        "CREATE TABLE role_permissions (" +
                        "  id BIGINT NOT NULL AUTO_INCREMENT," +
                        "  hospital_id BIGINT NOT NULL," +
                        "  role VARCHAR(30) NOT NULL," +
                        "  permission_code VARCHAR(40) NOT NULL," +
                        "  PRIMARY KEY (id)," +
                        "  UNIQUE KEY UK_role_permission (hospital_id, role, permission_code)," +
                        "  KEY idx_role_permission_hospital (hospital_id)," +
                        "  CONSTRAINT FK_role_permission_hospital FOREIGN KEY (hospital_id) " +
                        "    REFERENCES hospitals (id) ON DELETE CASCADE" +
                        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                log.info("DB migration applied: created role_permissions table");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (role_permissions): {}", e.getMessage());
        }
    }

    /**
     * OT Phase 1 — a surgery is its own aggregate, anchored on the patient.
     * An IPD admission becomes optional so day-care procedures (cataract,
     * endoscopy, minor orthopaedics) can exist without one.
     */
    private void ensureSurgeryDayCareColumns() {
        addColumnIfMissing("surgeries", "encounter_type", "VARCHAR(20) NOT NULL DEFAULT 'IPD'");
        try {
            jdbcTemplate.execute("ALTER TABLE surgeries MODIFY ipd_admission_id BIGINT NULL");
            log.info("DB migration applied: surgeries.ipd_admission_id is now nullable (day-care)");
        } catch (Exception e) {
            log.warn("DB migration skipped (surgeries.ipd_admission_id nullable): {}", e.getMessage());
        }
    }

    /**
     * OT Phase 1 — re-key surgery_forms from the ADMISSION to the SURGERY.
     *
     * The old unique key (ipd_admission_id, form_type) meant a second procedure in
     * the same admission overwrote the first procedure's signed consent. Order is
     * load-bearing: backfill surgery_id BEFORE swapping the key, because MySQL
     * treats NULLs in a unique key as distinct and would silently admit duplicates.
     */
    private void ensureSurgeryFormProcedureScope() {
        addColumnIfMissing("surgery_forms", "version", "INT NOT NULL DEFAULT 1");
        addColumnIfMissing("surgery_forms", "is_current", "TINYINT(1) DEFAULT 1");
        addColumnIfMissing("surgery_forms", "signed_at", "DATETIME(6) NULL");
        addColumnIfMissing("surgery_forms", "signed_by_user_id", "BIGINT NULL");
        addColumnIfMissing("surgery_forms", "recorded_by_user_id", "BIGINT NULL");

        // A day-care form has no admission.
        try {
            jdbcTemplate.execute("ALTER TABLE surgery_forms MODIFY ipd_admission_id BIGINT NULL");
        } catch (Exception e) {
            log.warn("DB migration skipped (surgery_forms.ipd_admission_id nullable): {}", e.getMessage());
        }

        // 1. Backfill: attach each orphan form to its admission's earliest non-cancelled surgery.
        try {
            int updated = jdbcTemplate.update(
                    "UPDATE surgery_forms f JOIN (" +
                    "  SELECT ipd_admission_id, MIN(id) AS sid FROM surgeries " +
                    "  WHERE status <> 'CANCELLED' AND ipd_admission_id IS NOT NULL " +
                    "  GROUP BY ipd_admission_id" +
                    ") s ON s.ipd_admission_id = f.ipd_admission_id " +
                    "SET f.surgery_id = s.sid WHERE f.surgery_id IS NULL");
            if (updated > 0) log.info("DB migration applied: backfilled surgery_id on {} surgery_forms rows", updated);

            // Reconciliation report: admissions with >1 surgery could not be disambiguated
            // automatically. Never guess silently.
            Integer ambiguous = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM (SELECT ipd_admission_id FROM surgeries " +
                    "WHERE status <> 'CANCELLED' AND ipd_admission_id IS NOT NULL " +
                    "GROUP BY ipd_admission_id HAVING COUNT(*) > 1) x", Integer.class);
            if (ambiguous != null && ambiguous > 0) {
                log.warn("OT Phase 1 reconciliation: {} admission(s) have multiple surgeries. Their forms were " +
                        "attached to the EARLIEST non-cancelled surgery and need manual review.", ambiguous);
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (surgery_forms surgery_id backfill): {}", e.getMessage());
        }

        // 2. Swap the unique key. Old key first, so the new one is never fighting it.
        dropIndexIfExists("surgery_forms", "UK_surgery_form_admission_type");
        if (!indexExists("surgery_forms", "UK_surgery_form_surgery_type")) {
            try {
                jdbcTemplate.execute("ALTER TABLE surgery_forms ADD CONSTRAINT UK_surgery_form_surgery_type " +
                        "UNIQUE (surgery_id, form_type, is_current)");
                log.info("DB migration applied: surgery_forms unique key is now (surgery_id, form_type, is_current)");
            } catch (Exception e) {
                log.warn("DB migration skipped (surgery_forms new unique key): {}", e.getMessage());
            }
        }

        // 3. Tighten only when the backfill left nothing behind. A form saved before any
        // surgery existed has no surgery to attach to; keep it readable rather than fail boot.
        try {
            Integer orphans = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM surgery_forms WHERE surgery_id IS NULL", Integer.class);
            if (orphans != null && orphans == 0) {
                jdbcTemplate.execute("ALTER TABLE surgery_forms MODIFY surgery_id BIGINT NOT NULL");
                log.info("DB migration applied: surgery_forms.surgery_id is now NOT NULL");
            } else if (orphans != null) {
                log.warn("OT Phase 1: {} surgery_forms row(s) have no surgery and stay nullable. " +
                        "They predate any surgery request; review and attach or delete them.", orphans);
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (surgery_forms.surgery_id NOT NULL): {}", e.getMessage());
        }
    }

    private boolean indexExists(String table, String indexName) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.STATISTICS " +
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?",
                    Integer.class, table, indexName);
            return count != null && count > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void dropIndexIfExists(String table, String indexName) {
        if (!indexExists(table, indexName)) return;
        try {
            jdbcTemplate.execute("ALTER TABLE " + table + " DROP INDEX " + indexName);
            log.info("DB migration applied: dropped index {}.{}", table, indexName);
        } catch (Exception e) {
            log.warn("DB migration skipped (drop index {}.{}): {}", table, indexName, e.getMessage());
        }
    }

    /**
     * OT-P0A reconciliation. OtPermissionService freezes a hospital's role_permissions rows the
     * moment it saves its first customisation: from then on, effectiveFor() reads only those
     * rows and never OtPermissions.defaultsFor(role) again. A hospital that customised before
     * this fix therefore cannot see OT_ASSIGN_TEAM / OT_RECOVERY / OT_TRANSFER acquire owners --
     * every role stays permanently at whatever it had the day the matrix was first saved.
     *
     * These three codes are not new: OtPermissions.ALL and the admin matrix catalogue always
     * listed them, so a hospital that already granted one of them to some role has an explicit,
     * intentional row for it. Touching that hospital's rows for that code -- even to add a
     * second role -- would either double a decision it already made or overwrite one it didn't.
     * The only population this can safely help is a hospital with ZERO rows for a given code,
     * across every role: nothing was ever decided about it, so the v2 default is a genuinely new
     * default arriving, not a correction being forced onto a customised choice.
     *
     * Residual, accepted gap: role_permissions has no history, so a hospital that once granted a
     * code and later revoked it from every role is indistinguishable from one that never
     * considered it -- both read as zero rows. Building a permission audit trail to close that
     * gap is disproportionate for three codes nobody could reach before this release (they had
     * no default owner, so reaching them required an admin to open the full catalogue and grant
     * one deliberately); flagged here rather than silently assumed away.
     */
    private void reconcileOtPermissionOrphanDefaults() {
        try {
            java.util.List<Long> configuredHospitalIds = jdbcTemplate.queryForList(
                    "SELECT DISTINCT hospital_id FROM role_permissions", Long.class);
            if (configuredHospitalIds.isEmpty()) return;

            java.util.List<String> orphanCodes = java.util.List.of(
                    com.hms.security.OtPermissions.OT_ASSIGN_TEAM,
                    com.hms.security.OtPermissions.OT_RECOVERY,
                    com.hms.security.OtPermissions.OT_TRANSFER);

            int inserted = 0;
            for (Long hospitalId : configuredHospitalIds) {
                for (String code : orphanCodes) {
                    Integer existing = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM role_permissions WHERE hospital_id = ? AND permission_code = ?",
                            Integer.class, hospitalId, code);
                    if (existing != null && existing > 0) continue; // this hospital has engaged with this code

                    for (String role : com.hms.security.OtPermissions.ROLES) {
                        if (!com.hms.security.OtPermissions.defaultsFor(role).contains(code)) continue;
                        jdbcTemplate.update(
                                "INSERT IGNORE INTO role_permissions (hospital_id, role, permission_code) "
                                        + "VALUES (?, ?, ?)",
                                hospitalId, role, code);
                        inserted++;
                    }
                }
            }
            if (inserted > 0) {
                log.info("DB migration applied: backfilled {} OT permission grant(s) for {} previously "
                        + "configured hospital(s) (OT_ASSIGN_TEAM/OT_RECOVERY/OT_TRANSFER)",
                        inserted, configuredHospitalIds.size());
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (OT permission reconciliation): {}", e.getMessage());
        }
    }

    /**
     * OT-P0B — a recovery bay is the smallest additive representation of "where" a recovering
     * patient is. Deliberately not an ot_room (theatres are turned over and freed the moment a
     * case COMPLETEs; recovery is a separate resource so occupancy in one is never read as
     * occupancy in the other) and not a Ward/Bed (recovery has its own tiny lifecycle -- a bay is
     * simply named and active/inactive, occupancy is derived from whether an undischarged
     * ot_recovery_episodes row currently references it).
     */
    private void ensureRecoveryBaysTable() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.TABLES "
                    + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recovery_bays'", Integer.class);
            if (count != null && count == 0) {
                jdbcTemplate.execute(
                        "CREATE TABLE recovery_bays ("
                        + "  id BIGINT NOT NULL AUTO_INCREMENT,"
                        + "  public_id VARCHAR(36) NOT NULL,"
                        + "  hospital_id BIGINT NOT NULL,"
                        + "  name VARCHAR(100) NOT NULL,"
                        + "  is_active TINYINT(1) NOT NULL DEFAULT 1,"
                        + "  created_at DATETIME(6) NOT NULL,"
                        + "  PRIMARY KEY (id),"
                        + "  UNIQUE KEY uk_recovery_bay_public_id (public_id),"
                        + "  UNIQUE KEY uk_recovery_bay_name (hospital_id, name),"
                        + "  CONSTRAINT FK_recovery_bay_hospital FOREIGN KEY (hospital_id) "
                        + "    REFERENCES hospitals (id) ON DELETE CASCADE"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                log.info("DB migration applied: created recovery_bays table");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (recovery_bays): {}", e.getMessage());
        }
    }

    /**
     * INV-2/INV-3 safety net for the batch + ledger tables (Flyway V14 is the authority).
     * Creation only -- the legacy medicines.stock_quantity -> opening-batch conversion lives in
     * V14 alone, because a data backfill that ran on every boot could double-post stock.
     */
    private void ensureMedicineStockTables() {
        try {
            Integer batches = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.TABLES "
                    + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'medicine_stock_batches'", Integer.class);
            if (batches != null && batches == 0) {
                jdbcTemplate.execute(
                        "CREATE TABLE medicine_stock_batches ("
                        + "  id BIGINT NOT NULL AUTO_INCREMENT,"
                        + "  public_id VARCHAR(36) NOT NULL,"
                        + "  hospital_id BIGINT NOT NULL,"
                        + "  medicine_id BIGINT NOT NULL,"
                        + "  batch_number VARCHAR(100) NOT NULL,"
                        + "  expiry_date DATE NOT NULL,"
                        + "  received_quantity INT NOT NULL DEFAULT 0,"
                        + "  current_quantity INT NOT NULL DEFAULT 0,"
                        + "  unit_price DOUBLE NULL,"
                        + "  is_active TINYINT(1) NOT NULL DEFAULT 1,"
                        + "  received_at DATETIME(6) NOT NULL,"
                        + "  created_at DATETIME(6) NOT NULL,"
                        + "  PRIMARY KEY (id),"
                        + "  UNIQUE KEY uk_medicine_stock_batch_public_id (public_id),"
                        + "  UNIQUE KEY uk_medicine_batch (hospital_id, medicine_id, batch_number),"
                        + "  KEY idx_medicine_batch_fefo (hospital_id, medicine_id, expiry_date),"
                        + "  CONSTRAINT FK_medicine_batch_medicine FOREIGN KEY (medicine_id) "
                        + "    REFERENCES medicines (id) ON DELETE CASCADE,"
                        + "  CONSTRAINT ck_medicine_batch_qty_non_negative CHECK (current_quantity >= 0)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                log.info("DB migration applied: created medicine_stock_batches table");
            }
            Integer movements = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.TABLES "
                    + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_movements'", Integer.class);
            if (movements != null && movements == 0) {
                jdbcTemplate.execute(
                        "CREATE TABLE stock_movements ("
                        + "  id BIGINT NOT NULL AUTO_INCREMENT,"
                        + "  public_id VARCHAR(36) NOT NULL,"
                        + "  hospital_id BIGINT NOT NULL,"
                        + "  inventory_domain VARCHAR(20) NOT NULL,"
                        + "  item_id BIGINT NOT NULL,"
                        + "  batch_id BIGINT NULL,"
                        + "  movement_type VARCHAR(30) NOT NULL,"
                        + "  direction VARCHAR(3) NOT NULL,"
                        + "  quantity INT NOT NULL,"
                        + "  balance_after INT NULL,"
                        + "  reference_type VARCHAR(40) NULL,"
                        + "  reference_id BIGINT NULL,"
                        + "  idempotency_key VARCHAR(100) NULL,"
                        + "  performed_by_user_id BIGINT NULL,"
                        + "  remarks VARCHAR(255) NULL,"
                        + "  created_at DATETIME(6) NOT NULL,"
                        + "  PRIMARY KEY (id),"
                        + "  UNIQUE KEY uk_stock_movement_public_id (public_id),"
                        + "  UNIQUE KEY uk_stock_movement_idempotency (hospital_id, idempotency_key, batch_id),"
                        + "  KEY idx_stock_movement_item (hospital_id, inventory_domain, item_id, id),"
                        + "  KEY idx_stock_movement_batch (hospital_id, batch_id, id),"
                        + "  CONSTRAINT ck_stock_movement_qty_positive CHECK (quantity > 0),"
                        + "  CONSTRAINT ck_stock_movement_direction CHECK (direction IN ('IN','OUT'))"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                log.info("DB migration applied: created stock_movements table");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (medicine stock tables): {}", e.getMessage());
        }
    }

    /**
     * V16 safety net. Creation only: the table holds request bookkeeping, never clinical data, so
     * there is nothing to backfill and an empty table is the correct starting state.
     */
    private void ensureOpdIdempotencyTable() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.TABLES "
                    + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'opd_idempotency'", Integer.class);
            if (count != null && count == 0) {
                jdbcTemplate.execute(
                        "CREATE TABLE opd_idempotency ("
                        + "  id BIGINT NOT NULL AUTO_INCREMENT,"
                        + "  hospital_id BIGINT NOT NULL,"
                        + "  idempotency_key VARCHAR(100) NOT NULL,"
                        + "  opd_id BIGINT NULL,"
                        + "  created_at DATETIME(6) NOT NULL,"
                        + "  PRIMARY KEY (id),"
                        + "  UNIQUE KEY uk_opd_idempotency (hospital_id, idempotency_key),"
                        + "  KEY idx_opd_idempotency_opd (opd_id)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                log.info("DB migration applied: created opd_idempotency table");
            }
        } catch (Exception e) {
            log.warn("DB migration skipped (opd_idempotency): {}", e.getMessage());
        }
    }

    private void addColumnIfMissing(String table, String column, String definition) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                Integer.class, table, column);
            if (count != null && count == 0) {
                jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
                log.info("DB migration applied: added {}.{}", table, column);
            }
        } catch (Exception e) {
            log.warn("DB migration skipped ({}.{}): {}", table, column, e.getMessage());
        }
    }

    /**
     * Repairs wards whose unit_type is blank.
     *
     * <p>ICU Phase 2 declared the column NOT NULL with a Java-side default only. Hibernate's
     * ddl-auto=update therefore emitted an ALTER with no DB default, and MySQL back-filled the
     * existing rows with '' rather than 'GENERAL' — this migration runs at ApplicationReadyEvent,
     * which is AFTER Hibernate, so its own DEFAULT arrived too late for a database that already
     * had wards. The entity now carries an explicit columnDefinition so new deployments never
     * take that path; this repairs the ones that already did.
     *
     * <p>Harmless while it lasted — CareUnitRegistry.isCriticalCare("") is false, so a blank ward
     * correctly stayed off the ICU board — but a blank is not a valid registry key and must not
     * be allowed to persist.
     */
    private void backfillWardUnitType() {
        try {
            int fixed = jdbcTemplate.update(
                    "UPDATE wards SET unit_type = 'GENERAL' WHERE unit_type IS NULL OR TRIM(unit_type) = ''");
            if (fixed > 0) {
                log.info("DB migration applied: defaulted unit_type on {} ward(s)", fixed);
            }
        } catch (Exception e) {
            log.warn("backfillWardUnitType skipped: {}", e.getMessage());
        }
    }

    /**
     * ICU Phase 9 - alert thresholds.
     *
     * <p>One table, and it ships EMPTY: unlike the other ICU config tables there is no lazy
     * default, because "no row" means no alert rather than a sensible one. A default threshold
     * would be the system deciding what a normal MAP is.
     *
     * <p>Per hospital only. There is deliberately no alert-event table (D-4): the roadmap scopes
     * this phase to threshold storage, so nothing records what fired and nothing de-duplicates.
     */
    private void ensureIcuAlertThresholdTable() {
        createTableIfMissing("icu_alert_threshold",
                "CREATE TABLE icu_alert_threshold ("
                    + " id BIGINT NOT NULL AUTO_INCREMENT,"
                    + " public_id VARCHAR(255) NOT NULL,"
                    + " hospital_id BIGINT NOT NULL,"
                    + " source VARCHAR(20) NOT NULL,"
                    + " metric_key VARCHAR(60) NOT NULL,"
                    + " min_value DECIMAL(12,3) NULL,"
                    + " max_value DECIMAL(12,3) NULL,"
                    + " enabled TINYINT(1) NOT NULL DEFAULT 1,"
                    + " updated_by_user_id BIGINT NULL,"
                    + " is_active TINYINT(1) NOT NULL DEFAULT 1,"
                    + " created_at DATETIME(6) NOT NULL,"
                    + " PRIMARY KEY (id),"
                    + " UNIQUE KEY uk_icu_alert_public_id (public_id),"
                    + " UNIQUE KEY uk_icu_alert_metric (hospital_id, source, metric_key)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    /**
     * ICU Phase 8 - severity scores and the small setting that says which ones a hospital uses.
     *
     * <p>The setting table is deliberately thinner than ICU-7's parameter catalogue: no display
     * name, no unit, no custom rows. A hospital chooses whether it runs SOFA, not what SOFA is -
     * a renamed component would no longer be comparable to anyone else's score.
     *
     * <p>Components live in components_json keyed by component key, and total_score is stored
     * rather than recomputed: a total is part of what was charted at that moment, not a derived
     * view of it.
     */
    private void ensureIcuSeverityScoreTables() {
        createTableIfMissing("icu_score_type_setting",
                "CREATE TABLE icu_score_type_setting ("
                    + " id BIGINT NOT NULL AUTO_INCREMENT,"
                    + " public_id VARCHAR(255) NOT NULL,"
                    + " hospital_id BIGINT NOT NULL,"
                    + " score_type VARCHAR(20) NOT NULL,"
                    + " enabled TINYINT(1) NOT NULL DEFAULT 1,"
                    + " created_at DATETIME(6) NOT NULL,"
                    + " PRIMARY KEY (id),"
                    + " UNIQUE KEY uk_icu_score_type_public_id (public_id),"
                    + " UNIQUE KEY uk_icu_score_type (hospital_id, score_type)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        createTableIfMissing("icu_severity_score",
                "CREATE TABLE icu_severity_score ("
                    + " id BIGINT NOT NULL AUTO_INCREMENT,"
                    + " public_id VARCHAR(255) NOT NULL,"
                    + " hospital_id BIGINT NOT NULL,"
                    + " ipd_admission_id BIGINT NOT NULL,"
                    + " patient_id BIGINT NOT NULL,"
                    + " icu_stay_id BIGINT NULL,"
                    + " score_type VARCHAR(20) NOT NULL,"
                    + " components_json TEXT NULL,"
                    + " total_score INT NULL,"
                    + " scored_at DATETIME(6) NOT NULL,"
                    + " recorded_by_user_id BIGINT NULL,"
                    + " performed_by_nurse_id BIGINT NULL,"
                    + " supersedes_score_id BIGINT NULL,"
                    + " note VARCHAR(255) NULL,"
                    + " is_active TINYINT(1) NOT NULL DEFAULT 1,"
                    + " created_at DATETIME(6) NOT NULL,"
                    + " PRIMARY KEY (id),"
                    + " UNIQUE KEY uk_icu_score_public_id (public_id),"
                    + " KEY idx_icu_score_admission (ipd_admission_id, score_type, scored_at),"
                    + " KEY idx_icu_score_hospital (hospital_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    /**
     * ICU Phase 7 - the ventilator parameter catalogue and the timed snapshots that use it.
     *
     * <p>Two tables because they answer different questions. The parameter table answers "what may
     * be charted?" and holds overrides only - a built-in with no row is enabled, so nothing is
     * seeded here. The setting table answers "what was recorded at 04:00?" and stores values in
     * values_json keyed by param_key, NOT one column per parameter: the catalogue is configurable,
     * and a column per parameter would mean a migration every time a hospital wanted one of its
     * own.
     *
     * <p>ventilation_status stays a typed NOT NULL column - it distinguishes a ventilated row from
     * an extubation row and must be queryable without parsing JSON.
     */
    private void ensureIcuVentilatorTables() {
        createTableIfMissing("icu_ventilator_parameter",
                "CREATE TABLE icu_ventilator_parameter ("
                    + " id BIGINT NOT NULL AUTO_INCREMENT,"
                    + " public_id VARCHAR(255) NOT NULL,"
                    + " hospital_id BIGINT NOT NULL,"
                    + " param_key VARCHAR(60) NOT NULL,"
                    + " display_name VARCHAR(60) NOT NULL,"
                    + " unit VARCHAR(20) NULL,"
                    + " category VARCHAR(20) NOT NULL DEFAULT 'SETTING',"
                    + " value_type VARCHAR(20) NOT NULL DEFAULT 'NUMBER',"
                    + " enabled TINYINT(1) NOT NULL DEFAULT 1,"
                    + " is_custom TINYINT(1) NOT NULL DEFAULT 0,"
                    + " sort_order INT NULL,"
                    + " created_at DATETIME(6) NOT NULL,"
                    + " PRIMARY KEY (id),"
                    + " UNIQUE KEY uk_icu_vent_param_public_id (public_id),"
                    + " UNIQUE KEY uk_icu_vent_param_key (hospital_id, param_key)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        createTableIfMissing("icu_ventilator_setting",
                "CREATE TABLE icu_ventilator_setting ("
                    + " id BIGINT NOT NULL AUTO_INCREMENT,"
                    + " public_id VARCHAR(255) NOT NULL,"
                    + " hospital_id BIGINT NOT NULL,"
                    + " ipd_admission_id BIGINT NOT NULL,"
                    + " patient_id BIGINT NOT NULL,"
                    + " icu_stay_id BIGINT NULL,"
                    + " ventilation_status VARCHAR(20) NOT NULL,"
                    + " values_json TEXT NULL,"
                    + " observed_at DATETIME(6) NOT NULL,"
                    + " recorded_by_user_id BIGINT NULL,"
                    + " performed_by_nurse_id BIGINT NULL,"
                    + " supersedes_setting_id BIGINT NULL,"
                    + " note VARCHAR(255) NULL,"
                    + " is_active TINYINT(1) NOT NULL DEFAULT 1,"
                    + " created_at DATETIME(6) NOT NULL,"
                    + " PRIMARY KEY (id),"
                    + " UNIQUE KEY uk_icu_vent_public_id (public_id),"
                    + " KEY idx_icu_vent_admission (ipd_admission_id, observed_at),"
                    + " KEY idx_icu_vent_hospital (hospital_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    /**
     * ICU Phase 6 - continuous infusions and their rate history.
     *
     * <p>Two tables on purpose: the span is one row, but the rate changes repeatedly and every
     * change must survive, so the current rate is NOT a column on the span. Separate from
     * icu_io_entry by decision (D-1) - an infusion is drug delivery, not a fluid-balance event.
     */
    private void ensureIcuInfusionTables() {
        createTableIfMissing("icu_infusion", "CREATE TABLE icu_infusion ("
                    + " id BIGINT NOT NULL AUTO_INCREMENT,"
                    + " public_id VARCHAR(255) NOT NULL,"
                    + " hospital_id BIGINT NOT NULL,"
                    + " ipd_admission_id BIGINT NOT NULL,"
                    + " patient_id BIGINT NOT NULL,"
                    + " prescription_id BIGINT NULL,"
                    + " medicine_name VARCHAR(255) NOT NULL,"
                    + " started_at DATETIME(6) NOT NULL,"
                    + " stopped_at DATETIME(6) NULL,"
                    + " stop_reason VARCHAR(255) NULL,"
                    + " started_by_user_id BIGINT NULL,"
                    + " performed_by_nurse_id BIGINT NULL,"
                    + " is_active TINYINT(1) NOT NULL DEFAULT 1,"
                    + " created_at DATETIME(6) NOT NULL,"
                    + " PRIMARY KEY (id),"
                    + " UNIQUE KEY uk_icu_inf_public_id (public_id),"
                    + " KEY idx_icu_inf_admission (ipd_admission_id),"
                    + " KEY idx_icu_inf_hospital (hospital_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        createTableIfMissing("icu_infusion_rate", "CREATE TABLE icu_infusion_rate ("
                    + " id BIGINT NOT NULL AUTO_INCREMENT,"
                    + " public_id VARCHAR(255) NOT NULL,"
                    + " hospital_id BIGINT NOT NULL,"
                    + " icu_infusion_id BIGINT NOT NULL,"
                    + " rate_value DECIMAL(12,3) NOT NULL,"
                    + " rate_unit VARCHAR(20) NOT NULL,"
                    + " effective_from DATETIME(6) NOT NULL,"
                    + " recorded_by_user_id BIGINT NULL,"
                    + " performed_by_nurse_id BIGINT NULL,"
                    + " supersedes_rate_id BIGINT NULL,"
                    + " is_active TINYINT(1) NOT NULL DEFAULT 1,"
                    + " created_at DATETIME(6) NOT NULL,"
                    + " PRIMARY KEY (id),"
                    + " UNIQUE KEY uk_icu_inf_rate_public_id (public_id),"
                    + " KEY idx_icu_inf_rate_infusion (icu_infusion_id),"
                    + " KEY idx_icu_inf_rate_effective (icu_infusion_id, effective_from)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    /**
     * ICU Phase 5 - the fluid intake/output event stream.
     *
     * <p>Authoritative for ICU fluid balance and the NABH I/O chart (D-2).
     * {@code vitals_records.urine_output_ml} is a separate point-in-time observation and is
     * never copied in here.
     */
    private void ensureIcuIoEntryTable() {
        try {
            Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
              + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'icu_io_entry'", Integer.class);
            if (exists != null && exists == 0) {
                jdbcTemplate.execute(
                    "CREATE TABLE icu_io_entry ("
                    + " id BIGINT NOT NULL AUTO_INCREMENT,"
                    + " public_id VARCHAR(255) NOT NULL,"
                    + " hospital_id BIGINT NOT NULL,"
                    + " ipd_admission_id BIGINT NOT NULL,"
                    + " patient_id BIGINT NOT NULL,"
                    + " direction VARCHAR(6) NOT NULL,"
                    + " route VARCHAR(30) NOT NULL,"
                    + " volume_ml INT NOT NULL,"
                    + " occurred_at DATETIME(6) NOT NULL,"
                    + " notes VARCHAR(255) NULL,"
                    + " recorded_by_user_id BIGINT NULL,"
                    + " performed_by_nurse_id BIGINT NULL,"
                    + " supersedes_io_entry_id BIGINT NULL,"
                    + " is_active TINYINT(1) NOT NULL DEFAULT 1,"
                    + " created_at DATETIME(6) NOT NULL,"
                    + " PRIMARY KEY (id),"
                    + " UNIQUE KEY uk_icu_io_public_id (public_id),"
                    + " KEY idx_icu_io_admission (ipd_admission_id),"
                    + " KEY idx_icu_io_hospital (hospital_id),"
                    + " KEY idx_icu_io_occurred (ipd_admission_id, occurred_at)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                log.info("DB migration applied: created icu_io_entry");
            }
        } catch (Exception e) {
            log.warn("ensureIcuIoEntryTable failed: {}", e.getMessage());
        }
    }

    /**
     * ICU Phase 4 — critical-care observations plus the append-only correction link.
     *
     * <p>Every column is NULLABLE, which is the backward-compatibility guarantee: existing rows
     * and every ward reading are untouched and no backfill is required. (ICU-2 learned the hard
     * way that a NOT NULL column with only a Java default is added by ddl-auto BEFORE this
     * runner, with no DB default — none of these take that path.)
     */
    private void ensureVitalsIcuColumns() {
        addColumnIfMissing("vitals_records", "map_mmhg", "INT NULL");
        addColumnIfMissing("vitals_records", "cvp_cmh2o", "INT NULL");
        addColumnIfMissing("vitals_records", "urine_output_ml", "INT NULL");
        addColumnIfMissing("vitals_records", "gcs_eye", "INT NULL");
        addColumnIfMissing("vitals_records", "gcs_verbal", "INT NULL");
        addColumnIfMissing("vitals_records", "gcs_motor", "INT NULL");
        addColumnIfMissing("vitals_records", "gcs_total", "INT NULL");
        addColumnIfMissing("vitals_records", "supersedes_vitals_id", "BIGINT NULL");
    }

    /** ICU Phase 3 — the ICU stay record. See IcuStay for why active_marker exists. */
    private void ensureIcuStayTable() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.TABLES " +
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'icu_stay'", Integer.class);
            if (count != null && count == 0) {
                jdbcTemplate.execute(
                    "CREATE TABLE icu_stay (" +
                    "  id BIGINT NOT NULL AUTO_INCREMENT," +
                    "  public_id VARCHAR(255) NOT NULL," +
                    "  hospital_id BIGINT NOT NULL," +
                    "  ipd_admission_id BIGINT NOT NULL," +
                    "  patient_id BIGINT NOT NULL," +
                    "  ward_id BIGINT NOT NULL," +
                    "  status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE'," +
                    "  source VARCHAR(20) NOT NULL," +
                    "  source_ref_id BIGINT DEFAULT NULL," +
                    "  admitted_at DATETIME(6) NOT NULL," +
                    "  admission_reason VARCHAR(255) DEFAULT NULL," +
                    "  intensivist_doctor_id BIGINT DEFAULT NULL," +
                    "  admitted_by_user_id BIGINT DEFAULT NULL," +
                    "  disposition VARCHAR(20) DEFAULT NULL," +
                    "  discharged_at DATETIME(6) DEFAULT NULL," +
                    "  discharged_by_user_id BIGINT DEFAULT NULL," +
                    "  active_marker BIGINT DEFAULT NULL," +
                    "  created_at DATETIME(6) NOT NULL," +
                    "  PRIMARY KEY (id)," +
                    "  UNIQUE KEY uk_icu_stay_public_id (public_id)," +
                    "  UNIQUE KEY uk_icu_stay_active (hospital_id, active_marker)," +
                    "  KEY idx_icu_stay_admission (ipd_admission_id)," +
                    "  KEY idx_icu_stay_hospital (hospital_id)" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                log.info("DB migration applied: created icu_stay");
            }
        } catch (Exception e) {
            log.warn("ensureIcuStayTable skipped: {}", e.getMessage());
        }
    }

    /**
     * ICU Phase 3 — gives every patient ALREADY lying in a critical-care bed an ACTIVE stay.
     *
     * <p>Without this the ICU board contradicts itself from the first minute: the bed is occupied
     * and the admission is active, but no stay exists, so the biconditional the module rests on
     * (ACTIVE stay ⟺ patient occupies a critical-care bed) is violated for every existing patient.
     *
     * <p>Deliberately honest about what it does NOT know. The moment critical care actually began
     * was never recorded, so {@code admitted_at} reuses the admission's own timestamp rather than
     * inventing one, the source is EXTERNAL_REFERRAL (the "arrived already in this state" value),
     * and the reason says plainly that the row was backfilled. No clinical history is fabricated.
     *
     * <p>Idempotent: the WHERE clause excludes admissions that already have an ACTIVE stay, so
     * running it on every startup is a no-op after the first.
     */
    private void backfillIcuStaysForCurrentOccupants() {
        try {
            String criticalCare = com.hms.service.hospital.icu.CareUnitRegistry.criticalCareKeys()
                    .stream().map(k -> "'" + k + "'").reduce((a, b) -> a + "," + b).orElse("''");
            int created = jdbcTemplate.update(
                "INSERT INTO icu_stay (public_id, hospital_id, ipd_admission_id, patient_id, ward_id," +
                "  status, source, admitted_at, admission_reason, admitted_by_user_id," +
                "  active_marker, created_at) " +
                "SELECT UUID(), a.hospital_id, a.id, a.patient_id, a.ward_id," +
                "  'ACTIVE', 'EXTERNAL_REFERRAL', a.admission_datetime," +
                "  'Backfilled at ICU-3: this patient already occupied a critical-care bed. " +
                     "The actual time critical care began was not recorded.', NULL," +
                "  a.id, NOW(6) " +
                "FROM ipd_admission a " +
                "JOIN wards w ON w.ward_id = a.ward_id AND w.hospital_id = a.hospital_id " +
                "WHERE a.status IN ('ADMITTED','DISCHARGE_PLANNED') " +
                "  AND w.unit_type IN (" + criticalCare + ") " +
                "  AND NOT EXISTS (SELECT 1 FROM icu_stay s " +
                "                  WHERE s.ipd_admission_id = a.id AND s.status = 'ACTIVE')");
            if (created > 0) {
                log.info("DB migration applied: backfilled {} ACTIVE ICU stay(s) for current occupants", created);
            }
        } catch (Exception e) {
            log.warn("backfillIcuStaysForCurrentOccupants skipped: {}", e.getMessage());
        }
    }

    /** CREATE TABLE when absent, matching the ensureXxx idiom used throughout this runner. */
    private void createTableIfMissing(String table, String ddl) {
        try {
            Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
              + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?", Integer.class, table);
            if (exists != null && exists == 0) {
                jdbcTemplate.execute(ddl);
                log.info("DB migration applied: created {}", table);
            }
        } catch (Exception e) {
            log.warn("createTableIfMissing skipped ({}): {}", table, e.getMessage());
        }
    }
}
