package com.hms.filter;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reported production defect: users got HTTP 429 on a normal login.
 *
 * <p>The AUTH tier consumed a token on every login attempt, successful ones included. With the
 * shipped defaults (5 per account per 15 minutes) a sixth legitimate sign-in inside that window
 * -- role switching, a re-login after token expiry, a second device -- was refused and stayed
 * refused for up to the full window. Per-IP (10/minute) had the same shape and is worse in a
 * hospital, where the whole site NATs behind one address and shift changes produce a burst of
 * successful logins from different people.
 *
 * <p>These buckets exist to throttle brute force. A successful authentication is not an attack,
 * so it is refunded; a failure is not.
 */
class RateLimitFilterTest {

    private RateLimitFilter filter;

    /** Defaults as shipped in application.properties. */
    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter();
        ReflectionTestUtils.setField(filter, "enabled", true);
        ReflectionTestUtils.setField(filter, "authIpCapacity", 10);
        ReflectionTestUtils.setField(filter, "authIpRefillMinutes", 1);
        ReflectionTestUtils.setField(filter, "authAccountCapacity", 5);
        ReflectionTestUtils.setField(filter, "authAccountRefillMinutes", 15);
        ReflectionTestUtils.setField(filter, "publicCapacity", 60);
        ReflectionTestUtils.setField(filter, "publicRefillMinutes", 1);
        ReflectionTestUtils.setField(filter, "authenticatedCapacity", 300);
        ReflectionTestUtils.setField(filter, "authenticatedRefillMinutes", 1);
    }

    private MockHttpServletRequest loginRequest(String email, String ip) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/login");
        req.setRequestURI("/login");
        req.setRemoteAddr(ip);
        req.setContentType("application/json");
        req.setContent(("{\"email\":\"" + email + "\",\"password\":\"x\"}").getBytes(StandardCharsets.UTF_8));
        return req;
    }

    /** Chain that answers with a fixed status, standing in for the auth controller. */
    private FilterChain chainReturning(int status) {
        return (request, response) -> ((MockHttpServletResponse) response).setStatus(status);
    }

    private int attemptLogin(String email, String ip, int outcomeStatus) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(loginRequest(email, ip), res, chainReturning(outcomeStatus));
        return res.getStatus();
    }

    /** The exact reported symptom: repeated *successful* logins must never start returning 429. */
    @Test
    void manySuccessfulLogins_areNeverRateLimited() throws Exception {
        for (int i = 1; i <= 25; i++) {
            int status = attemptLogin("nurse@hospital.test", "10.0.0.5", 200);
            assertThat(status)
                    .as("successful login #%d must not be throttled", i)
                    .isEqualTo(200);
        }
    }

    /** Brute force is still throttled: failures consume and are not refunded. */
    @Test
    void repeatedFailedLogins_areStillBlockedAfterTheAccountLimit() throws Exception {
        String email = "victim@hospital.test";
        for (int i = 1; i <= 5; i++) {
            assertThat(attemptLogin(email, "10.0.0.6", 401))
                    .as("failure #%d is within the account allowance", i)
                    .isEqualTo(401);
        }
        assertThat(attemptLogin(email, "10.0.0.6", 401))
                .as("the 6th consecutive failure must be refused")
                .isEqualTo(429);
    }

    /**
     * A user who mistypes a few times and then gets it right is not left locked out: the
     * successful attempt is refunded, so headroom remains.
     */
    @Test
    void aSuccessAfterSomeFailures_doesNotConsumeTheRemainingAllowance() throws Exception {
        String email = "typo@hospital.test";
        String ip = "10.0.0.7";
        assertThat(attemptLogin(email, ip, 401)).isEqualTo(401);
        assertThat(attemptLogin(email, ip, 401)).isEqualTo(401);

        // Correct password, then several more successful sign-ins.
        for (int i = 0; i < 10; i++) {
            assertThat(attemptLogin(email, ip, 200))
                    .as("successful sign-in must stay allowed")
                    .isEqualTo(200);
        }
    }

    /** The per-account bucket is per account: one throttled user must not lock out a colleague. */
    @Test
    void throttlingOneAccount_doesNotAffectAnother() throws Exception {
        for (int i = 1; i <= 6; i++) {
            attemptLogin("locked@hospital.test", "10.0.0.8", 401);
        }
        assertThat(attemptLogin("locked@hospital.test", "10.0.0.8", 401)).isEqualTo(429);

        assertThat(attemptLogin("colleague@hospital.test", "10.0.0.9", 200))
                .as("a different account from a different address is unaffected")
                .isEqualTo(200);
    }

    /** Shift change: many different staff signing in successfully from one NATed site address. */
    @Test
    void manyStaffSigningInFromOneSharedIp_areNotThrottled() throws Exception {
        String sharedIp = "203.0.113.10";
        for (int i = 1; i <= 20; i++) {
            assertThat(attemptLogin("staff" + i + "@hospital.test", sharedIp, 200))
                    .as("staff member #%d behind the shared site IP", i)
                    .isEqualTo(200);
        }
    }

    /** A 5xx is not a success and must not be refunded. */
    @Test
    void serverErrorsAreNotRefunded() throws Exception {
        String email = "err@hospital.test";
        for (int i = 1; i <= 5; i++) {
            assertThat(attemptLogin(email, "10.0.0.11", 500)).isEqualTo(500);
        }
        assertThat(attemptLogin(email, "10.0.0.11", 500))
                .as("server errors still consume the allowance")
                .isEqualTo(429);
    }
}
