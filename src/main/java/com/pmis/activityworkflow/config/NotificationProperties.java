package com.pmis.activityworkflow.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * External notification API config. Pulled from application.properties
 * under {@code app.notification.*}.
 */
@Component
@ConfigurationProperties(prefix = "app.notification")
@Data
public class NotificationProperties {

    /** Master switch. Disable in dev or unit tests. */
    private boolean enabled = true;

    /** Full URL of the external notify endpoint. */
    private String url;

    /** Optional bearer token sent as Authorization header. */
    private String authToken;

    /** Connect + read timeouts in milliseconds. */
    private int connectTimeoutMs = 3_000;
    private int readTimeoutMs    = 5_000;
}
