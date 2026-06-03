package com.pmis.activityworkflow.service.parallel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pmis.activityworkflow.entity.DivisionUserEntity;
import com.pmis.activityworkflow.entity.ParallelParticipantEntity;
import com.pmis.activityworkflow.exception.InvalidTransitionException;
import com.pmis.activityworkflow.repository.DivisionUserRepository;
import com.pmis.activityworkflow.repository.ParallelParticipantRepository;
import com.pmis.activityworkflow.service.WorkflowTransitionService;
import com.pmis.activityworkflow.service.assignments.ActivityAssignmentsClient;
import com.pmis.activityworkflow.service.assignments.AssignmentData;
import com.pmis.activityworkflow.service.notification.NotificationClient;
import com.pmis.activityworkflow.web.models.DivisionInput;
import com.pmis.activityworkflow.web.models.DivisionUserInput;
import com.pmis.activityworkflow.web.models.ParticipantInput;
import com.pmis.activityworkflow.web.models.ProcessInstanceDTO;
import com.pmis.activityworkflow.web.models.RequestInfo;
import com.pmis.activityworkflow.web.models.UserInfo;
import com.pmis.activityworkflow.web.request.AutoSeedRequest;
import com.pmis.activityworkflow.web.request.CastVoteRequest;
import com.pmis.activityworkflow.web.request.SeedParticipantsRequest;
import com.pmis.activityworkflow.web.request.TransitionRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Heart of the parallel-approval gate.
 *
 * <p><b>Lifecycle</b></p>
 * <pre>
 *   1. Caller fires a transition that lands the record on a parallel state
 *      (e.g. SUBMIT -> PENDINGATCONCERNEDDIVISION).
 *   2. Caller POSTs to /parallel/participants with the reviewer list.
 *      seedParticipants() inserts PENDING rows + notifies each via the
 *      external API.
 *   3. Reviewers POST individual votes to /parallel/vote.
 *      castVote() updates the row, then evaluateGate() decides:
 *        - any REJECTED → fire REJECT transition back to vendor
 *        - all APPROVED → fire FORWARD_TO_OWNER transition
 *        - some PENDING → do nothing, wait
 *   4. On re-submission after a reject, seedParticipants() is called again.
 *      Approved rows are preserved (skipped); the rejecter's row is reset
 *      to PENDING and re-notified.
 * </pre>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ParallelGateService {

    public static final String VOTE_PENDING  = "PENDING";
    public static final String VOTE_APPROVED = "APPROVED";
    public static final String VOTE_REJECTED = "REJECTED";

    public static final String NOTIFY_PENDING = "PENDING";

    /**
     * The action names fired against the workflow definition when the gate
     * resolves. These must exist on the parallel state in the workflow.
     */
    public static final String ACTION_ALL_APPROVED = "ALL_APPROVED";
    public static final String ACTION_ANY_REJECTED = "ANY_REJECTED";

    private final ParallelParticipantRepository participantRepository;
    private final DivisionUserRepository divisionUserRepository;
    private final NotificationClient notificationClient;
    private final WorkflowTransitionService transitionService;
    private final ActivityAssignmentsClient assignmentsClient;

    /* ==========================================================
     *  SEED participants on entry (or re-entry) to a parallel state
     * ========================================================== */
    @Transactional
    public List<ParallelParticipantEntity> seedParticipants(SeedParticipantsRequest req) {

        long now = System.currentTimeMillis();
        List<ParallelParticipantEntity> needsNotify = new ArrayList<>();
        boolean isResubmission = false;

        for (DivisionInput division : req.getDivisions()) {

            // ---- 1. approver: one row in aw_parallel_participant ----
            ParticipantInput approver = division.getApprover();
            if (approver == null) {
                throw new InvalidTransitionException(
                        "Division '" + division.getDivisionCode() + "' has no approver");
            }

            Optional<ParallelParticipantEntity> existing = participantRepository
                    .findByBusinessServiceAndActivityIdAndStateNameAndApproverUserUuid(
                            req.getBusinessService(),
                            req.getActivityId(),
                            req.getStateName(),
                            approver.getApproverUserUuid());

            if (existing.isPresent()) {
                ParallelParticipantEntity row = existing.get();

                // - APPROVED rows are kept untouched
                // - REJECTED rows are reset to PENDING and re-notified
                // - PENDING rows stay PENDING
                if (VOTE_APPROVED.equals(row.getVoteStatus())) {
                    log.info("Approver {} (division {}) already APPROVED for {}/{} - skipping",
                            approver.getApproverUserUuid(), division.getDivisionCode(),
                            req.getActivityId(), req.getStateName());
                } else if (VOTE_REJECTED.equals(row.getVoteStatus())) {
                    row.setVoteStatus(VOTE_PENDING);
                    row.setVoteComment(null);
                    row.setVotedAt(null);
                    row.setNotifyStatus(NOTIFY_PENDING);
                    row.setNotifyError(null);
                    row.setUpdatedAt(now);
                    participantRepository.save(row);
                    needsNotify.add(row);
                    isResubmission = true;
                }
                // else PENDING -> leave as-is
            } else {
                ParallelParticipantEntity fresh = ParallelParticipantEntity.builder()
                        .uuid(UUID.randomUUID().toString())
                        .businessService(req.getBusinessService())
                        .activityId(req.getActivityId())
                        .projectId(req.getProjectId())
                        .stateName(req.getStateName())
                        .divisionCode(division.getDivisionCode())
                        .divisionName(division.getDivisionName())
                        .approverUserUuid(approver.getApproverUserUuid())
                        .approverEmail(approver.getApproverEmail())
                        .approverName(approver.getApproverName())
                        .voteStatus(VOTE_PENDING)
                        .notifyStatus(NOTIFY_PENDING)
                        .createdAt(now)
                        .updatedAt(now)
                        .build();
                participantRepository.save(fresh);
                needsNotify.add(fresh);
            }

            // ---- 2. collaborator users: rows in aw_division_user (record-only) ----
            if (division.getUsers() != null) {
                for (DivisionUserInput u : division.getUsers()) {
                    if (u == null || u.getUserUuid() == null) continue;

                    boolean alreadyStored = divisionUserRepository
                            .findByBusinessServiceAndActivityIdAndStateNameAndDivisionCodeAndUserUuid(
                                    req.getBusinessService(),
                                    req.getActivityId(),
                                    req.getStateName(),
                                    division.getDivisionCode(),
                                    u.getUserUuid())
                            .isPresent();
                    if (alreadyStored) continue;

                    divisionUserRepository.save(DivisionUserEntity.builder()
                            .uuid(UUID.randomUUID().toString())
                            .businessService(req.getBusinessService())
                            .activityId(req.getActivityId())
                            .projectId(req.getProjectId())
                            .stateName(req.getStateName())
                            .divisionCode(division.getDivisionCode())
                            .divisionName(division.getDivisionName())
                            .userUuid(u.getUserUuid())
                            .userEmail(u.getUserEmail())
                            .userName(u.getUserName())
                            .createdAt(now)
                            .build());
                }
            }
        }

        // Notify only approvers — collaborator users are record-only.
        notificationClient.notifyApprovalRequested(needsNotify, isResubmission);

        return participantRepository
                .findByBusinessServiceAndActivityIdAndStateName(
                        req.getBusinessService(), req.getActivityId(), req.getStateName());
    }

    /* ==========================================================
     *  Auto-seed — fetch divisions from upstream API, then seed
     * ========================================================== */

    /**
     * One-call seed: pulls {@code divisionApprovers} + {@code divisionUsers}
     * from the upstream assignments API for the given activityId, builds a
     * {@link SeedParticipantsRequest} internally, and runs the normal seed
     * path. Owner-division approver is intentionally NOT seeded here — that
     * happens when the gate fires {@code ALL_APPROVED}.
     */
    @Transactional
    public List<ParallelParticipantEntity> autoSeed(AutoSeedRequest req) {

        AssignmentData data = assignmentsClient.fetch(req.getActivityId());

        if (data.getDivisionApprovers() == null || data.getDivisionApprovers().isEmpty()) {
            throw new InvalidTransitionException(
                    "Assignments API returned no divisionApprovers for activity "
                            + req.getActivityId());
        }

        // Build DivisionInputs from the upstream payload.
        List<DivisionInput> divisions = new ArrayList<>();
        for (Map.Entry<String, List<AssignmentData.UserRef>> entry
                : data.getDivisionApprovers().entrySet()) {

            String divisionCode = entry.getKey();
            List<AssignmentData.UserRef> approvers = entry.getValue();
            if (approvers == null || approvers.isEmpty()) {
                log.warn("Division '{}' has no approver in the assignments response - skipping",
                        divisionCode);
                continue;
            }
            AssignmentData.UserRef approver = approvers.get(0);   // single approver per division

            List<DivisionUserInput> users = new ArrayList<>();
            List<AssignmentData.UserRef> divUsers = data.getDivisionUsers() == null
                    ? List.<AssignmentData.UserRef>of()
                    : data.getDivisionUsers().getOrDefault(divisionCode, List.of());
            for (AssignmentData.UserRef u : divUsers) {
                users.add(DivisionUserInput.builder()
                        .userUuid(u.getId())
                        .userEmail(u.getEmail())
                        .userName(u.fullName())
                        .build());
            }

            divisions.add(DivisionInput.builder()
                    .divisionCode(divisionCode)
                    .divisionName(divisionCode)        // upstream API doesn't return a separate name
                    .approver(ParticipantInput.builder()
                            .approverUserUuid(approver.getId())
                            .approverEmail(approver.getEmail())
                            .approverName(approver.fullName())
                            .build())
                    .users(users)
                    .build());
        }

        if (divisions.isEmpty()) {
            throw new InvalidTransitionException(
                    "No usable divisions found in the assignments response for activity "
                            + req.getActivityId());
        }

        SeedParticipantsRequest seed = SeedParticipantsRequest.builder()
                .requestInfo(req.getRequestInfo())
                .businessService(req.getBusinessService())
                .activityId(req.getActivityId())
                .projectId(req.getProjectId())
                .stateName(req.getStateName())
                .divisions(divisions)
                .build();

        log.info("Auto-seed: {} division(s) resolved for activity {} from upstream API",
                divisions.size(), req.getActivityId());

        return seedParticipants(seed);
    }


    /* ==========================================================
     *  Reviewer casts a vote
     * ========================================================== */
    @Transactional
    public ParallelParticipantEntity castVote(CastVoteRequest req) {

        String voterUuid = Optional.ofNullable(req.getRequestInfo())
                .map(RequestInfo::getUserInfo)
                .map(UserInfo::getUuid)
                .orElseThrow(() -> new InvalidTransitionException(
                        "RequestInfo.userInfo.uuid is required to cast a vote"));

        ParallelParticipantEntity row = participantRepository
                .findByBusinessServiceAndActivityIdAndStateNameAndApproverUserUuid(
                        req.getBusinessService(),
                        req.getActivityId(),
                        req.getStateName(),
                        voterUuid)
                .orElseThrow(() -> new InvalidTransitionException(String.format(
                        "User %s is not a participant for %s/%s at state %s",
                        voterUuid, req.getBusinessService(), req.getActivityId(), req.getStateName())));

        if (!VOTE_PENDING.equals(row.getVoteStatus())) {
            throw new InvalidTransitionException(String.format(
                    "User %s has already voted (%s) on %s/%s at state %s",
                    voterUuid, row.getVoteStatus(),
                    req.getBusinessService(), req.getActivityId(), req.getStateName()));
        }

        long now = System.currentTimeMillis();
        row.setVoteStatus(req.getVote());
        row.setVoteComment(req.getComment());
        row.setVotedAt(now);
        row.setUpdatedAt(now);
        participantRepository.save(row);

        log.info("Vote recorded: user={} activityId={} state={} vote={}",
                voterUuid, req.getActivityId(), req.getStateName(), req.getVote());

        // After each vote, re-evaluate the gate.
        evaluateGate(req);

        return row;
    }

    /* ==========================================================
     *  After each vote: advance, reject, or wait
     * ========================================================== */
    private void evaluateGate(CastVoteRequest req) {

        List<ParallelParticipantEntity> all = participantRepository
                .findByBusinessServiceAndActivityIdAndStateName(
                        req.getBusinessService(), req.getActivityId(), req.getStateName());

        boolean anyRejected = all.stream().anyMatch(p -> VOTE_REJECTED.equals(p.getVoteStatus()));
        boolean anyPending  = all.stream().anyMatch(p -> VOTE_PENDING.equals(p.getVoteStatus()));

        if (anyRejected) {
            log.info("Gate REJECT: {}/{}/{} - at least one participant rejected",
                    req.getBusinessService(), req.getActivityId(), req.getStateName());
            fireSystemAction(req, ACTION_ANY_REJECTED);
            return;
        }
        if (anyPending) {
            log.info("Gate WAIT: {}/{}/{} - {} participant(s) still pending",
                    req.getBusinessService(), req.getActivityId(), req.getStateName(),
                    all.stream().filter(p -> VOTE_PENDING.equals(p.getVoteStatus())).count());
            return;
        }

        // all approved
        log.info("Gate ADVANCE: {}/{}/{} - all participants approved",
                req.getBusinessService(), req.getActivityId(), req.getStateName());
        fireSystemAction(req, ACTION_ALL_APPROVED);
    }

    /**
     * Fire a transition on behalf of "the system" — the gate, not a user.
     * Uses the original RequestInfo so the audit trail still ties the
     * resulting transition to whoever cast the deciding vote.
     */
    private void fireSystemAction(CastVoteRequest req, String actionName) {
        ProcessInstanceDTO pi = ProcessInstanceDTO.builder()
                .businessService(req.getBusinessService())
                .activityId(req.getActivityId())
                .projectId(req.getProjectId())
                .action(actionName)
                .comment("Auto-fired by parallel gate: " + actionName)
                .build();

        TransitionRequest tr = TransitionRequest.builder()
                .requestInfo(req.getRequestInfo())
                .processInstances(List.of(pi))
                .build();

        transitionService.transition(tr);
    }
}
