package com.pmis.activityworkflow.service.document;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Minimal parsed result from a successful upstream comment upload.
 *
 * <p>Only two things are interesting: {@code id} (the upstream comment id,
 * stored locally as docId) and {@code targetId} (the activity this comment
 * was attached to).</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class MilestoneCommentResult {

    /** Upstream comment id. */
    private String docId;

    /** Activity the upstream comment was attached to (data.targetId). */
    private String activityId;

    /** Author email (data.author.email) - upstream's view of who uploaded. */
    private String authorEmail;

    /** Author username/login (data.author.login). */
    private String authorLogin;
}
