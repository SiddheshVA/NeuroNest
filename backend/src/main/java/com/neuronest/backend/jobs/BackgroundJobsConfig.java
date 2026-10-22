package com.neuronest.backend.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;

// The background jobs run only when JOBS_ENABLED is true (jobs.enabled). Otherwise nothing is scheduled
// and one line says so at startup. The interval is JOBS_INTERVAL_MINUTES (at least 1, default 10),
// counted from the end of one run to the start of the next, so runs never overlap.
@Configuration(proxyBeanMethods = false)
public class BackgroundJobsConfig {

    static final Duration FIRST_RUN_DELAY = Duration.ofSeconds(30);

    private static final Logger log = LoggerFactory.getLogger(BackgroundJobsConfig.class);

    @Bean
    @ConditionalOnProperty(name = "jobs.enabled", havingValue = "true")
    public BackgroundJobs backgroundJobs(BookingJobStore store, ApplicationEventPublisher events,
                                         PlatformTransactionManager transactionManager) {
        return new BackgroundJobs(store, events, new TransactionTemplate(transactionManager), Clock.systemUTC());
    }

    @Bean
    @ConditionalOnProperty(name = "jobs.enabled", havingValue = "true")
    public SchedulingConfigurer backgroundJobsSchedule(BackgroundJobs jobs,
                                                       @Value("${jobs.interval-minutes:10}") long intervalMinutes) {
        Duration interval = Duration.ofMinutes(Math.max(1, intervalMinutes));
        return registrar -> {
            registrar.addFixedDelayTask(new FixedDelayTask(jobs::runOnce, interval, FIRST_RUN_DELAY));
            log.info("Background jobs are enabled: they run every {} minutes.", interval.toMinutes());
        };
    }

    @Bean
    @ConditionalOnProperty(name = "jobs.enabled", havingValue = "false", matchIfMissing = true)
    public ApplicationRunner backgroundJobsDisabledNotice() {
        return args -> log.info("Background jobs are disabled.");
    }
}
