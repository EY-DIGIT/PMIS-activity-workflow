package com.pmis.activityworkflow.service.parallel;

import com.pmis.activityworkflow.entity.DocumentEntity;
import com.pmis.activityworkflow.entity.ParallelParticipantEntity;
import com.pmis.activityworkflow.exception.InvalidTransitionException;
import com.pmis.activityworkflow.repository.ParallelParticipantRepository;
import com.pmis.activityworkflow.repository.ProcessInstanceRepository;
import com.pmis.activityworkflow.service.document.DocumentService;
import com.pmis.activityworkflow.service.document.DocumentService.DocumentMetadata;
import com.pmis.activityworkflow.service.notification.NotificationClient;
import com.pmis.activityworkflow.service.audit.WorkflowAuditService;
import com.pmis.activityworkflow.service.audit.WorkflowAuditService.ButtonAuditContext;
import com.pmis.activityworkflow.web.models.RequestInfo;
import com.pmis.activityworkflow.web.request.AutoSeedRequest;
import com.pmis.activityworkflow.web.request.RequestDivisionApprovalRequest;
import com.pmis.activityworkflow.web.request.RequestOwnerApprovalRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Admin-initiated "Request Division Approval" and "Request Owner Approval"
 * actions from the activity-approval workflow toolbar.
 *
 * <p>The flow for "Request Division Approval":</p>
 * <ol>
 *   <li>Optional file is uploaded to the upstream comments API and a row
 *       is persisted in {@code aw_document}.</li>
 *   <li>Each existing concerned-division approver row is re-notified
 *       (template APPROVAL_REQUESTED) with the admin's comment + attachment
 *       URL exposed as template variables.</li>
 * </ol>
 *
 * <p>The flow for "Request Owner Approval":</p>
 * <ol>
 *   <li>Optional file is uploaded just like above.</li>
 *   <li>{@link ParallelGateService#requestOwnerApproval} validates that every
 *       division has APPROVED and fires the ALL_APPROVED transition. That
 *       transition, in turn, dispatches READY_FOR_OWNER_REVIEW to the
 *       owner approver from upstream assignments.</li>
 * </ol>
 *
 * <p>Each upload row in {@code aw_document} captures activityId, uploader
 * uuid+username+roles, the comment, the upstream commentId and the file
 * URL — exactly what was asked for.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ApprovalRequestService {

    private final DocumentService documentService;
    private final ParallelParticipantRepository participantRepository;
    private final ProcessInstanceRepository processRepository;
    private final NotificationClient notificationClient;
    private final ParallelGateService parallelGateService;
    private final WorkflowAuditService auditService;

    /* ============================================================
     *  Request Division Approval
     * ============================================================ */
    @Transactional
    public RequestDivisionApprovalResult requestDivisionApproval(
            RequestDivisionApprovalRequest req,
            MultipartFile file) {

        // 0. Resolve which state to seed/notify on.
        //    Order: explicit request value → current state on aw_process_instance
        //    → PENDINGATCONCERNEDDIVISION default.
        String stateName = resolveStateName(req);

        // 1. Upload first. If the upstream comments API rejects the file or
        //    the network call fails, we throw out of this method here BEFORE
        //    writing any audit row, seeding any participants, or sending any
        //    notifications. The caller gets a 4xx/5xx and no DB state has
        //    been touched. This is the contract the UI relies on for
        //    "an action is committed only if its document upload succeeds".
        DocumentEntity doc = uploadIfPresent(file, req.getRequestInfo(),
                req.getBusinessService(),
                req.getActivityId(),
                req.getProjectId(),
                req.getComment(),
                "DIVISION_APPROVAL_REQUEST");

        // 2. Audit the button click — only reached if upload succeeded (or
        //    there was no upload to do). REQUIRES_NEW so the row commits
        //    even if a later step inside this @Transactional rolls back.
        auditService.recordButtonClick(new ButtonAuditContext(
                "REQUEST_DIVISION_APPROVAL",
                req.getBusinessService(),
                req.getActivityId(),
                req.getProjectId(),
                stateName,
                "activity-workflow",
                req.getComment(),
                req.getRequestInfo(),
                req));

        // 3. Auto-seed if nothing is there yet. Idempotent — if rows already
        //    exist, this is a no-op and we move straight to step 4.
        boolean seeded = false;
        List<ParallelParticipantEntity> approvers = participantRepository
                .findByBusinessServiceAndActivityIdAndStateName(
                        req.getBusinessService(),
                        req.getActivityId(),
                        stateName);

        if (approvers.isEmpty()) {
            log.info("No participants yet for {}/{}/{} - auto-seeding from upstream",
                    req.getBusinessService(), req.getActivityId(), stateName);

            AutoSeedRequest seedReq = AutoSeedRequest.builder()
                    .requestInfo(req.getRequestInfo())
                    .businessService(req.getBusinessService())
                    .activityId(req.getActivityId())
                    .projectId(req.getProjectId())
                    .stateName(stateName)
                    .build();

            // autoSeed() itself also notifies — but we want to control the
            // notification ourselves below so the admin's comment + file
            // context can drive the message. Suppress the seed-time email by
            // calling the lower-level seedFromAssignments() variant.
            parallelGateService.autoSeedWithoutNotify(seedReq);
            seeded = true;

            approvers = participantRepository
                    .findByBusinessServiceAndActivityIdAndStateName(
                            req.getBusinessService(),
                            req.getActivityId(),
                            stateName);
        }

        if (approvers.isEmpty()) {
            throw new InvalidTransitionException(
                    "Could not seed any division approvers for activity "
                            + req.getActivityId() + " - upstream assignments returned nothing");
        }

        // 2b. If any approver row is currently REJECTED, reset every row
        //     to PENDING and re-email everyone. This covers all rejection
        //     flows uniformly:
        //       - division approver rejected at the gate (ANY_REJECTED auto-fire)
        //       - owner clicked Return to Vendor (RETURN_TO_VENDOR transition)
        //       - admin clicked request-division-approval immediately after
        //         a rejection vote, without re-SUBMITting
        //     In every case, a REJECTED row means the gate has to restart,
        //     so wipe the slate and re-notify everyone.
        //
        //     Already-APPROVED rows on a normal re-send (no rejection in
        //     play) are still skipped — they don't need to re-vote.
        boolean didReset = false;
        boolean anyRejected = approvers.stream()
                .anyMatch(p -> ParallelGateService.VOTE_REJECTED.equals(p.getVoteStatus()));
        if (!seeded && anyRejected) {
            didReset = resetApproversToPending(approvers);
            // Re-read so 'toNotify' sees the updated voteStatus values.
            approvers = participantRepository
                    .findByBusinessServiceAndActivityIdAndStateName(
                            req.getBusinessService(),
                            req.getActivityId(),
                            stateName);
        }

        // 3. Notify every currently-PENDING approver.
        //    On a normal re-send, already-APPROVED rows are skipped (still APPROVED).
        //    After a reject reset, every row is PENDING — everyone re-emailed.
        List<ParallelParticipantEntity> toNotify = approvers.stream()
                .filter(p -> ParallelGateService.VOTE_PENDING.equals(p.getVoteStatus()))
                .toList();

        notificationClient.notifyApprovalRequested(toNotify, /* isResubmission */ didReset);

        log.info("Division approval requested by admin for activity {} (state {}) - "
                        + "{} approver(s) total, {} notified{}{}{}",
                req.getActivityId(), stateName,
                approvers.size(), toNotify.size(),
                seeded ? ", just seeded" : "",
                didReset ? ", reset-after-reject" : "",
                doc == null ? "" : ", attached doc " + doc.getUuid());

        return new RequestDivisionApprovalResult(
                doc, approvers.size(), toNotify.size(), seeded);
    }

    /**
     * Flip every approver row to PENDING and clear the per-vote fields.
     * Returns true if any row was actually changed.
     */
    private boolean resetApproversToPending(List<ParallelParticipantEntity> approvers) {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (ParallelParticipantEntity p : approvers) {
            if (!ParallelGateService.VOTE_PENDING.equals(p.getVoteStatus())) {
                p.setVoteStatus(ParallelGateService.VOTE_PENDING);
                p.setVoteComment(null);
                p.setVotedAt(null);
                p.setNotifyStatus("PENDING");
                p.setNotifyError(null);
                p.setNotifyAttemptedAt(null);
                p.setUpdatedAt(now);
                changed = true;
            }
        }
        if (changed) {
            participantRepository.saveAll(approvers);
            log.info("Reset {} approver row(s) to PENDING after owner-reject re-submit",
                    approvers.size());
        }
        return changed;
    }

    /**
     * Pick the state to operate on. Priority:
     * <ol>
     *   <li>{@code req.getStateName()} if provided</li>
     *   <li>The activity's currentState from {@code aw_process_instance}</li>
     *   <li>{@code PENDINGATCONCERNEDDIVISION} (the workflow's only parallel state today)</li>
     * </ol>
     */
    private String resolveStateName(RequestDivisionApprovalRequest req) {
        if (req.getStateName() != null && !req.getStateName().isBlank()) {
            return req.getStateName();
        }
        return processRepository
                .findByBusinessServiceAndActivityIdOrderByAuditDetails_CreatedTimeAsc(
                        req.getBusinessService(), req.getActivityId())
                .stream()
                .reduce((first, second) -> second)        // last in the stream = most recent
                .map(p -> p.getCurrentState())
                .filter(s -> s != null && !s.isBlank())
                .orElse("PENDINGATCONCERNEDDIVISION");
    }

    /* ============================================================
     *  Request Owner Approval
     * ============================================================ */
    @Transactional
    public RequestOwnerApprovalResult requestOwnerApproval(
            RequestOwnerApprovalRequest req,
            MultipartFile file) {

        // 1. Upload first. If upstream rejects the file or the network
        //    call fails, throw out HERE before audit, before validation,
        //    before the ALL_APPROVED transition fires. Caller gets 4xx/5xx
        //    and the workflow remains untouched.
        DocumentEntity doc = uploadIfPresent(file, req.getRequestInfo(),
                req.getBusinessService(),
                req.getActivityId(),
                req.getProjectId(),
                req.getComment(),
                "OWNER_APPROVAL_REQUEST");

        // 2. Audit the click — only after upload succeeds. The subsequent
        //    ALL_APPROVED transition will produce its own SUCCESS audit row,
        //    so in the trail you'll see two rows for one click:
        //       1) BUTTON_CLICK action=REQUEST_OWNER_APPROVAL
        //       2) SUCCESS      action=ALL_APPROVED
        auditService.recordButtonClick(new ButtonAuditContext(
                "REQUEST_OWNER_APPROVAL",
                req.getBusinessService(),
                req.getActivityId(),
                req.getProjectId(),
                req.getStateName(),
                "activity-workflow",
                req.getComment(),
                req.getRequestInfo(),
                req));

        // 3. Validate-and-fire. This raises if any participant is still
        //    PENDING or REJECTED. On success the resulting transition
        //    dispatches the READY_FOR_OWNER_REVIEW notification to the
        //    owner approver from upstream assignments.
        parallelGateService.requestOwnerApproval(
                req.getBusinessService(),
                req.getActivityId(),
                req.getProjectId(),
                req.getStateName(),
                req.getComment(),
                req.getRequestInfo());

        log.info("Owner approval requested by admin for activity {}{}",
                req.getActivityId(),
                doc == null ? "" : " (attached doc " + doc.getUuid() + ")");

        return new RequestOwnerApprovalResult(doc);
    }

    /* ============================================================ */

    private DocumentEntity uploadIfPresent(MultipartFile file,
                                           RequestInfo requestInfo,
                                           String businessService,
                                           String activityId,
                                           String projectId,
                                           String comment,
                                           String documentType) {
        // Skip the upstream call only when there's nothing to send.
        // A comment-only post (no file) is allowed and will produce an
        // aw_document row with docId set to the upstream comment id.
        boolean hasFile    = file != null && !file.isEmpty();
        boolean hasComment = comment != null && !comment.isBlank();
        if (!hasFile && !hasComment) return null;

        DocumentMetadata meta = new DocumentMetadata(
                documentType, activityId, projectId,
                businessService, /* processInstanceId */ null,
                comment);
        return documentService.uploadAndAttach(file, meta, requestInfo);
    }

    /* ----- result records exposed back to the controller ----- */

    public record RequestDivisionApprovalResult(
            DocumentEntity uploadedDocument,
            int totalApprovers,
            int notifiedApprovers,
            boolean justSeeded) {}

    public record RequestOwnerApprovalResult(
            DocumentEntity uploadedDocument) {}
}