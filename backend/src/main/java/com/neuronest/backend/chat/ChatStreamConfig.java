package com.neuronest.backend.chat;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class ChatStreamConfig {

    // Streamed chat turns run here, off the request thread. Bounded: when it is full the
    // controller answers with a "busy" error event instead of queueing indefinitely.
    @Bean(name = "chatStreamExecutor")
    public ThreadPoolTaskExecutor chatStreamExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("chat-stream-");
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(50);
        executor.initialize();
        return executor;
    }

    // Background rolling-summary updates (one LLM call every few turns per conversation).
    @Bean(name = "chatSummaryExecutor")
    public ThreadPoolTaskExecutor chatSummaryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("chat-summary-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.initialize();
        return executor;
    }
}
