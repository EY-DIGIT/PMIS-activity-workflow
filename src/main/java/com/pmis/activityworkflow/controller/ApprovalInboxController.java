package com.pmis.activityworkflow.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pmis.activityworkflow.service.inbox.ApprovalDetailService;
import com.pmis.activityworkflow.service.inbox.ApprovalInboxService;
import com.pmis.activityworkflow.web.response.ApprovalDetailResponse;
import com.pmis.activityworkflow.web.response.ApprovalInboxItem;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/activities/inbox")
@Tag(name = "Approval Inbox",
     description = "List activities awaiting (or already actioned by) a given approver")
@RequiredArgsConstructor
@Validated
public class ApprovalInboxController {

    private final ApprovalInboxService inboxService;
    private final ApprovalDetailService detailService;

    /**
     * Returns the approval inbox for the given approver. Optionally
     * filtered by {@code voteStatus} (PENDING / APPROVED / REJECTED).
     *
     * @param userUuid   the approver — usually the logged-in user's uuid
     * @param voteStatus optional filter; default is to return all statuses
     */
    @GetMapping
    @Operation(summary = "Approval inbox rows for a user (across activities + projects)")
    public ResponseEntity<List<ApprovalInboxItem>> inbox(
            @Parameter(description = "Approver user uuid (logged-in user)", required = true)
            @RequestParam @NotBlank String userUuid,

            @Parameter(description = "Optional filter: PENDING / APPROVED / REJECTED")
            @RequestParam(required = false) String voteStatus) {

        return ResponseEntity.ok(inboxService.inbox(userUuid, voteStatus));
    }
    
    /**
     * Detail view — everything needed to render the "Approval Request"
     * screen for one activity: header, project, activity details,
     * organization submission, attachments, per-division status.
     */
    @GetMapping("/{activityId}")
    @Operation(summary = "Full approval-request detail for one activity")
    public ResponseEntity<ApprovalDetailResponse> detail(
            @PathVariable @NotBlank String businessService,
            @PathVariable @NotBlank String activityId,
 
            @Parameter(description = "Logged-in user uuid - determines 'your status' fields",
                       required = true)
            @RequestParam @NotBlank String userUuid) {
 
        return ResponseEntity.ok(detailService.forActivity(businessService, activityId, userUuid));
    }
}
