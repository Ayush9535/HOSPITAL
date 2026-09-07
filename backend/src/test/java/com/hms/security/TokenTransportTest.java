package com.hms.security;

import com.hms.entity.*;
import com.hms.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.WebSocket;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R1b — where a token is allowed to travel, and revocation at the WebSocket door.
 *
 * <p>The filter used to accept {@code ?token=} on any path, described in a comment as serving
 * "WebSockets or secure file download/viewing endpoints". No download ever used it: every PDF and
 * file fetch goes through axios with {@code responseType: 'blob'}, i.e. the Authorization header.
 * The only query-string callers in the whole frontend are two WebSocket URLs. A bearer token valid
 * for hours does not belong in access logs, browser history or Referer headers, so it is now
 * accepted on {@code /ws/**} and nowhere else.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class TokenTransportTest {

    @Autowired TestRestTemplate rest;
    @Autowired JwtUtil jwtUtil;
    @Autowired UserRepository userRepository;
    @Autowired HospitalRepository hospitalRepository;
    @Autowired HospitalSettingRepository hospitalSettingRepository;

    private Hospital hospital;
    private User user;
    private String token;

    private String uniq() { return Long.toString(System.nanoTime()); }

    @BeforeEach
    void setUp() {
        hospital = new Hospital();
        hospital.setName("Tok-" + uniq());
        hospital.setCustomId("TK-" + uniq());
        hospital.setType(HospitalType.HOSPITAL);
        hospital.setIsActive(true);
        hospital.setIsSingleDoctor(false);
        hospital.setSubscriptionStatus("ACTIVE");
        hospital.setModules(List.of("OPD"));
        hospital = hospitalRepository.save(hospital);
        HospitalSetting s = new HospitalSetting();
        s.setHospital(hospital);
        s.setReceptionMode("HAS_RECEPTIONIST");
        s.setBillingHandler("RECEPTIONIST");
        s.setInClinic(false);
        hospitalSettingRepository.save(s);

        user = new User();
        user.setEmail("tok-" + uniq() + "@t.test");
        user.setPassword("{noop}x");
        user.setName("Tok User");
        user.setRole("HOSPITAL_ADMIN");
        user.setHospitalId(hospital.getId());
        user.setIsActive(true);
        user = userRepository.save(user);

        token = jwtUtil.generateToken(user.getId(), user.getEmail(), "HOSPITAL_ADMIN",
                hospital.getId(), hospital.getModules(), null, "HOSPITAL", List.of(),
                user.getTokenVersion());
    }

    private HttpHeaders headers(String tok) {
        HttpHeaders h = new HttpHeaders();
        if (tok != null) h.setBearerAuth(tok);
        h.set("X-Forwarded-For", "10.215.0.10");
        return h;
    }

    private int get(String path, String bearer) {
        return rest.exchange(path, HttpMethod.GET,
                new HttpEntity<>(null, headers(bearer)), String.class).getStatusCode().value();
    }

    // ── REST: the header works, the query string does not ─────────────────────
    @Test
    void theAuthorizationHeaderAuthenticatesARestCall() {
        assertThat(get("/auth/me", token)).isEqualTo(200);
    }

    @Test
    void aQueryStringTokenDoesNotAuthenticateARestCall() {
        assertThat(get("/auth/me?token=" + token, null))
                .as("a credential in the URL must not authenticate an ordinary endpoint")
                .isEqualTo(401);
    }

    @Test
    void aQueryStringTokenDoesNotAuthenticateAPlatformCall() {
        assertThat(get("/platform/hospitals?token=" + token, null)).isEqualTo(401);
    }

    /** The PDF paths all use the header, so scoping the fallback cost them nothing. */
    @Test
    void aPdfEndpointStillWorksWithTheHeaderAndNotWithTheQueryString() {
        int withHeader = get("/hospital/prescription/opd/99999999/pdf", token);
        int withQuery = get("/hospital/prescription/opd/99999999/pdf?token=" + token, null);

        assertThat(withHeader).as("authenticated — 404 because the OPD is fake, not 401")
                .isNotEqualTo(401);
        assertThat(withQuery).as("unauthenticated").isEqualTo(401);
    }

    // ── WebSocket: the query string is the only transport a browser has ───────
    @Test
    void theWebSocketHandshakeAcceptsAQueryStringToken() throws Exception {
        assertThat(openSocket(token)).as("a live session may open a socket").isTrue();
    }

    @Test
    void theWebSocketHandshakeRefusesARevokedSession() throws Exception {
        assertThat(openSocket(token)).isTrue();

        hospital.setIsActive(false);
        hospitalRepository.save(hospital);

        assertThat(openSocket(token))
                .as("a blocked tenant must not be able to open a live data feed").isFalse();
    }

    @Test
    void theWebSocketHandshakeRefusesADeactivatedAccount() throws Exception {
        User fresh = userRepository.findById(user.getId()).orElseThrow();
        fresh.setIsActive(false);
        userRepository.save(fresh);

        assertThat(openSocket(token)).isFalse();
    }

    @Test
    void theWebSocketHandshakeRefusesAStaleTokenVersion() throws Exception {
        String stale = jwtUtil.generateToken(user.getId(), user.getEmail(), "HOSPITAL_ADMIN",
                hospital.getId(), hospital.getModules(), null, "HOSPITAL", List.of(), 42);

        assertThat(openSocket(stale)).isFalse();
    }

    @Test
    void theWebSocketHandshakeRefusesAMissingToken() throws Exception {
        assertThat(openSocket(null)).isFalse();
    }

    /** True if the handshake completed. A rejected handshake fails the future. */
    private boolean openSocket(String tok) throws Exception {
        String base = rest.getRootUri().replaceFirst("^http", "ws");
        String url = base + "/ws/hospital/" + hospital.getId() + (tok == null ? "" : "?token=" + tok);
        WebSocket ws = null;
        try {
            ws = java.net.http.HttpClient.newHttpClient().newWebSocketBuilder()
                    .buildAsync(URI.create(url), new WebSocket.Listener() { })
                    .get(20, TimeUnit.SECONDS);
            return true;
        } catch (CompletionException | java.util.concurrent.ExecutionException e) {
            return false;
        } finally {
            if (ws != null) ws.abort();
        }
    }
}
