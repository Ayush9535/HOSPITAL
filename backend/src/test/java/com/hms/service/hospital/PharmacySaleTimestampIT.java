package com.hms.service.hospital;

import com.hms.entity.Hospital;
import com.hms.entity.HospitalType;
import com.hms.entity.pharmacy.PharmacySale;
import com.hms.repository.HospitalRepository;
import com.hms.repository.pharmacy.PharmacySaleRepository;
import com.hms.security.SecurityContextHelper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The pharmacy count, against the column production actually has.
 *
 * <p>{@code pharmacy_sales.created_at} is a MySQL {@code TIMESTAMP} in {@code schema-full.sql},
 * alone among the six tables this dashboard reads — the others are {@code DATETIME(6)}. The
 * difference is not cosmetic. {@code DATETIME} stores the literal wall clock it was given and is
 * indifferent to session timezone; {@code TIMESTAMP} is stored as UTC and converted on the way in
 * and out using the session's zone. A dashboard that hands MySQL an IST {@code LocalDateTime}
 * boundary is therefore relying on that session being IST, and nothing in the unit tests can
 * observe whether it is: Hibernate's {@code create-drop} generates {@code DATETIME(6)} from the
 * entity's {@code LocalDateTime}, so H2 and even a Testcontainers MySQL both test the wrong column
 * type unless the schema is corrected first.
 *
 * <p>So this test corrects it — test-only, after Hibernate has built the schema — and then proves
 * three things it could not otherwise: that the column really is {@code TIMESTAMP}, that the
 * session really is {@code +05:30}, and that the half-open business day lands where it should on
 * that column. The production schema and the entity are untouched.
 *
 * <p>Boundaries are at whole seconds because the canonical column is {@code timestamp} with no
 * fractional precision. That is worth knowing on its own: a sale written at 23:59:59.7 rounds up
 * into the following day, so the last second of a business day is approximate in a way the
 * {@code DATETIME(6)} tables are not. That is a property of the schema, not of this query.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(HospitalDashboardService.class)
class PharmacySaleTimestampIT {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 6);

    @Container
    @SuppressWarnings("resource") // lifecycle managed by the @Testcontainers extension
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withCommand("--default-time-zone=+05:30")
            .withUrlParam("serverTimezone", "Asia/Kolkata");

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
        properties.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        properties.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.MySQLDialect");
        properties.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.MySQLDialect");
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
        when(businessClock.zoneId()).thenReturn(java.time.ZoneId.of("Asia/Kolkata"));
        // DDL implicitly commits in MySQL, so this outlives the test's rollback, which is what we
        // want: the corrected column is the fixture. Idempotent, so re-running per test is free.
        em.createNativeQuery(
                "ALTER TABLE pharmacy_sales MODIFY created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP")
                .executeUpdate();
    }

    @Test
    void theColumnUnderTestIsTheOneProductionHasAndTheSessionIsIst() {
        Object[] column = (Object[]) em.createNativeQuery(
                "SELECT DATA_TYPE, DATETIME_PRECISION FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pharmacy_sales' "
                        + "AND COLUMN_NAME = 'created_at'")
                .getSingleResult();

        assertThat(String.valueOf(column[0]))
                .as("schema-full.sql declares `created_at` timestamp")
                .isEqualToIgnoringCase("timestamp");
        assertThat(((Number) column[1]).intValue())
                .as("no fractional seconds, unlike the DATETIME(6) tables")
                .isZero();

        // serverTimezone in a JDBC URL is what the driver converts with; it is not proof of what
        // the server session is set to. Ask the session itself.
        Object sessionZone = em.createNativeQuery("SELECT @@session.time_zone").getSingleResult();
        Object offset = em.createNativeQuery(
                "SELECT TIMEDIFF(NOW(), UTC_TIMESTAMP())").getSingleResult();
        assertThat(String.valueOf(sessionZone)).isEqualTo("+05:30");
        assertThat(String.valueOf(offset))
                .as("and the session really is five and a half hours ahead of UTC")
                .startsWith("05:30");
    }

    @Test
    void theBusinessDayIsHalfOpenOnARealTimestampColumn() {
        long mine = hospital();
        long theirs = createHospital();
        when(securityHelper.getCurrentHospitalId()).thenReturn(mine);

        sale(mine, "2026-09-05 23:59:59");   // last second of yesterday — out
        sale(mine, "2026-09-06 00:00:00");   // first second of today — in
        sale(mine, "2026-09-06 14:30:00");   // the middle of the day — in
        sale(mine, "2026-09-06 23:59:59");   // last second of today — in
        sale(mine, "2026-09-07 00:00:00");   // first second of tomorrow — out
        sale(theirs, "2026-09-06 12:00:00"); // another hospital's sale — never

        assertThat(dashboard.getOverview(DashboardRange.TODAY).getPharmacy().pharmacySales())
                .as("[from 2026-09-06 00:00:00, 2026-09-07 00:00:00), this tenant only")
                .isEqualTo(3);
    }

    private long hospital() {
        return createHospital();
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
     * under test — @CreationTimestamp would otherwise stamp "now" and round it into the column's
     * whole-second precision.
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
