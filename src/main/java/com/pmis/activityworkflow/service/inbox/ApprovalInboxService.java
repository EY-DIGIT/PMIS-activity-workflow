package com.pmis.activityworkflow.service.inbox;

import com.pmis.activityworkflow.entity.ParallelParticipantEntity;
import com.pmis.activityworkflow.entity.ProcessInstanceEntity;
import com.pmis.activityworkflow.repository.ParallelParticipantRepository;
import com.pmis.activityworkflow.repository.ProcessInstanceRepository;
import com.pmis.activityworkflow.service.assignments.ActivityDetailsClient;
import com.pmis.activityworkflow.web.response.ApprovalInboxItem;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Builds the rows that appear on the "Approval Inbox" screen.
 *
 * <p>Pipeline per call:</p>
 * <ol>
 *   <li>Query {@code aw_parallel_participant} for rows where this user is
 *       the approver (optionally filtered by vote status).</li>
 *   <li>For each row, fetch activity + project from the upstream system
 *       (cached per project within one call so we don't re-fetch).</li>
 *   <li>Look up the SUBMIT timestamp from {@code aw_process_instance}.</li>
 *   <li>Build a flat {@link ApprovalInboxItem} ready for the UI.</li>
 * </ol>
 *
 * <p>Upstream failures degrade gracefully — fields stay null rather than
 * blowing up the whole list.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ApprovalInboxService {

    private final ParallelParticipantRepository participantRepository;
    private final ProcessInstanceRepository processRepository;
    private final ActivityDetailsClient activityDetailsClient;

    public List<ApprovalInboxItem> inbox(String userUuid, String stateName, String voteStatus) {

        List<ParallelParticipantEntity> rows = participantRepository
                .findInboxForApprover(userUuid, normalize(stateName), normalize(voteStatus));

        if (rows.isEmpty()) return List.of();

        // Resolve each activity's current workflow state once. Used to
        // (a) hide rows whose stage the activity hasn't reached yet and
        // (b) reconcile voteStatus inside buildRow without a duplicate lookup.
        Map<String, String> currentStateByActivity = new HashMap<>();
        for (ParallelParticipantEntity p : rows) {
            currentStateByActivity.computeIfAbsent(p.getActivityId(), aid ->
                    processRepository
                            .findFirstByBusinessServiceAndActivityIdOrderByAuditDetails_CreatedTimeDesc(
                                    p.getBusinessService(), aid)
                            .map(ProcessInstanceEntity::getCurrentState)
                            .orElse(null));
        }

        // Hide rows whose state the activity hasn't reached YET, UNLESS the
        // user has already voted on the row. The vote-exception keeps
        // historical actions visible after a rejection rewinds the activity:
        // e.g. an approver rejects at the gate → activity goes back to
        // READYFORAPPROVAL → the row should still appear in their inbox
        // with voteStatus=REJECTED, not vanish.
        List<ParallelParticipantEntity> visible = rows.stream()
                .filter(p -> hasUserActed(p) || isAtOrPastRowState(
                        currentStateByActivity.get(p.getActivityId()),
                        p.getStateName()))
                .toList();
        if (visible.isEmpty()) return List.of();

        // Cache project JSON within this call — many activities share a project.
        Map<String, JsonNode> projectCache = new HashMap<>();
        List<ApprovalInboxItem> out = new ArrayList<>(visible.size());

        for (ParallelParticipantEntity p : visible) {
            out.add(buildRow(p, projectCache, currentStateByActivity.get(p.getActivityId())));
        }
        return out;
    }

    /* ============================================================ */

    /** Workflow state order, lowest (start) to highest (terminal). */
    private static final List<String> STATE_ORDER = List.of(
            "READYFORAPPROVAL",
            "PENDINGATCONCERNEDDIVISION",
            "PENDINGATOWNERDIVISION",
            "ACTIVITYCOMPLETED");

    /**
     * True if {@code currentState} is at or past {@code rowState} in the
     * workflow. Used to hide pre-stage rows from the inbox.
     *
     * <p>Permissive on unknowns: if either state is null or not in the
     * known order, we err on showing the row rather than silently
     * dropping it.</p>
     */
    private boolean isAtOrPastRowState(String currentState, String rowState) {
        if (currentState == null || rowState == null) return true;
        int currentIdx = STATE_ORDER.indexOf(currentState);
        int rowIdx     = STATE_ORDER.indexOf(rowState);
        if (currentIdx == -1 || rowIdx == -1) return true;
        return currentIdx >= rowIdx;
    }

    /**
     * True if the user has already cast a vote on this row (APPROVED,
     * REJECTED, RETURNED — anything other than PENDING). Used to keep
     * historical entries visible in the inbox even after a workflow
     * rewind drops the activity back below the row's state.
     */
    private boolean hasUserActed(ParallelParticipantEntity p) {
        String vs = p.getVoteStatus();
        return vs != null && !"PENDING".equalsIgnoreCase(vs);
    }

    private ApprovalInboxItem buildRow(ParallelParticipantEntity p,
                                       Map<String, JsonNode> projectCache,
                                       String currentState) {
        ApprovalInboxItem.ApprovalInboxItemBuilder b = ApprovalInboxItem.builder()
                .participantUuid(p.getUuid())
                .businessService(p.getBusinessService())
                .stateName(p.getStateName())
                .activityId(p.getActivityId())
                .projectId(p.getProjectId())
                .divisionCode(p.getDivisionCode())
                .divisionName(p.getDivisionName())
                .approverUserUuid(p.getApproverUserUuid())
                .approverName(p.getApproverName())
                .approverEmail(p.getApproverEmail())
                .voteStatus(effectiveVoteStatus(p, currentState))
                .votedAt(p.getVotedAt());

        // --- 1. activity details (always fetch, response field set is small) ---
        JsonNode activity = activityDetailsClient.fetchActivity(p.getActivityId());
        String vendorId = null;
        String projectIdFromActivity = null;
        if (activity != null) {
            b.activityDisplayCode(text(activity, "displayCode"));
            b.activityName(text(activity, "name"));
            vendorId             = text(activity, "vendorId");
            projectIdFromActivity = text(activity, "projectId");
        }

        // --- 2. project details (cache by projectId so we don't re-fetch) ---
        String projectId = StringUtils.hasText(p.getProjectId())
                ? p.getProjectId()
                : projectIdFromActivity;
        if (StringUtils.hasText(projectId)) {
            JsonNode project = projectCache.computeIfAbsent(projectId,
                    activityDetailsClient::fetchProject);
            if (project != null) {
                b.projectName(text(project, "name"));
                b.projectCode(text(project, "projectCode"));
                if (StringUtils.hasText(vendorId)) {
                    pickVendor(project, vendorId).ifPresent(v -> {
                        b.organizationId(text(v, "id"));
                        b.organizationName(text(v, "name"));
                    });
                }
            }
            b.projectId(projectId);
        }

        // --- 3. SUBMIT timestamp from our process_instance table ---
        Optional<ProcessInstanceEntity> submit = processRepository.findLatestSubmit(
                p.getBusinessService(), p.getActivityId());
        submit.map(s -> s.getAuditDetails() == null ? null : s.getAuditDetails().getCreatedTime())
              .ifPresent(b::submittedAt);

        return b.build();
    }

    /**
     * Reconcile the participant row's raw vote_status against the activity's
     * current workflow state. Without this, a row that was never updated
     * (e.g. owner row left at PENDING because the owner acted via
     * /process/_transition) keeps showing PENDING in the inbox forever.
     *
     * <p>Rules:</p>
     * <ul>
     *   <li>If the activity reached the terminal state
     *       ({@code ACTIVITYCOMPLETED}) AND this row is still PENDING,
     *       surface it as APPROVED — the only path to completion is
     *       through every gate approving.</li>
     *   <li>If the activity has moved BEFORE this row's stateName AND
     *       this row is still PENDING, surface as PENDING (correct — not
     *       reached yet).</li>
     *   <li>Otherwise, return whatever the row says.</li>
     * </ul>
     */
    private String effectiveVoteStatus(ParallelParticipantEntity p, String currentState) {
        String raw = p.getVoteStatus();
        if (!"PENDING".equalsIgnoreCase(raw)) return raw;       // APPROVED / REJECTED — trust the row
        if (!StringUtils.hasText(currentState)) return raw;

        // Activity terminated — must have approved at every gate to get here.
        if ("ACTIVITYCOMPLETED".equalsIgnoreCase(currentState)) {
            return "APPROVED";
        }

        return raw;
    }

    /** Match vendorId against project.vendors[].id. */
    private Optional<JsonNode> pickVendor(JsonNode project, String vendorId) {
        JsonNode vendors = project.path("vendors");
        if (!vendors.isArray()) return Optional.empty();
        for (JsonNode v : vendors) {
            if (vendorId.equals(text(v, "id"))) return Optional.of(v);
        }
        return Optional.empty();
    }

    /** Safe text accessor — null instead of "null" or missing-node sentinel. */
    private String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        String t = v.asText();
        return StringUtils.hasText(t) ? t : null;
    }

    private String normalize(String voteStatus) {
        if (!StringUtils.hasText(voteStatus)) return null;
        String upper = voteStatus.trim().toUpperCase();
        // Tolerate "all" / "any" as "give me everything"
        return ("ALL".equals(upper) || "ANY".equals(upper)) ? null : upper;
    }
}