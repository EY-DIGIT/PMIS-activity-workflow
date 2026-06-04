package com.pmis.activityworkflow.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Config for the upstream user-lookup API.
 *
 * <p>Used by the rejection-notification flow to resolve a uuid (e.g. the
 * SUBMIT actor) to an email + name.</p>
 */
@Component
@ConfigurationProperties(prefix = "app.user-details")
@Data
public class UserDetailsProperties {

    private boolean enabled = true;

    /**
     * URL template. {userId} gets substituted at call time.
     * Default points at the existing upstream.
     */
    private String urlTemplate =
            "http://10.1.131.199/users/api/v3/users/{userId}";

    private int connectTimeoutMs = 5_000;
    private int readTimeoutMs    = 10_000;
}
