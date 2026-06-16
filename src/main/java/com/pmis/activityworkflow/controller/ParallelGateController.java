package com.pmis.activityworkflow.controller;

import com.pmis.activityworkflow.entity.DivisionUserEntity;
import com.pmis.activityworkflow.entity.ParallelParticipantEntity;
import com.pmis.activityworkflow.repository.DivisionUserRepository;
import com.pmis.activityworkflow.repository.ParallelParticipantRepository;
import com.pmis.activityworkflow.service.parallel.ApprovalRequestService;
import com.pmis.activityworkflow.service.parallel.ApprovalRequestService.RequestDivisionApprovalResult;
import com.pmis.activityworkflow.service.parallel.ApprovalRequestService.RequestOwnerApprovalResult;
import com.pmis.activityworkflow.service.parallel.ParallelGateService;
import com.pmis.activityworkflow.web.models.RequestInfo;
import com.pmis.activityworkflow.web.request.AutoSeedRequest;
import com.pmis.activityworkflow.web.request.CastVoteRequest;
import com.pmis.activityworkflow.web.request.RequestDivisionApprovalRequest;
import com.pmis.activityworkflow.web.request.RequestOwnerApprovalRequest;
import com.pmis.activityworkflow.web.request.SeedParticipantsRequest;
import com.pmis.activityworkflow.web.response.GateStatusResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/activities/parallel")
@Tag(name = "Parallel Approval Gate",
     description = "Seed reviewers, cast votes, and read gate state")
@RequiredArgsConstructor
@Slf4j
public class ParallelGateController {

    private final ParallelGateService gateService;
    private final ApprovalRequestService approvalRequestService;
    private final ParallelParticipantRepository participantRepository;
    private final DivisionUserRepository divisionUserRepository;
    private final ObjectMapper objectMapper;

    @PostMapping("/participants")
    @Operation(summary = "Seed (or re-seed) the parallel approval gate for a record")
    public ResponseEntity<List<ParallelParticipantEntity>> seed(
            @Valid @RequestBody SeedParticipantsRequest request) {

        List<ParallelParticipantEntity> participants = gateService.seedParticipants(request);
        return new ResponseEntity<>(participants, HttpStatus.CREATED);
    }

    @PostMapping("/participants/auto-seed")
    @Operation(summary = "Auto-seed: pulls divisions + users from the upstream assignments API")
    public ResponseEntity<List<ParallelParticipantEntity>> autoSeed(
            @Valid @RequestBody AutoSeedRequest request) {

        List<ParallelParticipantEntity> participants = gateService.autoSeed(request);
        return new ResponseEntity<>(participants, HttpStatus.CREATED);
    }

    @PostMapping("/vote")
    @Operation(summary = "Cast one approver's vote (APPROVED or REJECTED)")
    public ResponseEntity<ParallelParticipantEntity> vote(
            @Valid @RequestBody CastVoteRequest request) {

        ParallelParticipantEntity row = gateService.castVote(request);
        return ResponseEntity.ok(row);
    }

    @GetMapping("/{businessService}/{activityId}/{stateName}")
    @Operation(summary = "All approvers (one per division) for a record at a parallel state")
    public ResponseEntity<List<ParallelParticipantEntity>> listApprovers(
            @PathVariable String businessService,
            @PathVariable String activityId,
            @PathVariable String stateName) {

        return ResponseEntity.ok(participantRepository
                .findByBusinessServiceAndActivityIdAndStateName(
                        businessService, activityId, stateName));
    }

    @GetMapping("/users/{businessService}/{activityId}/{stateName}")
    @Operation(summary = "All collaborator users across divisions (record-only, no vote)")
    public ResponseEntity<List<DivisionUserEntity>> listUsers(
            @PathVariable String businessService,
            @PathVariable String activityId,
            @PathVariable String stateName) {

        return ResponseEntity.ok(divisionUserRepository
                .findByBusinessServiceAndActivityIdAndStateName(
                        businessService, activityId, stateName));
    }

    @GetMapping("/users/{businessService}/{activityId}/{stateName}/{divisionCode}")
    @Operation(summary = "Collaborator users for one specific division")
    public ResponseEntity<List<DivisionUserEntity>> listUsersInDivision(
            @PathVariable String businessService,
            @PathVariable String activityId,
            @PathVariable String stateName,
            @PathVariable String divisionCode) {

        return ResponseEntity.ok(divisionUserRepository
                .findByBusinessServiceAndActivityIdAndStateNameAndDivisionCode(
                        businessService, activityId, stateName, divisionCode));
    }

    /**
     * Gate-status snapshot — the UI calls this to decide whether to
     * enable the "Request Owner Approval" button.
     *
     * <p>{@code stateName} is optional; defaults to the only parallel
     * state in the workflow ({@code PENDINGATCONCERNEDDIVISION}).</p>
     */
    @GetMapping("/gate-status/{businessService}/{activityId}")
    @Operation(summary = "Aggregate gate status with readyForOwner flag")
    public ResponseEntity<GateStatusResponse> gateStatus(
            @PathVariable String businessService,
            @PathVariable String activityId,
            @RequestParam(required = false) String stateName) {

        return ResponseEntity.ok(
                gateService.gateStatus(businessService, activityId, stateName));
    }

    /* ==========================================================
     *  Admin toolbar: "Request Division Approval" / "Request Owner Approval"
     *
     *  Both endpoints accept multipart/form-data with flat form fields —
     *  one optional `file` part and individual fields for the metadata.
     *  Simpler to build in Postman/Swagger than a nested JSON part.
     * ========================================================== */

    /**
     * Admin clicks "Request Division Approval" — optionally attaches one
     * file + comment, both shared with every division approver. Sends
     * APPROVAL_REQUESTED notifications to all currently-pending division
     * approvers; persists the uploaded file in {@code aw_document}.
     */
    @PostMapping(value = "/request-division-approval",
                 consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Admin button: seed (if needed) + notify every concerned-division approver")
    public ResponseEntity<RequestDivisionApprovalResult> requestDivisionApproval(
            @RequestPart(value = "files", required = false) List<MultipartFile> files,
            @RequestParam(value = "requestInfo", required = false) String requestInfoJson,
            @RequestParam String businessService,
            @RequestParam String activityId,
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String stateName,
            @RequestParam(required = false) String comment) {

        RequestDivisionApprovalRequest body = RequestDivisionApprovalRequest.builder()
                .requestInfo(parseRequestInfo(requestInfoJson))
                .businessService(businessService)
                .activityId(activityId)
                .projectId(projectId)
                .stateName(stateName)
                .comment(comment)
                .build();
        return ResponseEntity.ok(approvalRequestService.requestDivisionApproval(body, files));
    }

    /**
     * Admin clicks "Request Owner Approval" after all divisions have
     * approved. Optionally attaches one file + comment, both routed to
     * the owner approver. Validates the gate is fully approved, fires the
     * ALL_APPROVED transition, then dispatches READY_FOR_OWNER_REVIEW to
     * the owner.
     */
    @PostMapping(value = "/request-owner-approval",
                 consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Admin button: advance the activity to the owner-approval stage")
    public ResponseEntity<RequestOwnerApprovalResult> requestOwnerApproval(
            @RequestPart(value = "files", required = false) List<MultipartFile> files,
            @RequestParam(value = "requestInfo", required = false) String requestInfoJson,
            @RequestParam String businessService,
            @RequestParam String activityId,
            @RequestParam(required = false) String projectId,
            @RequestParam(defaultValue = "PENDINGATOWNERDIVISION") String stateName,
            @RequestParam(required = false) String comment) {

        RequestOwnerApprovalRequest body = RequestOwnerApprovalRequest.builder()
                .requestInfo(parseRequestInfo(requestInfoJson))
                .businessService(businessService)
                .activityId(activityId)
                .projectId(projectId)
                .stateName(stateName)
                .comment(comment)
                .build();
        return ResponseEntity.ok(approvalRequestService.requestOwnerApproval(body, files));
    }

    /* ----- helper ----- */

    /**
     * Parse the optional {@code requestInfo} JSON form field. Logs a
     * warning and returns null on bad JSON rather than failing the
     * whole request — the downstream service treats null as anonymous.
     */
    private RequestInfo parseRequestInfo(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, RequestInfo.class);
        } catch (JsonProcessingException ex) {
            log.warn("Bad 'requestInfo' form field, treating as anonymous: {}", ex.getMessage());
            return null;
        }
    }
}
