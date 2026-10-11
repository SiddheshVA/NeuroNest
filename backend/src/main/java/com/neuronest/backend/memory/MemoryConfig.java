package com.neuronest.backend.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(MemoryProperties.class)
public class MemoryConfig {

    private static final Logger log = LoggerFactory.getLogger(MemoryConfig.class);

    // Dedicated, bounded pool for background extraction so it can never starve
    // request threads. When the queue is full the task is dropped (not run on the
    // caller's thread): the idle sweep picks the conversation up later, and the
    // chat request is never slowed down.
    @Bean(name = "memoryExecutor")
    public ThreadPoolTaskExecutor memoryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("memory-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(200);
        executor.setRejectedExecutionHandler((task, pool) ->
                log.warn("Memory executor saturated; dropping extraction task (idle sweep will retry)."));
        executor.initialize();
        return executor;
    }
}
