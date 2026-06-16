package com.pmis.activityworkflow.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.completion-eligibility")
@Data
public class CompletionEligibilityProperties {

    private boolean enabled = true;

    private String urlTemplate =
            "http://10.1.131.199/projects/api/v3/activities/{activityId}/completion-eligibility";

    private int connectTimeoutMs = 5_000;
    private int readTimeoutMs    = 10_000;
}
