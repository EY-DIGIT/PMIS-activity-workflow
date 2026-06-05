package com.pmis.activityworkflow.web.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Snapshot of the concerned-division gate for one activity.
 *
 * <p>Designed for the UI to answer two questions cheaply:</p>
 * <ol>
 *   <li>Has every division voted? → {@code readyForOwner}</li>
 *   <li>If not, who's still pending? → per-division breakdown</li>
 * </ol>
 *
 * <p>{@code readyForOwner} is the single boolean the "Request Owner
 * Approval" button should bind to — true iff every approver is APPROVED.
 * If any row is REJECTED or PENDING, it's false.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GateStatusResponse {

    private String businessService;
    private String activityId;
    private String stateName;

    /** Total approver rows seeded at this gate state. */
    private int totalApprovers;

    private int approvedCount;
    private int pendingCount;
    private int rejectedCount;

    /**
     * True iff {@code approvedCount == totalApprovers > 0}.
     * UI uses this to enable/disable "Request Owner Approval".
     */
    private boolean readyForOwner;

    /**
     * Convenience flag — true if any approver has rejected. The activity
     * has likely already been auto-routed back to READYFORAPPROVAL by
     * the gate; this just lets the UI render a "rejection" badge without
     * a second call.
     */
    private boolean hasRejection;

    /** Per-approver detail — useful for the "Your Status" panel. */
    private List<DivisionStatus> divisions;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DivisionStatus {
        private String divisionCode;
        private String divisionName;
        private String approverUserUuid;
        private String approverName;
        private String approverEmail;
        /** PENDING / APPROVED / REJECTED. */
        private String voteStatus;
        private String voteComment;
        private Long votedAt;
    }
}
