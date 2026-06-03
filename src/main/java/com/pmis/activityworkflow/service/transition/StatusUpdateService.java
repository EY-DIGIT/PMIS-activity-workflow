package com.pmis.activityworkflow.service.transition;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pmis.activityworkflow.entity.AuditDetails;
import com.pmis.activityworkflow.entity.ProcessInstanceEntity;
import com.pmis.activityworkflow.mapper.ActivityMapper;
import com.pmis.activityworkflow.repository.ProcessInstanceRepository;
import com.pmis.activityworkflow.service.notification.NotificationClient;
import com.pmis.activityworkflow.service.notification.NotificationEvent;
import com.pmis.activityworkflow.service.notification.NotificationClient.Recipient;
import com.pmis.activityworkflow.web.models.AuditDetailsDTO;
import com.pmis.activityworkflow.web.models.ProcessInstanceDTO;
import com.pmis.activityworkflow.web.models.RequestInfo;
import com.pmis.activityworkflow.web.models.UserInfo;

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

        Recipient recipient = resolveSubmitter(saved, requestInfo);
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
     * Resolve the original submitter:
     *   1. Look up the very first ProcessInstance for this activity — its
     *      createdBy is the original submitter.
     *   2. Fall back to the current request's userInfo if we can't find one
     *      (shouldn't happen in steady state, but defensive).
     *
     * Email isn't stored on ProcessInstance, so the external API has to
     * resolve uuid -> email itself. We pass both when we have them.
     */
    private Recipient resolveSubmitter(ProcessInstanceEntity saved,
                                       RequestInfo requestInfo) {
        Optional<ProcessInstanceEntity> first = processRepository
                .findByBusinessServiceAndActivityIdOrderByAuditDetails_CreatedTimeAsc(
                        saved.getBusinessService(), saved.getActivityId())
                .stream().findFirst();

        String submitterUuid = first
                .map(p -> p.getAuditDetails() == null ? null : p.getAuditDetails().getCreatedBy())
                .orElseGet(() -> Optional.ofNullable(requestInfo)
                        .map(RequestInfo::getUserInfo).map(UserInfo::getUuid).orElse(null));

        UserInfo callerUser = Optional.ofNullable(requestInfo)
                .map(RequestInfo::getUserInfo).orElse(null);

        boolean callerIsSubmitter = callerUser != null
                && submitterUuid != null
                && submitterUuid.equals(callerUser.getUuid());

        return new Recipient(
                submitterUuid,
                null,                                       // external API resolves uuid -> email
                callerIsSubmitter ? callerUser.getName() : null);
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
