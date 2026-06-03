package com.pmis.activityworkflow.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Config for the upstream milestone-comments API.
 *
 * <p>We POST {@code multipart/form-data} with one {@code body} field
 * (the comment) and one {@code files} part (the attached file). The
 * caller's {@code Authorization} header is forwarded as-is.</p>
 */
@Component
@ConfigurationProperties(prefix = "app.milestone-comments")
@Data
public class MilestoneCommentsProperties {

    private boolean enabled = true;

    /**
     * URL template for the upload endpoint. {milestoneId} is substituted
     * at call time.
     */
    private String commentsUrlTemplate =
            "http://10.1.131.199/projects/api/v3/milestones/{milestoneId}/comments";

    /**
     * URL template used to look up the milestoneId from an activityId.
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
