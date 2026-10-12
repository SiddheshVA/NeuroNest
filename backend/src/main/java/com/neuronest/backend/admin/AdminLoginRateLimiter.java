package com.neuronest.backend.admin;

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

// In-memory, per-IP limiter for failed admin logins: MAX_FAILURES failures within WINDOW block
// that IP for BLOCK. State is lost on restart and not shared between instances, which is fine
// for a single backend instance.
@Component
public class AdminLoginRateLimiter {

    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);
    static final Duration BLOCK = Duration.ofMinutes(15);

    private static final class Entry {
        final Deque<Instant> failures = new ArrayDeque<>();
        Instant blockedUntil;
    }

    private final Map<String, Entry> entries = new HashMap<>();
    private final Clock clock;

    @Autowired
    public AdminLoginRateLimiter() {
        this(Clock.systemUTC());
    }

    AdminLoginRateLimiter(Clock clock) {
        this.clock = clock;
    }

    // Time left on the block for this IP, or empty when it may try to log in.
    public synchronized Optional<Duration> blockedFor(String ip) {
        Entry entry = entries.get(ip);
        Instant now = clock.instant();
        if (entry == null || entry.blockedUntil == null || !now.isBefore(entry.blockedUntil)) {
            return Optional.empty();
        }
        return Optional.of(Duration.between(now, entry.blockedUntil));
    }

    public synchronized void recordFailure(String ip) {
        Instant now = clock.instant();
        purgeStale(now);
        Entry entry = entries.computeIfAbsent(ip, k -> new Entry());
        dropOldFailures(entry, now);
        entry.failures.addLast(now);
        if (entry.failures.size() >= MAX_FAILURES) {
            entry.blockedUntil = now.plus(BLOCK);
            entry.failures.clear();
        }
    }

    public synchronized void recordSuccess(String ip) {
        entries.remove(ip);
    }

    private static void dropOldFailures(Entry entry, Instant now) {
        Instant cutoff = now.minus(WINDOW);
        while (!entry.failures.isEmpty() && !entry.failures.peekFirst().isAfter(cutoff)) {
            entry.failures.removeFirst();
        }
    }

    // Keeps the map from growing without bound: forget IPs with no live failures and no active block.
    private void purgeStale(Instant now) {
        entries.values().removeIf(entry -> {
            dropOldFailures(entry, now);
            boolean blocked = entry.blockedUntil != null && now.isBefore(entry.blockedUntil);
            return !blocked && entry.failures.isEmpty();
        });
    }
}
