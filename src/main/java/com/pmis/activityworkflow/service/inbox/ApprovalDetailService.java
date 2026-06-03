package com.pmis.activityworkflow.service.inbox;


import com.pmis.activityworkflow.entity.DocumentEntity;
import com.pmis.activityworkflow.entity.ParallelParticipantEntity;
import com.pmis.activityworkflow.entity.ProcessInstanceEntity;
import com.pmis.activityworkflow.exception.InvalidTransitionException;
import com.pmis.activityworkflow.repository.DocumentRepository;
import com.pmis.activityworkflow.repository.ParallelParticipantRepository;
import com.pmis.activityworkflow.repository.ProcessInstanceRepository;
import com.pmis.activityworkflow.service.assignments.ActivityDetailsClient;
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
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class ApprovalDetailService {
 
    private final ParallelParticipantRepository participantRepository;
    private final ProcessInstanceRepository processRepository;
    private final ActivityDetailsClient activityDetailsClient;
 
    public ApprovalDetailResponse forActivity(String businessService,
                                              String activityId,
                                              String userUuid) {
 
        // ---- 1. participants for this activity, at any parallel state ----
        // We pull the most recent state with participants - the one the
        // record currently sits on.
        List<ParallelParticipantEntity> participants =
                participantRepository.findInboxParticipantsForActivity(businessService, activityId);
 
        if (participants.isEmpty()) {
            throw new InvalidTransitionException(
                    "No participants found for activity " + activityId
                            + " under businessService " + businessService);
        }
 
        // current state = the state name on the newest participant rows
        String currentState = participants.get(0).getStateName();
        // narrow to rows on that state (older rows may exist from earlier seeds)
        List<ParallelParticipantEntity> currentRows = participants.stream()
                .filter(p -> currentState.equals(p.getStateName()))
                .toList();
 
        ParallelParticipantEntity yourRow = currentRows.stream()
                .filter(p -> userUuid.equals(p.getApproverUserUuid()))
                .findFirst()
                .orElse(null);
 
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
        List<DivisionStatus> breakdown = new ArrayList<>(currentRows.size());
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
        if (elements == null || !elements.isArray() || elements.isEmpty()) {
            return List.of();
        }
 
        List<OrganizationSubmission> out = new ArrayList<>(elements.size());
        for (JsonNode el : elements) {
            out.add(OrganizationSubmission.builder()
                    .commentId(text(el, "id"))
                    .body(text(el, "body"))
                    .createdAt(text(el, "createdAt"))
                    .author(toAuthor(el.path("author")))
                    .attachments(toAttachments(el.path("attachments")))
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
}