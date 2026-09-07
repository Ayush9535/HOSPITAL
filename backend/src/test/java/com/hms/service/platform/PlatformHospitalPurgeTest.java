package com.hms.service.platform;

import com.hms.dto.CreateHospitalRequest;
import com.hms.entity.Hospital;
import com.hms.entity.HospitalType;
import com.hms.entity.Plan;
import com.hms.repository.HospitalRepository;
import com.hms.repository.PlanRepository;
import com.hms.security.OtPermissions;
import com.hms.service.hospital.ot.OtPermissionService;
import com.hms.service.platform.TenantTables.Retention;
import com.hms.service.platform.TenantTables.TenantTable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SA-7a — the purge-safe subset actually leaves nothing behind.
 *
 * <p>Before this, deleting a facility left every one of these tables populated: they carried a
 * hospital_id, no cascade collected them (the application-built schema has none), and the explicit
 * delete list never mentioned them. These tests seed each purge-safe table, delete the facility and
 * assert zero rows survive.
 *
 * <p>Not {@code @Transactional}: the service's own transaction has to commit for the assertions to
 * mean anything.
 */
@SpringBootTest
@ActiveProfiles("test")
class PlatformHospitalPurgeTest {

    @Autowired PlatformHospitalService service;
    @Autowired HospitalRepository hospitalRepository;
    @Autowired PlanRepository planRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired OtPermissionService otPermissionService;

    /** Keeps generated filler distinct so unique constraints are not tripped by the fixture. */
    private static final java.util.concurrent.atomic.AtomicLong SEQ =
            new java.util.concurrent.atomic.AtomicLong(1000);

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Hospital newFacility() {
        Plan p = new Plan();
        p.setPublicId(UUID.randomUUID().toString());
        p.setName("SA7A-" + uniq());
        p.setType(HospitalType.HOSPITAL);
        p.setMonthlyPrice(BigDecimal.ONE);
        p.setYearlyPrice(BigDecimal.TEN);
        p.setModules(List.of("OPD"));
        p.setIsActive(true);
        planRepository.save(p);

        CreateHospitalRequest req = new CreateHospitalRequest();
        req.setHospitalName("SA7A " + uniq());
        req.setAdminEmail("sa7a-" + uniq() + "@example.test");
        req.setAdminPassword("Passw0rd!23");
        req.setAdminName("Sa Sevena");
        req.setType("HOSPITAL");
        req.setPlanPublicId(p.getPublicId());
        req.setBillingPeriod("MONTHLY");
        req.setIsSingleDoctor(false);
        return service.createHospital(req);
    }

    /**
     * Puts one row in every purge-safe table for this facility. Columns are read from the live
     * schema so the fixture cannot drift out of step with the entities.
     */
    private Map<String, Integer> seedPurgeSafeRows(Long hospitalId) {
        Map<String, Integer> seeded = new LinkedHashMap<>();
        for (TenantTable t : TenantTables.purgeSafeInOrder()) {
            // Parent-scoped tables are covered by seeding their parent; a link row needs the
            // parent's id, which the generic seeder cannot know.
            if (t.ownership() != TenantTables.Ownership.DIRECT) continue;
            if (insertMinimalRow(t.table(), hospitalId)) seeded.put(t.table(), 1);
        }
        return seeded;
    }

    /**
     * Insert a row with hospital_id set and every other NOT NULL column filled plausibly.
     *
     * <p>Reads the shape from JDBC {@link java.sql.DatabaseMetaData} rather than
     * INFORMATION_SCHEMA: H2 and MySQL disagree on the latter (H2 has no {@code EXTRA} column),
     * and this fixture has to run on whichever the profile provides.
     */
    private boolean insertMinimalRow(String table, Long hospitalId) {
        List<String> names = new ArrayList<>();
        List<String> values = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        try (java.sql.Connection c = jdbc.getDataSource().getConnection()) {
            java.sql.DatabaseMetaData meta = c.getMetaData();
            // H2 folds unquoted identifiers to upper case, MySQL keeps them as created; the
            // metadata pattern is matched against stored form, so ask which one this is.
            String pattern = meta.storesUpperCaseIdentifiers()
                    ? table.toUpperCase(java.util.Locale.ROOT)
                    : table;
            try (java.sql.ResultSet rs = meta.getColumns(
                    c.getCatalog(), c.getSchema(), pattern, null)) {
                while (rs.next()) {
                    String name = rs.getString("COLUMN_NAME");
                    int type = rs.getInt("DATA_TYPE");
                    boolean nullable = rs.getInt("NULLABLE") == java.sql.DatabaseMetaData.columnNullable;
                    boolean generated = "YES".equalsIgnoreCase(
                            String.valueOf(rs.getString("IS_AUTOINCREMENT")));
                    boolean hasDefault = rs.getString("COLUMN_DEF") != null;

                    if (generated) continue;
                    if ("hospital_id".equalsIgnoreCase(name)) {
                        names.add(name); values.add("?"); args.add(hospitalId); continue;
                    }
                    if (nullable || hasDefault) continue;

                    names.add(name);
                    values.add("?");
                    args.add(sampleFor(type, SEQ.incrementAndGet()));
                }
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Could not read the shape of " + table, e);
        }
        boolean hasTenantColumn = names.stream().anyMatch("hospital_id"::equalsIgnoreCase);
        if (!hasTenantColumn) return false;

        jdbc.update("INSERT INTO " + table + " (" + String.join(",", names) + ") VALUES ("
                + String.join(",", values) + ")", args.toArray());
        return true;
    }

    /**
     * A placeholder value for one NOT NULL column. Every value is derived from {@code seq} so
     * repeated inserts cannot collide on a unique constraint -- several of these tables are
     * unique on (nurse, date) or on a public id, and constant filler tripped them.
     */
    private Object sampleFor(int sqlType, long seq) {
        return switch (sqlType) {
            case java.sql.Types.BIGINT, java.sql.Types.INTEGER -> seq;
            case java.sql.Types.SMALLINT, java.sql.Types.TINYINT -> (int) (seq % 100);
            case java.sql.Types.DECIMAL, java.sql.Types.NUMERIC, java.sql.Types.DOUBLE,
                 java.sql.Types.FLOAT, java.sql.Types.REAL -> BigDecimal.valueOf(seq);
            case java.sql.Types.BIT, java.sql.Types.BOOLEAN -> Boolean.FALSE;
            case java.sql.Types.DATE ->
                    java.sql.Date.valueOf(java.time.LocalDate.now().minusDays(seq % 3000));
            case java.sql.Types.TIME -> java.sql.Time.valueOf(
                    java.time.LocalTime.of((int) (seq % 24), (int) (seq % 60)));
            case java.sql.Types.TIMESTAMP ->
                    java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusSeconds(seq));
            default -> "sa7a-" + seq;
        };
    }

    private long rowsFor(String table, Long hospitalId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE hospital_id = ?",
                Long.class, hospitalId);
    }

    @Test
    void purgeSafeTablesAreEmptyAfterTheFacilityIsDeleted() {
        Hospital h = newFacility();
        Map<String, Integer> seeded = seedPurgeSafeRows(h.getId());
        assertThat(seeded).as("fixture seeded something to test").isNotEmpty();
        for (String table : seeded.keySet()) {
            assertThat(rowsFor(table, h.getId())).as("seeded %s", table).isPositive();
        }

        service.deleteHospital(h.getPublicId());

        List<String> survivors = new ArrayList<>();
        for (String table : seeded.keySet()) {
            if (rowsFor(table, h.getId()) > 0) survivors.add(table);
        }
        assertThat(survivors).as("purge-safe tables still holding rows after delete").isEmpty();
    }

    @Test
    void deletingOneFacilityLeavesAnothersRowsIntact() {
        Hospital doomed = newFacility();
        Hospital keeper = newFacility();
        seedPurgeSafeRows(doomed.getId());
        Map<String, Integer> keeperRows = seedPurgeSafeRows(keeper.getId());

        Map<String, Long> before = new LinkedHashMap<>();
        keeperRows.keySet().forEach(t -> before.put(t, rowsFor(t, keeper.getId())));

        service.deleteHospital(doomed.getPublicId());

        for (Map.Entry<String, Long> e : before.entrySet()) {
            assertThat(rowsFor(e.getKey(), keeper.getId()))
                    .as("second facility's %s must be untouched", e.getKey())
                    .isEqualTo(e.getValue());
        }
        assertThat(hospitalRepository.findByPublicId(keeper.getPublicId())).isPresent();
    }

    @Test
    void globalMasterDataIsUntouched() {
        Hospital h = newFacility();
        seedPurgeSafeRows(h.getId());
        jdbc.update("INSERT INTO medicine_list (name, type, hospital_type) VALUES (?, ?, ?)",
                "SA7A-Cat-" + uniq(), "Tablet", "HOSPITAL");

        Map<String, Long> before = new LinkedHashMap<>();
        for (String table : TenantTables.namesWithRetention(Retention.GLOBAL_NEVER_DELETE)) {
            before.put(table, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
        }

        service.deleteHospital(h.getPublicId());

        for (Map.Entry<String, Long> e : before.entrySet()) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + e.getKey(), Long.class))
                    .as("global table %s must be untouched by a tenant delete", e.getKey())
                    .isEqualTo(e.getValue());
        }
    }

    /**
     * The security case for purging role_permissions first.
     *
     * <p>A facility id can come back: the AUTO_INCREMENT counter resets on TRUNCATE, on an
     * environment rebuild that re-imports child rows under their original ids, and on restart
     * before MySQL 8.0. OtPermissionService reads "no rows for this hospital" as "not configured"
     * and falls back to the built-in defaults, so a permission matrix left behind by the previous
     * occupant would be adopted silently by the next one.
     */
    @Test
    void areusedFacilityIdDoesNotInheritThePreviousPermissionMatrix() {
        Hospital first = newFacility();
        Long reusedId = first.getId();

        // The previous occupant grants DOCTOR a permission the defaults do not include.
        jdbc.update("INSERT INTO role_permissions (hospital_id, role, permission_code) VALUES (?,?,?)",
                reusedId, "DOCTOR", OtPermissions.OT_CANCEL);
        assertThat(otPermissionService.effectiveFor(reusedId, "DOCTOR"))
                .as("matrix is in force before the delete")
                .contains(OtPermissions.OT_CANCEL);

        service.deleteHospital(first.getPublicId());

        assertThat(rowsFor("role_permissions", reusedId))
                .as("permission matrix must not survive the facility").isZero();

        // Whoever inherits the id sees the safe defaults, not the previous occupant's grants.
        assertThat(otPermissionService.effectiveFor(reusedId, "DOCTOR"))
                .as("an inherited id must fall back to the built-in defaults")
                .isEqualTo(OtPermissions.defaultsFor("DOCTOR"))
                .doesNotContain(OtPermissions.OT_CANCEL);
    }

    /** The two link tables have no hospital_id; they are purged through their parent. */
    @Test
    void parentScopedLinkRowsArePurgedThroughTheirParent() {
        Hospital h = newFacility();

        jdbc.update("INSERT INTO prescription_presets "
                + "(hospital_id, name, preset_type, is_active, display_order) VALUES (?, ?, ?, ?, ?)",
                h.getId(), "Preset " + uniq(), "DOCTOR", true, 0);
        Long presetId = jdbc.queryForObject(
                "SELECT MAX(id) FROM prescription_presets WHERE hospital_id = ?", Long.class, h.getId());
        jdbc.update("INSERT INTO prescription_preset_items (preset_id, medicine_name, sort_order) "
                + "VALUES (?, ?, ?)", presetId, "Paracetamol", 0);

        jdbc.update("INSERT INTO hospital_services (hospital_id, name, charge, is_active) "
                + "VALUES (?, ?, ?, ?)", h.getId(), "Service " + uniq(), BigDecimal.TEN, true);
        Long serviceId = jdbc.queryForObject(
                "SELECT MAX(id) FROM hospital_services WHERE hospital_id = ?", Long.class, h.getId());
        jdbc.update("INSERT INTO hospital_service_items (service_id, master_item_id) VALUES (?, ?)",
                serviceId, 1L);

        service.deleteHospital(h.getPublicId());

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM prescription_preset_items WHERE preset_id = ?",
                Long.class, presetId)).as("preset items").isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM hospital_service_items WHERE service_id = ?",
                Long.class, serviceId)).as("service items").isZero();
    }
}
