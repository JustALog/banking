package vn.banking.auth.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

// shortcut: per-replica in-memory counter, move to Redis/gateway RequestRateLimiter when auth runs >1 replica.
// Per-email key lets a third party lock a victim out for 15 min.
@Component
public class LoginThrottle {

    static final int MAX_ATTEMPTS = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int SWEEP_EVERY = 1000;

    private final Clock clock;
    private final ConcurrentHashMap<String, Deque<Instant>> attempts = new ConcurrentHashMap<>();
    private final AtomicInteger calls = new AtomicInteger();

    public LoginThrottle(Clock clock) {
        this.clock = clock;
    }

    /** Records an attempt before the password is checked; false when the window already holds 5. */
    public boolean tryAcquire(String email) {
        if (calls.incrementAndGet() % SWEEP_EVERY == 0) sweep();
        boolean[] allowed = {false};
        attempts.compute(email, (k, d) -> {
            Instant now = clock.instant();
            if (d == null) d = new ArrayDeque<>();
            while (!d.isEmpty() && !d.peekFirst().isAfter(now.minus(WINDOW))) d.removeFirst();
            if (d.size() < MAX_ATTEMPTS) {
                d.addLast(now);
                allowed[0] = true;
            }
            return d;
        });
        return allowed[0];
    }

    public void reset(String email) {
        attempts.remove(email);
    }

    int size() {
        return attempts.size();
    }

    private void sweep() {
        Instant cutoff = clock.instant().minus(WINDOW);
        for (String k : attempts.keySet()) {
            attempts.computeIfPresent(k, (key, d) -> isStale(d, cutoff) ? null : d);
        }
    }

    private static boolean isStale(Deque<Instant> d, Instant cutoff) {
        return d.isEmpty() || !d.peekLast().isAfter(cutoff);
    }
}
