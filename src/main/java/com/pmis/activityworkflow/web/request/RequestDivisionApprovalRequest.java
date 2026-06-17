package com.pmis.activityworkflow.web.request;

import com.pmis.activityworkflow.web.models.RequestInfo;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Body for {@code POST /activities/parallel/request-division-approval}.
 *
 * <p>Each division gets its own independent comment and document store IDs
 * via {@link #divisionApprovals}. The legacy flat {@code comment} field is
 * still accepted for backward compatibility when {@code divisionApprovals}
 * is absent.</p>
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
     * Optional. If omitted, resolved from the activity's current state
     * in {@code aw_process_instance}, falling back to
     * {@code PENDINGATCONCERNEDDIVISION}.
     */
    private String stateName;

    /**
     * Per-division comments and document store IDs.
     * When present, each entry is handled independently — different comments
     * and attachments are stored per division and shown only to that division.
     */
    @Valid
    private List<DivisionApprovalInput> divisionApprovals;

    /**
     * Legacy flat comment — used only when {@code divisionApprovals} is
     * absent (old single-comment-for-all flow).
     */
    private String comment;
}
