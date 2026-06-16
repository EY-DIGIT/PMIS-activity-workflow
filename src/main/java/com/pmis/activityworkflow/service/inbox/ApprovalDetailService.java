package com.pmis.activityworkflow.service.inbox;

import com.pmis.activityworkflow.entity.DocumentEntity;
import com.pmis.activityworkflow.entity.ParallelParticipantEntity;
import com.pmis.activityworkflow.entity.ProcessInstanceEntity;
import com.pmis.activityworkflow.exception.InvalidTransitionException;
import com.pmis.activityworkflow.repository.DocumentRepository;
import com.pmis.activityworkflow.repository.ParallelParticipantRepository;
import com.pmis.activityworkflow.repository.ProcessInstanceRepository;
import com.pmis.activityworkflow.service.assignments.ActivityAssignmentsClient;
import com.pmis.activityworkflow.service.assignments.ActivityDetailsClient;
import com.pmis.activityworkflow.service.assignments.AssignmentData;
import com.pmis.activityworkflow.web.response.ApprovalDetailResponse;
import com.pmis.activityworkflow.web.response.ApprovalDetailResponse.Attachment;
import com.pmis.activityworkflow.web.response.ApprovalDetailResponse.CommentAuthor;
import com.pmis.activityworkflow.web.response.ApprovalDetailResponse.DivisionStatus;
import com.pmis.activityworkflow.web.response.ApprovalDetailResponse.OrganizationSubmission;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Builds the single payload returned by GET /activities/inbox/{activityId}.
 *
 * <p>Combines:</p>
 * <ul>
 *   <li>Upstream activity API → displayCode, name, description, dates,
 *       ownerDivision, vendorId, projectId</li>
 *   <li>Upstream project API → name, projectCode, vendors[]</li>
 *   <li>{@code aw_parallel_participant} → per-division vote status</li>
 *   <li>{@code aw_process_instance} → latest SUBMIT row (timestamp, comment)</li>
 *   <li>{@code aw_document} → attachments tied to the activity</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ApprovalDetailService {

    private final ParallelParticipantRepository participantRepository;
    private final ProcessInstanceRepository processRepository;
    private final ActivityDetailsClient activityDetailsClient;
    private final ActivityAssignmentsClient assignmentsClient;
    private final DocumentRepository documentRepository;

    public ApprovalDetailResponse forActivity(String activityId, String userUuid, String stateName) {

        // ---- 1. participants for this activity, at any parallel state ----
        // We pull the most recent state with participants - the one the
        // record currently sits on.
        List<ParallelParticipantEntity> participants =
                participantRepository.findInboxParticipantsForActivity(activityId);

        if (participants.isEmpty()) {
            throw new InvalidTransitionException(
                    "No participants found for activity " + activityId);
        }

        // businessService is the same across all rows for one activity;
        // pluck it off the first row for the SUBMIT lookup below.
        String businessService = participants.get(0).getBusinessService();

        // Authoritative current state of the activity comes from the workflow
        // table (aw_process_instance), NOT from any participant row. Participant
        // rows are anchored to the state they were seeded for and don't move.
        String currentActivityState = processRepository
                .findFirstByBusinessServiceAndActivityIdOrderByAuditDetails_CreatedTimeDesc(
                        businessService, activityId)
                .map(ProcessInstanceEntity::getCurrentState)
                .orElse(null);

        // The "your status" breakdown is ALWAYS about the concerned-division
        // gate — that's where the parallel voting happens. The OWNER row
        // (state_name=PENDINGATOWNERDIVISION, division_code=OWNER) is the
        // post-gate single-approver step and lives outside this breakdown.
        List<ParallelParticipantEntity> currentRows = participants.stream()
                .filter(p -> "PENDINGATCONCERNEDDIVISION".equalsIgnoreCase(p.getStateName()))
                .toList();

        // Fallback: if there's no division-gate row at all (e.g. the activity
        // was created before parallel-gate seeding shipped), preserve the
        // old behavior so the UI doesn't go blank.
        if (currentRows.isEmpty()) {
            String fallbackState = participants.get(0).getStateName();
            currentRows = participants.stream()
                    .filter(p -> fallbackState.equalsIgnoreCase(p.getStateName()))
                    .toList();
        }

        // Header field — prefer the workflow's real current state; fall back
        // to the gate-row state for legacy activities with no process-instance row.
        String currentState = currentActivityState != null
                ? currentActivityState
                : (currentRows.isEmpty()
                        ? participants.get(0).getStateName()
                        : currentRows.get(0).getStateName());

        // yourRow: the row that represents the logged-in user's status AT THE
        // CURRENT STAGE. If the same user holds two participant rows (e.g.
        // both a gate approver AND the owner approver), we must pick the one
        // that matches what the user is being asked to do RIGHT NOW —
        // otherwise we'd surface "APPROVED" from their old gate vote while
        // they still have a pending owner decision.
        ParallelParticipantEntity yourRow = pickYourRow(
                participants, userUuid, currentActivityState, stateName);

        // ---- 2. upstream activity + project ----
        JsonNode activity = activityDetailsClient.fetchActivity(activityId);
        String vendorId  = text(activity, "vendorId");
        String projectId = text(activity, "projectId");
        if (projectId == null && yourRow != null) projectId = yourRow.getProjectId();

        JsonNode project = projectId == null ? null : activityDetailsClient.fetchProject(projectId);
        JsonNode vendor  = pickVendor(project, vendorId);

        // ---- 3. latest SUBMIT (for the submittedAt timestamp on the screen) ----
        Optional<ProcessInstanceEntity> submit = processRepository.findLatestSubmit(
                businessService, activityId);
        Long submittedAt = submit
                .map(s -> s.getAuditDetails() == null ? null : s.getAuditDetails().getCreatedTime())
                .orElse(null);

        // ---- 4. organization submissions — every comment + attachment for this activity ----
        List<OrganizationSubmission> submissions = fetchSubmissions(activityId);

        // ---- 5. per-division status rows ----
        // Scoped by the screen the UI is on:
        //   - stateName == PENDINGATCONCERNEDDIVISION → only gate rows
        //   - stateName == PENDINGATOWNERDIVISION    → only OWNER row
        //   - stateName missing → both (legacy behavior)
        boolean includeGate  = !"PENDINGATOWNERDIVISION".equalsIgnoreCase(stateName);
        boolean includeOwner = !"PENDINGATCONCERNEDDIVISION".equalsIgnoreCase(stateName);

        List<ParallelParticipantEntity> ownerRows = participants.stream()
                .filter(p -> "OWNER".equalsIgnoreCase(p.getDivisionCode()))
                .toList();

        List<DivisionStatus> breakdown = new ArrayList<>(currentRows.size() + 1);

        if (includeGate) {
            for (ParallelParticipantEntity p : currentRows) {
                breakdown.add(DivisionStatus.builder()
                        .divisionCode(p.getDivisionCode())
                        .divisionName(p.getDivisionName())
                        .approverUserUuid(p.getApproverUserUuid())
                        .approverName(p.getApproverName())
                        .voteStatus(p.getVoteStatus())
                        .votedAt(p.getVotedAt())
                        .isYou(userUuid.equals(p.getApproverUserUuid()))
                        .build());
            }
        }

        // Append the OWNER row. If the activity has a real DB row (modern
        // seeding), use it. Otherwise (legacy activity, pre-owner-seed) fall
        // back to the upstream assignments API so the response always shows
        // an owner entry alongside the division approvers.
        if (includeOwner) {
            if (!ownerRows.isEmpty()) {
                for (ParallelParticipantEntity p : ownerRows) {
                    breakdown.add(DivisionStatus.builder()
                            .divisionCode(p.getDivisionCode())
                            .divisionName(p.getDivisionName())
                            .approverUserUuid(p.getApproverUserUuid())
                            .approverName(p.getApproverName())
                            .voteStatus(p.getVoteStatus())
                            .votedAt(p.getVotedAt())
                            .isYou(userUuid.equals(p.getApproverUserUuid()))
                            .build());
                }
            } else {
                DivisionStatus synthesized = synthesizeOwnerFromUpstream(activityId, userUuid);
                if (synthesized != null) breakdown.add(synthesized);
            }
        }

        // ---- 5b. availableDivisions — always populated with concerned-
        //          division (gate) approvers, regardless of the stateName
        //          filter on yourStatusBreakdown OR the activity's current
        //          workflow state. Sourced directly from ALL participant
        //          rows whose stateName is PENDINGATCONCERNEDDIVISION,
        //          so the list is the same whether the activity is mid-gate,
        //          past it (owner stage), or rewound back to READYFORAPPROVAL.
        //          Used by the UI's "Return to Concerned Division" modal
        //          on the owner screen.
        List<ParallelParticipantEntity> gateRows = participants.stream()
                .filter(p -> "PENDINGATCONCERNEDDIVISION".equalsIgnoreCase(p.getStateName()))
                .toList();
        List<DivisionStatus> availableDivisions = new ArrayList<>(gateRows.size());
        for (ParallelParticipantEntity p : gateRows) {
            availableDivisions.add(DivisionStatus.builder()
                    .divisionCode(p.getDivisionCode())
                    .divisionName(p.getDivisionName())
                    .approverUserUuid(p.getApproverUserUuid())
                    .approverName(p.getApproverName())
                    .voteStatus(p.getVoteStatus())
                    .votedAt(p.getVotedAt())
                    .isYou(userUuid.equals(p.getApproverUserUuid()))
                    .build());
        }

        // ---- 6. assemble ----
        return ApprovalDetailResponse.builder()
                .activityId(activityId)
                .activityDisplayCode(text(activity, "displayCode"))
                .activityName(text(activity, "name"))
                .yourStatus(yourRow == null ? null : yourRow.getVoteStatus())
                .projectId(projectId)
                .projectName(text(project, "name"))
                .projectCode(text(project, "projectCode"))
                .organizationId(text(vendor, "id"))
                .organizationName(text(vendor, "name"))
                .activityOwnerDivision(text(activity, "ownerDivision"))
                .yourDivisionCode(yourRow == null ? null : yourRow.getDivisionCode())
                .yourDivisionName(yourRow == null ? null : yourRow.getDivisionName())
                .startDate(text(activity, "startDate"))
                .endDate(text(activity, "endDate"))
                .submittedAt(submittedAt)
                .description(text(activity, "description"))
                .organizationSubmissions(submissions)
                .yourStatusBreakdown(breakdown)
                .availableDivisions(availableDivisions)
                .build();
    }

    /* ============================================================ */

    /**
     * Pull the comments+attachments collection from upstream for this
     * activity and surface every entry. No filtering — both the body
     * (comment text) and the attached files are returned as-is.
     *
     * <p>If the upstream call fails or there are no comments, we return
     * an empty list rather than null so the rest of the screen still
     * renders.</p>
     */
    private List<OrganizationSubmission> fetchSubmissions(String activityId) {
        JsonNode elements = activityDetailsClient.fetchActivityComments(activityId);

        // Build a lookup: upstream comment id → local DocumentEntity rows.
        // This lets us enrich each comment's attachments from our own aw_document
        // table, which is reliable even when the upstream GET /comments doesn't
        // include attachment metadata in its response.
        Map<String, List<DocumentEntity>> docsByCommentId = documentRepository
                .findByActivityIdOrderByCreatedAtAsc(activityId)
                .stream()
                .filter(d -> d.getDocId() != null && d.getFileName() != null)
                .collect(Collectors.groupingBy(DocumentEntity::getDocId));

        if (elements == null || !elements.isArray() || elements.isEmpty()) {
            return List.of();
        }

        List<OrganizationSubmission> out = new ArrayList<>(elements.size());
        for (JsonNode el : elements) {
            String commentId = text(el, "id");

            // Prefer upstream attachment list; fall back to local DB records.
            List<Attachment> attachments = toAttachments(el.path("attachments"));
            if (attachments.isEmpty() && commentId != null) {
                attachments = toAttachmentsFromDocs(docsByCommentId.get(commentId));
            }

            out.add(OrganizationSubmission.builder()
                    .commentId(commentId)
                    .body(text(el, "body"))
                    .createdAt(text(el, "createdAt"))
                    .author(toAuthor(el.path("author")))
                    .attachments(attachments)
                    .build());
        }
        return out;
    }

    private List<Attachment> toAttachmentsFromDocs(List<DocumentEntity> docs) {
        if (docs == null || docs.isEmpty()) return List.of();
        List<Attachment> out = new ArrayList<>(docs.size());
        for (DocumentEntity d : docs) {
            out.add(Attachment.builder()
                    .fileName(d.getFileName())
                    .url(d.getFileUrl())
                    .uploadedAt(d.getCreatedAt() == null ? null
                            : java.time.Instant.ofEpochMilli(d.getCreatedAt())
                                    .atZone(java.time.ZoneId.systemDefault())
                                    .toString())
                    .build());
        }
        return out;
    }

    private CommentAuthor toAuthor(JsonNode author) {
        if (author == null || author.isMissingNode() || author.isNull()) return null;
        String first = text(author, "firstName");
        String last  = text(author, "lastName");
        String login = text(author, "login");
        String display;
        if (first != null || last != null) {
            display = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
        } else {
            display = login;
        }
        return CommentAuthor.builder()
                .id(text(author, "id"))
                .login(login)
                .firstName(first)
                .lastName(last)
                .email(text(author, "email"))
                .displayName(display)
                .build();
    }

    private List<Attachment> toAttachments(JsonNode arr) {
        if (arr == null || !arr.isArray()) return List.of();
        List<Attachment> out = new ArrayList<>(arr.size());
        for (JsonNode a : arr) {
            out.add(Attachment.builder()
                    .fileName(text(a, "filename"))
                    .mimeType(text(a, "mimeType"))
                    .sizeBytes(longOrNull(a, "sizeBytes"))
                    .url(text(a, "url"))
                    .uploadedAt(text(a, "uploadedAt"))
                    .build());
        }
        return out;
    }

    private Long longOrNull(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull() || !v.isNumber()) return null;
        return v.asLong();
    }

    private JsonNode pickVendor(JsonNode project, String vendorId) {
        if (project == null || vendorId == null) return null;
        JsonNode vendors = project.path("vendors");
        if (!vendors.isArray()) return null;
        for (JsonNode v : vendors) {
            if (vendorId.equals(text(v, "id"))) return v;
        }
        return null;
    }

    private String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        String t = v.asText();
        return StringUtils.hasText(t) ? t : null;
    }

    /**
     * Pick the participant row that represents "you, right now" for the
     * detail screen. Rule:
     *
     * <ol>
     *   <li>If activity is currently at {@code PENDINGATCONCERNEDDIVISION},
     *       prefer the user's gate row.</li>
     *   <li>If activity is at {@code PENDINGATOWNERDIVISION} or
     *       {@code ACTIVITYCOMPLETED}, prefer the user's OWNER row.</li>
     *   <li>Otherwise (e.g. {@code READYFORAPPROVAL}), use the gate row
     *       if it exists; OWNER row if not.</li>
     * </ol>
     *
     * <p>This matters when one human is both a division approver and the
     * owner approver. Without contextual selection we'd show "APPROVED"
     * (their gate vote) while they still have a pending owner decision.</p>
     */
    /**
     * Pick the participant row that represents the logged-in user's status
     * "right now". Resolution order:
     *
     * <ol>
     *   <li>{@code requestedStateName} matches one of the user's rows
     *       → return that row. Lets the UI say "I want owner-stage
     *       status" by passing {@code stateName=PENDINGATOWNERDIVISION}
     *       (matches OWNER row), even when the activity hasn't yet
     *       transitioned to that state.</li>
     *   <li>Else, the row whose stateName aligns with the activity's
     *       current workflow state.</li>
     *   <li>Else, a sensible default - gate row if available, owner row otherwise.</li>
     * </ol>
     *
     * <p>This matters when one human is both a division approver and the
     * owner approver. Without contextual selection we'd show "APPROVED"
     * (their gate vote) while they still have a pending owner decision.</p>
     */
    private ParallelParticipantEntity pickYourRow(
            List<ParallelParticipantEntity> participants,
            String userUuid,
            String currentActivityState,
            String requestedStateName) {

        List<ParallelParticipantEntity> mine = participants.stream()
                .filter(p -> userUuid.equals(p.getApproverUserUuid()))
                .toList();
        if (mine.isEmpty()) return null;

        ParallelParticipantEntity gateRow = mine.stream()
                .filter(p -> "PENDINGATCONCERNEDDIVISION".equalsIgnoreCase(p.getStateName()))
                .findFirst().orElse(null);
        ParallelParticipantEntity ownerRow = mine.stream()
                .filter(p -> "OWNER".equalsIgnoreCase(p.getDivisionCode()))
                .findFirst().orElse(null);

        // Explicit override from the UI - highest priority.
        if (requestedStateName != null && !requestedStateName.isBlank()) {
            ParallelParticipantEntity match = mine.stream()
                    .filter(p -> requestedStateName.equalsIgnoreCase(p.getStateName()))
                    .findFirst().orElse(null);
            if (match != null) return match;
        }

        if (currentActivityState == null) {
            return gateRow != null ? gateRow : ownerRow;
        }

        return switch (currentActivityState.toUpperCase()) {
            case "PENDINGATOWNERDIVISION", "ACTIVITYCOMPLETED"
                    -> ownerRow != null ? ownerRow : gateRow;
            case "PENDINGATCONCERNEDDIVISION"
                    -> gateRow != null ? gateRow : ownerRow;
            default
                    -> gateRow != null ? gateRow : ownerRow;
        };
    }

    /**
     * Build an OWNER entry for the breakdown from the upstream assignments
     * API. Used when no OWNER participant row exists for the activity
     * (legacy activities created before owner-row auto-seeding shipped).
     * Returns {@code null} if upstream gives us nothing usable.
     *
     * <p>voteStatus is set to PENDING because we have no DB record of an
     * owner action. If the owner has in fact approved, that's surfaced via
     * the activity's current state (ACTIVITYCOMPLETED) — not from this
     * synthesized row.</p>
     */
    private DivisionStatus synthesizeOwnerFromUpstream(String activityId, String userUuid) {
        try {
            AssignmentData data = assignmentsClient.fetch(activityId);
            if (data == null || data.getOwnerApprover() == null
                    || data.getOwnerApprover().isEmpty()) {
                return null;
            }
            AssignmentData.UserRef owner = data.getOwnerApprover().get(0);
            return DivisionStatus.builder()
                    .divisionCode("OWNER")
                    .divisionName("OWNER")
                    .approverUserUuid(owner.getId())
                    .approverName(owner.displayName())
                    .voteStatus("PENDING")
                    .isYou(userUuid.equals(owner.getId()))
                    .build();
        } catch (Exception ex) {
            log.warn("Failed to synthesize OWNER row from assignments for activity {}: {}",
                    activityId, ex.getMessage());
            return null;
        }
    }
}