package vn.banking.auth.security;

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

    private void fail(int n) {
        for (int i = 0; i < n; i++) throttle.fail("a@b.vn");
    }

    @Test
    void fifthFailureBlocks() {
        fail(4);
        assertFalse(throttle.blocked("a@b.vn"));
        fail(1);
        assertTrue(throttle.blocked("a@b.vn"));
        assertFalse(throttle.blocked("other@b.vn"));
    }

    @Test
    void blockExpiresAfter15Minutes() {
        fail(5);
        now.set(now.get().plus(Duration.ofMinutes(14)));
        assertTrue(throttle.blocked("a@b.vn"));
        now.set(now.get().plus(Duration.ofMinutes(2)));
        assertFalse(throttle.blocked("a@b.vn"));
    }

    @Test
    void successResets() {
        fail(4);
        throttle.reset("a@b.vn");
        fail(4);
        assertFalse(throttle.blocked("a@b.vn"));
    }
}
