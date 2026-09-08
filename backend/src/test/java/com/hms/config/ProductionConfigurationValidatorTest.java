package com.hms.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A production instance must not start with development credentials.
 *
 * <p>application.properties ships a committed JWT placeholder and a localhost CORS origin so a
 * clone runs out of the box. Nothing checked that production overrode either, and the failure was
 * silent: the app started, served traffic, and looked healthy while anyone holding this source
 * could mint a token for any hospital.
 */
class ProductionConfigurationValidatorTest {

    private static final String STRONG_SECRET = "a-real-production-secret-of-sufficient-length-1234567890";
    private static final String REAL_ORIGIN = "https://hms.example.com";

    private ProductionConfigurationValidator validator(String[] profiles, String secret, String origins) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return new ProductionConfigurationValidator(env, secret, origins);
    }

    // ------------------------------------------------------------- production accepted

    @Test
    void productionStartsWithAStrongSecretAndAnHttpsOrigin() {
        assertThatCode(() -> validator(new String[]{"prod"}, STRONG_SECRET, REAL_ORIGIN).validate())
                .doesNotThrowAnyException();
    }

    @Test
    void productionAcceptsSeveralHttpsOrigins() {
        assertThatCode(() -> validator(new String[]{"production"}, STRONG_SECRET,
                "https://hms.example.com, https://admin.example.com").validate())
                .doesNotThrowAnyException();
    }

    // ------------------------------------------------------------- production refused

    @Test
    void productionRefusesTheCommittedPlaceholderSecret() {
        assertThatThrownBy(() -> validator(new String[]{"prod"},
                ProductionConfigurationValidator.DEFAULT_JWT_SECRET, REAL_ORIGIN).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("placeholder");
    }

    @Test
    void productionRefusesAMissingSecret() {
        assertThatThrownBy(() -> validator(new String[]{"prod"}, "", REAL_ORIGIN).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not set");
    }

    @Test
    void productionRefusesAShortSecret() {
        String tooShort = "x".repeat(ProductionConfigurationValidator.MIN_JWT_SECRET_BYTES - 1);
        assertThatThrownBy(() -> validator(new String[]{"prod"}, tooShort, REAL_ORIGIN).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least");
    }

    @Test
    void productionRefusesWellKnownWeakSecrets() {
        for (String weak : new String[]{"changeme", "secret", "PASSWORD", "dev"}) {
            assertThatThrownBy(() -> validator(new String[]{"prod"}, weak, REAL_ORIGIN).validate())
                    .as("weak secret %s", weak)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void productionRefusesALocalhostFrontendOrigin() {
        assertThatThrownBy(() -> validator(new String[]{"prod"}, STRONG_SECRET,
                "https://localhost:5173").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("local machine");
    }

    @Test
    void productionRefusesANonHttpsFrontendOrigin() {
        assertThatThrownBy(() -> validator(new String[]{"prod"}, STRONG_SECRET,
                "http://hms.example.com").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not HTTPS");
    }

    @Test
    void productionRefusesAWildcardOrigin() {
        assertThatThrownBy(() -> validator(new String[]{"prod"}, STRONG_SECRET, "*").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wildcard");
    }

    @Test
    void productionRefusesAMissingFrontendOrigin() {
        assertThatThrownBy(() -> validator(new String[]{"prod"}, STRONG_SECRET, "").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FRONTEND_URL");
    }

    /** One bad origin in a list poisons the list; it is not enough that another is fine. */
    @Test
    void productionRefusesAListContainingOneUnsafeOrigin() {
        assertThatThrownBy(() -> validator(new String[]{"prod"}, STRONG_SECRET,
                "https://hms.example.com, http://staging.example.com").validate())
                .isInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------- other profiles untouched

    /**
     * Development must keep working with the shipped defaults. A check that breaks every
     * developer's first run gets deleted rather than fixed.
     */
    @Test
    void developmentIsUnaffectedByTheShippedDefaults() {
        assertThatCode(() -> validator(new String[]{},
                ProductionConfigurationValidator.DEFAULT_JWT_SECRET, "http://localhost:5173").validate())
                .doesNotThrowAnyException();
    }

    @Test
    void testAndStagingProfilesAreUnaffected() {
        for (String profile : new String[]{"test", "staging"}) {
            assertThatCode(() -> validator(new String[]{profile},
                    ProductionConfigurationValidator.DEFAULT_JWT_SECRET, "http://localhost:5173").validate())
                    .as("profile %s", profile)
                    .doesNotThrowAnyException();
        }
    }

    // ------------------------------------------------------------- no secret leakage

    /** A configuration error must describe the problem without printing the credential. */
    @Test
    void errorMessagesNeverContainTheSecretItself() {
        String distinctive = "super-distinctive-secret-value";
        assertThatThrownBy(() -> ProductionConfigurationValidator.validateJwtSecret(distinctive))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(distinctive));
    }
}
