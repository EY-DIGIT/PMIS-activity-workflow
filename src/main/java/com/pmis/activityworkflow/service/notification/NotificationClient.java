package com.pmis.activityworkflow.service.notification;

import com.pmis.activityworkflow.config.NotificationProperties;
import com.pmis.activityworkflow.entity.ParallelParticipantEntity;
import com.pmis.activityworkflow.entity.ProcessInstanceEntity;
import com.pmis.activityworkflow.repository.ParallelParticipantRepository;
import com.pmis.activityworkflow.repository.ProcessInstanceRepository;
import com.pmis.activityworkflow.service.assignments.ActivityDetailsClient;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
    private final ProcessInstanceRepository processRepository;
    private final NotificationTemplateService templates;
    private final ActivityDetailsClient activityDetailsClient;

    public NotificationClient(
            NotificationProperties props,
            @Qualifier("notificationRestClient") RestClient notificationRestClient,
            ParallelParticipantRepository participantRepository,
            ProcessInstanceRepository processRepository,
            NotificationTemplateService templates,
            ActivityDetailsClient activityDetailsClient) {
        this.props = props;
        this.notificationRestClient = notificationRestClient;
        this.participantRepository = participantRepository;
        this.processRepository = processRepository;
        this.templates = templates;
        this.activityDetailsClient = activityDetailsClient;
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
     *  Reminder notification (cron job — pending for ≥ 30 days)
     * ============================================================ */

    /**
     * Send a reminder to one approver whose item has been PENDING for
     * {@code pendingDays} days. Updates {@code lastReminderSentAt} on success
     * so the scheduler knows not to re-send within the configured window.
     */
    public void notifyReminder(ParallelParticipantEntity participant, long pendingDays) {
        if (!canDispatch(NotificationEvent.APPROVAL_REMINDER, 1)) return;
        if (isBlank(participant.getApproverEmail())) {
            log.warn("No email for approver {} on activity {} - skipping reminder",
                    participant.getApproverUserUuid(), participant.getActivityId());
            return;
        }

        Map<String, Object> vars = buildApprovalVariables(participant);
        vars.put("pendingDays", String.valueOf(pendingDays));
        vars.put("divisionCode", participant.getDivisionCode() == null ? "" : participant.getDivisionCode());
        vars.put("divisionName", participant.getDivisionName() == null ? "" : participant.getDivisionName());

        RenderedNotification rendered = templates.render(NotificationEvent.APPROVAL_REMINDER, vars);
        Recipient recipient = new Recipient(
                participant.getApproverUserUuid(),
                participant.getApproverEmail(),
                participant.getApproverName());
        Map<String, Object> payload = buildPayload(NotificationEvent.APPROVAL_REMINDER,
                recipient, vars, rendered, null);

        try {
            String response = post(payload);
            long now = System.currentTimeMillis();
            participant.setLastReminderSentAt(now);
            participant.setUpdatedAt(now);
            participantRepository.save(participant);
            log.info("Reminder sent to {} for activity {} (pending {} day(s))",
                    participant.getApproverEmail(), participant.getActivityId(), pendingDays);
            log.debug("Reminder response: {}", response);
        } catch (Exception ex) {
            log.warn("Reminder failed for approver {} ({}) on activity {}: {}",
                    participant.getApproverUserUuid(), participant.getApproverEmail(),
                    participant.getActivityId(), ex.getMessage());
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

    /**
     * Send the same outcome notification to multiple recipients. Each goes
     * out as its own POST so the rendered greeting carries the right name
     * and a failure on one does not block the rest.
     *
     * <p>Recipients with a blank email are silently skipped. Duplicates
     * (same email seen more than once) are de-duped here so nobody gets
     * two copies even if the caller's list overlaps.</p>
     */
    public void notifyOutcomeMany(ProcessInstanceEntity transition,
                                  NotificationEvent event,
                                  List<Recipient> recipients) {

        if (recipients == null || recipients.isEmpty()) {
            log.debug("notifyOutcomeMany called with no recipients for {} on activity {} - skipping",
                    event, transition.getActivityId());
            return;
        }

        // De-dup by lowercased email; drop blanks.
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<Recipient> unique = recipients.stream()
                .filter(r -> r != null && !isBlank(r.email()))
                .filter(r -> seen.add(r.email().trim().toLowerCase()))
                .toList();

        if (unique.isEmpty()) {
            log.warn("No recipients with an email for {} on activity {} - skipping",
                    event, transition.getActivityId());
            return;
        }

        if (!canDispatch(event, unique.size())) return;

        for (Recipient r : unique) {
            // Delegate to the single-recipient path. canDispatch was already
            // checked above; the per-call check is a cheap no-op.
            notifyOutcome(transition, event, r);
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
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON,
                        MediaType.TEXT_PLAIN,
                        MediaType.ALL);
        if (!isBlank(props.getAuthToken())) {
            request = request.header("Authorization", "Bearer " + props.getAuthToken());
        }

        // Drop to .exchange() so we read the raw response stream ourselves
        // and bypass HttpMessageConverters entirely. Some upstream servers
        // return application/octet-stream for what's actually text/JSON,
        // which neither the String nor byte[] converters will accept.
        // Reading the stream directly sidesteps the mismatch.
        return request.body(payload).exchange((req, resp) -> {
            int status = resp.getStatusCode().value();
            byte[] bytes;
            try (var in = resp.getBody()) {
                bytes = in.readAllBytes();
            }
            String body = bytes == null || bytes.length == 0
                    ? ""
                    : new String(bytes, java.nio.charset.StandardCharsets.UTF_8);

            if (status >= 400) {
                // Throw something the caller will catch + log + write to notify_error.
                throw new org.springframework.web.client.RestClientException(
                        "Upstream " + status + ": " + body);
            }
            return body;
        });
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
        vars.put("submittedAt", p.getCreatedAt());      // formatted by template engine
        vars.put("approvalUrl", deepLink(p));

        // Enrich with human-readable activity + project details + submitter
        enrichWithActivityDetails(vars, p.getActivityId(), p.getProjectId());
        vars.put("submittedBy", lookupSubmittedBy(p.getBusinessService(), p.getActivityId()));
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

        // Same enrichment as approval-request emails so all templates can
        // reference {{activityName}} / {{projectCode}} etc consistently.
        enrichWithActivityDetails(vars, t.getActivityId(), t.getProjectId());
        vars.put("submittedBy", lookupSubmittedBy(t.getBusinessService(), t.getActivityId()));
        return vars;
    }

    /**
     * Fill {@code activityName}, {@code activityDisplayCode},
     * {@code projectName}, {@code projectCode} from the upstream APIs.
     *
     * <p>All four default to empty strings so templates never render
     * literal {@code null}. Best-effort — if the upstream lookup fails
     * we leave the placeholders empty but the email still goes out.</p>
     */
    private void enrichWithActivityDetails(Map<String, Object> vars,
                                           String activityId,
                                           String projectId) {
        // Seed safe defaults first
        vars.put("activityDisplayCode", "");
        vars.put("activityName", "");
        vars.put("projectCode", "");
        vars.put("projectName", "");

        if (!StringUtils.hasText(activityId)) return;

        try {
            JsonNode activity = activityDetailsClient.fetchActivity(activityId);
            if (activity != null) {
                putIfPresent(vars, "activityDisplayCode", activity, "displayCode");
                putIfPresent(vars, "activityName",        activity, "name");
                // If projectId wasn't on the participant row, fall back to the upstream value
                if (!StringUtils.hasText(projectId)) {
                    projectId = activity.path("projectId").asText(null);
                }
            }
        } catch (Exception ex) {
            log.warn("Activity-details enrichment failed for {}: {}", activityId, ex.getMessage());
        }

        if (!StringUtils.hasText(projectId)) return;

        try {
            JsonNode project = activityDetailsClient.fetchProject(projectId);
            if (project != null) {
                putIfPresent(vars, "projectName", project, "name");
                putIfPresent(vars, "projectCode", project, "projectCode");
            }
        } catch (Exception ex) {
            log.warn("Project-details enrichment failed for {}: {}", projectId, ex.getMessage());
        }
    }

    /**
     * Resolve the "Submitted by" name. Prefers the upstream activity's
     * {@code owner[0].login} (which is the activity submitter); falls back
     * to the createdBy uuid on the earliest SUBMIT row in
     * {@code aw_process_instance}.
     */
    private String lookupSubmittedBy(String businessService, String activityId) {
        if (!StringUtils.hasText(activityId)) return "";
        try {
            JsonNode activity = activityDetailsClient.fetchActivity(activityId);
            if (activity != null) {
                JsonNode owners = activity.path("owner");
                if (owners.isArray() && !owners.isEmpty()) {
                    JsonNode first = owners.get(0);
                    String firstName = first.path("firstName").asText(null);
                    String lastName  = first.path("lastName").asText(null);
                    if (StringUtils.hasText(firstName) || StringUtils.hasText(lastName)) {
                        return ((firstName == null ? "" : firstName) + " "
                                + (lastName == null ? "" : lastName)).trim();
                    }
                    String login = first.path("login").asText(null);
                    if (StringUtils.hasText(login)) return login;
                }
            }
        } catch (Exception ex) {
            log.debug("Owner lookup for submittedBy failed on {}: {}", activityId, ex.getMessage());
        }

        // Fall back to the earliest SUBMIT row's actor in our own DB
        return Optional.ofNullable(processRepository
                        .findByBusinessServiceAndActivityIdOrderByAuditDetails_CreatedTimeAsc(
                                businessService, activityId))
                .filter(rows -> !rows.isEmpty())
                .map(rows -> rows.get(0))
                .map(row -> row.getAuditDetails() == null ? null
                        : row.getAuditDetails().getCreatedBy())
                .orElse("");
    }

    private void putIfPresent(Map<String, Object> vars, String key, JsonNode node, String field) {
        if (node == null) return;
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) return;
        String text = v.asText();
        if (StringUtils.hasText(text)) vars.put(key, text);
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
