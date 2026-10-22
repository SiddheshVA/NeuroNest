package com.neuronest.backend.jobs;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class BackgroundJobsConfigTest {

    private final BookingJobStore store = mock(BookingJobStore.class);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BackgroundJobsConfig.class)
            .withBean(BookingJobStore.class, () -> store)
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));

    @Test
    void withJobsDisabledNothingIsRegisteredAndNothingRuns() {
        for (String setting : new String[]{"jobs.enabled=false", "jobs.enabled=FALSE"}) {
            runner.withPropertyValues(setting).run(context -> {
                assertThat(context).doesNotHaveBean(BackgroundJobs.class);
                assertThat(context).doesNotHaveBean(SchedulingConfigurer.class);
                assertThat(context).hasBean("backgroundJobsDisabledNotice");
            });
        }
        // Not set at all: the default is off.
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(BackgroundJobs.class);
            assertThat(context).hasBean("backgroundJobsDisabledNotice");
        });
        verifyNoInteractions(store);
    }

    @Test
    void withJobsEnabledOneFixedDelayTaskIsRegisteredWithAtLeastOneMinute() {
        runner.withPropertyValues("jobs.enabled=true", "jobs.interval-minutes=0").run(context -> {
            assertThat(context).hasSingleBean(BackgroundJobs.class);
            assertThat(context).doesNotHaveBean("backgroundJobsDisabledNotice");
            ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
            context.getBean(SchedulingConfigurer.class).configureTasks(registrar);
            assertThat(registrar.getFixedDelayTaskList()).hasSize(1);
            FixedDelayTask task = (FixedDelayTask) registrar.getFixedDelayTaskList().get(0);
            assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofMinutes(1));
        });
        runner.withPropertyValues("jobs.enabled=true").run(context -> {
            ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
            context.getBean(SchedulingConfigurer.class).configureTasks(registrar);
            assertThat(registrar.getFixedDelayTaskList().get(0).getIntervalDuration()).isEqualTo(Duration.ofMinutes(10));
        });
    }
}
