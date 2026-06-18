package com.pmis.activityworkflow.service.document;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

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

    /** First file's original filename (convenience field — same as attachments.get(0).fileName). */
    private String fileName;

    /** First file's download URL (convenience field — same as attachments.get(0).fileUrl). */
    private String fileUrl;

    /** All attachments returned by the upstream comments API for this comment. */
    @Builder.Default
    private List<Attachment> attachments = List.of();

    /** One uploaded file as returned in {@code data.attachments[]} by the upstream API. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Attachment {
        private String fileName;
        private String fileUrl;
        private String mimeType;
        private Long sizeBytes;
    }
}
