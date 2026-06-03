package com.pmis.activityworkflow.web.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One row in the approval inbox screen.
 *
 * <p>Shape matches the columns in the UI:
 * <ul>
 *   <li>ACTIVITY    — {@code activityDisplayCode} + {@code activityName}</li>
 *   <li>PROJECT     — {@code projectName} + {@code projectCode}</li>
 *   <li>ORGANIZATION — {@code organizationName} (vendor matched by activity.vendorId)</li>
 *   <li>SUBMITTED   — {@code submittedAt} (timestamp of most recent SUBMIT)</li>
 *   <li>STATUS      — {@code voteStatus} (this approver's vote status)</li>
 * </ul></p>
 *
 * <p>{@code divisionCode} / {@code divisionName} let the UI render the
 * heading ("TMD1 — Technology & Modules Division 1").</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApprovalInboxItem {

    /** Local participant row uuid — useful as a stable row id in the UI. */
    private String participantUuid;

    /** Workflow definition (e.g. "ACTIVITY"). */
    private String businessService;

    /** Parallel state (e.g. "PENDINGATCONCERNEDDIVISION"). */
    private String stateName;

    /* ----- activity ----- */
    private String activityId;
    private String activityDisplayCode;     // "A2.1"
    private String activityName;            // "API Implementation Documentation"

    /* ----- project ----- */
    private String projectId;
    private String projectName;             // "Aadhaar Authentication API v3.0 Upgrade"
    private String projectCode;             // "PRJ-2026-018"

    /* ----- organization (vendor) ----- */
    private String organizationId;
    private String organizationName;        // "TechSolutions India Pvt Ltd"

    /* ----- approver / division ----- */
    private String divisionCode;            // "tmd1"
    private String divisionName;            // "Technology & Modules Division 1"
    private String approverUserUuid;
    private String approverName;
    private String approverEmail;

    /* ----- workflow state ----- */
    private String voteStatus;              // PENDING / APPROVED / REJECTED
    private Long   submittedAt;             // ms epoch of the SUBMIT transition
    private Long   votedAt;                 // ms epoch, present when voteStatus != PENDING
}
