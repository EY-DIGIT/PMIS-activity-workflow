package com.pmis.activityworkflow.web.request;

import com.pmis.activityworkflow.web.models.RequestInfo;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body for {@code POST /activities/parallel/request-division-approval}.
 *
 * <p>The single attached file + comment are SHARED across every division
 * approver — same email goes to all of them. The file part is sent as a
 * separate multipart 'file' field on the request, not in this body.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RequestDivisionApprovalRequest {

    @JsonProperty("RequestInfo")
    private RequestInfo requestInfo;

    @NotBlank
    private String businessService;

    @NotBlank
    private String activityId;

    private String projectId;

    /**
     * Optional. If omitted, we resolve from the activity's current state
     * in {@code aw_process_instance}, falling back to
     * {@code PENDINGATCONCERNEDDIVISION}.
     */
    private String stateName;

    /** Optional admin note attached to each approval-request email. */
    private String comment;
}
