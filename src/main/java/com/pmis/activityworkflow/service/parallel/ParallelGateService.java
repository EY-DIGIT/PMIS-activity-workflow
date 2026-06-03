package com.pmis.activityworkflow.service.parallel;

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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    /** The only parallel state in the workflow — where division votes live. */
    public static final String GATE_STATE = "PENDINGATCONCERNEDDIVISION";

    /** Pseudo division code we use to store the owner approver in aw_division_user. */
    public static final String OWNER_DIVISION_CODE = "OWNER";

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
        return seedParticipants(req, /* notify */ true);
    }

    /**
     * Seed variant that lets the caller suppress the per-approver
     * APPROVAL_REQUESTED notification — useful when the caller will fire
     * the notification themselves moments later with a different payload
     * (e.g. admin's comment + attachment).
     */
    @Transactional
    public List<ParallelParticipantEntity> seedParticipants(SeedParticipantsRequest req,
                                                            boolean notify) {

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
        if (notify) {
            notificationClient.notifyApprovalRequested(needsNotify, isResubmission);
        } else {
            log.debug("Seed notifications suppressed by caller for {}/{}/{} ({} would-be recipients)",
                    req.getBusinessService(), req.getActivityId(), req.getStateName(),
                    needsNotify.size());
        }

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
        return autoSeed(req, /* notify */ true);
    }

    private List<ParallelParticipantEntity> autoSeed(AutoSeedRequest req, boolean notify) {

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

        // Also stash the owner approver as a row under the synthetic 'OWNER'
        // divisionCode in aw_division_user. They don't vote (so no participant
        // row gets counted by the gate), but the inbox / detail screens can
        // find their email + uuid in one place alongside the division
        // collaborators.
        appendOwnerApproverAsDivisionUser(req, data);

        SeedParticipantsRequest seed = SeedParticipantsRequest.builder()
                .requestInfo(req.getRequestInfo())
                .businessService(req.getBusinessService())
                .activityId(req.getActivityId())
                .projectId(req.getProjectId())
                .stateName(req.getStateName())
                .divisions(divisions)
                .build();

        log.info("Auto-seed: {} division(s) resolved for activity {} from upstream API (notify={})",
                divisions.size(), req.getActivityId(), notify);

        return seedParticipants(seed, notify);
    }

    /**
     * Same as {@link #autoSeed(AutoSeedRequest)} but does NOT send the
     * per-approver APPROVAL_REQUESTED notification. Useful when the caller
     * intends to fire its own notification (with extra context like an
     * admin comment + attachment) immediately after.
     */
    @Transactional
    public List<ParallelParticipantEntity> autoSeedWithoutNotify(AutoSeedRequest req) {
        return autoSeed(req, /* notify */ false);
    }

    /**
     * Persist the upstream {@code ownerApprover[0]} as a synthetic division
     * row under {@link #OWNER_DIVISION_CODE}, seeded at
     * {@code PENDINGATOWNERDIVISION} (not at the parallel gate state).
     *
     * <p>This is the only writer that puts the owner-approver into our
     * tables — the inbox / detail screens then find owner info alongside
     * the division collaborators without needing a separate query.</p>
     *
     * <p>The owner does NOT vote in the parallel gate; the gate-evaluation
     * logic only considers rows on {@code PENDINGATCONCERNEDDIVISION} so
     * this row sits passively until the activity actually reaches
     * {@code PENDINGATOWNERDIVISION}.</p>
     */
    private void appendOwnerApproverAsDivisionUser(AutoSeedRequest req, AssignmentData data) {
        List<AssignmentData.UserRef> owners = data.getOwnerApprover();
        if (owners == null || owners.isEmpty()) {
            log.debug("Upstream returned no ownerApprover - skipping OWNER row");
            return;
        }
        AssignmentData.UserRef owner = owners.get(0);
        if (owner == null || owner.getId() == null) return;

        long now = System.currentTimeMillis();
        String ownerState = "PENDINGATOWNERDIVISION";

        // Skip if a row is already there - keeps the call idempotent.
        boolean alreadyExists = !participantRepository
                .findByBusinessServiceAndActivityIdAndStateNameAndApproverUserUuid(
                        req.getBusinessService(), req.getActivityId(),
                        ownerState, owner.getId()).isEmpty();
        if (alreadyExists) {
            log.debug("OWNER participant row already exists for activity {} - skipping",
                    req.getActivityId());
            return;
        }

        ParallelParticipantEntity ownerRow = ParallelParticipantEntity.builder()
                .uuid(UUID.randomUUID().toString())
                .businessService(req.getBusinessService())
                .activityId(req.getActivityId())
                .projectId(req.getProjectId())
                .stateName(ownerState)
                .divisionCode(OWNER_DIVISION_CODE)
                .divisionName(OWNER_DIVISION_CODE)
                .approverUserUuid(owner.getId())
                .approverEmail(owner.getEmail())
                .approverName(owner.fullName())
                .voteStatus(VOTE_PENDING)
                .notifyStatus("PENDING")
             //   .auditDetails(AuditDetails.builder()
//                        .createdTime(now)
//                        .lastModifiedTime(now)
//                        .build())
                .build();
        participantRepository.save(ownerRow);

        // Mirror into aw_division_user so the OWNER row is also discoverable
        // by collaborator-user queries.
        DivisionUserEntity ownerAsUser = DivisionUserEntity.builder()
                .uuid(UUID.randomUUID().toString())
                .businessService(req.getBusinessService())
                .activityId(req.getActivityId())
                .projectId(req.getProjectId())
                .stateName(ownerState)
                .divisionCode(OWNER_DIVISION_CODE)
                .userUuid(owner.getId())
                .userEmail(owner.getEmail())
                .userName(owner.fullName())
                .createdAt(now)
         //       .updatedAt(now)
                .build();
        divisionUserRepository.save(ownerAsUser);

        log.info("OWNER row persisted for activity {}: approver={} email={}",
                req.getActivityId(), owner.getId(), owner.getEmail());
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
     *  After each vote: reject immediately, otherwise wait for admin
     * ========================================================== */
    private void evaluateGate(CastVoteRequest req) {

        List<ParallelParticipantEntity> all = participantRepository
                .findByBusinessServiceAndActivityIdAndStateName(
                        req.getBusinessService(), req.getActivityId(), req.getStateName());

        boolean anyRejected = all.stream().anyMatch(p -> VOTE_REJECTED.equals(p.getVoteStatus()));

        if (anyRejected) {
            // Reject is a hard stop — auto-fire it so the record goes back
            // to READYFORAPPROVAL without any manual step.
            log.info("Gate REJECT: {}/{}/{} - at least one participant rejected",
                    req.getBusinessService(), req.getActivityId(), req.getStateName());
            fireSystemAction(req, ACTION_ANY_REJECTED, "Auto-fired by parallel gate: ANY_REJECTED");
            return;
        }

        boolean anyPending = all.stream().anyMatch(p -> VOTE_PENDING.equals(p.getVoteStatus()));
        if (anyPending) {
            log.info("Gate WAIT: {}/{}/{} - {} participant(s) still pending",
                    req.getBusinessService(), req.getActivityId(), req.getStateName(),
                    all.stream().filter(p -> VOTE_PENDING.equals(p.getVoteStatus())).count());
            return;
        }

        // All approved — but DO NOT auto-fire. Admin clicks "Request Owner
        // Approval" to move the record forward. Log so the wait is visible.
        log.info("Gate READY: {}/{}/{} - all participants approved; "
                + "awaiting admin 'Request Owner Approval' action",
                req.getBusinessService(), req.getActivityId(), req.getStateName());
    }

    /* ==========================================================
     *  Admin fires ALL_APPROVED manually (the "Request Owner Approval" button)
     * ========================================================== */

    /**
     * Admin-initiated counterpart to the (now-removed) auto-fire of
     * ALL_APPROVED. Verifies every concerned-division approver has APPROVED,
     * then fires the transition so the record moves to PENDINGATOWNERDIVISION.
     *
     * <p>{@code stateName} in the request is the <em>destination</em>
     * (PENDINGATOWNERDIVISION) — what the UI shows. We always validate
     * against the gate state {@link #GATE_STATE} regardless.</p>
     *
     * @param businessService workflow definition name
     * @param activityId      the record being advanced
     * @param projectId       optional, carried onto the transition row
     * @param stateName       UI-facing destination state name (informational)
     * @param comment         optional admin note attached to the transition
     * @param requestInfo     admin's identity for audit
     */
    @Transactional
    public void requestOwnerApproval(String businessService,
                                     String activityId,
                                     String projectId,
                                     String stateName,
                                     String comment,
                                     RequestInfo requestInfo) {

        // Always validate against the gate state — the destination state
        // (typically PENDINGATOWNERDIVISION) has no participant rows yet
        // and isn't where the approvals live.
        List<ParallelParticipantEntity> rows = participantRepository
                .findByBusinessServiceAndActivityIdAndStateName(
                        businessService, activityId, GATE_STATE);

        if (rows.isEmpty()) {
            throw new InvalidTransitionException(
                    "No participants seeded for " + businessService + "/" + activityId
                            + " at state " + GATE_STATE
                            + " - call request-division-approval first");
        }

        boolean anyRejected = rows.stream().anyMatch(p -> VOTE_REJECTED.equals(p.getVoteStatus()));
        boolean anyPending  = rows.stream().anyMatch(p -> VOTE_PENDING.equals(p.getVoteStatus()));

        if (anyRejected) {
            throw new InvalidTransitionException(
                    "Cannot request owner approval - at least one division has rejected");
        }
        if (anyPending) {
            long pending = rows.stream().filter(p -> VOTE_PENDING.equals(p.getVoteStatus())).count();
            throw new InvalidTransitionException(
                    "Cannot request owner approval - " + pending
                            + " division(s) have not yet approved");
        }

        // Build a synthetic CastVoteRequest just so fireSystemAction has the
        // shape it expects. We do not write any vote. The stateName must be
        // the gate state — that's where ALL_APPROVED is defined in the
        // workflow config; the *destination* state lives in the workflow
        // definition's `nextState`.
        CastVoteRequest synthetic = CastVoteRequest.builder()
                .requestInfo(requestInfo)
                .businessService(businessService)
                .activityId(activityId)
                .projectId(projectId)
                .stateName(GATE_STATE)
                .build();

        String adminComment = (comment == null || comment.isBlank())
                ? "Admin: Request Owner Approval"
                : "Admin: " + comment;

        log.info("Admin {} requested owner approval for {}/{}",
                Optional.ofNullable(requestInfo)
                        .map(RequestInfo::getUserInfo).map(UserInfo::getUuid).orElse("?"),
                businessService, activityId);

        fireSystemAction(synthetic, ACTION_ALL_APPROVED, adminComment);
    }

    /**
     * Fire a transition on behalf of "the system" — the gate or the admin
     * button, not a regular user vote. Uses whatever RequestInfo we have so
     * the audit trail still attributes the resulting transition.
     */
    private void fireSystemAction(CastVoteRequest req, String actionName, String comment) {
        ProcessInstanceDTO pi = ProcessInstanceDTO.builder()
                .businessService(req.getBusinessService())
                .activityId(req.getActivityId())
                .projectId(req.getProjectId())
                .action(actionName)
                .comment(comment)
                .build();

        TransitionRequest tr = TransitionRequest.builder()
                .requestInfo(req.getRequestInfo())
                .processInstances(List.of(pi))
                .build();

        transitionService.transition(tr);
    }
}