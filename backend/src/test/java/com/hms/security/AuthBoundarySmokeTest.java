package com.hms.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the authentication boundary against the real SecurityConfig on a real server.
 *
 * SecurityConfig gates whole namespaces with hasAnyRole(...). A single stray permitAll(),
 * or a new controller mounted outside a guarded namespace, silently exposes patient data.
 * These are the assertions that fail when that happens.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AuthBoundarySmokeTest {

    @Autowired
    private TestRestTemplate rest;

    @ParameterizedTest(name = "unauthenticated GET {0} is rejected")
    @ValueSource(strings = {
            "/hospital/patients",
            "/hospital/ot/rooms",
            "/hospital/surgeries/board",
            "/hospital/nurse-tasks",
            "/pharmacy/inventory",
            "/pharmacy/sales",
            "/platform/hospitals",
    })
    void protectedEndpointsRejectAnonymousCallers(String path) {
        ResponseEntity<String> res = rest.getForEntity(path, String.class);

        assertThat(res.getStatusCode())
                .as("%s must not be reachable without a token", path)
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    @Test
    void healthEndpointStaysPublic() {
        // Render/UptimeRobot poll this unauthenticated; locking it down would break deploys.
        ResponseEntity<String> res = rest.getForEntity("/api/public/health", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
