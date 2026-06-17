package com.pmis.activityworkflow.web.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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

    /** Original file name of the first (or only) uploaded file. */
    private String fileName;

    /** Download URL returned by the upstream document store, if available. */
    private String fileUrl;

    /** The activity this file was attached to. */
    private String activityId;
}
