package com.pmis.activityworkflow.service.transition;

import com.pmis.activityworkflow.entity.AuditDetails;
import com.pmis.activityworkflow.entity.ProcessInstanceEntity;
import com.pmis.activityworkflow.mapper.ActivityMapper;
import com.pmis.activityworkflow.repository.ProcessInstanceRepository;
import com.pmis.activityworkflow.service.assignments.ActivityAssignmentsClient;
import com.pmis.activityworkflow.service.assignments.AssignmentData;
import com.pmis.activityworkflow.service.notification.NotificationClient;
import com.pmis.activityworkflow.service.notification.NotificationClient.Recipient;
import com.pmis.activityworkflow.service.notification.NotificationEvent;
import com.pmis.activityworkflow.web.models.AuditDetailsDTO;
import com.pmis.activityworkflow.web.models.ProcessInstanceDTO;
import com.pmis.activityworkflow.web.models.RequestInfo;
import com.pmis.activityworkflow.web.models.UserInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Persists the new transition row, then fires the appropriate outcome
 * notification (APPROVED / REJECTED / COMPLETED).
 *
 * <p>Notification dispatch happens AFTER {@code saveAll(...)} so a
 * notification failure doesn't roll back the state change. The external
 * notify call is best-effort with its own error handling inside
 * {@link NotificationClient}.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StatusUpdateService {

    private final ProcessInstanceRepository processRepository;
    private final ActivityMapper mapper;
    private final NotificationClient notificationClient;
    private final ActivityAssignmentsClient assignmentsClient;

    @Transactional
    public void updateStatus(RequestInfo requestInfo,
                             List<ProcessStateAndAction> tuples) {

        List<ProcessInstanceEntity> rows = new ArrayList<>(tuples.size());

        for (ProcessStateAndAction tuple : tuples) {
            ProcessInstanceDTO req = tuple.getProcessInstanceFromRequest();

            // previousStatus = uuid of the state we were in BEFORE the action
            if (req.getState() != null && req.getState().getUuid() != null) {
                req.setPreviousStatus(req.getState().getUuid());
            } else if (tuple.getCurrentState() != null) {
                req.setPreviousStatus(tuple.getCurrentState().getUuid());
            }

            // state = the resultant state (full StateDTO mirroring Digit)
            req.setState(mapper.toStateDTO(tuple.getResultantState()));

            rows.add(toEntity(tuple));
        }

        List<ProcessInstanceEntity> saved = processRepository.saveAll(rows);
        log.info("Persisted {} ProcessInstance transition(s) via JPA", saved.size());

        // Fire outcome notifications AFTER persist — best-effort.
        for (int i = 0; i < saved.size(); i++) {
            dispatchOutcomeNotification(saved.get(i), tuples.get(i), requestInfo);
        }
    }

    /* ====================  outcome notification routing  ==================== */

    /**
     * Routes the freshly-persisted transition to the right template, based
     * on the action that produced it. The recipient is the original
     * submitter (the actor who fired the very first transition for this
     * activityId).
     */
    private void dispatchOutcomeNotification(ProcessInstanceEntity saved,
                                             ProcessStateAndAction tuple,
                                             RequestInfo requestInfo) {

        NotificationEvent event = resolveEvent(saved, tuple);
        if (event == null) {
            // Not a notify-worthy transition — e.g. SUBMIT just entering the gate.
            return;
        }

        Recipient recipient = resolveRecipient(saved, event, requestInfo);
        notificationClient.notifyOutcome(saved, event, recipient);
    }

    /**
     * Maps action name / resultant state to a notification event.
     *
     * <ul>
     *   <li>resultant state is terminal → COMPLETED</li>
     *   <li>ALL_APPROVED → READY_FOR_OWNER_REVIEW (gate passed, owner notified)</li>
     *   <li>ANY_REJECTED → REJECTED_BY_REVIEWER</li>
     *   <li>APPROVE      → OWNER_APPROVED</li>
     *   <li>REJECT       → OWNER_REJECTED</li>
     *   <li>everything else (SUBMIT, UPDATE, ...) → no notification</li>
     * </ul>
     */
    private NotificationEvent resolveEvent(ProcessInstanceEntity saved,
                                           ProcessStateAndAction tuple) {
        if (Boolean.TRUE.equals(saved.getTerminateState())) {
            return NotificationEvent.COMPLETED;
        }
        String action = saved.getActionName();
        if (action == null) return null;

        return switch (action.toUpperCase()) {
            case "ALL_APPROVED" -> NotificationEvent.READY_FOR_OWNER_REVIEW;
            case "ANY_REJECTED" -> NotificationEvent.REJECTED_BY_REVIEWER;
            case "APPROVE"      -> NotificationEvent.OWNER_APPROVED;
            case "REJECT"       -> NotificationEvent.OWNER_REJECTED;
            default             -> null;
        };
    }

    /**
     * Pick the email + name to notify based on which event we're firing.
     *
     * <ul>
     *   <li>READY_FOR_OWNER_REVIEW → the owner-division approver (from
     *       {@code ownerApprover[0]} on the upstream assignments API)</li>
     *   <li>OWNER_APPROVED / OWNER_REJECTED / REJECTED_BY_REVIEWER /
     *       COMPLETED → the activity's owner / submitter (from {@code owner[0]}
     *       on the upstream assignments API)</li>
     * </ul>
     *
     * <p>If the upstream lookup fails or returns nothing for the chosen
     * field, we return a recipient with null email — the notification call
     * will then be skipped by {@link NotificationClient#notifyOutcome} with
     * a warning rather than the upstream 422.</p>
     */
    private Recipient resolveRecipient(ProcessInstanceEntity saved,
                                       NotificationEvent event,
                                       RequestInfo requestInfo) {
        try {
            AssignmentData data = assignmentsClient.fetch(saved.getActivityId());
            AssignmentData.UserRef target = pickRecipientFromAssignments(data, event);
            if (target != null && target.getEmail() != null) {
                return new Recipient(
                        target.getId(),
                        target.getEmail(),
                        target.fullName());
            }
        } catch (Exception ex) {
            log.warn("Assignments lookup for {} on activity {} failed: {} - falling back",
                    event, saved.getActivityId(), ex.getMessage());
        }

        // Fallback — caller info if we have it; otherwise empty recipient.
        UserInfo callerUser = Optional.ofNullable(requestInfo)
                .map(RequestInfo::getUserInfo).orElse(null);
        return new Recipient(
                callerUser != null ? callerUser.getUuid() : null,
                null,
                callerUser != null ? callerUser.getName() : null);
    }

    /** Choose the right user from the assignments payload for this event. */
    private AssignmentData.UserRef pickRecipientFromAssignments(AssignmentData data,
                                                                NotificationEvent event) {
        if (data == null) return null;

        List<AssignmentData.UserRef> candidates = switch (event) {
            case READY_FOR_OWNER_REVIEW -> data.getOwnerApprover();
            // All other outcome events go back to the activity owner/submitter
            case OWNER_APPROVED, OWNER_REJECTED, REJECTED_BY_REVIEWER, COMPLETED
                                        -> data.getOwner();
            default                     -> null;
        };
        if (candidates == null || candidates.isEmpty()) return null;
        return candidates.get(0);
    }

    /* =====================  DTO -> Entity  ===================== */

    private ProcessInstanceEntity toEntity(ProcessStateAndAction tuple) {
        ProcessInstanceDTO req = tuple.getProcessInstanceFromRequest();
        AuditDetailsDTO ad = req.getAuditDetails();

        return ProcessInstanceEntity.builder()
                .uuid(req.getId())
                .businessService(req.getBusinessService())
                .activityId(req.getActivityId())
                .projectId(req.getProjectId())
                .moduleName(req.getModuleName())
                .currentState(tuple.getResultantState().getStateName())
                .previousState(tuple.getCurrentState() == null
                        ? null : tuple.getCurrentState().getStateName())
                .actionName(tuple.getAction().getActionName())
                .comment(req.getComment())
                .assignee(extractFirstAssigneeUuid(req))
                .sla(req.getStateSla() == null ? 0L : req.getStateSla())
                .terminateState(Boolean.TRUE.equals(tuple.getResultantState().getIsTerminateState()))
                .roles(tuple.getAction().getRoles() == null
                        ? new ArrayList<>()
                        : new ArrayList<>(tuple.getAction().getRoles()))
                .auditDetails(AuditDetails.builder()
                        .createdBy(ad == null ? null : ad.getCreatedBy())
                        .createdTime(ad == null ? null : ad.getCreatedTime())
                        .lastModifiedBy(ad == null ? null : ad.getLastModifiedBy())
                        .lastModifiedTime(ad == null ? null : ad.getLastModifiedTime())
                        .build())
                .build();
    }

    private String extractFirstAssigneeUuid(ProcessInstanceDTO req) {
        if (req.getAssignes() == null || req.getAssignes().isEmpty()) return null;
        return req.getAssignes().get(0).getUuid();
    }
}
