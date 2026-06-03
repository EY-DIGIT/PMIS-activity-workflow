package com.pmis.activityworkflow.service.inbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.pmis.activityworkflow.entity.ParallelParticipantEntity;
import com.pmis.activityworkflow.entity.ProcessInstanceEntity;
import com.pmis.activityworkflow.repository.ParallelParticipantRepository;
import com.pmis.activityworkflow.repository.ProcessInstanceRepository;
import com.pmis.activityworkflow.service.assignments.ActivityDetailsClient;
import com.pmis.activityworkflow.web.response.ApprovalInboxItem;

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

    public List<ApprovalInboxItem> inbox(String userUuid, String voteStatus) {

        List<ParallelParticipantEntity> rows = participantRepository
                .findInboxForApprover(userUuid, normalize(voteStatus));

        if (rows.isEmpty()) return List.of();

        // Cache project JSON within this call — many activities share a project.
        Map<String, JsonNode> projectCache = new HashMap<>();
        List<ApprovalInboxItem> out = new ArrayList<>(rows.size());

        for (ParallelParticipantEntity p : rows) {
            out.add(buildRow(p, projectCache));
        }
        return out;
    }

    /* ============================================================ */

    private ApprovalInboxItem buildRow(ParallelParticipantEntity p,
                                       Map<String, JsonNode> projectCache) {

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
                .voteStatus(p.getVoteStatus())
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
