package com.pmis.activityworkflow.service.document;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What we extract from a successful upstream comment-with-attachment
 * response. The upstream API returns a Comment object with an attachment
 * URL; we surface the minimum we need to persist locally.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class MilestoneCommentResult {

    /** Upstream comment id — we use this as our local docId. */
    private String commentId;

    /** Upstream milestone the comment was attached to. */
    private String milestoneId;

    /** Pre-signed or canonical URL to the file. */
    private String fileUrl;

    /** What the user typed in the body field, echoed back from the upstream API. */
    private String commentBody;
}
