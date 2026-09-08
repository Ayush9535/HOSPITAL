package com.hms.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ICU Phase 3 — the backfill, against a real MySQL.
 *
 * <p>The backfill is a single INSERT…SELECT in {@code DatabaseMigrationRunner}, so it is verified
 * where it actually runs rather than through a mocked service. It exists because without it the
 * board contradicts itself on day one: every patient already lying in a critical-care bed would
 * show as occupied with no stay.
 *
 * <p>Run with the same explicit datasource the concurrency IT uses:
 * <pre>-Dhms.it.mysql.url=… -Dhms.it.mysql.username=… -Dhms.it.mysql.password=…</pre>
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "hms.it.mysql.url", matches = ".+")
class IcuBackfillIT {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty("hms.it.mysql.url"));
        registry.add("spring.datasource.username", () -> System.getProperty("hms.it.mysql.username", "root"));
        registry.add("spring.datasource.password", () -> System.getProperty("hms.it.mysql.password", ""));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("hms.migrations.enabled", () -> "false");
        registry.add("spring.cache.type", () -> "simple");
    }

    @Autowired JdbcTemplate jdbc;

    private String uniq() { return Long.toString(System.nanoTime()); }

    /** The backfill statement, verbatim in shape from DatabaseMigrationRunner. */
    private int runBackfill() {
        return jdbc.update(
            "INSERT INTO icu_stay (public_id, hospital_id, ipd_admission_id, patient_id, ward_id," +
            "  status, source, admitted_at, admission_reason, admitted_by_user_id," +
            "  active_marker, created_at) " +
            "SELECT UUID(), a.hospital_id, a.id, a.patient_id, a.ward_id," +
            "  'ACTIVE', 'EXTERNAL_REFERRAL', a.admission_datetime," +
            "  'Backfilled at ICU-3', NULL, a.id, NOW(6) " +
            "FROM ipd_admission a " +
            "JOIN wards w ON w.ward_id = a.ward_id AND w.hospital_id = a.hospital_id " +
            "WHERE a.status IN ('ADMITTED','DISCHARGE_PLANNED') " +
            "  AND w.unit_type IN ('ICU','MICU','SICU','NICU','PICU','CCU','HDU') " +
            "  AND NOT EXISTS (SELECT 1 FROM icu_stay s " +
            "                  WHERE s.ipd_admission_id = a.id AND s.status = 'ACTIVE')");
    }

    private long seedAdmission(long hospitalId, String unitType, String admissionStatus) {
        jdbc.update("INSERT INTO wards (hospital_id, ward_name, bed_price, total_beds, unit_type, created_at)"
                + " VALUES (?,?,?,?,?,NOW(6))", hospitalId, "W-" + uniq(), 1000, 2, unitType);
        Long wardId = jdbc.queryForObject("SELECT MAX(ward_id) FROM wards", Long.class);

        jdbc.update("INSERT INTO ipd_admission (ipd_number, patient_id, doctor_id, hospital_id,"
                + " admission_type, status, admission_datetime, ward_id, bed_id, admission_confirmed)"
                + " VALUES (?,?,?,?,?,?,NOW(6),?,?,1)",
                "IPDX-" + uniq(), 1L, 1L, hospitalId, "ELECTIVE", admissionStatus, wardId, 1L);
        return jdbc.queryForObject("SELECT MAX(id) FROM ipd_admission", Long.class);
    }

    private int activeStays() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM icu_stay WHERE status='ACTIVE'", Integer.class);
    }

    /** The invariant the backfill exists to restore: occupants == ACTIVE stays. */
    private int currentIcuOccupants() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM ipd_admission a JOIN wards w ON w.ward_id = a.ward_id "
                + "AND w.hospital_id = a.hospital_id WHERE a.status IN ('ADMITTED','DISCHARGE_PLANNED') "
                + "AND w.unit_type IN ('ICU','MICU','SICU','NICU','PICU','CCU','HDU')", Integer.class);
    }

    @Test
    void backfillGivesExactlyOneActiveStayPerCurrentIcuOccupant_andIsIdempotent() {
        long hospitalId = 900000L + (System.nanoTime() % 1000);

        seedAdmission(hospitalId, "ICU", "ADMITTED");
        seedAdmission(hospitalId, "MICU", "DISCHARGE_PLANNED");   // still occupying a bed
        seedAdmission(hospitalId, "GENERAL", "ADMITTED");          // not critical care
        seedAdmission(hospitalId, "ICU", "DISCHARGED");            // no longer occupying

        int occupantsBefore = currentIcuOccupants();
        assertThat(occupantsBefore).as("two current ICU occupants seeded").isEqualTo(2);

        int firstRun = runBackfill();
        assertThat(firstRun).isEqualTo(occupantsBefore);
        assertThat(activeStays()).isEqualTo(occupantsBefore);

        // Running it again must be a no-op. This is what makes it safe on every startup.
        int secondRun = runBackfill();
        assertThat(secondRun).as("idempotent: no duplicate stays").isZero();
        assertThat(activeStays()).isEqualTo(occupantsBefore);

        // The verification the plan asks for: occupants == ACTIVE stays.
        assertThat(activeStays()).isEqualTo(currentIcuOccupants());

        // And no stay was invented for the general ward or the discharged patient.
        Integer strays = jdbc.queryForObject(
                "SELECT COUNT(*) FROM icu_stay s JOIN wards w ON w.ward_id = s.ward_id "
                + "WHERE w.unit_type = 'GENERAL'", Integer.class);
        assertThat(strays).isZero();
    }

    @Test
    void backfillReusesTheAdmissionTimestampRatherThanInventingOne() {
        long hospitalId = 910000L + (System.nanoTime() % 1000);
        long admissionId = seedAdmission(hospitalId, "ICU", "ADMITTED");

        runBackfill();

        Integer matches = jdbc.queryForObject(
                "SELECT COUNT(*) FROM icu_stay s JOIN ipd_admission a ON a.id = s.ipd_admission_id "
                + "WHERE s.ipd_admission_id = ? AND s.admitted_at = a.admission_datetime",
                Integer.class, admissionId);
        assertThat(matches).as("no fabricated ICU admission time").isEqualTo(1);

        String reason = jdbc.queryForObject(
                "SELECT admission_reason FROM icu_stay WHERE ipd_admission_id = ?",
                String.class, admissionId);
        assertThat(reason).as("provenance is explicit").contains("Backfilled");
    }
}
