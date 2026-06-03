package com.pmis.activityworkflow.service.notification;

import com.pmis.activityworkflow.config.NotificationProperties;
import com.pmis.activityworkflow.entity.ParallelParticipantEntity;
import com.pmis.activityworkflow.entity.ProcessInstanceEntity;
import com.pmis.activityworkflow.repository.ParallelParticipantRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wraps the external notification API.
 *
 * <p>Templates are rendered LOCALLY via {@link NotificationTemplateService}
 * (plain text only). The rendered {@code subject} + {@code body} are sent
 * as part of the JSON payload so the external API only needs to dispatch
 * — no template engine on that side.</p>
 *
 * <p>Two entry points:</p>
 * <ol>
 *   <li>{@link #notifyApprovalRequested(List, boolean)} — per-reviewer
 *       notifications when a parallel gate opens; updates the participant
 *       row with notify_status.</li>
 *   <li>{@link #notifyOutcome(ProcessInstanceEntity, NotificationEvent,
 *       Recipient)} — single fire-and-forget for APPROVED / REJECTED /
 *       COMPLETED events.</li>
 * </ol>
 */
@Service
@Slf4j
public class NotificationClient {

    private static final String STATUS_SENT   = "SENT";
    private static final String STATUS_FAILED = "FAILED";

    private final NotificationProperties props;
    private final RestClient notificationRestClient;
    private final ParallelParticipantRepository participantRepository;
    private final NotificationTemplateService templates;

    public NotificationClient(
            NotificationProperties props,
            @Qualifier("notificationRestClient") RestClient notificationRestClient,
            ParallelParticipantRepository participantRepository,
            NotificationTemplateService templates) {
        this.props = props;
        this.notificationRestClient = notificationRestClient;
        this.participantRepository = participantRepository;
        this.templates = templates;
    }

    /* ============================================================
     *  Per-participant APPROVAL_REQUESTED notifications
     * ============================================================ */

    /**
     * @param participants the rows freshly seeded or re-seeded
     * @param isResubmission true on a re-seed (uses APPROVAL_RESUBMITTED template)
     */
    public void notifyApprovalRequested(List<ParallelParticipantEntity> participants,
                                        boolean isResubmission) {
        if (participants == null || participants.isEmpty()) return;

        NotificationEvent event = isResubmission
                ? NotificationEvent.APPROVAL_RESUBMITTED
                : NotificationEvent.APPROVAL_REQUESTED;

        if (!canDispatch(event, participants.size())) return;

        for (ParallelParticipantEntity p : participants) {
            postAndUpdateRow(event, p);
        }
    }

    /* ============================================================
     *  Single outcome notification (APPROVED / REJECTED / COMPLETED)
     * ============================================================ */
    public void notifyOutcome(ProcessInstanceEntity transition,
                              NotificationEvent event,
                              Recipient recipient) {

        if (!canDispatch(event, 1)) return;
        if (recipient == null || isBlank(recipient.email())) {
            // Upstream API requires at least one email in 'to'. Skipping the
            // call avoids a guaranteed 422 from the notify API.
            log.warn("No email for recipient on {} for activity {} - skipping notification",
                    event, transition.getActivityId());
            return;
        }

        Map<String, Object> vars = buildOutcomeVariables(transition, recipient);
        RenderedNotification rendered = templates.render(event, vars);
        Map<String, Object> payload = buildPayload(event, recipient, vars, rendered, transition);

        try {
            String response = post(payload);
            log.info("Sent {} to {} for activity {}",
                    event, recipient.email(), transition.getActivityId());
            log.debug("Notify response: {}", response);
        } catch (Exception ex) {
            log.warn("Outcome notification {} failed for activity {}: {}",
                    event, transition.getActivityId(), ex.getMessage());
        }
    }

    /* ============================================================
     *  HTTP plumbing
     * ============================================================ */

    private boolean canDispatch(NotificationEvent event, int count) {
        if (!props.isEnabled()) {
            log.info("Notifications disabled - skipping {} {} notification(s)", count, event);
            return false;
        }
        if (isBlank(props.getUrl())) {
            log.warn("app.notification.url not configured - skipping {} {} notification(s)",
                    count, event);
            return false;
        }
        return true;
    }

    private String post(Map<String, Object> payload) {
        var request = notificationRestClient.post()
                .uri(props.getUrl())
                .contentType(MediaType.APPLICATION_JSON);
        if (!isBlank(props.getAuthToken())) {
            request = request.header("Authorization", "Bearer " + props.getAuthToken());
        }
        return request.body(payload).retrieve().body(String.class);
    }

    /** Sends the approval-request notification, then writes notify_status back. */
    private void postAndUpdateRow(NotificationEvent event, ParallelParticipantEntity row) {
        long now = System.currentTimeMillis();
        row.setNotifyAttemptedAt(now);

        try {
            Map<String, Object> vars = buildApprovalVariables(row);
            RenderedNotification rendered = templates.render(event, vars);

            Recipient recipient = new Recipient(
                    row.getApproverUserUuid(), row.getApproverEmail(), row.getApproverName());
            Map<String, Object> payload = buildPayload(event, recipient, vars, rendered, null);
            // upstream API only accepts to / subject / body / is_html.
            // We log the workflow context separately rather than putting it in the request.
            log.debug("Notification context: activityId={} projectId={} stateName={}",
                    row.getActivityId(), row.getProjectId(), row.getStateName());

            String response = post(payload);

            row.setNotifyStatus(STATUS_SENT);
            row.setNotifyError(null);
            log.info("{} sent to {} for activity {}",
                    event, row.getApproverEmail(), row.getActivityId());
            log.debug("Notify response: {}", response);

        } catch (Exception ex) {
            row.setNotifyStatus(STATUS_FAILED);
            row.setNotifyError(truncate(ex.getMessage(), 1024));
            log.warn("{} failed for {} ({}): {}",
                    event, row.getApproverUserUuid(), row.getApproverEmail(), ex.getMessage());
        } finally {
            row.setUpdatedAt(System.currentTimeMillis());
            participantRepository.save(row);
        }
    }

    /* ============================================================
     *  Payload + variable builders
     * ============================================================ */

    /**
     * Build the request body the upstream notification API expects.
     *
     * Upstream API shape (POST /notification/email/send):
     * <pre>
     *   {
     *     "to":      ["recipient@example.com"],
     *     "subject": "...",
     *     "body":    "...",
     *     "is_html": false
     *   }
     * </pre>
     *
     * The {@code context} argument and {@code variables} are intentionally
     * ignored when assembling the outgoing payload — we keep them in the
     * method signature so the call sites stay symmetric, and so it's a
     * small change to add them back if the upstream API ever wants
     * metadata.
     */
    private Map<String, Object> buildPayload(NotificationEvent event,
                                             Recipient recipient,
                                             Map<String, Object> variables,
                                             RenderedNotification rendered,
                                             ProcessInstanceEntity context) {
        Map<String, Object> body = new LinkedHashMap<>();

        // 'to' must be a non-empty list of email addresses
        if (recipient != null && recipient.email() != null && !recipient.email().isBlank()) {
            body.put("to", List.of(recipient.email()));
        } else {
            body.put("to", List.of());      // upstream will reject — we surface the validation error
        }

        body.put("subject", rendered.getSubject());
        body.put("body",    rendered.getBody());
        body.put("is_html", false);          // our templates are plain text
        return body;
    }

    private Map<String, Object> buildApprovalVariables(ParallelParticipantEntity p) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("approverName", p.getApproverName());
        vars.put("approverEmail", p.getApproverEmail());
        vars.put("activityId", p.getActivityId());
        vars.put("projectId", p.getProjectId());
        vars.put("businessService", p.getBusinessService());
        vars.put("stateName", p.getStateName());
        vars.put("submittedBy", "");                    // optional — fill from latest ProcessInstance if you want
        vars.put("submittedAt", p.getCreatedAt());      // formatted by template engine
        vars.put("approvalUrl", deepLink(p));
        return vars;
    }

    private Map<String, Object> buildOutcomeVariables(ProcessInstanceEntity t, Recipient r) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("approverName", r.displayName());
        vars.put("recipientEmail", r.email());
        vars.put("activityId", t.getActivityId());
        vars.put("projectId", t.getProjectId());
        vars.put("businessService", t.getBusinessService());
        vars.put("currentState", t.getCurrentState());
        vars.put("previousState", t.getPreviousState());
        vars.put("actionName", t.getActionName());
        vars.put("actorName",  t.getAuditDetails() == null ? null : t.getAuditDetails().getCreatedBy());
        vars.put("actionAt",   t.getAuditDetails() == null
                ? System.currentTimeMillis() : t.getAuditDetails().getCreatedTime());
        vars.put("comment", t.getComment() == null ? "" : t.getComment());
        return vars;
    }

    private String deepLink(ParallelParticipantEntity p) {
        return String.format("/approvals/%s/%s/%s",
                p.getBusinessService(), p.getActivityId(), p.getStateName());
    }

    /* ============================================================ */

    private boolean isBlank(String s) { return s == null || s.isBlank(); }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** Lightweight recipient projection so we don't pass entities around. */
    public record Recipient(String userUuid, String email, String displayName) {}
}
