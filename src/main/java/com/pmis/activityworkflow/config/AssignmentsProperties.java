package com.pmis.activityworkflow.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Config for the upstream "activity assignments" API that returns
 * division approvers, division users, and the owner approver for a given
 * activity id.
 *
 * <p>Auth — the caller's Authorization header is forwarded as-is. No
 * service-account token is configured here.</p>
 */
@Component
@ConfigurationProperties(prefix = "app.assignments")
@Data
public class AssignmentsProperties {

    /** Master switch. Disable in tests if you don't want HTTP calls. */
    private boolean enabled = true;

    /**
     * Base URL where {activityId} will be substituted. Defaults to the
     * pattern shown in your curl.
     */
    private String urlTemplate =
            "http://10.1.131.199/projects/api/v3/activities/{activityId}/assignments";

    private int connectTimeoutMs = 5_000;
    private int readTimeoutMs    = 10_000;
}
