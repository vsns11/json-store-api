package com.nest.jsonstore.security;

import com.nest.jsonstore.error.TooManySignInAttemptsException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignInAttemptsTest {

    private static final AccessProperties ACCESS =
            new AccessProperties(List.of(), List.of("developers"), List.of(), "uid", 3, Duration.ofMinutes(5));

    /** A clock the test moves by hand. */
    private static final class Hands extends Clock {
        Instant now = Instant.parse("2026-09-13T09:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void pausesANameAfterItsWrongPasswordsAndSaysForHowLong() {
        Hands clock = new Hands();
        SignInAttempts attempts = new SignInAttempts(ACCESS, clock);

        for (int i = 0; i < 3; i++) {
            attempts.checkAllowed("mallory");
            attempts.failed("mallory");
            clock.now = clock.now.plusSeconds(60);
        }

        // The first failure was three minutes ago, so it stops counting in two.
        assertThatThrownBy(() -> attempts.checkAllowed("MALLORY"))
                .isInstanceOf(TooManySignInAttemptsException.class)
                .hasMessageContaining("121 seconds");
        assertThatCode(() -> attempts.checkAllowed("bob")).doesNotThrowAnyException();

        clock.now = clock.now.plusSeconds(121);
        assertThatCode(() -> attempts.checkAllowed("mallory")).doesNotThrowAnyException();
    }

    @Test
    void forgetsTheWrongPasswordsOnceTheRightOneIsGiven() {
        SignInAttempts attempts = new SignInAttempts(ACCESS, new Hands());
        attempts.failed("alice");
        attempts.failed("alice");
        attempts.succeeded("Alice");
        attempts.failed("alice");
        attempts.failed("alice");

        assertThatCode(() -> attempts.checkAllowed("alice")).doesNotThrowAnyException();
    }
}
