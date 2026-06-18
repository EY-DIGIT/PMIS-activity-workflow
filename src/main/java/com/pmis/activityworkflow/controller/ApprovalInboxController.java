package com.pmis.activityworkflow.controller;

import com.pmis.activityworkflow.service.inbox.ActivityTimelineService;
import com.pmis.activityworkflow.service.inbox.ApprovalDetailService;
import com.pmis.activityworkflow.service.inbox.ApprovalInboxService;
import com.pmis.activityworkflow.service.inbox.ApprovalSummaryService;
import com.pmis.activityworkflow.web.response.ApprovalDetailResponse;
import com.pmis.activityworkflow.web.response.ApprovalInboxItem;
import com.pmis.activityworkflow.web.response.ApprovalSummaryResponse;
import com.pmis.activityworkflow.web.response.TimelineEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/activities/inbox")
@Tag(name = "Approval Inbox",
     description = "List + detail endpoints for the approver inbox screens")
@RequiredArgsConstructor
@Validated
public class ApprovalInboxController {

    private final ApprovalInboxService inboxService;
    private final ApprovalDetailService detailService;
    private final ActivityTimelineService timelineService;
    private final ApprovalSummaryService summaryService;

    /**
     * List view — returns one row per approval the user is involved in.
     *
     * @param userUuid   the approver — usually the logged-in user's uuid
     * @param voteStatus optional filter; default is to return all statuses
     */
    @GetMapping
    @Operation(summary = "Approval inbox rows for a user (across activities + projects)")
    public ResponseEntity<List<ApprovalInboxItem>> inbox(
            @Parameter(description = "Approver user uuid (logged-in user)", required = true)
            @RequestParam @NotBlank String userUuid,

            @Parameter(description = "Optional filter: PENDINGATCONCERNEDDIVISION / PENDINGATOWNERDIVISION. " +
                                     "Omit to return rows from every state.")
            @RequestParam(required = false) String stateName,

            @Parameter(description = "Optional filter: PENDING / APPROVED / REJECTED")
            @RequestParam(required = false) String voteStatus) {

        return ResponseEntity.ok(inboxService.inbox(userUuid, stateName, voteStatus));
    }

    /**
     * Detail view — everything needed to render the "Approval Request"
     * screen for one activity: header, project, activity details,
     * organization submission, attachments, per-division status.
     *
     * <p>Looked up purely by {@code activityId} — an activity cannot
     * belong to two business services in this system.</p>
     */
    @GetMapping("/{activityId}")
    @Operation(summary = "Full approval-request detail for one activity")
    public ResponseEntity<ApprovalDetailResponse> detail(
            @PathVariable @NotBlank String activityId,

            @Parameter(description = "Logged-in user uuid - determines 'your status' fields",
                       required = true)
            @RequestParam @NotBlank String userUuid,

            @Parameter(description = "Optional context: PENDINGATCONCERNEDDIVISION when on the " +
                                     "division approver screen, PENDINGATOWNERDIVISION when on " +
                                     "the owner approval screen. Disambiguates when one user holds " +
                                     "both roles. Omit to fall back to a sensible default.")
            @RequestParam(required = false) String stateName) {

        return ResponseEntity.ok(detailService.forActivity(activityId, userUuid, stateName));
    }

    /**
     * Structured approval summary — answers in one call:
     * when division approval was requested, when owner approval was requested,
     * and each approver's decision (approved / rejected / pending) with timestamp.
     */
    @GetMapping("/{activityId}/approval-summary")
    @Operation(summary = "Approval summary: request dates, concerned-division decisions, owner decision")
    public ResponseEntity<ApprovalSummaryResponse> approvalSummary(
            @PathVariable @NotBlank String activityId) {

        return ResponseEntity.ok(summaryService.summary(activityId));
    }

    /**
     * Timeline feed — chronological list of significant actions on the
     * activity: state transitions, votes, owner actions. Newest first.
     */
    @GetMapping("/{activityId}/timeline")
    @Operation(summary = "Activity timeline - transitions + votes + owner actions, newest first")
    public ResponseEntity<List<TimelineEvent>> timeline(
            @PathVariable @NotBlank String activityId,

            @Parameter(description = "Optional - businessService scope; usually omitted")
            @RequestParam(required = false) String businessService) {

        return ResponseEntity.ok(timelineService.timeline(businessService, activityId));
    }
}