package com.neuronest.backend.pro;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

// In-memory, per-IP limit on professional registration attempts: MAX_ATTEMPTS per WINDOW. Every
// attempt counts (not only failures), since each one may call the Clerk Backend API. State is lost
// on restart and not shared between instances, which is fine for a single backend.
@Component
public class ProRegistrationRateLimiter {

    static final int MAX_ATTEMPTS = 5;
    static final Duration WINDOW = Duration.ofHours(1);

    private final Map<String, Deque<Instant>> attempts = new HashMap<>();
    private final Clock clock;

    @Autowired
    public ProRegistrationRateLimiter() {
        this(Clock.systemUTC());
    }

    ProRegistrationRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /**
     * Records an attempt from this IP. Returns how long to wait when the limit is already reached
     * (that attempt is not counted), or empty when the attempt may go ahead.
     */
    public synchronized Optional<Duration> tryAcquire(String ip) {
        Instant now = clock.instant();
        Instant cutoff = now.minus(WINDOW);
        attempts.values().removeIf(times -> {
            while (!times.isEmpty() && !times.peekFirst().isAfter(cutoff)) {
                times.removeFirst();
            }
            return times.isEmpty();
        });
        Deque<Instant> times = attempts.computeIfAbsent(ip, k -> new ArrayDeque<>());
        if (times.size() >= MAX_ATTEMPTS) {
            return Optional.of(Duration.between(now, times.peekFirst().plus(WINDOW)));
        }
        times.addLast(now);
        return Optional.empty();
    }
}
