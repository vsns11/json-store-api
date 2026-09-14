package com.nest.jsonstore.security;

import com.nest.jsonstore.error.TooManySignInAttemptsException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pauses sign-in for a username after too many wrong passwords in a short time, so the endpoint
 * cannot be used to guess at one account, or to lock it out of the directory by tripping the
 * directory's own lockout policy.
 *
 * Counted per replica, in memory: across N replicas a name gets up to N times the allowance. The
 * ingress or route in front of the API adds a limit per client address on top of this.
 */
@Component
class SignInAttempts {

    /** Beyond this many names, new ones go uncounted rather than letting the map grow without end. */
    private static final int TRACKED_NAMES = 10_000;

    private final int maxFailures;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    @Autowired
    SignInAttempts(AccessProperties access) {
        this(access, Clock.systemUTC());
    }

    SignInAttempts(AccessProperties access, Clock clock) {
        this.maxFailures = access.maxFailedSignIns();
        this.window = access.failedSignInWindow();
        this.clock = clock;
    }

    /** @throws TooManySignInAttemptsException while this name has used up its wrong passwords */
    void checkAllowed(String username) {
        Deque<Instant> recent = failures.get(key(username));
        if (recent == null) {
            return;
        }
        synchronized (recent) {
            Instant now = clock.instant();
            prune(recent, now);
            if (recent.size() >= maxFailures) {
                long seconds = Duration.between(now, recent.peekFirst().plus(window)).toSeconds() + 1;
                throw new TooManySignInAttemptsException(Math.max(1, seconds));
            }
        }
    }

    void failed(String username) {
        Instant now = clock.instant();
        if (failures.size() >= TRACKED_NAMES) {
            failures.values().removeIf(recent -> {
                synchronized (recent) {
                    prune(recent, now);
                    return recent.isEmpty();
                }
            });
            if (failures.size() >= TRACKED_NAMES && !failures.containsKey(key(username))) {
                return;
            }
        }
        Deque<Instant> recent = failures.computeIfAbsent(key(username), name -> new ArrayDeque<>());
        synchronized (recent) {
            prune(recent, now);
            recent.addLast(now);
        }
    }

    void succeeded(String username) {
        failures.remove(key(username));
    }

    private void prune(Deque<Instant> recent, Instant now) {
        while (!recent.isEmpty() && !recent.peekFirst().plus(window).isAfter(now)) {
            recent.removeFirst();
        }
    }

    private static String key(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }
}
