package com.neuronest.backend.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class MailConfig {

    public static final String MAIL_EXECUTOR = "mailExecutor";

    private static final Logger log = LoggerFactory.getLogger(MailConfig.class);

    // Booking emails are sent here, after the booking has been committed. Small and bounded: when the
    // queue is full the email is dropped with one log line, never run on (and slowing) the request thread.
    @Bean(name = MAIL_EXECUTOR)
    public ThreadPoolTaskExecutor mailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("mail-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setRejectedExecutionHandler((task, pool) -> log.warn("Email skipped: the mail queue is full"));
        executor.initialize();
        return executor;
    }
}
