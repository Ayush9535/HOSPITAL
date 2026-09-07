package com.hms.service.hospital;

import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Every dashboard aggregate, re-run against real MySQL.
 *
 * <p>The unit tests execute real SQL, but against H2 in MySQL mode with {@code H2Dialect}, and the
 * two engines do not agree about everything that matters here. The busiest-specialities query had
 * already failed once on a strict engine: a bound parameter inside the CASE rendered as
 * {@code cast(? as char)} in the SELECT and as a bare {@code ?} in the GROUP BY, so the two
 * expressions were no longer the same expression and grouping was rejected. MySQL's
 * {@code ONLY_FULL_GROUP_BY} — on by default since 5.7 — applies the same rule, and reasoning that
 * it will therefore accept the rewritten form is not the same as watching it do so.
 *
 * <p>Inheriting the whole unit-test class rather than restating a few queries is the pattern
 * {@code IstDateBoundaryIT} established, and it is the point: the assertions that run here are
 * character-for-character the ones that ran on H2, so anything the engines disagree about shows up
 * as a failure instead of as a gap. That covers all eight aggregates, the half-open day boundaries,
 * tenant scoping, the NULL/Unassigned speciality bucket and the aggregate projections
 * ({@code COUNT} arriving as Long or BigInteger, native {@code DATE()} as {@code java.sql.Date}).
 *
 * <p>The container is pinned to +05:30 and the connection asks for Asia/Kolkata, which is the
 * deployed chain. That also puts {@code pharmacy_sales.created_at} under test as the MySQL
 * {@code TIMESTAMP} it really is, rather than the {@code DATETIME} H2 gives it.
 *
 * <p>Named {@code *IT} so Failsafe runs it at {@code verify} and the fast {@code mvn test} tier
 * stays free of Docker. Skipped, not failed, where Docker is absent.
 */
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class HospitalDashboardServiceIT extends HospitalDashboardServiceTest {

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
}
