package com.pmis.activityworkflow.service.inbox;

import com.pmis.activityworkflow.entity.ParallelParticipantEntity;
import com.pmis.activityworkflow.entity.WorkflowAuditEntity;
import com.pmis.activityworkflow.repository.ParallelParticipantRepository;
import com.pmis.activityworkflow.repository.WorkflowAuditRepository;
import com.pmis.activityworkflow.web.response.ApprovalSummaryResponse;
import com.pmis.activityworkflow.web.response.ApprovalSummaryResponse.DivisionDecision;
import com.pmis.activityworkflow.web.response.ApprovalSummaryResponse.RequestEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * Builds the structured approval summary for one activity.
 *
 * <p>Data sources:
 * <ul>
 *   <li>{@code aw_workflow_audit} — BUTTON_CLICK rows for REQUEST_DIVISION_APPROVAL
 *       and REQUEST_OWNER_APPROVAL (first occurrence = the request event).</li>
 *   <li>{@code aw_parallel_participant} — per-division vote decisions
 *       (voteStatus, votedAt, voteComment, approverName).</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ApprovalSummaryService {

    private final WorkflowAuditRepository auditRepository;
    private final ParallelParticipantRepository participantRepository;

    public ApprovalSummaryResponse summary(String activityId) {

        // ---- 1. Audit rows — find the two admin request events ----
        List<WorkflowAuditEntity> auditRows =
                auditRepository.findByActivityIdOrderByCreatedTimeAsc(activityId);

        RequestEvent divisionRequest = auditRows.stream()
                .filter(r -> "BUTTON_CLICK".equalsIgnoreCase(r.getOutcome())
                        && "REQUEST_DIVISION_APPROVAL".equalsIgnoreCase(r.getActionName()))
                .min(Comparator.comparingLong(r -> r.getCreatedTime() == null ? Long.MAX_VALUE : r.getCreatedTime()))
                .map(this::toRequestEvent)
                .orElse(null);

        RequestEvent ownerRequest = auditRows.stream()
                .filter(r -> "BUTTON_CLICK".equalsIgnoreCase(r.getOutcome())
                        && "REQUEST_OWNER_APPROVAL".equalsIgnoreCase(r.getActionName()))
                .min(Comparator.comparingLong(r -> r.getCreatedTime() == null ? Long.MAX_VALUE : r.getCreatedTime()))
                .map(this::toRequestEvent)
                .orElse(null);

        // ---- 2. Participant rows — per-division decisions ----
        List<ParallelParticipantEntity> participants =
                participantRepository.findInboxParticipantsForActivity(activityId);

        List<DivisionDecision> concernedDivisions = participants.stream()
                .filter(p -> "PENDINGATCONCERNEDDIVISION".equalsIgnoreCase(p.getStateName()))
                .sorted(Comparator.comparing(p -> p.getDivisionCode() == null ? "" : p.getDivisionCode()))
                .map(this::toDecision)
                .toList();

        DivisionDecision ownerDivision = participants.stream()
                .filter(p -> "OWNER".equalsIgnoreCase(p.getDivisionCode()))
                .findFirst()
                .map(this::toDecision)
                .orElse(null);

        return ApprovalSummaryResponse.builder()
                .activityId(activityId)
                .divisionApprovalRequest(divisionRequest)
                .ownerApprovalRequest(ownerRequest)
                .concernedDivisions(concernedDivisions)
                .ownerDivision(ownerDivision)
                .build();
    }

    private RequestEvent toRequestEvent(WorkflowAuditEntity row) {
        return RequestEvent.builder()
                .requestedAt(row.getCreatedTime())
                .requestedByUuid(row.getPerformedByUuid())
                .requestedByUsername(row.getPerformedByUsername())
                .comment(row.getComment())
                .build();
    }

    private DivisionDecision toDecision(ParallelParticipantEntity p) {
        return DivisionDecision.builder()
                .divisionCode(p.getDivisionCode())
                .divisionName(p.getDivisionName())
                .approverUuid(p.getApproverUserUuid())
                .approverName(p.getApproverName())
                .approverEmail(p.getApproverEmail())
                .status(p.getVoteStatus())
                .actionAt(p.getVotedAt())
                .comment(p.getVoteComment())
                .build();
    }
}
