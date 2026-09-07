package com.hms.service.hospital;

import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Runs the identical service/cache/predicate contract on MySQL DATETIME(6). */
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class IstDateBoundaryIT extends IstDateBoundaryTest {
    @Container
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
