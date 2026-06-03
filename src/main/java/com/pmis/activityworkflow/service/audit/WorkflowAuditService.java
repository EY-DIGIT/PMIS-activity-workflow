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

    private final WorkflowAuditRepository auditRepository;
    private final ObjectMapper objectMapper;

    /* ============================================================
     *  SUCCESS — joins the transition's transaction (atomic)
     * ============================================================ */
    @Transactional
    public void recordSuccess(TransitionRequest request,
                              List<ProcessStateAndAction> tuples) {

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
//                    .ipAddress(http.ip())
//                    .userAgent(http.userAgent())
//                    .requestPayload(payload)
                    .createdTime(now)
                    .build());
        }
        auditRepository.saveAll(rows);
        log.info("Audit: recorded {} SUCCESS transition attempt(s)", rows.size());
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
}
