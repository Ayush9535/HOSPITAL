package com.hms.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.health.redis.enabled=false")
@ActiveProfiles("test")
class ActuatorAccessTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void anonymousHealthAndProbeEndpointsRemainPublicWithoutComponentDetails() {
        assertPublicHealth("/actuator/health");
        assertPublicHealth("/actuator/health/liveness");
        assertPublicHealth("/actuator/health/readiness");
    }

    @Test
    void anonymousInfoRemainsPublicButMetricsEndpointsRemainProtected() {
        assertThat(rest.getForEntity("/actuator/info", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.getForEntity("/actuator/prometheus", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.getForEntity("/actuator/metrics", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private void assertPublicHealth(String path) {
        ResponseEntity<String> response = rest.getForEntity(path, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"").doesNotContain("\"components\"");
    }
}
