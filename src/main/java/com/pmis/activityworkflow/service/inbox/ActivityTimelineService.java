package com.pmis.activityworkflow.service.inbox;

import com.pmis.activityworkflow.entity.WorkflowAuditEntity;
import com.pmis.activityworkflow.repository.WorkflowAuditRepository;
import com.pmis.activityworkflow.web.response.TimelineEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Builds the activity-detail "Timeline" feed.
 *
 * <p>Reads {@code aw_workflow_audit} and projects:</p>
 * <ul>
 *   <li>SUCCESS rows where the actionName is a state transition →
 *       {@code kind=STATE_TRANSITION} (e.g. SUBMIT, ALL_APPROVED) or
 *       {@code kind=OWNER_ACTION} (APPROVE, RETURN_TO_VENDOR, RETURN_TO_DIVISION)</li>
 *   <li>BUTTON_CLICK rows where actionName starts with {@code VOTE_} →
 *       {@code kind=VOTE}</li>
 * </ul>
 *
 * <p>Button clicks for the admin's "Request Division Approval" / "Request
 * Owner Approval" are intentionally NOT included — the resulting state
 * transitions already represent the same moment in a more meaningful way.
 * Notifications are also excluded; they're a side effect, not a user action.</p>
 *
 * <p>Output is sorted newest first (typical activity feed).</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ActivityTimelineService {

    private final WorkflowAuditRepository auditRepository;

    private static final Set<String> OWNER_ACTIONS = Set.of(
            "APPROVE", "REJECT",
            "RETURN_TO_VENDOR", "RETURNTOVENDOR",
            "RETURN_TO_DIVISION", "OWNER_RETURN_TO_DIVISIONS",
            "RETURNTOCONCERNEDDIVISION", "RETURNTOCONSERNEDDEVISION");

    public List<TimelineEvent> timeline(String businessService, String activityId) {
        List<WorkflowAuditEntity> rows;
        if (StringUtils.hasText(businessService)) {
            rows = auditRepository.findByBusinessServiceAndActivityIdOrderByCreatedTimeAsc(
                    businessService, activityId);
        } else {
            rows = auditRepository.findByActivityIdOrderByCreatedTimeAsc(activityId);
        }

        List<TimelineEvent> events = new ArrayList<>(rows.size());
        for (WorkflowAuditEntity row : rows) {
            TimelineEvent ev = project(row);
            if (ev != null) events.add(ev);
        }

        // Newest first
        events.sort(Comparator.comparing(TimelineEvent::getTimestamp,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return events;
    }

    /* ============================================================ */

    /** Returns null for rows the timeline doesn't surface (button clicks, failures). */
    private TimelineEvent project(WorkflowAuditEntity row) {
        String action = row.getActionName();
        String outcome = row.getOutcome();
        if (action == null) return null;

        TimelineEvent.TimelineEventBuilder b = TimelineEvent.builder()
                .eventId(row.getUuid())
                .timestamp(row.getCreatedTime())
                .actorUuid(row.getPerformedByUuid())
                .actorUsername(row.getPerformedByUsername())
                .actionName(action)
                .previousState(row.getPreviousState())
                .resultantState(row.getResultantState())
                .activityId(row.getActivityId())
                .projectId(row.getProjectId())
                .businessService(row.getBusinessService())
                .detail(row.getComment());

        // ---- 1. Votes — BUTTON_CLICK with VOTE_* action name ----
        if ("BUTTON_CLICK".equalsIgnoreCase(outcome) && action.toUpperCase().startsWith("VOTE_")) {
            String verb = action.toUpperCase().substring("VOTE_".length()).toLowerCase();
            String who = StringUtils.hasText(row.getPerformedByUsername())
                    ? row.getPerformedByUsername() : "Approver";
            return b.kind("VOTE")
                    .title(who + " " + verb)        // "saurabh4321 approved"
                    .build();
        }

        // ---- 2. Owner actions — successful APPROVE / RETURN_* ----
        if ("SUCCESS".equalsIgnoreCase(outcome) && OWNER_ACTIONS.contains(action.toUpperCase())) {
            return b.kind("OWNER_ACTION")
                    .title(ownerTitle(action))
                    .build();
        }

        // ---- 3. Other state transitions (SUBMIT, ALL_APPROVED, ANY_REJECTED, etc) ----
        if ("SUCCESS".equalsIgnoreCase(outcome)) {
            return b.kind("STATE_TRANSITION")
                    .title(transitionTitle(action))
                    .build();
        }

        // BUTTON_CLICK with non-VOTE action (REQUEST_DIVISION_APPROVAL, ...)
        // and FAILED rows — not on the timeline.
        return null;
    }

    private String transitionTitle(String action) {
        return switch (action.toUpperCase()) {
            case "SUBMIT"       -> "Activity submitted for review";
            case "ALL_APPROVED" -> "All divisions approved";
            case "ANY_REJECTED" -> "Activity rejected by a reviewer";
            default             -> "Workflow action: " + action;
        };
    }

    private String ownerTitle(String action) {
        return switch (action.toUpperCase()) {
            case "APPROVE"                                              -> "Owner approved the activity";
            case "REJECT", "RETURN_TO_VENDOR", "RETURNTOVENDOR"          -> "Owner returned to vendor";
            case "RETURN_TO_DIVISION",
                 "OWNER_RETURN_TO_DIVISIONS",
                 "RETURNTOCONCERNEDDIVISION",
                 "RETURNTOCONSERNEDDEVISION"                              -> "Owner returned to concerned division";
            default                                                      -> "Owner action: " + action;
        };
    }
}
