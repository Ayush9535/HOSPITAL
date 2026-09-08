package com.hms.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for integration tests that run against a REAL MySQL instance (Testcontainers),
 * not the in-memory H2 used by the fast unit/API tests. This catches issues H2 hides:
 * MySQL-specific DDL, column types/collations, constraints, and real transaction behaviour.
 *
 * Naming: subclasses end in `IT` so the Maven Failsafe plugin runs them at `verify`
 * (medium/slow tier), keeping the fast `mvn test` tier free of Docker.
 *
 * Requires Docker. GitHub runners provide it; if Docker is absent locally these tests are
 * skipped (Testcontainers throws at container start, which Failsafe reports as a skip).
 * See docs/testing/TESTING_STRATEGY.md.
 */
@Testcontainers(disabledWithoutDocker = true) // skips (not fails) where Docker is absent
@SpringBootTest
public abstract class AbstractMySqlIT {

    @Container
    @SuppressWarnings("resource") // lifecycle managed by the @Testcontainers extension
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("hms_it")
            .withUsername("hms")
            .withPassword("hms");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        // DynamicPropertySource has higher precedence than OS env vars, so this wins over
        // any SPRING_DATASOURCE_URL the CI job sets for the H2 unit tests.
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        // Build the schema from the JPA entities; the custom MySQL migration runner is not
        // needed for a throwaway container.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("hms.migrations.enabled", () -> "false");
        // Avoid a hard Redis dependency in the container-backed context.
        registry.add("spring.cache.type", () -> "simple");
    }
}
