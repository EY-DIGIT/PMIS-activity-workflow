package com.pmis.activityworkflow.web.request;

import com.pmis.activityworkflow.web.models.RequestInfo;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Body for {@code POST /activities/parallel/request-owner-approval}.
 *
 * <p>Sent by the admin when all concerned divisions have approved. The
 * single attached file + comment are routed to the owner approver. The
 * file part is sent as a separate multipart 'file' field on the request,
 * not in this body.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RequestOwnerApprovalRequest {

    @JsonProperty("RequestInfo")
    private RequestInfo requestInfo;

    @NotBlank
    private String businessService;

    @NotBlank
    private String activityId;

    private String projectId;

    /** Current parallel state, e.g. PENDINGATCONCERNEDDIVISION. */
    @NotBlank
    private String stateName;

    /** Optional admin note attached to the owner-approval email. */
    private String comment;

    /**
     * Upstream comment ids returned by {@code POST /activities/documents/upload}
     * with {@code divisionId=OWNER}. Pre-uploaded files are tagged to the owner
     * division when this request is processed.
     */
    private List<String> documentStoreIds;
}
