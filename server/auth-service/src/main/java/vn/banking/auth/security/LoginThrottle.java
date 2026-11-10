package vn.banking.auth.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

// shortcut: per-replica in-memory counter, move to Redis/gateway RequestRateLimiter when auth runs >1 replica.
@Component
public class LoginThrottle {

    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);

    private final Clock clock;
    private final ConcurrentHashMap<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    public LoginThrottle(Clock clock) {
        this.clock = clock;
    }

    public boolean blocked(String email) {
        Deque<Instant> d = failures.get(email);
        if (d == null) return false;
        synchronized (d) {
            prune(d);
            return d.size() >= MAX_FAILURES;
        }
    }

    public void fail(String email) {
        Deque<Instant> d = failures.computeIfAbsent(email, k -> new ArrayDeque<>());
        synchronized (d) {
            prune(d);
            d.addLast(clock.instant());
        }
    }

    public void reset(String email) {
        failures.remove(email);
    }

    private void prune(Deque<Instant> d) {
        Instant cutoff = clock.instant().minus(WINDOW);
        while (!d.isEmpty() && !d.peekFirst().isAfter(cutoff)) d.removeFirst();
    }
}
