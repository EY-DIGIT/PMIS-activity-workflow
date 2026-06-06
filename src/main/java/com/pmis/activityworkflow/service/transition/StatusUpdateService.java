package com.pmis.activityworkflow.service.transition;

import com.pmis.activityworkflow.entity.AuditDetails;
import com.pmis.activityworkflow.entity.ParallelParticipantEntity;
import com.pmis.activityworkflow.entity.ProcessInstanceEntity;
import com.pmis.activityworkflow.mapper.ActivityMapper;
import com.pmis.activityworkflow.repository.ParallelParticipantRepository;
import com.pmis.activityworkflow.repository.ProcessInstanceRepository;
import com.pmis.activityworkflow.service.assignments.ActivityAssignmentsClient;
import com.pmis.activityworkflow.service.assignments.AssignmentData;
import com.pmis.activityworkflow.service.notification.NotificationClient;
import com.pmis.activityworkflow.service.notification.NotificationClient.Recipient;
import com.pmis.activityworkflow.service.notification.NotificationEvent;
import com.pmis.activityworkflow.service.users.UserDetailsClient;
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
    private final ParallelParticipantRepository participantRepository;
    private final ActivityMapper mapper;
    private final NotificationClient notificationClient;
    private final ActivityAssignmentsClient assignmentsClient;
    private final UserDetailsClient userDetailsClient;

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

        // After persist: sync the owner's parallel-participant row so the
        // inbox reflects the action correctly, then fire notifications.
        for (int i = 0; i < saved.size(); i++) {
            syncOwnerParticipantRow(saved.get(i), tuples.get(i), requestInfo);
            dispatchOutcomeNotification(saved.get(i), tuples.get(i), requestInfo);
        }
    }

    /**
     * Keep the OWNER row in {@code aw_parallel_participant} in sync with the
     * transition the owner just fired through {@code /process/_transition}.
     *
     * <p>Why this exists: division approvers vote through
     * {@code /parallel/vote} which writes their row directly. Owners act
     * through {@code /process/_transition}, which only writes
     * {@code aw_process_instance}. Without this sync, the owner's
     * participant row sits at {@code PENDING} forever and the inbox keeps
     * showing the activity as "Pending" for the owner even after they've
     * approved.</p>
     *
     * <p>We update only the OWNER row (matched by state + division code +
     * approver uuid) and only when the action is a final owner decision —
     * APPROVE, RETURN_TO_VENDOR, or RETURN_TO_DIVISION. Other transitions
     * (SUBMIT, ALL_APPROVED, ANY_REJECTED) don't touch participant rows.</p>
     */
    private void syncOwnerParticipantRow(ProcessInstanceEntity saved,
                                         ProcessStateAndAction tuple,
                                         RequestInfo requestInfo) {

        String action = saved.getActionName();
        if (action == null) return;

        String mapped = switch (action.toUpperCase()) {
            case "APPROVE"                                            -> "APPROVED";
            case "REJECT", "RETURN_TO_VENDOR", "RETURNTOVENDOR"       -> "REJECTED";
            case "RETURN_TO_DIVISION",
                 "OWNER_RETURN_TO_DIVISIONS",
                 "RETURNTOCONCERNEDDIVISION"                          -> "RETURNED";
            default                                                   -> null;
        };
        if (mapped == null) return;

        // The owner's row was seeded under state PENDINGATOWNERDIVISION with
        // divisionCode 'OWNER'. We need that row, regardless of where the
        // activity has moved to *after* this transition.
        List<ParallelParticipantEntity> ownerRows = participantRepository
                .findByBusinessServiceAndActivityIdAndStateName(
                        saved.getBusinessService(),
                        saved.getActivityId(),
                        "PENDINGATOWNERDIVISION");

        if (ownerRows.isEmpty()) {
            log.debug("No OWNER participant row to sync for activity {} - skipping",
                    saved.getActivityId());
            return;
        }

        long now = System.currentTimeMillis();
        String actorUuid = Optional.ofNullable(requestInfo)
                .map(RequestInfo::getUserInfo)
                .map(UserInfo::getUuid)
                .orElse(null);

        for (ParallelParticipantEntity p : ownerRows) {
            // Defensive: only touch the OWNER division row (auto-seeded as 'OWNER').
            if (!"OWNER".equalsIgnoreCase(p.getDivisionCode())) continue;

            p.setVoteStatus(mapped);
            p.setVoteComment(saved.getComment());
            p.setVotedAt(now);
            p.setUpdatedAt(now);
            // capture WHO acted, in case the seeded approver_user_uuid was a
            // stale snapshot or different from the user actually clicking.
            if (actorUuid != null) {
                p.setApproverUserUuid(actorUuid);
            }
        }
        participantRepository.saveAll(ownerRows);
        log.info("Synced OWNER participant row to '{}' for activity {} (action={})",
                mapped, saved.getActivityId(), action);
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

        // All rejection variants fan out to:
        //   - earliest SUBMIT actor (original project admin)
        //   - latest SUBMIT actor (current project admin, may be same person)
        //   - the approver rows in the CURRENT state who voted REJECTED
        // For OWNER_RETURN_TO_DIVISIONS we ALSO notify all concerned-division
        // approvers (they're about to be asked to vote again).
        if (event == NotificationEvent.REJECTED_BY_REVIEWER
                || event == NotificationEvent.OWNER_REJECTED
                || event == NotificationEvent.OWNER_RETURNED_TO_DIVISIONS) {

            // Owner sent it back for re-vote — flip every approver row back
            // to PENDING so the gate re-opens. Do this BEFORE notification so
            // the recipients aren't told to re-vote until their rows are
            // actually ready to accept a new vote.
            if (event == NotificationEvent.OWNER_RETURNED_TO_DIVISIONS) {
                resetDivisionParticipantsToPending(saved);
            }

            List<Recipient> all = resolveRejectionRecipients(saved, event);
            notificationClient.notifyOutcomeMany(saved, event, all);
            return;
        }

        Recipient recipient = resolveRecipient(saved, event, requestInfo);
        notificationClient.notifyOutcome(saved, event, recipient);
    }

    /**
     * Reset all concerned-division approver rows to PENDING so the gate
     * re-opens. Called when the owner clicks "Return to Concerned
     * Division". APPROVED + REJECTED rows alike flip to PENDING; the
     * vote comment is cleared so it doesn't carry over from the prior
     * round. notify_status is reset too so the next request-division-
     * approval call re-sends emails.
     */
    private void resetDivisionParticipantsToPending(ProcessInstanceEntity transition) {
        List<ParallelParticipantEntity> rows = participantRepository
                .findByBusinessServiceAndActivityIdAndStateName(
                        transition.getBusinessService(),
                        transition.getActivityId(),
                        "PENDINGATCONCERNEDDIVISION");
        if (rows.isEmpty()) {
            log.warn("RETURN_TO_DIVISION fired but no participant rows found for {}/{}",
                    transition.getBusinessService(), transition.getActivityId());
            return;
        }

        long now = System.currentTimeMillis();
        int reset = 0;
        for (ParallelParticipantEntity p : rows) {
            p.setVoteStatus("PENDING");
            p.setVoteComment(null);
            p.setVotedAt(null);
            p.setNotifyStatus("PENDING");
            p.setNotifyError(null);
            p.setNotifyAttemptedAt(null);
            p.setUpdatedAt(now);
            reset++;
        }
        participantRepository.saveAll(rows);
        log.info("Reset {} participant row(s) to PENDING for activity {} after RETURN_TO_DIVISION",
                reset, transition.getActivityId());
    }

    /**
     * Build the rejection recipient list:
     * <ul>
     *   <li>The original SUBMIT actor (earliest aw_process_instance row's
     *       audit_details.created_by). Email resolved via the upstream
     *       user-lookup API.</li>
     *   <li>The most-recent SUBMIT actor (may equal the original if no
     *       resubmits have happened yet).</li>
     *   <li>Approvers in the rejection state who voted REJECTED.</li>
     *   <li>For OWNER_RETURNED_TO_DIVISIONS: every concerned-division
     *       approver row (they're being asked to re-vote).</li>
     * </ul>
     */
    private List<Recipient> resolveRejectionRecipients(ProcessInstanceEntity saved,
                                                       NotificationEvent event) {
        List<Recipient> out = new ArrayList<>();
        String activityId      = saved.getActivityId();
        String businessService = saved.getBusinessService();

        // 1) Earliest + latest SUBMIT actors. There is at least one SUBMIT row
        //    (we got to a rejection state, so submission happened).
        List<ProcessInstanceEntity> submits = processRepository
                .findByBusinessServiceAndActivityIdOrderByAuditDetails_CreatedTimeAsc(
                        businessService, activityId)
                .stream()
                .filter(p -> "SUBMIT".equalsIgnoreCase(p.getActionName()))
                .toList();
        if (!submits.isEmpty()) {
            addUserAsRecipient(out, submitterUuid(submits.get(0)),                    "earliest SUBMIT actor");
            if (submits.size() > 1) {
                addUserAsRecipient(out, submitterUuid(submits.get(submits.size() - 1)), "latest SUBMIT actor");
            }
        }

        // 2) Approver rows in the rejection state that voted REJECTED.
        //    For OWNER_REJECTED / OWNER_RETURNED_TO_DIVISIONS we look at
        //    PENDINGATOWNERDIVISION. For REJECTED_BY_REVIEWER (concerned
        //    division rejected) we look at PENDINGATCONCERNEDDIVISION.
        String rejectingState = (event == NotificationEvent.OWNER_REJECTED
                              || event == NotificationEvent.OWNER_RETURNED_TO_DIVISIONS)
                ? "PENDINGATOWNERDIVISION"
                : "PENDINGATCONCERNEDDIVISION";

        List<ParallelParticipantEntity> approvers = participantRepository
                .findByBusinessServiceAndActivityIdAndStateName(
                        businessService, activityId, rejectingState);

        for (ParallelParticipantEntity p : approvers) {
            if ("REJECTED".equalsIgnoreCase(p.getVoteStatus())) {
                out.add(new Recipient(p.getApproverUserUuid(), p.getApproverEmail(), p.getApproverName()));
            }
        }

        // 3) For owner-return-to-divisions: also notify the concerned-division
        //    approvers since they are being asked to re-vote.
        if (event == NotificationEvent.OWNER_RETURNED_TO_DIVISIONS) {
            List<ParallelParticipantEntity> divisionRows = participantRepository
                    .findByBusinessServiceAndActivityIdAndStateName(
                            businessService, activityId, "PENDINGATCONCERNEDDIVISION");
            for (ParallelParticipantEntity p : divisionRows) {
                out.add(new Recipient(p.getApproverUserUuid(), p.getApproverEmail(), p.getApproverName()));
            }
        }

        log.info("Rejection recipients for {}/{} on event {}: {} total",
                businessService, activityId, event, out.size());
        return out;
    }

    /** Look up a uuid via the upstream user API and add to the recipient list. */
    private void addUserAsRecipient(List<Recipient> out, String userUuid, String labelForLogs) {
        if (userUuid == null || userUuid.isBlank()) return;
        try {
            UserDetailsClient.UserDetails u = userDetailsClient.fetch(userUuid);
            if (u != null && u.getEmail() != null && !u.getEmail().isBlank()) {
                out.add(new Recipient(u.getId(), u.getEmail(), u.bestDisplayName()));
                log.debug("Added {} (uuid={}, email={}) to rejection recipients",
                        labelForLogs, u.getId(), u.getEmail());
            } else {
                log.warn("User-lookup for {} ({}) returned no email - skipping",
                        labelForLogs, userUuid);
            }
        } catch (Exception ex) {
            log.warn("User-lookup for {} ({}) failed: {}", labelForLogs, userUuid, ex.getMessage());
        }
    }

    private String submitterUuid(ProcessInstanceEntity p) {
        return p.getAuditDetails() == null ? null : p.getAuditDetails().getCreatedBy();
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
            case "ALL_APPROVED"                                       -> NotificationEvent.READY_FOR_OWNER_REVIEW;
            case "ANY_REJECTED"                                       -> NotificationEvent.REJECTED_BY_REVIEWER;
            case "APPROVE"                                            -> NotificationEvent.OWNER_APPROVED;
            // Owner -> READYFORAPPROVAL — vendor/admin must fix and re-submit.
            // Two spellings supported: the legacy "REJECT" alias, and the
            // explicit "RETURN_TO_VENDOR" name that the new UI button uses.
            case "REJECT",
                 "RETURN_TO_VENDOR",
                 "RETURNTOVENDOR"                                     -> NotificationEvent.OWNER_REJECTED;
            // Owner -> PENDINGATCONCERNEDDIVISION — divisions re-evaluate, no re-submit.
            // Multiple spellings supported for backward compatibility with
            // earlier workflow definitions.
            case "RETURN_TO_DIVISION",
                 "OWNER_RETURN_TO_DIVISIONS",
                 "RETURNTOCONSERNEDDEVISION",
                 "RETURNTOCONCERNEDDIVISION"                          -> NotificationEvent.OWNER_RETURNED_TO_DIVISIONS;
            default                                                   -> null;
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
