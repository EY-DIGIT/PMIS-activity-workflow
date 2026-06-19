package com.pmis.activityworkflow.service.audit;

import com.pmis.activityworkflow.entity.WorkflowAuditEntity;
import com.pmis.activityworkflow.repository.WorkflowAuditRepository;
import com.pmis.activityworkflow.service.transition.ProcessStateAndAction;
import com.pmis.activityworkflow.web.models.ProcessInstanceDTO;
import com.pmis.activityworkflow.web.models.RequestInfo;
import com.pmis.activityworkflow.web.models.Role;
import com.pmis.activityworkflow.web.models.UserInfo;
import com.pmis.activityworkflow.web.request.TransitionRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Writes the workflow audit trail.
 *
 * <p><b>Transaction strategy:</b>
 * <ul>
 *   <li>{@link #recordSuccess} joins the caller's transaction — the audit row
 *       commits atomically with the transition.</li>
 *   <li>{@link #recordFailure} uses {@code REQUIRES_NEW} so the FAILED row
 *       is committed even when the caller's transaction rolls back.</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WorkflowAuditService {

    private static final String SUCCESS = "SUCCESS";
    private static final String FAILED  = "FAILED";
    private static final String BUTTON  = "BUTTON_CLICK";

    private final WorkflowAuditRepository auditRepository;

    /* ============================================================
     *  BUTTON CLICK — non-transition user action (admin buttons, votes)
     * ============================================================ */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordButtonClick(ButtonAuditContext ctx) {
        try {
            UserSnapshot user = userSnapshot(ctx.requestInfo());

            WorkflowAuditEntity row = WorkflowAuditEntity.builder()
                    .uuid(UUID.randomUUID().toString())
                    .businessService(ctx.businessService())
                    .activityId(ctx.activityId())
                    .projectId(ctx.projectId())
                    .moduleName(ctx.moduleName())
                    .actionName(ctx.buttonName())
                    .previousState(ctx.stateName())
                    .resultantState(ctx.stateName())
                    .outcome(BUTTON)
                    .errorMessage(null)
                    .performedByUuid(user.uuid())
                    .performedByUsername(user.username())
                    .performedByRoles(user.roles())
                    .comment(ctx.comment())
                    .createdTime(System.currentTimeMillis())
                    .build();

            auditRepository.save(row);
            log.info("Audit: recorded BUTTON_CLICK '{}' for activity {} by {}",
                    ctx.buttonName(), ctx.activityId(), user.uuid());
        } catch (Exception ex) {
            log.warn("Audit: failed to record BUTTON_CLICK '{}' for activity {}: {}",
                    ctx.buttonName(), ctx.activityId(), ex.getMessage());
        }
    }

    /* ============================================================
     *  SUCCESS — joins the transition's transaction (atomic)
     * ============================================================ */
    @Transactional
    public void recordSuccess(TransitionRequest request,
                              List<ProcessStateAndAction> tuples) {
        try {
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
        UserSnapshot user = userSnapshot(request.getRequestInfo());
        long now = System.currentTimeMillis();
        String message = error == null ? "Unknown error" : error.getMessage();

        List<ProcessInstanceDTO> instances = request.getProcessInstances() == null
                ? Collections.emptyList()
                : request.getProcessInstances();

        List<WorkflowAuditEntity> rows = new ArrayList<>(Math.max(1, instances.size()));

        if (instances.isEmpty()) {
            rows.add(baseFailureRow(null, message, user, now));
        } else {
            for (ProcessInstanceDTO req : instances) {
                rows.add(baseFailureRow(req, message, user, now));
            }
        }

        auditRepository.saveAll(rows);
        log.warn("Audit: recorded {} FAILED transition attempt(s): {}", rows.size(), message);
    }

    private WorkflowAuditEntity baseFailureRow(ProcessInstanceDTO req,
                                               String message,
                                               UserSnapshot user,
                                               long now) {
        return WorkflowAuditEntity.builder()
                .uuid(UUID.randomUUID().toString())
                .businessService(req != null ? req.getBusinessService() : null)
                .activityId(req != null ? req.getActivityId() : null)
                .projectId(req != null ? req.getProjectId() : null)
                .moduleName(req != null ? req.getModuleName() : null)
                .actionName(req != null ? req.getAction() : null)
                .previousState(null)
                .resultantState(null)
                .outcome(FAILED)
                .errorMessage(message)
                .performedByUuid(user.uuid())
                .performedByUsername(user.username())
                .performedByRoles(user.roles())
                .comment(req != null ? req.getComment() : null)
                .createdTime(now)
                .build();
    }

    /* ============================================================
     *  helpers
     * ============================================================ */

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

    private record UserSnapshot(String uuid, String username, List<String> roles) {}

    /**
     * @param buttonName      a short stable name, e.g. "REQUEST_DIVISION_APPROVAL"
     * @param businessService "ACTIVITY"
     * @param activityId      which activity the click acted on
     * @param projectId       parent project, if known
     * @param stateName       the state the activity was in when clicked
     * @param moduleName      optional — the calling module
     * @param comment         optional admin/user note
     * @param requestInfo     the caller's RequestInfo (for uuid/username/roles)
     * @param payloadForAudit optional full request payload to capture
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
