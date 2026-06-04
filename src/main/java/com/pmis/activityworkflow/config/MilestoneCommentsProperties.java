package com.pmis.activityworkflow.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Config for the upstream activity-comments API.
 *
 * <p>We POST {@code multipart/form-data} with one {@code body} field
 * (the comment) and one {@code files} part (the attached file). The
 * caller's {@code Authorization} header is forwarded as-is.</p>
 *
 * <p>Note — the property prefix is still {@code app.milestone-comments}
 * for backward compatibility with existing deployments; the upstream
 * endpoint itself is keyed by {@code activityId} now.</p>
 */
@Component
@ConfigurationProperties(prefix = "app.milestone-comments")
@Data
public class MilestoneCommentsProperties {

    private boolean enabled = true;

    /**
     * URL template for the upload endpoint. {activityId} is substituted
     * at call time.
     */
    private String commentsUrlTemplate =
            "http://10.1.131.199/projects/api/v3/activities/{activityId}/comments";

    /**
     * URL template used to fetch existing comments for an activity (GET).
     * Used by the approval-detail screen to show prior submissions.
     */
    private String commentsListUrlTemplate =
            "http://10.1.131.199/projects/api/v3/activities/{activityId}/comments";

    /**
     * URL template used to fetch activity details (for the approval inbox
     * detail screen — separate concern from the upload itself).
     */
    private String activityLookupUrlTemplate =
            "http://10.1.131.199/projects/api/v3/activities/{activityId}";

    /** Form field carrying the comment text. */
    private String bodyField = "body";

    /** Form field carrying the file. */
    private String filesField = "files";

    /** Max file size enforced before we POST upstream. */
    private long maxFileSizeBytes = 25L * 1024 * 1024;

    private int connectTimeoutMs = 5_000;
    private int readTimeoutMs    = 30_000;
}
