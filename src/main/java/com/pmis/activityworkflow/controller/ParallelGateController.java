package com.pmis.activityworkflow.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.pmis.activityworkflow.entity.DivisionUserEntity;
import com.pmis.activityworkflow.entity.ParallelParticipantEntity;
import com.pmis.activityworkflow.repository.DivisionUserRepository;
import com.pmis.activityworkflow.repository.ParallelParticipantRepository;
import com.pmis.activityworkflow.service.parallel.ParallelGateService;
import com.pmis.activityworkflow.web.request.AutoSeedRequest;
import com.pmis.activityworkflow.web.request.CastVoteRequest;
import com.pmis.activityworkflow.web.request.SeedParticipantsRequest;

import java.util.List;

@RestController
@RequestMapping("/activities/parallel")
@Tag(name = "Parallel Approval Gate",
     description = "Seed reviewers, cast votes, and read gate state")
@RequiredArgsConstructor
public class ParallelGateController {

    private final ParallelGateService gateService;
    private final ParallelParticipantRepository participantRepository;
    private final DivisionUserRepository divisionUserRepository;

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
}
