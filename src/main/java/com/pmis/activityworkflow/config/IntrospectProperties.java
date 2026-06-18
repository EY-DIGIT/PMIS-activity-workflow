package com.pmis.activityworkflow.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Config for the upstream token introspect API.
 * Called on every inbound request to validate the Bearer token.
 */
@Component
@ConfigurationProperties(prefix = "app.introspect")
@Data
public class IntrospectProperties {

    private boolean enabled = true;

    private String urlTemplate =
            "http://10.1.131.199/users/api/v3/users/introspect";

    private int connectTimeoutMs = 3_000;
    private int readTimeoutMs    = 5_000;
}
