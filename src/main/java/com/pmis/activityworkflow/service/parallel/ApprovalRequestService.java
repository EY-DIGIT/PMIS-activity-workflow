package com.pmis.activityworkflow.service.parallel;

import com.pmis.activityworkflow.entity.DocumentEntity;
import com.pmis.activityworkflow.entity.ParallelParticipantEntity;
import com.pmis.activityworkflow.entity.ProcessInstanceEntity;
import com.pmis.activityworkflow.exception.InvalidTransitionException;
import com.pmis.activityworkflow.repository.ParallelParticipantRepository;
import com.pmis.activityworkflow.repository.ProcessInstanceRepository;
import com.pmis.activityworkflow.service.assignments.ActivityAssignmentsClient;
import com.pmis.activityworkflow.service.assignments.AssignmentData;
import com.pmis.activityworkflow.service.document.DocumentService;
import com.pmis.activityworkflow.service.document.DocumentService.DocumentMetadata;
import com.pmis.activityworkflow.service.notification.NotificationClient;
import com.pmis.activityworkflow.service.audit.WorkflowAuditService;
import com.pmis.activityworkflow.service.audit.WorkflowAuditService.ButtonAuditContext;
import com.pmis.activityworkflow.web.models.RequestInfo;
import com.pmis.activityworkflow.web.request.AutoSeedRequest;
import com.pmis.activityworkflow.web.request.DivisionApprovalInput;
import com.pmis.activityworkflow.web.request.RequestDivisionApprovalRequest;
import com.pmis.activityworkflow.web.request.RequestOwnerApprovalRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
    private final ActivityAssignmentsClient assignmentsClient;
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
            RequestDivisionApprovalRequest req) {

        // 0. Resolve state.
        String stateName = resolveStateName(req);

        // 1. Persist per-division comments and document store ID references.
        //    Each division gets its own isolated comment and attachments stored
        //    under divisionCode so the inbox can filter by division later.
        List<DocumentEntity> docs = savePerDivisionDocuments(req);

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

        // 2b. Decide which approver rows to reset, if any:
        //
        //  - Owner-side rejection (RETURN_TO_VENDOR + new SUBMIT, no
        //    intervening ALL_APPROVED): the entire round is invalidated,
        //    so reset ALL non-PENDING rows. Divisions re-vote fresh.
        //
        //  - Gate-side rejection (any approver REJECTED while we're still
        //    in PENDINGATCONCERNEDDIVISION): only reset the REJECTED rows.
        //    Approved divisions keep their vote and don't get re-emailed.
        //
        //  - No rejections in play: normal nudge — no reset.
        boolean didReset = false;
        boolean resetAll = false;
        if (!seeded) {
            if (isResubmitAfterOwnerReject(req)) {
                didReset  = resetApproversToPending(approvers, /* rejectedOnly */ false);
                resetAll  = didReset;
            } else if (approvers.stream().anyMatch(
                    p -> ParallelGateService.VOTE_REJECTED.equals(p.getVoteStatus()))) {
                didReset = resetApproversToPending(approvers, /* rejectedOnly */ true);
            }
            if (didReset) {
                // Re-read so 'toNotify' sees the updated voteStatus values.
                approvers = participantRepository
                        .findByBusinessServiceAndActivityIdAndStateName(
                                req.getBusinessService(),
                                req.getActivityId(),
                                stateName);
            }
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
                docs.isEmpty() ? "" : ", " + docs.size() + " doc(s) attached");

        return new RequestDivisionApprovalResult(
                docs, approvers.size(), toNotify.size(), seeded);
    }

    /**
     * Flip every approver row to PENDING and clear the per-vote fields.
     * Returns true if any row was actually changed.
     */
    /**
     * Flip approver rows back to PENDING and clear their vote/notify state.
     *
     * @param approvers       all approvers at the current state
     * @param rejectedOnly    when true, ONLY rows currently in REJECTED are
     *                        reset (APPROVED rows are untouched — they keep
     *                        their vote). When false, every non-PENDING row
     *                        is reset.
     * @return true if at least one row was modified
     */
    private boolean resetApproversToPending(List<ParallelParticipantEntity> approvers,
                                            boolean rejectedOnly) {
        long now = System.currentTimeMillis();
        boolean changed = false;
        int rejectedReset = 0;
        int approvedReset = 0;
        for (ParallelParticipantEntity p : approvers) {
            String vs = p.getVoteStatus();
            if (ParallelGateService.VOTE_PENDING.equals(vs)) continue;
            boolean isRejected = ParallelGateService.VOTE_REJECTED.equals(vs);
            if (rejectedOnly && !isRejected) continue;

            if (isRejected) rejectedReset++; else approvedReset++;
            p.setVoteStatus(ParallelGateService.VOTE_PENDING);
            p.setVoteComment(null);
            p.setVotedAt(null);
            p.setNotifyStatus("PENDING");
            p.setNotifyError(null);
            p.setNotifyAttemptedAt(null);
            p.setUpdatedAt(now);
            changed = true;
        }
        if (changed) {
            participantRepository.saveAll(approvers);
            log.info("Reset {} REJECTED + {} APPROVED approver row(s) to PENDING "
                    + "(rejectedOnly={})", rejectedReset, approvedReset, rejectedOnly);
        }
        return changed;
    }

    /**
     * True when the activity's history shows it just came back from the
     * owner via RETURN_TO_VENDOR (or REJECT, the legacy alias) and was
     * then re-SUBMITted with no intervening ALL_APPROVED. Used to
     * differentiate the owner-reject recovery flow (reset ALL rows) from
     * the gate-reject flow (reset only REJECTED rows).
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
                // already re-approved once - we're no longer in the
                // post-owner-reject window.
                case "ALL_APPROVED"     -> ownerReturnedToVendor = false;
                default                 -> { /* SUBMIT, votes, etc — irrelevant */ }
            }
        }

        // The latest row should be a SUBMIT (that's what placed us back
        // on PENDINGATCONCERNEDDIVISION after the owner reject).
        ProcessInstanceEntity latest = history.get(history.size() - 1);
        boolean latestIsSubmit = latest.getActionName() != null
                && latest.getActionName().equalsIgnoreCase("SUBMIT");
        return ownerReturnedToVendor && latestIsSubmit;
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
            List<MultipartFile> files) {

        // 1. Upload files tagged to the OWNER division. If upstream rejects any
        //    file or the network call fails, throw out BEFORE audit and transition.
        List<DocumentEntity> docs = uploadIfPresent(files, req.getRequestInfo(),
                req.getBusinessService(),
                req.getActivityId(),
                req.getProjectId(),
                req.getComment(),
                "OWNER_APPROVAL_REQUEST",
                "OWNER");

        // 2. Audit the click — only after upload succeeds.
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

        // 3. Validate-and-fire.
        parallelGateService.requestOwnerApproval(
                req.getBusinessService(),
                req.getActivityId(),
                req.getProjectId(),
                req.getStateName(),
                req.getComment(),
                req.getRequestInfo());

        log.info("Owner approval requested by admin for activity {} ({} doc(s))",
                req.getActivityId(), docs.size());

        return new RequestOwnerApprovalResult(docs);
    }

    /* ============================================================ */

    /**
     * For each division entry in the request:
     * <ol>
     *   <li>Upload the division's comment (text-only) to the upstream
     *       comments API so it appears in the inbox submissions.</li>
     *   <li>Save each pre-uploaded {@code documentStoreId} as a local
     *       {@link DocumentEntity} reference tagged with the division code.</li>
     * </ol>
     * Falls back to the old flat comment (no division isolation) when
     * {@code divisionApprovals} is absent.
     */
    private List<DocumentEntity> savePerDivisionDocuments(RequestDivisionApprovalRequest req) {
        List<DivisionApprovalInput> divisions = req.getDivisionApprovals();

        // Legacy path — no per-division structure provided.
        if (divisions == null || divisions.isEmpty()) {
            return uploadIfPresent(null, req.getRequestInfo(),
                    req.getBusinessService(), req.getActivityId(),
                    req.getProjectId(), req.getComment(),
                    "DIVISION_APPROVAL_REQUEST", null);
        }

        // Fetch assignments once — used to resolve each division's reviewer UUID.
        // Best-effort: if the call fails we proceed without reviewer tagging.
        Map<String, List<AssignmentData.UserRef>> divisionApprovers = Map.of();
        try {
            AssignmentData assignments = assignmentsClient.fetch(req.getActivityId());
            if (assignments.getDivisionApprovers() != null) {
                divisionApprovers = assignments.getDivisionApprovers();
            }
        } catch (Exception ex) {
            log.warn("Could not fetch assignments for activity {} while saving division docs: {}",
                    req.getActivityId(), ex.getMessage());
        }

        List<DocumentEntity> saved = new ArrayList<>();
        for (DivisionApprovalInput div : divisions) {
            String divisionCode = div.getDivisionId();
            boolean hasComment  = div.getComment() != null && !div.getComment().isBlank();
            boolean hasDocs     = div.getDocumentStoreIds() != null
                                  && !div.getDocumentStoreIds().isEmpty();

            if (!hasComment && !hasDocs) continue;

            // Resolve reviewer UUID: divisionApprovers[divisionCode][0].id
            String reviewerUuid = resolveReviewerUuid(divisionApprovers, divisionCode);

            DocumentMetadata meta = new DocumentMetadata(
                    "DIVISION_APPROVAL_REQUEST",
                    req.getActivityId(), req.getProjectId(),
                    req.getBusinessService(), null,
                    div.getComment(), divisionCode);

            // Upload the comment text to upstream so it shows in organizationSubmissions.
            if (hasComment) {
                saved.add(documentService.uploadAndAttach(null, meta, req.getRequestInfo()));
            }

            // Stamp each pre-uploaded document row with divisionCode + reviewerUuid.
            // If the frontend called POST /activities/documents/upload first,
            // the row already exists with the real fileUrl — we just update
            // divisionCode and reviewerUuid. If not (legacy path), a new row is created.
            if (hasDocs) {
                for (String storeId : div.getDocumentStoreIds()) {
                    saved.add(documentService.assignDivisionCode(
                            storeId, divisionCode,
                            reviewerUuid,
                            req.getBusinessService(), req.getProjectId(),
                            meta, req.getRequestInfo()));
                }
            }
        }
        return saved;
    }

    /**
     * Resolve the first approver's UUID for a given division code from the
     * upstream assignments map. Case-insensitive match on division code.
     * Returns null if no match is found.
     */
    private String resolveReviewerUuid(Map<String, List<AssignmentData.UserRef>> divisionApprovers,
                                        String divisionCode) {
        if (divisionApprovers == null || divisionApprovers.isEmpty() || divisionCode == null) {
            return null;
        }
        return divisionApprovers.entrySet().stream()
                .filter(e -> divisionCode.equalsIgnoreCase(e.getKey()))
                .map(Map.Entry::getValue)
                .filter(list -> list != null && !list.isEmpty())
                .map(list -> list.get(0).getId())
                .findFirst()
                .orElse(null);
    }

    /**
     * Upload zero or more files to the upstream comments API tagged to a
     * specific division. The comment is posted as a standalone entry when
     * no files are provided. All-or-nothing: any file failure propagates
     * before DB rows are written.
     *
     * @param divisionCode division context to stamp on every row ({@code "OWNER"},
     *                     a division code, or {@code null} for legacy flat uploads)
     */
    private List<DocumentEntity> uploadIfPresent(List<MultipartFile> files,
                                                  RequestInfo requestInfo,
                                                  String businessService,
                                                  String activityId,
                                                  String projectId,
                                                  String comment,
                                                  String documentType,
                                                  String divisionCode) {
        List<MultipartFile> realFiles = files == null ? List.of() : files.stream()
                .filter(f -> f != null && !f.isEmpty())
                .toList();
        boolean hasFiles   = !realFiles.isEmpty();
        boolean hasComment = comment != null && !comment.isBlank();

        if (!hasFiles && !hasComment) return List.of();

        DocumentMetadata meta = new DocumentMetadata(
                documentType, activityId, projectId,
                businessService, null, comment, divisionCode);

        List<DocumentEntity> saved = new ArrayList<>();
        if (hasFiles) {
            for (MultipartFile file : realFiles) {
                saved.add(documentService.uploadAndAttach(file, meta, requestInfo));
            }
        } else {
            saved.add(documentService.uploadAndAttach(null, meta, requestInfo));
        }
        return saved;
    }

    /* ----- result records exposed back to the controller ----- */

    public record RequestDivisionApprovalResult(
            List<DocumentEntity> uploadedDocuments,
            int totalApprovers,
            int notifiedApprovers,
            boolean justSeeded) {}

    public record RequestOwnerApprovalResult(
            List<DocumentEntity> uploadedDocuments) {}
}
