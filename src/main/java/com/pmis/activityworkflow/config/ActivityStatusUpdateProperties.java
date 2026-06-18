package com.pmis.activityworkflow.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Config for the upstream PATCH /projects/api/v3/activities/{activityId} call
 * that marks an activity as completed when the workflow reaches ACTIVITYCOMPLETED.
 */
@Component
@ConfigurationProperties(prefix = "app.activity-status-update")
@Data
public class ActivityStatusUpdateProperties {

    private boolean enabled = true;

    private String urlTemplate =
            "http://10.1.131.199/projects/api/v3/activities/{activityId}";

    private int connectTimeoutMs = 5_000;
    private int readTimeoutMs    = 10_000;
}
