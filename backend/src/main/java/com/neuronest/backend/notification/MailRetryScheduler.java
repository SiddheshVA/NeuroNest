package com.neuronest.backend.notification;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

// Runs the single retry of a failed email later. A private one-thread scheduler, deliberately not a
// TaskScheduler bean, so it never takes over the app's @Scheduled jobs.
@Component
public class MailRetryScheduler {

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mail-retry");
        thread.setDaemon(true);
        return thread;
    });

    public void schedule(Runnable task, Duration delay) {
        scheduler.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
