package vn.banking.auth.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class LoginThrottleTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
    private final Clock clock = new Clock() {
        public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId zone) { return this; }
        public Instant instant() { return now.get(); }
    };
    private final LoginThrottle throttle = new LoginThrottle(clock);

    private boolean attempt(String key) {
        return throttle.tryAcquire(key);
    }

    private void attempts(int n) {
        for (int i = 0; i < n; i++) attempt("a@b.vn");
    }

    @Test
    void fifthFailureBlocks() {
        attempts(4);
        assertTrue(attempt("a@b.vn"));
        assertFalse(attempt("a@b.vn"));
        assertTrue(attempt("other@b.vn"));
    }

    @Test
    void blockExpiresAfter15Minutes() {
        attempts(5);
        now.set(now.get().plus(Duration.ofMinutes(14)));
        assertFalse(attempt("a@b.vn"));
        now.set(now.get().plus(Duration.ofMinutes(2)));
        assertTrue(attempt("a@b.vn"));
    }

    @Test
    void successResets() {
        attempts(4);
        throttle.reset("a@b.vn");
        attempts(4);
        assertTrue(attempt("a@b.vn"));
    }

    @Test
    void sweepDropsStaleEntries() {
        attempt("stale@b.vn");
        now.set(now.get().plus(Duration.ofMinutes(16)));
        for (int i = 0; i < 1000; i++) attempt("k" + (i % 3));
        assertEquals(3, throttle.size());
    }
}
