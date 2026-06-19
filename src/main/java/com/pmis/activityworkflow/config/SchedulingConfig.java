package com.pmis.activityworkflow.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring's {@code @Scheduled} task support.
 * Jobs are only active when {@code spring.task.scheduling.enabled=true} (default).
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
