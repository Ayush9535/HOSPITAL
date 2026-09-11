package com.hms.security;

import com.hms.entity.*;
import com.hms.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R1b — a session ends when the thing it depends on changes.
 *
 * <p>Before this, {@code JwtAuthenticationFilter} read nothing but the token: a valid signature and
 * an unexpired {@code exp} were sufficient. Blocking a tenant, expiring a subscription,
 * deactivating an account, resetting a password and changing a role were therefore all advisory —
 * they took effect only when the token expired, up to twelve hours later.
 *
 * <p>Every assertion here is on what the server does with an already-issued token AFTER the change,
 * which is the only thing that matters.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SessionRevocationTest {

    @Autowired TestRestTemplate rest;
    @Autowired JwtUtil jwtUtil;
    @org.springframework.beans.factory.annotation.Value("${jwt.secret}") String jwtSecret;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired UserRepository userRepository;
    @Autowired HospitalRepository hospitalRepository;
    @Autowired HospitalSettingRepository hospitalSettingRepository;

    private static final String PASSWORD = "pass12345"; // pragma: allowlist secret

    private static final java.util.concurrent.atomic.AtomicInteger loginIp =
            new java.util.concurrent.atomic.AtomicInteger();

    private Hospital hospital;
    private User user;
    private String token;

    private String uniq() { return Long.toString(System.nanoTime()); }

    @BeforeEach
    void setUp() {
        hospital = newHospital();
        user = newUser(hospital.getId(), "HOSPITAL_ADMIN");
        token = login(user.getEmail());
    }

    // ── fixtures ──────────────────────────────────────────────────────────────
    private Hospital newHospital() {
        Hospital h = new Hospital();
        h.setName("Rev-" + uniq());
        h.setCustomId("RV-" + uniq());
        h.setType(HospitalType.HOSPITAL);
        h.setIsActive(true);
        h.setIsSingleDoctor(false);
        h.setSubscriptionStatus("ACTIVE");
        h.setModules(List.of("OPD", "BILLING"));
        h = hospitalRepository.save(h);
        HospitalSetting s = new HospitalSetting();
        s.setHospital(h);
        s.setReceptionMode("HAS_RECEPTIONIST");
        s.setBillingHandler("RECEPTIONIST");
        s.setInClinic(false);
        hospitalSettingRepository.save(s);
        return h;
    }

    private User newUser(Long hospitalId, String role) {
        User u = new User();
        u.setEmail("rev-" + uniq() + "@t.test");
        u.setPassword(passwordEncoder.encode(PASSWORD));
        u.setName("Rev User");
        u.setRole(role);
        u.setHospitalId(hospitalId);
        u.setIsActive(true);
        return userRepository.save(u);
    }

    private HttpHeaders headers(String tok) {
        HttpHeaders h = new HttpHeaders();
        if (tok != null) h.setBearerAuth(tok);
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Forwarded-For", "10.214.0.10");
        return h;
    }

    /** Real login through the HTTP endpoint, so the token carries whatever the server stamps. */
    private String login(String email) {
        try {
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(rest.getRootUri() + "/login"))
                    .header("Content-Type", "application/json")
                    // A fresh source IP per login: the AUTH tier allows 10/min/IP and this class
                    // logs in far more often than that. Each test already uses a unique email, so
                    // the per-account bucket is not the constraint.
                    .header("X-Forwarded-For", "10.214." + (loginIp.incrementAndGet() % 250) + ".7")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                            "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                    .build();
            java.net.http.HttpResponse<String> res = java.net.http.HttpClient.newHttpClient()
                    .send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(res.statusCode()).as("fixture login: %s", res.body()).isEqualTo(200);
            String b = res.body();
            int i = b.indexOf("\"token\":\"");
            return b.substring(i + 9, b.indexOf('"', i + 9));
        } catch (Exception e) {
            throw new IllegalStateException("login failed", e);
        }
    }

    /**
     * An ordinary data endpoint — deliberately NOT /auth/me.
     *
     * <p>/auth/me re-reads the tenant (and now the account) in the service, so it answers 401 for a
     * blocked tenant whether or not the filter checks anything. Probing there would have let three
     * of these tests pass with the filter check removed — proved by running them that way — which
     * means they would not have been testing the filter at all. This path has no such re-check, so
     * a 401 here can only have come from authentication.
     */
    private int probe(String tok) {
        return rest.exchange("/hospital/patients?page=0&size=1", HttpMethod.GET,
                new HttpEntity<>(null, headers(tok)), String.class).getStatusCode().value();
    }

    /** /auth/me specifically, for the profile-endpoint assertions. */
    private int probeAuthMe(String tok) {
        return rest.exchange("/auth/me", HttpMethod.GET,
                new HttpEntity<>(null, headers(tok)), String.class).getStatusCode().value();
    }

    private void reloadAndSave(java.util.function.Consumer<User> mutation) {
        User fresh = userRepository.findById(user.getId()).orElseThrow();
        mutation.accept(fresh);
        userRepository.save(fresh);
    }

    // ── 1. baseline ───────────────────────────────────────────────────────────
    @Test
    void anActiveUserAtAnActiveTenantIsAuthenticated() {
        assertThat(probe(token)).isEqualTo(200);
    }

    @Test
    void aValidAuthorizationHeaderIsAccepted() {
        assertThat(probe(token)).isEqualTo(200);
        assertThat(probe(null)).as("no header at all").isEqualTo(401);
    }

    // ── 2. tenant blocked ─────────────────────────────────────────────────────
    @Test
    void blockingTheTenantEndsSessionsAlreadyInFlight() {
        assertThat(probe(token)).isEqualTo(200);

        hospital.setIsActive(false);
        hospitalRepository.save(hospital);

        assertThat(probe(token)).as("the token is still signed and unexpired — and now refused")
                .isEqualTo(401);
    }

    // ── 3. subscription expiry ────────────────────────────────────────────────
    @Test
    void expiringTheSubscriptionEndsSessionsAlreadyInFlight() {
        assertThat(probe(token)).isEqualTo(200);

        // What PlanExpiryScheduler writes when a subscription lapses.
        hospital.setIsActive(false);
        hospital.setSubscriptionStatus("EXPIRED");
        hospitalRepository.save(hospital);

        assertThat(probe(token)).isEqualTo(401);
    }

    // ── 4. user deactivated ───────────────────────────────────────────────────
    @Test
    void deactivatingTheAccountEndsItsSession() {
        assertThat(probe(token)).isEqualTo(200);

        reloadAndSave(u -> u.setIsActive(false));

        assertThat(probe(token)).isEqualTo(401);
    }

    @Test
    void authMeAlsoRefusesADeactivatedAccount() {
        assertThat(probeAuthMe(token)).as("a live account reads its profile").isEqualTo(200);

        // /auth/me re-checked the tenant but never the account, so a deactivated user kept a valid
        // profile. Now refused by the filter and, behind it, by the service check added in R1b.
        reloadAndSave(u -> u.setIsActive(false));

        assertThat(probeAuthMe(token)).isEqualTo(401);
    }

    // ── 5. password reset ─────────────────────────────────────────────────────
    @Test
    void resettingThePasswordEndsTheOldSession() {
        assertThat(probe(token)).isEqualTo(200);

        reloadAndSave(u -> u.setPassword(passwordEncoder.encode("different-9999")));

        assertThat(probe(token)).as("the old token dies with the old password").isEqualTo(401);
    }

    @Test
    void loggingInAgainAfterAPasswordResetWorks() {
        reloadAndSave(u -> u.setPassword(passwordEncoder.encode(PASSWORD)));
        assertThat(probe(token)).as("old token revoked").isEqualTo(401);

        String fresh = login(user.getEmail());

        assertThat(probe(fresh)).as("a new login is unaffected").isEqualTo(200);
    }

    /** The bump is derived from the row, not from a call the caller must remember to make. */
    @Test
    void theTokenVersionBumpIsAutomaticOnAPasswordChange() {
        Integer before = userRepository.findById(user.getId()).orElseThrow().getTokenVersion();

        reloadAndSave(u -> u.setPassword(passwordEncoder.encode("another-8888")));

        assertThat(userRepository.findById(user.getId()).orElseThrow().getTokenVersion())
                .isEqualTo(before + 1);
    }

    @Test
    void anUnrelatedUserEditDoesNotEndTheSession() {
        Integer before = userRepository.findById(user.getId()).orElseThrow().getTokenVersion();

        reloadAndSave(u -> u.setName("Renamed Person"));

        assertThat(userRepository.findById(user.getId()).orElseThrow().getTokenVersion())
                .as("only credentials and authority invalidate").isEqualTo(before);
        assertThat(probe(token)).isEqualTo(200);
    }

    // ── 6. role change ────────────────────────────────────────────────────────
    @Test
    void changingTheRoleEndsAWorkingSession() {
        assertThat(probe(token)).isEqualTo(200);

        reloadAndSave(u -> u.setRole("RECEPTIONIST"));

        assertThat(probe(token)).as("a token carrying the old role is refused").isEqualTo(401);
    }

    /** The production promote path: NurseService flips NURSE to NURSE_INCHARGE. */
    @Test
    void promotingANurseToInchargeBumpsTheVersionAndRefusesTheOldToken() {
        User nurse = newUser(hospital.getId(), "NURSE");
        String nurseToken = jwtUtil.generateToken(nurse.getId(), nurse.getEmail(), "NURSE",
                hospital.getId(), hospital.getModules(), null, "HOSPITAL", List.of(),
                nurse.getTokenVersion());

        User fresh = userRepository.findById(nurse.getId()).orElseThrow();
        fresh.setRole("NURSE_INCHARGE");
        userRepository.save(fresh);

        assertThat(userRepository.findById(nurse.getId()).orElseThrow().getTokenVersion())
                .isEqualTo(nurse.getTokenVersion() + 1);
        assertThat(probe(nurseToken)).isEqualTo(401);
    }

    /** And the demote path back to NURSE. */
    @Test
    void demotingAnInchargeBumpsTheVersionAndRefusesTheOldToken() {
        User incharge = newUser(hospital.getId(), "NURSE_INCHARGE");
        String inchargeToken = jwtUtil.generateToken(incharge.getId(), incharge.getEmail(),
                "NURSE_INCHARGE", hospital.getId(), hospital.getModules(), null, "HOSPITAL",
                List.of(), incharge.getTokenVersion());

        User fresh = userRepository.findById(incharge.getId()).orElseThrow();
        fresh.setRole("NURSE");
        userRepository.save(fresh);

        assertThat(probe(inchargeToken)).isEqualTo(401);
    }

    // ── 7. Super Admin ────────────────────────────────────────────────────────
    @Test
    void theSuperAdminHasNoTenantAndKeepsWorking() {
        User su = userRepository.findByEmail("admin123@gmail.com").orElseThrow();
        String suToken = jwtUtil.generateToken(su.getId(), su.getEmail(), "SUPER_ADMIN",
                null, List.of(), null, null, List.of(), su.getTokenVersion());

        ResponseEntity<String> res = rest.exchange("/platform/hospitals", HttpMethod.GET,
                new HttpEntity<>(null, headers(suToken)), String.class);

        assertThat(res.getStatusCode().value())
                .as("a null hospitalId must not be treated as a dead tenant").isEqualTo(200);
    }

    // ── 8. fail-closed ────────────────────────────────────────────────────────
    @Test
    void aTokenForAUserThatDoesNotExistIsRefused_notFailedOpen() {
        String ghost = jwtUtil.generateToken(9_999_999L, "ghost@t.test", "HOSPITAL_ADMIN",
                hospital.getId(), hospital.getModules(), null, "HOSPITAL", List.of(), 0);

        assertThat(probe(ghost)).as("a deleted user must not keep working").isEqualTo(401);
    }

    @Test
    void aTokenForATenantThatDoesNotExistIsRefused() {
        String orphan = jwtUtil.generateToken(user.getId(), user.getEmail(), "HOSPITAL_ADMIN",
                8_888_888L, List.of("OPD"), null, "HOSPITAL", List.of(), user.getTokenVersion());

        assertThat(probe(orphan)).isEqualTo(401);
    }

    @Test
    void aStaleTokenVersionIsRefused() {
        String stale = jwtUtil.generateToken(user.getId(), user.getEmail(), "HOSPITAL_ADMIN",
                hospital.getId(), hospital.getModules(), null, "HOSPITAL", List.of(), 99);

        assertThat(probe(stale)).isEqualTo(401);
    }

    @Test
    void theDefaultTokenVersionOfZeroAuthenticatesNormally() {
        assertThat(userRepository.findById(user.getId()).orElseThrow().getTokenVersion())
                .as("a new account starts at 0").isZero();
        String v0 = jwtUtil.generateToken(user.getId(), user.getEmail(), "HOSPITAL_ADMIN",
                hospital.getId(), hospital.getModules(), null, "HOSPITAL", List.of(), 0);

        assertThat(probe(v0)).isEqualTo(200);
    }

    /**
     * The agreed compatibility strategy: tokens minted before the claim existed do not
     * authenticate, so the release that closes the hole does not leave 12 hours of
     * revocation-proof sessions behind it. One forced logout, deliberately.
     */
    @Test
    void aTokenPredatingTheMechanismIsRefused() {
        // Built with the signing key directly: every JwtUtil overload now stamps the claim, so a
        // genuinely claim-less token — the kind minted by the release before this one — cannot be
        // produced through the normal API.
        java.util.Map<String, Object> claims = new java.util.HashMap<>();
        claims.put("userId", user.getId());
        claims.put("role", "HOSPITAL_ADMIN");
        claims.put("hospitalId", hospital.getId());
        claims.put("modules", hospital.getModules());
        claims.put("hospitalType", "HOSPITAL");
        String legacy = io.jsonwebtoken.Jwts.builder()
                .claims(claims)
                .subject(user.getEmail())
                .issuedAt(new java.util.Date())
                .expiration(new java.util.Date(System.currentTimeMillis() + 3_600_000))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .compact();

        assertThat(probe(legacy)).as("no tokenVersion claim ⇒ refused").isEqualTo(401);
    }

    // ── 9. cross-tenant unchanged ─────────────────────────────────────────────
    @Test
    void aForeignTenantTokenIsStillRejectedByTenantScoping() {
        Hospital other = newHospital();
        User intruder = newUser(other.getId(), "HOSPITAL_ADMIN");
        String foreign = jwtUtil.generateToken(intruder.getId(), intruder.getEmail(), "HOSPITAL_ADMIN",
                other.getId(), other.getModules(), null, "HOSPITAL", List.of(),
                intruder.getTokenVersion());

        // Authenticates (its own tenant is alive) but must not reach the platform tier.
        assertThat(probe(foreign)).isEqualTo(200);
        assertThat(rest.exchange("/platform/hospitals", HttpMethod.GET,
                new HttpEntity<>(null, headers(foreign)), String.class)
                .getStatusCode().value()).isEqualTo(403);
    }
}
