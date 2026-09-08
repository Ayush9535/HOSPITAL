package com.hms.service.hospital;

import com.hms.entity.Hospital;
import com.hms.entity.HospitalType;
import com.hms.entity.pharmacy.PharmacySale;
import com.hms.repository.HospitalRepository;
import com.hms.repository.pharmacy.PharmacySaleRepository;
import com.hms.security.SecurityContextHelper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The pharmacy count, on the column production actually has, through the binding path production
 * actually uses.
 *
 * <p><b>Why the column needs correcting.</b> {@code pharmacy_sales.created_at} is a MySQL
 * {@code TIMESTAMP} in {@code schema-full.sql}, alone among the six tables this dashboard reads —
 * the others are {@code DATETIME(6)}. {@code DATETIME} stores the literal wall clock it is given;
 * {@code TIMESTAMP} is stored as UTC and converted both ways using the session's zone. Hibernate's
 * {@code create-drop} generates {@code DATETIME(6)} from the entity's {@code LocalDateTime}, so
 * every other test — H2 and Testcontainers alike — exercises the wrong type. This one corrects it
 * test-side after the schema is built. Production schema and entity are untouched.
 *
 * <p><b>Why the JVM zone is pinned.</b> The binding path was read out of Hibernate 6.5.3 rather
 * than assumed. {@code TimestampJdbcType}'s binder calls
 * {@code javaType.unwrap(value, Timestamp.class, options)} and then, because no
 * {@code hibernate.jdbc.time_zone} is configured anywhere in this project,
 * {@code PreparedStatement.setTimestamp(index, timestamp)} with no Calendar.
 * {@code LocalDateTimeJavaType.unwrap} performs that conversion with
 * {@code Timestamp.valueOf(LocalDateTime)}, which resolves the local value in the <em>JVM default
 * zone</em>. Connector/J then renders that instant in {@code connectionTimeZone}. So a bound
 * boundary survives intact only while JVM default, connection zone and MySQL session zone agree —
 * which they do in production, and which a UTC build agent would silently break. Pinning is done
 * from {@code @DynamicPropertySource}, which Spring invokes during context bootstrap and therefore
 * before the datasource and session factory exist; it is restored in {@code @AfterAll}. A skipped
 * class never bootstraps a context, so nothing is pinned and nothing leaks to later classes.
 *
 * <p>Boundaries are whole seconds because the canonical column is {@code timestamp} with no
 * fractional precision. Sub-second values round rather than truncate on that column, so the last
 * second of a business day is approximate there in a way it is not on the {@code DATETIME(6)}
 * tables. That is a property of the schema and is deliberately not addressed here.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(HospitalDashboardService.class)
class PharmacySaleTimestampIT {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 6);
    private static final String BUSINESS_ZONE = "Asia/Kolkata";

    private static TimeZone originalDefault;

    @Container
    @SuppressWarnings("resource") // lifecycle managed by the @Testcontainers extension
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+05:30")
            .withUrlParam("serverTimezone", BUSINESS_ZONE);

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry properties) {
        // Runs during context bootstrap, before the datasource and session factory are built, and
        // only when this class actually executes. See the class comment for why this matters.
        originalDefault = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone(BUSINESS_ZONE));

        properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
        properties.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        properties.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.MySQLDialect");
        properties.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.MySQLDialect");
    }

    @AfterAll
    static void restoreDefaultTimeZone() {
        if (originalDefault != null) {
            TimeZone.setDefault(originalDefault);
        }
    }

    @Autowired HospitalDashboardService dashboard;
    @Autowired EntityManager em;
    @Autowired HospitalRepository hospitalRepository;
    @Autowired PharmacySaleRepository pharmacySaleRepository;

    @MockBean BusinessClock businessClock;
    @MockBean SecurityContextHelper securityHelper;

    @BeforeEach
    void setUp() {
        when(businessClock.today()).thenReturn(DAY);
        when(businessClock.now()).thenReturn(DAY.atTime(14, 30));
        when(businessClock.zoneId()).thenReturn(java.time.ZoneId.of(BUSINESS_ZONE));
        // DDL implicitly commits in MySQL, so the corrected column outlives the test's rollback,
        // which is what we want: it is the fixture. Idempotent, so repeating it per test is free.
        em.createNativeQuery(
                "ALTER TABLE pharmacy_sales MODIFY created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP")
                .executeUpdate();
    }

    // ── the environment the boundary proof depends on ────────────────────────

    @Test
    void theColumnIsTheOneProductionHasAndEveryClockInThePathIsIst() {
        Object[] column = (Object[]) em.createNativeQuery(
                "SELECT DATA_TYPE, DATETIME_PRECISION FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pharmacy_sales' "
                        + "AND COLUMN_NAME = 'created_at'")
                .getSingleResult();
        assertThat(String.valueOf(column[0])).as("schema-full.sql declares it TIMESTAMP")
                .isEqualToIgnoringCase("timestamp");
        assertThat(((Number) column[1]).intValue()).as("with no fractional seconds").isZero();

        assertThat(TimeZone.getDefault().getID())
                .as("Timestamp.valueOf resolves the bound LocalDateTime in this zone")
                .isEqualTo(BUSINESS_ZONE);

        // serverTimezone in a JDBC URL says what the driver converts with. It is not evidence of
        // what the server session is set to, so ask the session, and then ask it to prove it.
        assertThat(String.valueOf(
                em.createNativeQuery("SELECT @@session.time_zone").getSingleResult()))
                .isEqualTo("+05:30");
        assertThat(String.valueOf(
                em.createNativeQuery("SELECT TIMEDIFF(NOW(), UTC_TIMESTAMP())").getSingleResult()))
                .as("and really is five and a half hours ahead of UTC")
                .startsWith("05:30");
    }

    @Test
    void theWindowTheServiceReportsIsTheIstBusinessDay() {
        long mine = createHospital();
        when(securityHelper.getCurrentHospitalId()).thenReturn(mine);

        var overview = dashboard.getOverview(DashboardRange.TODAY);

        assertThat(overview.getFrom()).isEqualTo(DAY.atStartOfDay());
        assertThat(overview.getToExclusive()).isEqualTo(DAY.plusDays(1).atStartOfDay());
        assertThat(overview.getTimezone()).isEqualTo(BUSINESS_ZONE);
    }

    // ── membership, one row at a time ────────────────────────────────────────

    /**
     * One row per invocation, so each answer is 0 or 1 and means one thing. A total across several
     * rows cannot do this: three rows spanning yesterday-evening to this-afternoon also total
     * three, so a window shifted by the very offset this file exists to police would still pass.
     */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "2026-09-05 23:59:59, 0",  // last second of yesterday
            "2026-09-06 00:00:00, 1",  // first second of today, inclusive bound
            "2026-09-06 14:30:00, 1",  // the middle of the business day
            "2026-09-06 23:59:59, 1",  // last second of today
            "2026-09-07 00:00:00, 0",  // first second of tomorrow, exclusive bound
    })
    void eachBoundaryRowIsCountedOrNotOnItsOwn(String createdAt, long expected) {
        long mine = createHospital();
        when(securityHelper.getCurrentHospitalId()).thenReturn(mine);
        sale(mine, createdAt);

        assertThat(dashboard.getOverview(DashboardRange.TODAY).getPharmacy().pharmacySales())
                .as("%s against [2026-09-06 00:00:00, 2026-09-07 00:00:00)", createdAt)
                .isEqualTo(expected);
    }

    @Test
    void anotherTenantsSaleInsideTodayIsNeverCounted() {
        long mine = createHospital();
        long theirs = createHospital();
        when(securityHelper.getCurrentHospitalId()).thenReturn(mine);
        sale(theirs, "2026-09-06 12:00:00");

        assertThat(dashboard.getOverview(DashboardRange.TODAY).getPharmacy().pharmacySales())
                .isZero();
    }

    private long createHospital() {
        Hospital h = new Hospital();
        h.setName("Hospital");
        h.setType(HospitalType.HOSPITAL);
        h.setModules(new java.util.ArrayList<>(List.of("PHARMACY")));
        return hospitalRepository.saveAndFlush(h).getId();
    }

    /**
     * The literal is written straight to the column so the stored value is exactly the boundary
     * under test; @CreationTimestamp would stamp "now" instead. Placement is by SQL literal, but
     * the SELECT that decides membership is the production query with production
     * {@code LocalDateTime} bounds, which is the half that has to be proven.
     */
    private void sale(long hospitalId, String createdAt) {
        PharmacySale s = new PharmacySale();
        s.setHospitalId(hospitalId);
        s.setPostingStatus("POSTED");
        s.setPaymentStatus("PAID");
        s.setNetAmount(new BigDecimal("100"));
        s.setBillNumber("PHB-" + System.nanoTime());
        Long id = pharmacySaleRepository.saveAndFlush(s).getId();
        em.createNativeQuery("UPDATE pharmacy_sales SET created_at = :at WHERE id = :id")
                .setParameter("at", createdAt).setParameter("id", id).executeUpdate();
    }
}
