package com.pmis.activityworkflow.web.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response returned by {@code POST /activities/documents/upload}.
 *
 * <p>The {@code documentStoreId} is the upstream comment id returned by the
 * document store. Pass it in {@code divisionApprovals[].documentStoreIds}
 * when calling {@code POST /activities/parallel/request-division-approval}.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DocumentUploadResponse {

    /** The upstream document store ID — use this in divisionApprovals[].documentStoreIds. */
    private String documentStoreId;

    /** Division code this upload belongs to (echoed back for the frontend's reference). */
    private String divisionId;

    /** The activity this file was attached to. */
    private String activityId;

    /** Comment text submitted with this upload. */
    private String comment;

    /** CONCERNED_DIVISION or OWNER_DIVISION — identifies which stage this upload belongs to. */
    private String documentCategory;

    /** Epoch-milliseconds timestamp of when the upload was persisted locally. */
    private Long uploadedAt;

    /** All files uploaded in this request, with their download URLs. */
    private List<UploadedFile> attachments;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class UploadedFile {
        private String fileName;
        private String fileUrl;
        private String mimeType;
        private Long sizeBytes;
    }
}
