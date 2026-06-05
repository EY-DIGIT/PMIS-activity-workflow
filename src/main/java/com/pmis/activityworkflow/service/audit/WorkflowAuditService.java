package com.pmis.activityworkflow.service.audit;

import com.pmis.activityworkflow.entity.WorkflowAuditEntity;
import com.pmis.activityworkflow.repository.WorkflowAuditRepository;
import com.pmis.activityworkflow.service.transition.ProcessStateAndAction;
import com.pmis.activityworkflow.web.models.ProcessInstanceDTO;
import com.pmis.activityworkflow.web.models.RequestInfo;
import com.pmis.activityworkflow.web.models.Role;
import com.pmis.activityworkflow.web.models.UserInfo;
import com.pmis.activityworkflow.web.request.TransitionRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Writes the workflow audit trail.
 *
 * <p><b>Transaction strategy</b> — this is the important part:</p>
 * <ul>
 *   <li>{@link #recordSuccess} uses the default propagation, so it joins the
 *       caller's transaction. The audit row commits atomically with the
 *       transition — if the transition somehow rolls back, the SUCCESS audit
 *       rolls back too. Correct.</li>
 *   <li>{@link #recordFailure} uses {@code REQUIRES_NEW}. When a transition
 *       fails, the caller's transaction rolls back — but the audit row must
 *       survive so you have a record of the failed attempt. A separate
 *       transaction guarantees the FAILED row is committed regardless.</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WorkflowAuditService {

    private static final String SUCCESS = "SUCCESS";
    private static final String FAILED  = "FAILED";
    /** Non-transition admin/user action — e.g. button click, vote cast. */
    private static final String BUTTON  = "BUTTON_CLICK";

    private final WorkflowAuditRepository auditRepository;
    private final ObjectMapper objectMapper;

    /* ============================================================
     *  BUTTON CLICK — non-transition user action (admin buttons, votes)
     *  Uses REQUIRES_NEW so it commits even if the surrounding business
     *  transaction rolls back later (e.g. the gate detects a problem after
     *  the audit row is written).
     * ============================================================ */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordButtonClick(ButtonAuditContext ctx) {
        try {
            HttpMeta http = httpMeta();
            UserSnapshot user = userSnapshot(ctx.requestInfo());
            String payload = serializeAny(ctx.payloadForAudit());

            WorkflowAuditEntity row = WorkflowAuditEntity.builder()
                    .uuid(UUID.randomUUID().toString())
                    .businessService(ctx.businessService())
                    .activityId(ctx.activityId())
                    .projectId(ctx.projectId())
                    .moduleName(ctx.moduleName())
                    .actionName(ctx.buttonName())          // e.g. "REQUEST_DIVISION_APPROVAL"
                    .previousState(ctx.stateName())
                    .resultantState(ctx.stateName())       // button clicks don't move state
                    .outcome(BUTTON)
                    .errorMessage(null)
                    .performedByUuid(user.uuid())
                    .performedByUsername(user.username())
                    .performedByRoles(user.roles())
                    .comment(ctx.comment())
//                    .ipAddress(http.ip())
//                    .userAgent(http.userAgent())
//                    .requestPayload(payload)
                    .createdTime(System.currentTimeMillis())
                    .build();

            auditRepository.save(row);
            log.info("Audit: recorded BUTTON_CLICK '{}' for activity {} by {}",
                    ctx.buttonName(), ctx.activityId(), user.uuid());
        } catch (Exception ex) {
            // Auditing failures must never break the main flow.
            log.warn("Audit: failed to record BUTTON_CLICK '{}' for activity {}: {}",
                    ctx.buttonName(), ctx.activityId(), ex.getMessage());
        }
    }

    /* ============================================================
     *  SUCCESS — joins the transition's transaction (atomic)
     *
     *  Any exception inside is caught and logged — auditing must never
     *  break the actual transition. The transition is the source of
     *  truth; if a single audit row fails to persist, that's a P3
     *  reconciliation issue, not a workflow blocker.
     * ============================================================ */
    @Transactional
    public void recordSuccess(TransitionRequest request,
                              List<ProcessStateAndAction> tuples) {
        try {
            String payload = serializeRedacted(request);
            HttpMeta http = httpMeta();
            UserSnapshot user = userSnapshot(request.getRequestInfo());
            long now = System.currentTimeMillis();

            List<WorkflowAuditEntity> rows = new ArrayList<>(tuples.size());
            for (ProcessStateAndAction tuple : tuples) {
                ProcessInstanceDTO req = tuple.getProcessInstanceFromRequest();
                rows.add(WorkflowAuditEntity.builder()
                        .uuid(UUID.randomUUID().toString())
                        .businessService(req.getBusinessService())
                        .activityId(req.getActivityId())
                        .projectId(req.getProjectId())
                        .moduleName(req.getModuleName())
                        .actionName(tuple.getAction() != null ? tuple.getAction().getActionName() : req.getAction())
                        .previousState(tuple.getCurrentState() != null ? tuple.getCurrentState().getStateName() : null)
                        .resultantState(tuple.getResultantState() != null ? tuple.getResultantState().getStateName() : null)
                        .outcome(SUCCESS)
                        .errorMessage(null)
                        .performedByUuid(user.uuid())
                        .performedByUsername(user.username())
                        .performedByRoles(user.roles())
                        .comment(req.getComment())
//                        .ipAddress(http.ip())
//                        .userAgent(http.userAgent())
//                        .requestPayload(payload)
                        .createdTime(now)
                        .build());
            }
            auditRepository.saveAll(rows);
            log.info("Audit: recorded {} SUCCESS transition attempt(s)", rows.size());
        } catch (Exception ex) {
            log.error("Audit: failed to record SUCCESS transition - transition itself is unaffected: {}",
                    ex.getMessage(), ex);
        }
    }

    /* ============================================================
     *  FAILURE — REQUIRES_NEW so it survives the rollback
     * ============================================================ */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(TransitionRequest request, Throwable error) {

        String payload = serializeRedacted(request);
        HttpMeta http = httpMeta();
        UserSnapshot user = userSnapshot(request.getRequestInfo());
        long now = System.currentTimeMillis();
        String message = error == null ? "Unknown error" : error.getMessage();

        List<ProcessInstanceDTO> instances = request.getProcessInstances() == null
                ? Collections.emptyList()
                : request.getProcessInstances();

        List<WorkflowAuditEntity> rows = new ArrayList<>(Math.max(1, instances.size()));

        if (instances.isEmpty()) {
            // couldn't even parse instances — still record the attempt
            rows.add(baseFailureRow(null, message, user, http, payload, now));
        } else {
            for (ProcessInstanceDTO req : instances) {
                rows.add(baseFailureRow(req, message, user, http, payload, now));
            }
        }

        auditRepository.saveAll(rows);
        log.warn("Audit: recorded {} FAILED transition attempt(s): {}", rows.size(), message);
    }

    private WorkflowAuditEntity baseFailureRow(ProcessInstanceDTO req,
                                               String message,
                                               UserSnapshot user,
                                               HttpMeta http,
                                               String payload,
                                               long now) {
        return WorkflowAuditEntity.builder()
                .uuid(UUID.randomUUID().toString())
                .businessService(req != null ? req.getBusinessService() : null)
                .activityId(req != null ? req.getActivityId() : null)
                .projectId(req != null ? req.getProjectId() : null)
                .moduleName(req != null ? req.getModuleName() : null)
                .actionName(req != null ? req.getAction() : null)
                .previousState(null)      // unknown on failure
                .resultantState(null)
                .outcome(FAILED)
                .errorMessage(message)
                .performedByUuid(user.uuid())
                .performedByUsername(user.username())
                .performedByRoles(user.roles())
                .comment(req != null ? req.getComment() : null)
//                .ipAddress(http.ip())
//                .userAgent(http.userAgent())
//                .requestPayload(payload)
                .createdTime(now)
                .build();
    }

    /* ============================================================
     *  helpers
     * ============================================================ */

    /** Serialize the request to JSON with authToken redacted. Never throws. */
    private String serializeRedacted(TransitionRequest request) {
        try {
            ObjectNode root = objectMapper.valueToTree(request);
            // TransitionRequest serializes RequestInfo under "RequestInfo" (@JsonProperty)
            if (root.get("RequestInfo") instanceof ObjectNode ri && ri.has("authToken")) {
                ri.put("authToken", "***REDACTED***");
            }
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.debug("Audit: could not serialize request payload: {}", e.getMessage());
            return null;
        }
    }

    /** Serialize an arbitrary object to JSON. Never throws. */
    private String serializeAny(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.debug("Audit: could not serialize payload: {}", e.getMessage());
            return null;
        }
    }

    private UserSnapshot userSnapshot(RequestInfo info) {
        UserInfo u = Optional.ofNullable(info).map(RequestInfo::getUserInfo).orElse(null);
        if (u == null) {
            return new UserSnapshot("system", null, Collections.emptyList());
        }
        List<String> roles = (u.getRoles() == null ? Collections.<Role>emptyList() : u.getRoles())
                .stream()
                .map(Role::getCode)
                .filter(c -> c != null && !c.isBlank())
                .toList();
        return new UserSnapshot(
                u.getUuid() == null ? "system" : u.getUuid(),
                u.getUserName(),
                roles);
    }

    /** Pull IP + user-agent from the current servlet request, if there is one. */
    private HttpMeta httpMeta() {
        try {
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
                var http = attrs.getRequest();
                String ip = http.getHeader("X-Forwarded-For");
                if (ip == null || ip.isBlank()) {
                    ip = http.getRemoteAddr();
                }
                return new HttpMeta(ip, http.getHeader("User-Agent"));
            }
        } catch (Exception ignored) {
            // best effort — auditing must never break the main flow
        }
        return new HttpMeta(null, null);
    }

    /* small value carriers */
    private record UserSnapshot(String uuid, String username, List<String> roles) {}
    private record HttpMeta(String ip, String userAgent) {}

    /**
     * Carrier for the bits {@link #recordButtonClick} needs. Public so
     * callers (admin button endpoints, vote endpoint) can build it from
     * their own request shapes.
     *
     * @param buttonName         a short stable name, e.g. "REQUEST_DIVISION_APPROVAL"
     * @param businessService    "ACTIVITY"
     * @param activityId         which activity the click acted on
     * @param projectId          parent project, if known
     * @param stateName          the state the activity was in when clicked
     * @param moduleName         optional - the calling module
     * @param comment            optional admin/user note
     * @param requestInfo        the caller's RequestInfo (for uuid/username/roles)
     * @param payloadForAudit    optional full request payload to capture
     */
    public record ButtonAuditContext(
            String buttonName,
            String businessService,
            String activityId,
            String projectId,
            String stateName,
            String moduleName,
            String comment,
            RequestInfo requestInfo,
            Object payloadForAudit) {}
}
