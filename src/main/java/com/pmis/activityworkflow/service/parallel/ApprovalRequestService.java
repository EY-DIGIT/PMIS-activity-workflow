package com.pmis.activityworkflow.service.parallel;

import com.pmis.activityworkflow.entity.DocumentEntity;
import com.pmis.activityworkflow.entity.ParallelParticipantEntity;
import com.pmis.activityworkflow.entity.ProcessInstanceEntity;
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

        // Audit the button click up-front. Uses REQUIRES_NEW so the entry
        // survives even if seeding/notification rolls back later.
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

        // 1. Optional upload first — outside any tx-sensitive work.
        DocumentEntity doc = uploadIfPresent(file, req.getRequestInfo(),
                req.getBusinessService(),
                req.getActivityId(),
                req.getProjectId(),
                req.getComment(),
                "DIVISION_APPROVAL_REQUEST");

        // 2. Auto-seed if nothing is there yet. Idempotent — if rows already
        //    exist, this is a no-op and we move straight to step 3.
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

        // 2b. If the activity got here via the path
        //     PENDINGATOWNERDIVISION ──RETURN_TO_VENDOR──> READYFORAPPROVAL ──SUBMIT──> PENDINGATCONCERNEDDIVISION,
        //     the divisions should re-vote from scratch — vendor reworked the
        //     deliverable. Reset every approver row to PENDING so all of them
        //     get a fresh approval-request email (not just the never-voted ones).
        boolean resetAfterOwnerReject = false;
        if (!seeded && isResubmitAfterOwnerReject(req)) {
            resetAfterOwnerReject = resetApproversToPending(approvers);
            // Re-read so 'toNotify' sees the updated voteStatus values.
            approvers = participantRepository
                    .findByBusinessServiceAndActivityIdAndStateName(
                            req.getBusinessService(),
                            req.getActivityId(),
                            stateName);
        }

        // 3. Notify every currently-PENDING approver.
        //    On a normal re-send, already-APPROVED rows are skipped (still APPROVED).
        //    After an owner-reject reset, every row is PENDING — everyone re-emailed.
        List<ParallelParticipantEntity> toNotify = approvers.stream()
                .filter(p -> ParallelGateService.VOTE_PENDING.equals(p.getVoteStatus()))
                .toList();

        notificationClient.notifyApprovalRequested(toNotify, /* isResubmission */ resetAfterOwnerReject);

        log.info("Division approval requested by admin for activity {} (state {}) - "
                        + "{} approver(s) total, {} notified{}{}{}",
                req.getActivityId(), stateName,
                approvers.size(), toNotify.size(),
                seeded ? ", just seeded" : "",
                resetAfterOwnerReject ? ", reset-after-owner-reject" : "",
                doc == null ? "" : ", attached doc " + doc.getUuid());

        return new RequestDivisionApprovalResult(
                doc, approvers.size(), toNotify.size(), seeded);
    }

    /**
     * True when the activity's history shows it just came back from the
     * owner via RETURN_TO_VENDOR (or REJECT, the legacy alias) and was
     * then re-SUBMITted. Walks {@code aw_process_instance} oldest→newest
     * and looks for a RETURN_TO_VENDOR followed by a SUBMIT with no
     * intervening ALL_APPROVED.
     *
     * <p>That last condition matters: once the divisions re-approve and
     * the activity advances back to the owner, we shouldn't keep
     * treating subsequent calls as "post-owner-reject" forever.</p>
     */
    private boolean isResubmitAfterOwnerReject(RequestDivisionApprovalRequest req) {
        List<ProcessInstanceEntity> history = processRepository
                .findByBusinessServiceAndActivityIdOrderByAuditDetails_CreatedTimeAsc(
                        req.getBusinessService(), req.getActivityId());
        if (history.isEmpty()) return false;

        boolean ownerReturnedToVendor = false;
        for (ProcessInstanceEntity p : history) {
            String action = p.getActionName();
            if (action == null) continue;
            switch (action.toUpperCase()) {
                case "RETURN_TO_VENDOR",
                     "RETURNTOVENDOR",
                     "REJECT"           -> ownerReturnedToVendor = true;
                // ALL_APPROVED past a RETURN_TO_VENDOR means the divisions
                // already re-approved once - we're not in the post-reject
                // window anymore.
                case "ALL_APPROVED"     -> ownerReturnedToVendor = false;
                default                 -> { /* SUBMIT, votes, etc — irrelevant */ }
            }
        }

        // The latest row should be a SUBMIT (that's what just placed us
        // on PENDINGATCONCERNEDDIVISION).
        ProcessInstanceEntity latest = history.get(history.size() - 1);
        boolean latestIsSubmit = latest.getActionName() != null
                && latest.getActionName().equalsIgnoreCase("SUBMIT");

        return ownerReturnedToVendor && latestIsSubmit;
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

        // Audit the click. This logs the BUTTON_CLICK; the subsequent
        // ALL_APPROVED transition will produce its own SUCCESS audit row.
        // So in the trail you'll see two rows for one click:
        //   1) BUTTON_CLICK action=REQUEST_OWNER_APPROVAL
        //   2) SUCCESS      action=ALL_APPROVED
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

        // 1. Optional upload — same shape as division side.
        DocumentEntity doc = uploadIfPresent(file, req.getRequestInfo(),
                req.getBusinessService(),
                req.getActivityId(),
                req.getProjectId(),
                req.getComment(),
                "OWNER_APPROVAL_REQUEST");

        // 2. Validate-and-fire. This raises if any participant is still
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
        if (file == null || file.isEmpty()) return null;

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
