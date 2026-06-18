package com.pmis.activityworkflow.web.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Structured approval summary for one activity.
 *
 * <p>Returned by {@code GET /activities/inbox/{activityId}/approval-summary}.
 * Answers four questions in one call:
 * <ul>
 *   <li>When was division approval requested, and by whom?</li>
 *   <li>When was owner approval requested, and by whom?</li>
 *   <li>Which concerned divisions approved / rejected, and when?</li>
 *   <li>Did the owner approve / reject, and when?</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApprovalSummaryResponse {

    private String activityId;

    /** Set when the admin clicked "Request Division Approval". Null if not yet requested. */
    private RequestEvent divisionApprovalRequest;

    /** Set when the admin clicked "Request Owner Approval". Null if not yet requested. */
    private RequestEvent ownerApprovalRequest;

    /** One entry per concerned division (PENDINGATCONCERNEDDIVISION participants). */
    private List<DivisionDecision> concernedDivisions;

    /** The owner-division approver row. Null if the activity has not reached the owner stage. */
    private DivisionDecision ownerDivision;

    /* ============================================================ */

    /** Captures an admin button-click event (REQUEST_DIVISION_APPROVAL or REQUEST_OWNER_APPROVAL). */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RequestEvent {
        /** Epoch-milliseconds when the admin clicked the button. */
        private Long requestedAt;
        private String requestedByUuid;
        private String requestedByUsername;
        /** Admin's comment submitted with the request. */
        private String comment;
    }

    /** Captures one approver's decision (APPROVED / REJECTED / PENDING). */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DivisionDecision {
        private String divisionCode;
        private String divisionName;
        private String approverUuid;
        private String approverName;
        private String approverEmail;
        /** PENDING / APPROVED / REJECTED. */
        private String status;
        /** Epoch-milliseconds when the vote was cast. Null while PENDING. */
        private Long actionAt;
        /** Reviewer's comment submitted with the vote. */
        private String comment;
    }
}
