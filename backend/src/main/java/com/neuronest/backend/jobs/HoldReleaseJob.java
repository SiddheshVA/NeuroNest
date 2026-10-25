package com.neuronest.backend.jobs;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;

// Every minute: releases the slots of bookings whose 10-minute payment hold ended unpaid (EXPIRED, with the
// user's "released" email). It runs even when the other background jobs (JOBS_ENABLED) are off, because a
// hold that is never released would keep its slots taken. Off only with bookings.hold-release.enabled=false.
@Component
@ConditionalOnProperty(name = "bookings.hold-release.enabled", havingValue = "true", matchIfMissing = true)
public class HoldReleaseJob {

    private final BackgroundJobs jobs;

    public HoldReleaseJob(BookingJobStore store, ApplicationEventPublisher events,
                          PlatformTransactionManager transactionManager,
                          @Value("${bookings.payment-grace-minutes:2}") long paymentGraceMinutes) {
        this.jobs = new BackgroundJobs(store, events, new TransactionTemplate(transactionManager), Clock.systemUTC(),
                Duration.ofMinutes(Math.max(0, paymentGraceMinutes)));
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT30S")
    public void run() {
        jobs.releaseExpiredHolds();
    }
}
