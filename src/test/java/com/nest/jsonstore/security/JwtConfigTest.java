package com.nest.jsonstore.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtConfigTest {

    private static SecurityProperties withSecret(String secret) {
        return new SecurityProperties(
                new SecurityProperties.Jwt(secret, Duration.ofHours(8), Duration.ofHours(24)),
                new SecurityProperties.Ldap(null, null, null, null, null));
    }

    private static MockEnvironment active(String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        return environment;
    }

    @Test
    void refusesThePublishedDevelopmentSecretUnderAnyNamedProfile() {
        // A deployment with a mistyped profile — staging instead of prod — used to fall back to it.
        for (String profile : new String[] {"staging", "prod", "uat"}) {
            assertThatThrownBy(() -> new JwtConfig(withSecret(JwtConfig.DEVELOPMENT_SECRET), active(profile)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("published in this repository");
        }
    }

    @Test
    void acceptsTheDevelopmentSecretOnADevelopersOwnMachine() {
        MockEnvironment nothingChosen = new MockEnvironment();
        nothingChosen.setDefaultProfiles("local");

        assertThatCode(() -> new JwtConfig(withSecret(JwtConfig.DEVELOPMENT_SECRET), nothingChosen))
                .doesNotThrowAnyException();
        assertThatCode(() -> new JwtConfig(withSecret(JwtConfig.DEVELOPMENT_SECRET), active("local")))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsASecretOfYourOwnAnywhere() {
        assertThatCode(() -> new JwtConfig(withSecret("a-secret-of-our-own-that-is-long-enough"), active("prod")))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesASecretTooShortToSignWith() {
        assertThatThrownBy(() -> new JwtConfig(withSecret("short"), active("prod")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 characters");
    }
}
